package dev.brikk.duckbridge.trino.plugin

import com.gizmodata.quack.jdbc.sql.QuackDriver
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowReader
import org.duckdb.DuckDBResultSet
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.io.path.writeText

/** Read-only transport inventory. This probe does not grant a type mapping or equate errors. */
class TestNanosTransportProbe {
    @Test
    fun recordsRawGettersAndArrowSchemas() {
        val output = mutableListOf<String>()
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            captureJdbc(connection, "embedded", output)
            connection.createStatement().use { it.execute("SET TimeZone='America/New_York'") }
            captureJdbc(connection, "embedded_new_york", output)
            for ((label, expression) in expressions()) captureArrow(connection, label, expression, output)
        }
        TestingQuackServer().use { server ->
            QuackDriver().connect(server.connectionUrl(), Properties().apply { setProperty("token", server.token) }).use { connection ->
                captureJdbc(connection, "quack", output)
            }
        }
        val directory = Files.createDirectories(Path.of("build/function-audit"))
        directory.resolve("nanos-transport-probe.txt").writeText(output.joinToString("\n"))
    }

    private fun expressions(): Map<String, String> = linkedMapOf(
        "timestamp_ns" to "CAST('1969-12-31 23:59:59.999999999' AS TIMESTAMP_NS)",
        "timestamp_ns_inf" to "CAST('infinity' AS TIMESTAMP_NS)",
        "timestamp_ns_neginf" to "CAST('-infinity' AS TIMESTAMP_NS)",
        "timestamptz_ns" to "CAST('1969-12-31 23:59:59.999999999+00' AS TIMESTAMPTZ_NS)",
        "timestamptz_ns_inf" to "CAST('infinity' AS TIMESTAMPTZ_NS)",
        "timestamptz_ns_min" to "CAST('1677-09-21 00:12:43.145224194+00' AS TIMESTAMPTZ_NS)",
        "time_ns" to "CAST('23:59:59.999999999' AS TIME_NS)",
        "time_ns_24" to "CAST('24:00:00' AS TIME_NS)",
        "timetz" to "TIMETZ '12:34:56.123456+05:30'",
    )

    private fun captureJdbc(connection: Connection, transport: String, output: MutableList<String>) {
        for ((label, expression) in expressions()) {
            try {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT $expression, CAST($expression AS VARCHAR)").use { rows ->
                        check(rows.next())
                        output.add("$transport $label: ${rows.metaData.getColumnTypeName(1)} " +
                            "object=${rows.getObject(1)} string=${rows.getString(1)} server=${rows.getString(2)}")
                    }
                }
            } catch (error: SQLException) { output.add("$transport $label ERROR: ${error.message}") }
            catch (error: java.time.DateTimeException) { output.add("$transport $label GETTER_ERROR: ${error.message}") }
        }
    }

    private fun captureArrow(connection: Connection, label: String, expression: String, output: MutableList<String>) {
        try {
            connection.createStatement().use { statement ->
                    (statement.executeQuery("SELECT $expression AS value") as DuckDBResultSet).use { result ->
                        arrowResult(result, label, output)
                    }
            }
        } catch (error: SQLException) { output.add("arrow $label ERROR: ${error.message}") }
        catch (error: java.time.DateTimeException) { output.add("arrow $label GETTER_ERROR: ${error.message}") }
    }

    private fun arrowResult(result: DuckDBResultSet, label: String, output: MutableList<String>) {
        RootAllocator().use { allocator ->
            (result.arrowExportStream(allocator, 1024) as ArrowReader).use { reader ->
                check(reader.loadNextBatch())
                val vector = reader.vectorSchemaRoot.getVector(0)
                output.add("arrow $label: ${vector.field} ${vector.javaClass.simpleName} object=${vector.getObject(0)}")
            }
        }
    }
}
