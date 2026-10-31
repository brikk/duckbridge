package dev.brikk.duckbridge.trino.plugin

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.duckdb.DuckDBPreparedStatement
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TestScanBenchmarkReaders {
    private fun fixture(): String {
        val parent = Path.of("build/scan-reader-tests").toAbsolutePath()
        Files.createDirectories(parent)
        val url = "jdbc:duckdb:" + Files.createTempDirectory(parent, "owned-").resolve("data.duckdb")
        ScanBenchmarkReader.open(url, ScanBenchmarkReader.Mode.JDBC).use { ScanBenchmarkData.seed(it, 10_000, 10_000, 10_000) }
        return url
    }

    @Test
    fun testBoundValuesNullsUnicodeAndMultiplePagesMatchIndependentJdkValues() {
        val url = fixture()
        for (shape in ScanBenchmarkData.Shape.entries) {
            val workload = ScanBenchmarkData.Workload("check", shape, 37, 5037)
            val expected = ScanBenchmarkData.expected(workload)
            for (mode in ScanBenchmarkReader.Mode.entries) {
                ScanBenchmarkReader.open(url, mode).use { connection ->
                    val actual = ScanBenchmarkReader.scan(connection, mode, workload)
                    assertThat(actual.rows).describedAs("%s %s rows", mode, shape).isEqualTo(5000)
                    assertThat(actual.hash).describedAs("%s %s values", mode, shape).isEqualTo(expected.hash)
                    assertThat(actual.pages).isGreaterThan(1)
                    assertThat(actual.statementClosed).isTrue()
                }
            }
        }
    }

    @Test
    fun testEarlyCloseAndAnIndependentMemoryObserverDoNotPoisonTheOwner() {
        val url = fixture()
        val workload = ScanBenchmarkData.Workload("prefix", ScanBenchmarkData.Shape.NUMERIC, 0, 10_000, 2048, ordered = false)
        val expected = ScanBenchmarkData.expected(workload)
        for (mode in ScanBenchmarkReader.Mode.entries) {
            ScanBenchmarkReader.open(url, mode).use { connection ->
                connection.duplicate().use { observer ->
                    val actual = ScanBenchmarkReader.scan(connection, mode, workload) {
                        assertThat(ScanBenchmarkReader.nativeMemory(observer)).isGreaterThanOrEqualTo(0)
                    }
                    assertThat(actual.hash).describedAs(mode.name).isEqualTo(expected.hash)
                    assertThat(actual.rows).isEqualTo(2048)
                }
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT 42").use { rows -> check(rows.next()); assertThat(rows.getInt(1)).isEqualTo(42) }
                }
            }
        }
    }

    @Test
    fun testChunkQueryErrorsAndParentCloseAreExplicit() {
        val connection = ScanBenchmarkReader.open(fixture(), ScanBenchmarkReader.Mode.CHUNKED)
        try {
            (connection.prepareStatement("SELECT CAST(? AS BIGINT)") as DuckDBPreparedStatement).use { statement ->
                statement.setString(1, "not-a-number")
                assertThatThrownBy { statement.query().use { while (it.nextChunk()) { /* drain actual native chunks */ } } }
                    .hasMessageContaining("Conversion Error")
            }
            val statement = connection.prepareStatement("SELECT * FROM range(10000)") as DuckDBPreparedStatement
            val result = statement.query()
            try {
                assertThat(result.nextChunk()).isTrue()
                connection.close()
                assertThatThrownBy { result.nextChunk() }.hasMessageContaining("closed")
            } finally { result.close(); statement.close() }
        } finally { connection.close() }
    }
}
