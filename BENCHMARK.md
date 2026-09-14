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

## First attempt: connection dispatcher inheritance

The original rsocket-kotlin Ktor TCP transport construction appends `Dispatchers.Default`, overriding a dispatcher in its parent
context. This branch reverses the composition from
`context.supervisorContext() + Dispatchers.Default` to
`Dispatchers.Default + context.supervisorContext()`. Default remains the fallback; a supplied Loom
dispatcher now reaches the connection and RSocket processing scopes.

- [Client change](rsocket-transports/ktor-tcp/src/commonMain/kotlin/io/rsocket/kotlin/transport/ktor/tcp/KtorTcpClientTransport.kt#L55-L61)
- [Server change](rsocket-transports/ktor-tcp/src/commonMain/kotlin/io/rsocket/kotlin/transport/ktor/tcp/KtorTcpServerTransport.kt#L62-L68)
- [Regression test](rsocket-transports/ktor-tcp/src/commonTest/kotlin/io/rsocket/kotlin/transport/ktor/tcp/TcpTransportTest.kt#L41-L54)

Results apply to this modified library behavior, not the released implementation.

### Resources

| Resource                         | Default                                 | Loom                                 |
|----------------------------------|-----------------------------------------|--------------------------------------|
| Connection/RSocket execution     | 4 Default workers                       | 4 Loom carriers                      |
| Ktor NIO pumps                   | `Dispatchers.IO`, sharing the 4 workers | `Dispatchers.IO`, separate 4 workers |
| Selector                         | 1 dedicated thread                      | 1 dedicated thread                   |
| JMH generator                    | 1 thread                                | 1 thread                             |
| Relevant platform-thread ceiling | 6                                       | 10                                   |

Thread ceilings exclude JVM service threads. CPU cores are not pinned in either attempt.

### Results

Scores are batched benchmark invocations per second:

| Operation        |    Default, ops/s |       Loom, ops/s | Loom difference |
|------------------|------------------:|------------------:|----------------:|
| Request-response | 623.470 +- 16.650 | 537.522 +- 23.186 |          -13.8% |
| Request-stream   |    5.842 +- 0.215 |    2.818 +- 0.116 |          -51.8% |
| Request-channel  |   35.160 +- 1.244 |   19.568 +- 0.068 |          -44.3% |

Errors are 99.9% confidence intervals across 15 samples.

## Second attempt: Ktor I/O dispatcher patch

### Hypothesis and patch

The baseline includes the rsocket-kotlin dispatcher-inheritance fix, but Ktor still runs socket
I/O on `Dispatchers.IO`. Loom therefore uses two schedulers: four carriers plus four I/O workers.
We suspected that handoffs and wakeups between them reduced throughput.

The Ktor patch adds `ioDispatcher` to TCP client/server socket options and passes it to
reader/writer pumps. The benchmark selects the connection dispatcher for both ends, removing
Loom's extra I/O pool. Unconfigured sockets retain `Dispatchers.IO`.

### Resources

| Resource                         | Default                                 | Loom                                          |
|----------------------------------|-----------------------------------------|-----------------------------------------------|
| Connection/RSocket execution     | Default scheduler                       | Virtual threads                               |
| Connection parallelism           | 4 workers                               | 4 carriers                                    |
| Ktor NIO pumps                   | Default scheduler                       | Virtual threads                               |
| Selector                         | 1 dedicated thread                      | 1 dedicated thread                            |
| JMH generator                    | 1 thread                                | 1 thread                                      |
| Relevant platform-thread ceiling | 6                                       | 6                                             |

### Results

Same workload and JMH configuration.
Errors are again 99.9% confidence intervals across 15 samples. Changes use the baseline above.

| Operation        |    Default, ops/s |   Default change |       Loom, ops/s | Loom change | Loom vs Default |
|------------------|------------------:|-----------------:|------------------:|------------:|----------------:|
| Request-response | 659.821 +- 19.017 |            +5.8% | 619.838 +- 15.736 |      +15.3% |           -6.1% |
| Request-stream   |    5.840 +- 0.261 | approximately 0% |    3.923 +- 0.058 |      +39.2% |          -32.8% |
| Request-channel  |   34.429 +- 1.195 |            -2.1% |   28.222 +- 0.383 |      +44.2% |          -18.0% |

Loom improves substantially but remains slower than Default. Default's stream/channel changes
fall within overlapping baseline confidence intervals.

### Profiler findings

Compared before/after request-stream async-profiler recordings, one fork each,
on Apple M3 Max / JVM 21.0.10. Throughput above comes from separate JMH benchmark runs.

- **Patch confirmed:** Loom's socket reader/writer stacks move from Default workers to Loom
  carriers; Default workers disappear from sampled execution.
- **Less scheduler coordination:** Loom wait/signal sample shares fall from 30.9%/5.9% to
  16.2%/1.6%. Mean system CPU load falls from 13.6% to 8.7% of machine capacity, supporting
  the handoff hypothesis.
- **Remaining suspects:** L2 buffer-pool paths appear in 12.1% of Loom samples versus 6.8% on
  Default. Virtual-thread lifecycle/JVMTI paths appear in 8.0% of Loom samples; profiling may
  amplify that overhead.

Caveats: macOS recordings report `event=cpu` but `engine=wall`; sample shares are not exact CPU
costs. There are no matching post-patch standard JFR or allocation recordings. Next checks:
measure profiler overhead and collect allocation profiles before changing buffer pooling.

## Reproduce the patched benchmark

Publish the patched Ktor network module before building the benchmark:

```shell
./ktor/gradlew -p ktor :ktor-network:publishToMavenLocal
```

```shell
./gradlew :rsocket-transport-benchmarks-rsocket-kotlin:jvmKtorTcpDispatcherRequestResponseBenchmark --no-parallel --max-workers=1 --no-daemon
./gradlew :rsocket-transport-benchmarks-rsocket-kotlin:jvmKtorTcpDispatcherRequestStreamBenchmark --no-parallel --max-workers=1 --no-daemon
./gradlew :rsocket-transport-benchmarks-rsocket-kotlin:jvmKtorTcpDispatcherRequestChannelBenchmark --no-parallel --max-workers=1 --no-daemon
```
