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
import java.util.concurrent.*
import kotlin.coroutines.*

private const val CONNECTION_PARALLELISM = 4

@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = WARMUP, time = WARMUP_DURATION)
@Measurement(iterations = ITERATION, time = ITERATION_DURATION)
@Fork(
    value = 3,
    // Both variants use the same four-worker coroutine scheduler for Ktor's Dispatchers.IO.
    // Default connection work shares that scheduler; Loom connection work uses four carriers.
    jvmArgsAppend = [
        "-Dkotlinx.coroutines.scheduler.core.pool.size=$CONNECTION_PARALLELISM",
        "-Dkotlinx.coroutines.scheduler.max.pool.size=$CONNECTION_PARALLELISM",
        "-Djdk.virtualThreadScheduler.parallelism=$CONNECTION_PARALLELISM",
        "-Djdk.virtualThreadScheduler.maxPoolSize=$CONNECTION_PARALLELISM",
    ]
)
@State(Scope.Benchmark)
class KtorTcpDispatcherRSocketKotlinBenchmark : KtorTcpRSocketKotlinBenchmark() {
    @Param("DEFAULT", "LOOM")
    var dispatcher: String = ""

    // Keep socket readiness work on the same dedicated platform thread in both variants.
    private val selectorExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val loomDispatcher: ExecutorCoroutineDispatcher? by lazy {
        when (dispatcher) {
            "DEFAULT" -> null
            "LOOM"    -> Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
            else      -> error("wrong parameter 'dispatcher=$dispatcher'")
        }
    }
    override val connectionDispatcher: CoroutineDispatcher by lazy {
        loomDispatcher ?: Dispatchers.Default
    }
    override val selectorDispatcher: CoroutineDispatcher get() = selectorExecutor

    @Setup
    override fun setup() {
        check(serverTarget.coroutineContext[ContinuationInterceptor] === connectionDispatcher) {
            "Ktor TCP replaced the requested '$dispatcher' connection dispatcher"
        }
        super.setup()
    }

    @TearDown
    override fun cleanup() {
        try {
            super.cleanup()
        } finally {
            selectorExecutor.close()
            loomDispatcher?.close()
        }
    }
}
