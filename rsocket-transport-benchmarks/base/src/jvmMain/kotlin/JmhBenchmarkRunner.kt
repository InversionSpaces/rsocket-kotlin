/*
 * Copyright 2015-2026 the original author or authors.
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

@file:OptIn(kotlinx.benchmark.internal.KotlinxBenchmarkRuntimeInternalApi::class)

package io.rsocket.kotlin.transport.benchmarks

import kotlinx.benchmark.RunnerConfiguration
import org.openjdk.jmh.results.format.ResultFormatType
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.Options
import org.openjdk.jmh.runner.options.OptionsBuilder
import org.openjdk.jmh.runner.options.TimeValue
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

// Adapt the plugin's configuration as kotlinx-benchmark 0.5.0 does, using JMH's default formatter.
// Workaround for https://github.com/Kotlin/kotlinx-benchmark/issues/413.
// Remove this launcher once the upstream formatter correctly forwards fork output.
fun main(args: Array<String>) {
    val config = RunnerConfiguration(File(args.single()).readText())
    Runner(jmhOptions(config, ManagementFactory.getRuntimeMXBean().inputArguments)).run()
}

internal fun jmhOptions(config: RunnerConfiguration, jvmArguments: List<String>): Options {
    val options = OptionsBuilder()
    config.iterations?.let { options.measurementIterations(it) }
    config.warmups?.let { options.warmupIterations(it) }
    config.iterationTime?.let {
        val time = TimeValue(it, config.iterationTimeUnit ?: TimeUnit.SECONDS)
        options.warmupTime(time)
        options.measurementTime(time)
    }
    config.outputTimeUnit?.let { options.timeUnit(it) }
    config.mode?.let { options.mode(it) }
    config.include.forEach { options.include(it) }
    config.exclude.forEach { options.exclude(it) }
    config.params.forEach { (name, values) -> options.param(name, *values.toTypedArray()) }

    if (jvmArguments.any { it.contains("libasyncProfiler") }) {
        options.forks(0)
    } else {
        when (val forks = config.advanced["jvmForks"]) {
            null -> options.forks(1)
            "definedByJmh" -> Unit
            else -> {
                val count = forks.toIntOrNull()?.takeIf { it >= 0 }
                require(count != null) { "jvmForks: expected a non-negative integer or \"definedByJmh\"" }
                options.forks(count)
            }
        }
    }

    options.resultFormat(ResultFormatType.valueOf(config.reportFormat.uppercase()))
    options.result(config.reportFile)
    return options.build()
}
