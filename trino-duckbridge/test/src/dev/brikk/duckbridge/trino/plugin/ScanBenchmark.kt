package dev.brikk.duckbridge.trino.plugin

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Fork-isolated API-to-Page benchmark, deliberately outside normal tests and the production plugin. */
object ScanBenchmark {
    private const val WARMUPS = 3
    private const val MEASUREMENTS = 5

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--child") child(args) else suite(Path.of(args.single()))
    }

    private fun suite(output: Path) {
        Files.createDirectories(output)
        val run = Files.createTempDirectory(output, "owned-run-")
        val database = run.resolve("fixture.duckdb")
        val url = "jdbc:duckdb:$database"
        ScanBenchmarkReader.open(url, ScanBenchmarkReader.Mode.JDBC).use { connection -> ScanBenchmarkData.seed(connection) }
        val samples = mutableListOf("workload,mode,iteration,rows,pages,digest,wall_ns,caller_cpu_ns,caller_allocated_bytes,arrow_managed_peak_bytes")
        val memory = mutableListOf("workload,mode,native_baseline_bytes,native_sampled_peak_bytes,java_heap_sampled_peak_bytes,process_rss_hwm_bytes")
        for ((index, workload) in ScanBenchmarkData.workloads.withIndex()) {
            val expected = ScanBenchmarkData.expected(workload)
            // Rotate order between workloads; each pair still receives a completely fresh JVM.
            val modes = ScanBenchmarkReader.Mode.entries.toList()
            val order = modes.drop(index % modes.size) + modes.take(index % modes.size)
            for (mode in order) {
                val log = run.resolve("${workload.name}-${mode.name}.log")
                fork(run, database, workload.name, mode, expected.hash, log)
                val lines = Files.readAllLines(log)
                val recorded = lines.filter { it.startsWith("SAMPLE,") }.map { it.removePrefix("SAMPLE,") }
                check(recorded.size == MEASUREMENTS) { "Incomplete measurements: $log" }
                samples.addAll(recorded)
                memory.add(lines.single { it.startsWith("MEMORY,") }.removePrefix("MEMORY,"))
                println("BENCHMARK ${workload.name} ${mode.name} checked ${workload.consume} rows x $MEASUREMENTS")
            }
        }
        Files.write(run.resolve("samples.csv"), samples)
        Files.write(run.resolve("memory.csv"), memory)
        Files.writeString(run.resolve("profile.txt"), "JDK=${Runtime.version()}\nOS=${System.getProperty("os.name")} " +
            "${System.getProperty("os.version")} ${System.getProperty("os.arch")}\n" +
            "warmups=$WARMUPS measurements=$MEASUREMENTS sql_threads=1 page_target=${ScanBenchmarkReader.PAGE_ROWS}\n" +
            "API-level embedded scalar conversion, not end-to-end Trino/remote throughput.\n")
        println("BENCHMARK_RESULT_DIR=$run")
    }

    private fun fork(run: Path, database: Path, workload: String, mode: ScanBenchmarkReader.Mode, expected: Long, log: Path) {
        val arguments = run.resolve("$workload-${mode.name}.args")
        val values = listOf("-Xms256m", "-Xmx1g", "-XX:ActiveProcessorCount=2", "--add-modules=jdk.incubator.vector",
            "--add-opens=java.base/java.nio=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow",
            "-cp", System.getProperty("java.class.path"), javaClass.name, "--child", mode.name, workload, database.toString(), expected.toString())
        Files.write(arguments, values.map { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "@$arguments")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            check(process.waitFor(180, TimeUnit.SECONDS)) { "Benchmark child exceeded bounded run: $log" }
            check(process.exitValue() == 0) { "Benchmark child failed: $log\n${Files.readString(log)}" }
        } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
    }

    private fun child(args: Array<String>) {
        val mode = ScanBenchmarkReader.Mode.valueOf(args[1])
        val workload = ScanBenchmarkData.workloads.single { it.name == args[2] }
        val url = "jdbc:duckdb:${args[3]}"
        val expected = args[4].toLong()
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        if (bean.isThreadAllocatedMemorySupported) bean.isThreadAllocatedMemoryEnabled = true
        ScanBenchmarkReader.open(url, mode).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version()").use { rows -> check(rows.next()); check(rows.getString(1) == "v2.0.0") }
            }
            repeat(WARMUPS) { verify(ScanBenchmarkReader.scan(connection, mode, workload), workload.consume, expected) }
            repeat(MEASUREMENTS) { iteration ->
                val beforeAllocated = allocated(bean)
                val cpu = if (bean.isCurrentThreadCpuTimeSupported) bean.currentThreadCpuTime else -1L
                val start = System.nanoTime()
                val result = ScanBenchmarkReader.scan(connection, mode, workload)
                val elapsed = System.nanoTime() - start
                val cpuDelta = if (cpu >= 0) bean.currentThreadCpuTime - cpu else -1L
                val allocationDelta = if (beforeAllocated >= 0) allocated(bean) - beforeAllocated else -1L
                verify(result, workload.consume, expected)
                println("SAMPLE,${workload.name},${mode.name},$iteration,${result.rows},${result.pages},${result.hash}," +
                    "$elapsed,$cpuDelta,$allocationDelta,${result.arrowManagedPeak}")
            }
            // Memory observations are a separate untimed sweep, not SQL queries in the timed reader loop.
            memorySweep(connection, workload, mode, expected)
        }
    }

    private fun allocated(bean: ThreadMXBean): Long =
        if (bean.isThreadAllocatedMemorySupported) bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) else -1L

    private fun verify(result: ScanBenchmarkReader.Result, rows: Long, hash: Long) {
        check(result.rows == rows && result.hash == hash && result.statementClosed) { "Benchmark value/cleanup mismatch: $result" }
    }

    private fun memorySweep(connection: org.duckdb.DuckDBConnection, workload: ScanBenchmarkData.Workload,
                            mode: ScanBenchmarkReader.Mode, expected: Long) {
        connection.duplicate().use { observer ->
            val baseline = ScanBenchmarkReader.nativeMemory(observer)
            var nativePeak = baseline
            var heapPeak = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
            var pages = 0
            val result = ScanBenchmarkReader.scan(connection, mode, workload) {
                if (pages++ % 8 == 0) {
                    nativePeak = max(nativePeak, ScanBenchmarkReader.nativeMemory(observer))
                    heapPeak = max(heapPeak, ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
                }
            }
            verify(result, workload.consume, expected)
            println("MEMORY,${workload.name},${mode.name},$baseline,$nativePeak,$heapPeak,${rssHighWater()}")
        }
    }

    private fun rssHighWater(): Long {
        val status = Path.of("/proc/self/status")
        if (!Files.isRegularFile(status)) return -1L
        val line = Files.readAllLines(status).single { it.startsWith("VmHWM:") }
        return line.removePrefix("VmHWM:").trim().substringBefore(' ').toLong() * 1024
    }
}
