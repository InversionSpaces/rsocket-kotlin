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
        configurations.register("ktorTcpDispatcher${operation.replaceFirstChar { it.uppercase() }}") {
            reportFormat = "csv"
            advanced("jvmForks", 3)
            include("KtorTcpDispatcherRSocketKotlinBenchmark.${operation}Concurrent")
            param("dispatcher", "DEFAULT", "LOOM")
        }
    }
}

// The dispatcher comparison is JVM-only because virtual threads require Java 21.
tasks.withType<NativeBenchmarkExec>()
    .named { it.contains("KtorTcpDispatcher") }
    .configureEach { onlyIf { false } }

tasks.withType<org.gradle.jvm.tasks.Jar>()
    .matching { it.name == "jvmBenchmarkJar" }
    .configureEach {
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    }

listOf("Loom", "Default").forEach { dispatcher ->
    tasks.register<JavaExec>("profile${dispatcher}RequestStream") {
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
        args(
            "^io[.]rsocket[.]kotlin[.]transport[.]benchmarks[.]kotlin[.]KtorTcpDispatcherRSocketKotlinBenchmark[.]requestStreamConcurrent$",
            "-p", "dispatcher=${dispatcher.uppercase()}",
            "-f", "1",
            "-prof", profiler
        )
    }
}
