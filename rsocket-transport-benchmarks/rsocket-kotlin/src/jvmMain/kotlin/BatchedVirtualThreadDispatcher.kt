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
import kotlin.coroutines.*

/**
 * Batching view over a VT-per-task dispatcher, intended for nonblocking tasks.
 * The owner must keep [underlying] open until all coroutine work has finished.
 * Each batch uses a fresh VT; there is no worker limit or blocking compensation.
 */
internal class BatchedVirtualThreadDispatcher(
    private val batchSize: Int,
    private val underlying: CoroutineDispatcher,
) : CoroutineDispatcher() {
    init {
        require(batchSize > 0)
    }

    private val expandThreshold: Int = 4

    private val queue = ConcurrentLinkedQueue<Runnable>()
    private val expandingWorkers = AtomicBoolean()
    // Includes submissions being published and workers reserved but not yet started.
    private val queuedTasks = AtomicLong()
    private val activeWorkers = AtomicLong()
    private val startedWorkers = LongAdder()
    private val emptyWorkers = LongAdder()
    private val executedTasks = LongAdder()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        // Count before publication so a worker cannot consume an uncounted task.
        queuedTasks.incrementAndGet()
        queue.add(block)
        startWorker()
    }

    private fun needsWorker(): Boolean {
        if (queue.isEmpty()) return false
        val workers = activeWorkers.get()
        return workers == 0L || queuedTasks.get() > workers * expandThreshold
    }

    private fun startWorker() {
        while (needsWorker()) {
            if (!expandingWorkers.compareAndSet(false, true)) return
            try {
                while (needsWorker()) {
                    activeWorkers.incrementAndGet()
                    // The runner belongs to the view, not to the coroutine that triggered this batch.
                    underlying.dispatch(EmptyCoroutineContext) { runBatch() }
                }
            } finally {
                expandingWorkers.set(false)
            }
            // Recheck after releasing ownership: enqueue or retirement may have requested
            // expansion after our last check while the flag was still held.
        }
    }

    private fun runBatch() {
        startedWorkers.increment()
        var executed = 0
        try {
            repeat(batchSize) {
                val task = queue.poll() ?: return
                queuedTasks.decrementAndGet()
                executed++
                try {
                    task.run()
                } catch (e: Throwable) {
                    val thread = Thread.currentThread()
                    thread.uncaughtExceptionHandler.uncaughtException(thread, e)
                }
            }
        } finally {
            executedTasks.add(executed.toLong())
            if (executed == 0) emptyWorkers.increment()
            activeWorkers.decrementAndGet()
            // Submissions racing with retirement either schedule a worker or appear here.
            startWorker()
        }
    }

    // A snapshot is final once all work and the underlying executor have terminated.
    fun statistics(): Statistics = Statistics(startedWorkers.sum(), emptyWorkers.sum(), executedTasks.sum())

    // Reset between iterations; counts at the boundary are approximate if batches are still running.
    fun resetStatistics() {
        startedWorkers.reset()
        emptyWorkers.reset()
        executedTasks.reset()
    }

    data class Statistics(val virtualThreads: Long, val emptyVirtualThreads: Long, val tasks: Long) {
        override fun toString(): String = "stats (VT=$virtualThreads, empty=$emptyVirtualThreads, tasks=$tasks) " +
                "[tasks per vt=${tasks / virtualThreads}]"
    }

    override fun toString(): String = "LoomBatched(batchSize=$batchSize)"
}
