/*
 * Copyright 2015-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.kotlin.transport.benchmarks.kotlin

import kotlinx.coroutines.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.test.*
import kotlin.coroutines.*

class BatchedVirtualThreadDispatcherTest {
    @Test
    fun backlogStartsAnotherWorkerBeforeCurrentTaskFinishes() {
        for (batchSize in listOf(1, 4, 16)) {
            val pending = ArrayDeque<Runnable>()
            val underlying = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
            }
            val dispatcher = BatchedVirtualThreadDispatcher(batchSize, underlying)
            val pendingCounts = mutableListOf<Int>()
            dispatcher.dispatch(EmptyCoroutineContext) {
                repeat(4) { dispatcher.dispatch(EmptyCoroutineContext) {} }
                pendingCounts.add(pending.size)
                dispatcher.dispatch(EmptyCoroutineContext) {}
                pendingCounts.add(pending.size)
                repeat(10) { dispatcher.dispatch(EmptyCoroutineContext) {} }
                pendingCounts.add(pending.size)
            }
            pending.removeFirst().run()
            assertEquals(listOf(0, 1, 3), pendingCounts, "batchSize=$batchSize")
            while (pending.isNotEmpty()) pending.removeFirst().run()
            assertEquals(16L, dispatcher.statistics().tasks)
            // Retirement must leave the dispatcher able to start again from idle.
            dispatcher.dispatch(EmptyCoroutineContext) {}
            assertEquals(1, pending.size)
            pending.removeFirst().run()
        }
    }

    @Test
    fun batchesReuseVirtualThreadsAndRespectBudget() {
        val pending = ArrayDeque<Runnable>()
        val underlying = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        }
        val dispatcher = BatchedVirtualThreadDispatcher(4, underlying)
        val threads = ConcurrentHashMap<Thread, AtomicInteger>()
        repeat(37) {
            dispatcher.dispatch(EmptyCoroutineContext, Runnable {
                threads.computeIfAbsent(Thread.currentThread()) { AtomicInteger() }.incrementAndGet()
            })
        }
        assertEquals(10, pending.size, "Backlog should reserve enough workers before any batch starts")
        while (pending.isNotEmpty()) {
            Thread.ofVirtual().start(pending.removeFirst()).join()
        }
        assertEquals(37, threads.values.sumOf { it.get() })
        assertTrue(threads.keys.all { it.isVirtual })
        assertTrue(threads.values.all { it.get() <= 4 })
        assertEquals(BatchedVirtualThreadDispatcher.Statistics(10, 0, 37), dispatcher.statistics())
    }

    @Test
    fun slowUnderlyingDispatchDoesNotBlockAnotherSubmission() {
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(2)
            val underlying = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    entered.countDown()
                    release.await()
                    executor.execute(block)
                }
            }
            val dispatcher = BatchedVirtualThreadDispatcher(4, underlying)
            val first = Thread.ofVirtual().start {
                dispatcher.dispatch(EmptyCoroutineContext, Runnable { finished.countDown() })
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                // Returns even while the first submission is held inside underlying.dispatch.
                dispatcher.dispatch(EmptyCoroutineContext, Runnable { finished.countDown() })
            } finally {
                release.countDown()
                first.join()
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun expansionIncludesSubmissionsArrivingDuringUnderlyingDispatch() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pending = ConcurrentLinkedQueue<Runnable>()
        val underlying = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                entered.countDown()
                release.await()
                pending.add(block)
            }
        }
        val dispatcher = BatchedVirtualThreadDispatcher(4, underlying)
        val executed = AtomicInteger()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val first = caller.submit {
                dispatcher.dispatch(EmptyCoroutineContext) { executed.incrementAndGet() }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(36) {
                dispatcher.dispatch(EmptyCoroutineContext) { executed.incrementAndGet() }
            }
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertEquals(10, pending.size, "Expansion must finish without starting a batch")
            while (true) (pending.poll() ?: break).run()
            assertEquals(37, executed.get())
        } finally {
            release.countDown()
            caller.shutdownNow()
        }
    }

    @Test
    fun concurrentSubmissionsExecuteExactlyOnceAcrossBatchSizes() {
        for (batchSize in listOf(1, 4, 16, 64)) {
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            val dispatcher = BatchedVirtualThreadDispatcher(batchSize, executor.asCoroutineDispatcher())
            val counts = AtomicIntegerArray(4_000)
            val perThread = ConcurrentHashMap<Thread, AtomicInteger>()
            val finished = CountDownLatch(4_000)
            executor.use {
                Executors.newFixedThreadPool(4).use { producers ->
                    val submissions = (0 until 4).map { producer ->
                        producers.submit {
                            repeat(1_000) { index ->
                                dispatcher.dispatch(EmptyCoroutineContext, Runnable {
                                    counts.incrementAndGet(producer * 1_000 + index)
                                    perThread.computeIfAbsent(Thread.currentThread()) { AtomicInteger() }.incrementAndGet()
                                    finished.countDown()
                                })
                            }
                        }
                    }
                    submissions.forEach { it.get(10, TimeUnit.SECONDS) }
                }
                assertTrue(finished.await(10, TimeUnit.SECONDS))
            }
            repeat(counts.length()) { assertEquals(1, counts.get(it), "batchSize=$batchSize, task=$it") }
            assertTrue(perThread.values.all { it.get() <= batchSize })
            assertEquals(4_000L, dispatcher.statistics().tasks)
        }
    }

    @Test
    fun submissionsRacingWithWorkerRetirementAreNotStranded() {
        for (batchSize in listOf(1, 16)) {
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            val dispatcher = BatchedVirtualThreadDispatcher(batchSize, executor.asCoroutineDispatcher())
            executor.use {
                repeat(1_000) {
                    val ran = CountDownLatch(1)
                    dispatcher.dispatch(EmptyCoroutineContext, Runnable { ran.countDown() })
                    assertTrue(ran.await(5, TimeUnit.SECONDS), "Task stranded at idle transition $it")
                }
            }
            assertEquals(1_000L, dispatcher.statistics().tasks)
        }
    }

    @Test
    fun throwingTaskDoesNotStrandQueuedWork() {
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val dispatcher = BatchedVirtualThreadDispatcher(4, executor.asCoroutineDispatcher())
            val reported = CompletableFuture<Throwable>()
            val expected = IllegalStateException("test failure")
            val followingTaskRan = CountDownLatch(1)
            dispatcher.dispatch(EmptyCoroutineContext, Runnable {
                Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                    reported.complete(error)
                }
                dispatcher.dispatch(EmptyCoroutineContext, Runnable { followingTaskRan.countDown() })
                throw expected
            })
            assertSame(expected, reported.get(5, TimeUnit.SECONDS))
            assertTrue(followingTaskRan.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun coroutineResumptionsAndCancellationWork() = runBlocking {
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val dispatcher = BatchedVirtualThreadDispatcher(16, executor.asCoroutineDispatcher())
            val total = AtomicInteger()
            val jobs = List(100) {
                launch(dispatcher) {
                    repeat(10) {
                        total.incrementAndGet()
                        yield()
                        delay(1)
                    }
                }
            }
            withTimeout(10_000) { jobs.joinAll() }
            assertEquals(1_000, total.get())

            val started = CompletableDeferred<Unit>()
            val cleanedUp = CompletableDeferred<Unit>()
            val job = launch(dispatcher) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cleanedUp.complete(Unit)
                }
            }
            withTimeout(5_000) { started.await() }
            withTimeout(5_000) {
                job.cancelAndJoin()
                cleanedUp.await()
            }
        }
    }
}
