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

import io.rsocket.kotlin.transport.benchmarks.*
import kotlinx.benchmark.*
import kotlinx.coroutines.*
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.runner.IterationType
import java.util.concurrent.*
import kotlin.coroutines.*

private const val CONNECTION_PARALLELISM = 4

@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = WARMUP, time = WARMUP_DURATION)
@Measurement(iterations = ITERATION, time = ITERATION_DURATION)
@Fork(
    value = 3,
    // Connection, RSocket, and Ktor NIO work all use the selected dispatcher.
    jvmArgsAppend = [
        "-Dkotlinx.coroutines.scheduler.core.pool.size=$CONNECTION_PARALLELISM",
        "-Dkotlinx.coroutines.scheduler.max.pool.size=$CONNECTION_PARALLELISM",
        "-Djdk.virtualThreadScheduler.parallelism=$CONNECTION_PARALLELISM",
        "-Djdk.virtualThreadScheduler.maxPoolSize=$CONNECTION_PARALLELISM",
    ]
)
@State(Scope.Benchmark)
abstract class KtorTcpDispatcherBenchmarkBase : KtorTcpRSocketKotlinBenchmark() {
    protected abstract fun createDispatcher(): CoroutineDispatcher?

    // Keep socket readiness work on the same dedicated platform thread in all variants.
    private val selectorExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var loomExecutor: ExecutorService? = null
    protected fun createLoomDispatcher(): CoroutineDispatcher =
        Executors.newVirtualThreadPerTaskExecutor().also { loomExecutor = it }.asCoroutineDispatcher()

    private val loomDispatcher: CoroutineDispatcher? by lazy { createDispatcher() }
    override val connectionDispatcher: CoroutineDispatcher by lazy {
        loomDispatcher ?: Dispatchers.Default
    }
    override val selectorDispatcher: CoroutineDispatcher get() = selectorExecutor

    // Lifecycle annotations are inherited from KtorTcpRSocketKotlinBenchmark.
    override fun setup() {
        check(serverTarget.coroutineContext[ContinuationInterceptor] === connectionDispatcher) {
            "Ktor TCP replaced the requested $connectionDispatcher connection dispatcher"
        }
        super.setup()
    }

    private var measurementStarted = false
    private var measurementStatistics: BatchedVirtualThreadDispatcher.Statistics? = null

    @org.openjdk.jmh.annotations.Setup(Level.Iteration)
    fun startIteration(params: IterationParams) {
        if (params.type == IterationType.MEASUREMENT && !measurementStarted) {
            (loomDispatcher as? BatchedVirtualThreadDispatcher)?.resetStatistics()
            measurementStarted = true
        }
    }

    @org.openjdk.jmh.annotations.TearDown(Level.Iteration)
    fun finishIteration(params: IterationParams) {
        if (params.type == IterationType.MEASUREMENT) {
            measurementStatistics = (loomDispatcher as? BatchedVirtualThreadDispatcher)?.statistics()
        }
    }

    override fun cleanup() {
        try {
            super.cleanup()
        } finally {
            selectorExecutor.close()
            loomExecutor?.close()
            (loomDispatcher as? BatchedVirtualThreadDispatcher)?.let {
                println("$it: $measurementStatistics")
            }
        }
    }
}

@State(Scope.Benchmark)
class KtorTcpDispatcherRSocketKotlinBenchmark : KtorTcpDispatcherBenchmarkBase() {
    @Param("DEFAULT", "LOOM")
    var dispatcher: String = ""

    override fun createDispatcher(): CoroutineDispatcher? = when (dispatcher) {
        "DEFAULT" -> null
        "LOOM" -> createLoomDispatcher()
        else -> error("wrong parameter 'dispatcher=$dispatcher'")
    }
}

@State(Scope.Benchmark)
class KtorTcpDispatcherBatchingRSocketKotlinBenchmark : KtorTcpDispatcherBenchmarkBase() {
    @Param("1", "4", "16", "64")
    var batchSize: Int = 16

    override fun createDispatcher(): CoroutineDispatcher = BatchedVirtualThreadDispatcher(
        batchSize = batchSize,
        underlying = createLoomDispatcher(),
    )
}

@State(Scope.Benchmark)
class KtorTcpDispatcherLimitedRSocketKotlinBenchmark : KtorTcpDispatcherBenchmarkBase() {
    @Param("1", "4", "16", "64")
    var parallelism: Int = 4

    override fun createDispatcher(): CoroutineDispatcher = createLoomDispatcher().limitedParallelism(parallelism)
}
