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

import rsocketbuild.*
import kotlinx.benchmark.gradle.*

plugins {
    id("rsocketbuild.multiplatform-benchmarks")
}

kotlin {
    jvmTarget(jdkVersion = 21)

    macosX64()
    macosArm64()
    linuxX64()

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
        commonMain.dependencies {
            implementation(projects.rsocketTransportBenchmarksBase)

            implementation(projects.rsocketTransportLocal)
            implementation(projects.rsocketTransportKtorTcp)
            implementation(projects.rsocketTransportKtorWebsocketClient)
            implementation(projects.rsocketTransportKtorWebsocketServer)

            // ktor engines
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.client.cio)
        }
        jvmMain.dependencies {
            implementation(projects.rsocketTransportNettyTcp)
            implementation(projects.rsocketTransportNettyQuic)
            implementation(libs.netty.codec.quic.map {
                val javaOsName = System.getProperty("os.name")
                val javaOsArch = System.getProperty("os.arch")
                val suffix = when {
                    javaOsName.contains("mac", ignoreCase = true)     -> "osx"
                    javaOsName.contains("linux", ignoreCase = true)   -> "linux"
                    javaOsName.contains("windows", ignoreCase = true) -> "windows"
                    else                                              -> error("Unknown os.name: $javaOsName")
                } + "-" + when (javaOsArch) {
                    "x86_64", "amd64"  -> "x86_64"
                    "arm64", "aarch64" -> "aarch_64"
                    else               -> error("Unknown os.arch: $javaOsArch")
                }
                "$it:$suffix"
            })
            implementation(libs.bouncycastle)
        }
    }
}

benchmark {
    targets {
        register("jvm")
        register("macosArm64")
        register("macosX64")
        register("linuxX64")
    }

    registerTransportBenchmarks(
        "RSocketKotlin",
        listOf(
            "local",
            "ktorTcp", "ktorWs",
            "nettyTcp", "nettyQuic"
        )
    ) { transport ->
        if (transport == "local") {
            param("dispatcher", "DEFAULT", "UNCONFINED")
            param("channels", "S", "M")
        }
    }

    listOf("requestResponse", "requestStream", "requestChannel").forEach { operation ->
        configurations.register("ktorTcpDispatcherDefault${operation.replaceFirstChar { it.uppercase() }}") {
            reportFormat = "csv"
            advanced("jvmForks", 3)
            include("KtorTcpDispatcherRSocketKotlinBenchmark.${operation}Concurrent")
            param("dispatcher", "DEFAULT")
        }
        configurations.register("ktorTcpDispatcher${operation.replaceFirstChar { it.uppercase() }}") {
            reportFormat = "csv"
            advanced("jvmForks", 3)
            include("KtorTcpDispatcherRSocketKotlinBenchmark.${operation}Concurrent")
            param("dispatcher", "DEFAULT", "LOOM")
        }
        configurations.register("ktorTcpDispatcherBatching${operation.replaceFirstChar { it.uppercase() }}") {
            reportFormat = "csv"
            advanced("jvmForks", 3)
            include("KtorTcpDispatcherRSocketKotlinBenchmark.${operation}Concurrent")
            include("KtorTcpDispatcherBatchingRSocketKotlinBenchmark.${operation}Concurrent")
            include("KtorTcpDispatcherLimitedRSocketKotlinBenchmark.${operation}Concurrent")
        }
        configurations.register("ktorTcpDispatcherLimited${operation.replaceFirstChar { it.uppercase() }}") {
            reportFormat = "csv"
            advanced("jvmForks", 3)
            include("KtorTcpDispatcherLimitedRSocketKotlinBenchmark.${operation}Concurrent")
        }
    }
}

// The dispatcher comparison is JVM-only because virtual threads require Java 21.
tasks.named<Test>("jvmTest") {
    // Exercise concurrent scheduling with two carrier threads.
    jvmArgs("-Djdk.virtualThreadScheduler.parallelism=2", "-Djdk.virtualThreadScheduler.maxPoolSize=2")
}

tasks.withType<NativeBenchmarkExec>()
    .named { it.contains("KtorTcpDispatcher") }
    .configureEach { onlyIf { false } }

tasks.withType<org.gradle.jvm.tasks.Jar>()
    .matching { it.name == "jvmBenchmarkJar" }
    .configureEach {
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    }

mapOf("Loom" to "LOOM", "Default" to "DEFAULT", "LoomBatched" to "LOOM_BATCHED").forEach { (name, dispatcher) ->
    tasks.register<JavaExec>("profile${name}RequestStream") {
        group = "benchmark"
        description = "Profile the $dispatcher request-stream benchmark with JFR (one fork); set -PasyncProfilerLib for native JVM stacks."
        dependsOn("jvmBenchmarkJar")
        classpath(files(provider {
            tasks.named<org.gradle.jvm.tasks.Jar>("jvmBenchmarkJar").get().archiveFile.get().asFile
        }))
        mainClass.set("org.openjdk.jmh.Main")
        // Async-profiler captures native JVM execution as well as Java stacks, in JFR format.
        val profiler = providers.gradleProperty("asyncProfilerLib").map { library ->
            "async:libPath=$library;event=cpu;output=jfr;cstack=fp"
        }.getOrElse("jfr")
        val benchmarkClass = if (dispatcher == "LOOM_BATCHED") {
            // Default batch size for profiling one case; regular benchmarks run all configured sizes.
            args("-p", "batchSize=16")
            "KtorTcpDispatcherBatchingRSocketKotlinBenchmark"
        } else {
            args("-p", "dispatcher=$dispatcher")
            "KtorTcpDispatcherRSocketKotlinBenchmark"
        }
        args(
            "^io[.]rsocket[.]kotlin[.]transport[.]benchmarks[.]kotlin[.]$benchmarkClass[.]requestStreamConcurrent$",
            "-f", "1",
            "-prof", profiler
        )
    }
}
