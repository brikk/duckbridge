package dev.brikk.duckbridge.trino.plugin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Raw carrier observations, kept distinct from host mapping qualification. */
class TestBoundaryCarrierContracts {
    @Test
    fun testPinnedNativeAndQuackBoundaryContracts() {
        cases.forEachIndexed { index, expression -> forkNative(index, expression) }
        TestingQuackServer().use { server -> lifecycleConnection(server).use { capture(it) } }
    }

    private fun capture(connection: Connection) {
        cases.forEachIndexed { index, expression -> captureCase(connection, index, expression) }
    }

    private fun captureCase(connection: Connection, index: Int, expression: String) {
            connection.createStatement().use { it.execute("CREATE TABLE boundary_$index AS SELECT $expression v") }
            connection.metaData.getColumns(null, null, "boundary\\_$index", "%").use { rows ->
                assertThat(rows.next()).isTrue()
                println("BOUNDARY ${connection.javaClass.simpleName} expression=$expression " +
                    "type=${rows.getString("TYPE_NAME")} code=${rows.getInt("DATA_TYPE")}")
            }
            for (projection in listOf("v", "CAST(v AS VARCHAR)")) {
                try {
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT $projection FROM boundary_$index").use { rows ->
                            assertThat(rows.next()).isTrue()
                            println("BOUNDARY $projection type=${rows.metaData.getColumnTypeName(1)} " +
                                "object=${rows.getObject(1)?.javaClass?.name} string=${rows.getString(1)}")
                        }
                    }
                } catch (error: SQLException) { println("BOUNDARY $projection ERROR ${error.sqlState}: ${error.message}") }
            }
    }

    private fun forkNative(index: Int, expression: String) {
        val directory = Path.of("build/boundary-type-probes").toAbsolutePath()
        Files.createDirectories(directory)
        val output = directory.resolve("native-$index.log")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=${directory.resolve("native-$index-hs-error.log")}",
            "-cp", checkNotNull(System.getProperty("duckbridge.test.classpath")), javaClass.name, expression)
            .redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).describedAs("Raw driver probe must terminate: %s", expression).isTrue()
            val text = Files.readString(output)
            if (expression == "[row(1,'x'),NULL]") {
                // A negative control for the temporary guard, NOT a passing value mapping.
                assertThat(process.exitValue()).isNotZero()
                assertThat(text).contains("SIGSEGV", "VectorCastHelpers::StringCast")
            } else {
                assertThat(process.exitValue()).describedAs("%s\n%s", expression, text).isZero()
            }
        } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
    }

    companion object {
        private val cases = listOf("NULL", "[NULL,NULL]", "row(1,'x')", "[row(1,'x'),NULL]", "CAST({'a':1} AS VARIANT)",
            "JSON '{\"x\":1,\"x\":2}'", "JSON 'null'", "CAST(NULL AS JSON)",
            "{'a':[1,NULL], 'b':1.2300::DECIMAL(10,4)}", "map(['a'],[1])", "[[1,NULL],[2]]")

        @JvmStatic
        fun main(args: Array<String>) {
            DriverManager.getConnection("jdbc:duckdb:").use { TestBoundaryCarrierContracts().captureCase(it, 0, args.single()) }
        }
    }
}
