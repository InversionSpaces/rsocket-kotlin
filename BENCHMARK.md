# JVM dispatcher benchmark

Tracks rsocket-kotlin client/server throughput over a Ktor TCP loopback connection, investigating
why virtual-thread dispatchers lag behind `Dispatchers.Default`.

| Investigation stage                  | Dispatcher variants                                                            | Measured operations                               |
|--------------------------------------|--------------------------------------------------------------------------------|---------------------------------------------------|
| 1. Connection dispatcher inheritance | Default, Loom (VT per task)                                                    | Request-response, request-stream, request-channel |
| 2. Ktor I/O dispatcher patch         | Default, Loom; socket I/O uses the same dispatcher                             | Request-response, request-stream, request-channel |
| 3. VT lifecycle amortization         | Default, Loom, batches of 1/4/16/64, Loom with `limitedParallelism(1/4/16/64)` | Request-stream                                    |

## Common setup

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

- [Dispatcher variants](rsocket-transport-benchmarks/rsocket-kotlin/src/jvmMain/kotlin/KtorTcpDispatcherRSocketKotlinBenchmark.kt#L99-L128)
- [Transport setup](rsocket-transport-benchmarks/rsocket-kotlin/src/commonMain/kotlin/KtorTcpRSocketKotlinBenchmark.kt#L32-L62)
- [RSocket workload](rsocket-transport-benchmarks/rsocket-kotlin/src/commonMain/kotlin/RSocketKotlinBenchmark.kt#L36-L85)
- [Workload generators](rsocket-transport-benchmarks/base/src/commonMain/kotlin/RSocketTransportBenchmark.kt#L48-L105)
- [VT batching implementation](rsocket-transport-benchmarks/rsocket-kotlin/src/jvmMain/kotlin/BatchedVirtualThreadDispatcher.kt#L29)

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

Thread ceilings exclude JVM service threads. CPU cores are not pinned in these experiments.

### Results

Scores are complete benchmark invocations per second:

| Operation        |    Default, ops/s |       Loom, ops/s | Loom difference |
|------------------|------------------:|------------------:|----------------:|
| Request-response | 623.470 +- 16.650 | 537.522 +- 23.186 |          -13.8% |
| Request-stream   |    5.842 +- 0.215 |    2.818 +- 0.116 |          -51.8% |
| Request-channel  |   35.160 +- 1.244 |   19.568 +- 0.068 |          -44.3% |

Errors are 99.9% confidence intervals across 15 samples.

## Second attempt: Ktor I/O dispatcher patch

### Hypothesis and patch

The first attempt includes the rsocket-kotlin dispatcher-inheritance fix, but Ktor still runs socket
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
costs. There are no matching post-patch standard JFR or allocation recordings. These findings
motivated the VT batching experiment below; profiling overhead and buffer-pool costs remain unresolved.

## Third attempt: virtual threads per batch

### Hypothesis and implementation

Test whether reusing a VT for several continuation tasks amortizes creation/lifecycle overhead.
A dispatcher view over the VT-per-task executor drains a shared FIFO queue, executing at most
`batchSize` tasks per VT and exiting early when the queue empties. There is no stealing or hard worker limit.

We also compare VT-per-task with Kotlin's `limitedParallelism(n)`.

### Resources and results

Same patched Ktor request-stream workload.

| Dispatcher | Batch size | Parallelism limit | Throughput, ops/s | vs Default | Tasks per VT | Empty VTs |
|------------|-----------:|------------------:|------------------:|-----------:|-------------:|----------:|
| Default    |          — |                 — |    6.155 +- 0.164 |       0.0% |            — |         — |
| Loom       |          — |                 — |    3.714 +- 0.350 |     -39.7% |            — |         — |
| Batch      |          1 |                 — |    3.254 +- 0.036 |     -47.1% |         1.00 |     0.00% |
| Batch      |          4 |                 — |    4.295 +- 0.046 |     -30.2% |         4.00 |     0.00% |
| Batch      |         16 |                 — |    5.155 +- 0.041 |     -16.2% |        15.72 |     0.58% |
| Batch      |         64 |                 — |    5.384 +- 0.130 |     -12.5% |        46.69 |     9.05% |
| Limited    |          — |                 1 |    4.089 +- 0.009 |     -33.6% |            — |         — |
| Limited    |          — |                 4 |    4.007 +- 0.072 |     -34.9% |            — |         — |
| Limited    |          — |                16 |    4.065 +- 0.149 |     -34.0% |            — |         — |
| Limited    |          — |                64 |    3.961 +- 0.068 |     -35.6% |            — |         — |

`vs Default` is the throughput difference relative to Default; negative values mean lower throughput.
Parallelism limit is the `n` in `limitedParallelism(n)`.

Observations:
- **Batching helps:** batch 64 is 65.4% faster than batch 1 and 45.0% faster than plain
  Loom, but remains 12.5% below Default. 
- Plain Loom has substantial fork-to-fork variation.
- Actual reuse rises to 46.69 tasks per VT. At batch 64, 9.05% of VTs find no work; other workers
  can drain the queue before a reserved worker starts.
- Limited parallelism shows no clear gain over Loom within the reported uncertainty.
- These results support amortizing VT overhead, but do not isolate creation cost from scheduling,
  queue contention, or locality.

## Reproduce the current benchmarks

Publish the patched Ktor network module before building the benchmark:

```shell
./ktor/gradlew -p ktor :ktor-network:publishToMavenLocal
```

```shell
./gradlew :rsocket-transport-benchmarks-rsocket-kotlin:jvmKtorTcpDispatcherRequestResponseBenchmark --no-parallel --max-workers=1 --no-daemon
./gradlew :rsocket-transport-benchmarks-rsocket-kotlin:jvmKtorTcpDispatcherRequestStreamBenchmark --no-parallel --max-workers=1 --no-daemon
./gradlew :rsocket-transport-benchmarks-rsocket-kotlin:jvmKtorTcpDispatcherRequestChannelBenchmark --no-parallel --max-workers=1 --no-daemon
```

Run the batching and limited-parallelism comparison:

```shell
./gradlew :rsocket-transport-benchmarks-rsocket-kotlin:jvmKtorTcpDispatcherBatchingRequestStreamBenchmark --no-parallel --max-workers=1 --no-daemon
```
