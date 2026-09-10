# JVM dispatcher benchmark

Measures rsocket-kotlin client/server throughput over a Ktor TCP loopback connection with either
`Dispatchers.Default` or `Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()`.

## Matrix

| Parameter             | Values                                            |
|-----------------------|---------------------------------------------------|
| Operations            | request-response, request-stream, request-channel |
| Connection dispatcher | Default, Loom                                     |

## Setup

| Setting   | Value                                                    |
|-----------|----------------------------------------------------------|
| Transport | Ktor TCP loopback                                        |
| Generator | concurrent coroutines on one JMH thread                  |
| Payload   | 0 bytes                                                  |
| JMH       | throughput; 3 forks; 5 x 5 s warmup; 5 x 5 s measurement |

| Operation        | Pattern                      | Concurrent requests | Elements per request |
|------------------|------------------------------|--------------------:|---------------------:|
| Request-response | One request, one response    |               1,000 |                    1 |
| Request-stream   | One request, response stream |                 100 |                5,000 |
| Request-channel  | Bidirectional streams        |                  10 |                5,000 |

- [Dispatcher matrix](rsocket-transport-benchmarks/rsocket-kotlin/src/jvmMain/kotlin/KtorTcpDispatcherRSocketKotlinBenchmark.kt#L26-L77)
- [Transport setup](rsocket-transport-benchmarks/rsocket-kotlin/src/commonMain/kotlin/KtorTcpRSocketKotlinBenchmark.kt#L32-L56)
- [RSocket workload](rsocket-transport-benchmarks/rsocket-kotlin/src/commonMain/kotlin/RSocketKotlinBenchmark.kt#L43-L85)
- [Batching and generators](rsocket-transport-benchmarks/base/src/commonMain/kotlin/RSocketTransportBenchmark.kt#L48-L105)

## Library modification

Stock Ktor TCP construction appends `Dispatchers.Default`, overriding a dispatcher in its parent
context. This branch reverses the composition from
`context.supervisorContext() + Dispatchers.Default` to
`Dispatchers.Default + context.supervisorContext()`. Default remains the fallback; a supplied Loom
dispatcher now reaches the connection and RSocket processing scopes.

- [Client change](rsocket-transports/ktor-tcp/src/commonMain/kotlin/io/rsocket/kotlin/transport/ktor/tcp/KtorTcpClientTransport.kt#L55-L61)
- [Server change](rsocket-transports/ktor-tcp/src/commonMain/kotlin/io/rsocket/kotlin/transport/ktor/tcp/KtorTcpServerTransport.kt#L62-L68)
- [Regression test](rsocket-transports/ktor-tcp/src/commonTest/kotlin/io/rsocket/kotlin/transport/ktor/tcp/TcpTransportTest.kt#L41-L54)

Results apply to this modified library behavior, not the released implementation.

## Resources

| Resource                         | Default                                 | Loom                                          |
|----------------------------------|-----------------------------------------|-----------------------------------------------|
| Connection/RSocket execution     | Default scheduler                       | Virtual threads                               |
| Connection parallelism           | 4 workers                               | 4 carriers                                    |
| Ktor NIO pumps                   | `Dispatchers.IO`, sharing the 4 workers | `Dispatchers.IO`, separate 4-worker scheduler |
| Selector                         | 1 dedicated thread                      | 1 dedicated thread                            |
| JMH generator                    | 1 thread                                | 1 thread                                      |
| Relevant platform-thread ceiling | 6                                       | 10                                            |

Ktor hardcodes its NIO [reader](https://github.com/ktorio/ktor/blob/3.1.1/ktor-network/jvm/src/io/ktor/network/sockets/CIOReader.kt#L25)
and [writer](https://github.com/ktorio/ktor/blob/3.1.1/ktor-network/jvm/src/io/ktor/network/sockets/CIOWriter.kt#L20)
to `Dispatchers.IO`. Default shares that scheduler; Loom adds a carrier pool. Connection parallelism
is equal, but total platform-thread capacity is not. CPU cores are not pinned.

## Run

```shell
./gradlew :rsocket-transport-benchmarks-rsocket-kotlin:jvmKtorTcpDispatcherRequestResponseBenchmark --no-parallel --max-workers=1 --no-daemon
./gradlew :rsocket-transport-benchmarks-rsocket-kotlin:jvmKtorTcpDispatcherRequestStreamBenchmark --no-parallel --max-workers=1 --no-daemon
./gradlew :rsocket-transport-benchmarks-rsocket-kotlin:jvmKtorTcpDispatcherRequestChannelBenchmark --no-parallel --max-workers=1 --no-daemon
```

## Results

Results matching this setup; scores are batched benchmark invocations per second:

| Operation        |    Default, ops/s |       Loom, ops/s | Loom difference |
|------------------|------------------:|------------------:|----------------:|
| Request-response | 623.470 +- 16.650 | 537.522 +- 23.186 |          -13.8% |
| Request-stream   |    5.842 +- 0.215 |    2.818 +- 0.116 |          -51.8% |
| Request-channel  |   35.160 +- 1.244 |   19.568 +- 0.068 |          -44.3% |

Errors are 99.9% confidence intervals across 15 samples.
