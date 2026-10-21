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

import io.rsocket.kotlin.*
import io.rsocket.kotlin.core.*
import io.rsocket.kotlin.payload.*
import io.rsocket.kotlin.transport.*
import io.rsocket.kotlin.transport.benchmarks.*
import kotlinx.benchmark.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.*
import kotlin.random.*

@State(Scope.Benchmark)
@OptIn(ExperimentalStreamsApi::class)
@Suppress("unused") // Benchmark entry points are called by the generated JMH harness.
abstract class RSocketKotlinBenchmark : RSocketTransportBenchmark<Payload, Blackhole>() {
    protected abstract fun clientTarget(serverInstance: RSocketServerInstance): RSocketClientTarget
    protected abstract val serverTarget: RSocketServerTarget<*>

    private val requestStrategy = PrefetchStrategy(64, 0)

    protected val benchJob = Job()
    private lateinit var client: RSocket
    private lateinit var payload: Payload
    private lateinit var payloadsFlow: Flow<Payload>

    override fun createPayload(size: Int): Payload = if (size == 0) Payload.Empty else Payload(
        data = Buffer().apply { write(Random.nextBytes(size / 2)) },
        metadata = Buffer().apply { write(Random.nextBytes(size / 2)) }
    )

    override fun createPayloadCopy(): Payload = payload.copy()
    override fun consumePayload(bh: Blackhole, value: Payload) = bh.consume(value)
    override fun releasePayload(payload: Payload) = payload.close()

    override suspend fun doRequestResponse(): Payload = client.requestResponse(createPayloadCopy())
    override fun doRequestStream(): Flow<Payload> = client.requestStream(createPayloadCopy()).flowOn(requestStrategy)
    override fun doRequestChannel(): Flow<Payload> = client.requestChannel(createPayloadCopy(), payloadsFlow).flowOn(requestStrategy)

    override fun setup(): Unit = runBlocking {
        payload = createPayload(payloadSize)
        payloadsFlow = flow { repeat(5000) { emit(createPayloadCopy()) } }

        val serverInstance = RSocketServer().startServer(serverTarget) {
            RSocketRequestHandler {
                requestResponse {
                    it.close()
                    createPayloadCopy()
                }
                requestStream {
                    it.close()
                    payloadsFlow
                }
                requestChannel { init, payloads ->
                    init.close()
                    payloads.flowOn(requestStrategy)
                }
            }
        }
        client = RSocketConnector().connect(clientTarget(serverInstance))
    }

    override fun cleanup(): Unit = runBlocking {
        client.coroutineContext.job.cancelAndJoin()
        benchJob.cancelAndJoin()
    }

    @Param("0")
    override var payloadSize: Int = 0

    @Benchmark
    fun requestResponseBlocking(bh: Blackhole) = super.runRequestResponseBlocking(bh)

    @Benchmark
    fun requestResponseParallel(bh: Blackhole) = super.runRequestResponseParallel(bh)

    @Benchmark
    fun requestResponseConcurrent(bh: Blackhole) = super.runRequestResponseConcurrent(bh)

    @Benchmark
    fun requestStreamBlocking(bh: Blackhole) = super.runRequestStreamBlocking(bh)

    @Benchmark
    fun requestStreamParallel(bh: Blackhole) = super.runRequestStreamParallel(bh)

    @Benchmark
    fun requestStreamConcurrent(bh: Blackhole) = super.runRequestStreamConcurrent(bh)

    @Benchmark
    fun requestChannelBlocking(bh: Blackhole) = super.runRequestChannelBlocking(bh)

    @Benchmark
    fun requestChannelParallel(bh: Blackhole) = super.runRequestChannelParallel(bh)

    @Benchmark
    fun requestChannelConcurrent(bh: Blackhole) = super.runRequestChannelConcurrent(bh)
}
