package dev.brikk.duckbridge.doris.audit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties
import kotlin.io.path.writeText
import kotlin.io.path.readBytes

/** Capture, not type support: do not hide driver getter failures behind a cast or a legacy setting. */
object DuckDbBoundaryAudit {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected output and development Quack JDBC URL" }
        Class.forName("org.duckdb.DuckDBDriver")
        Class.forName("com.gizmodata.quack.jdbc.sql.QuackDriver")
        val reports = mutableListOf<JsonObject>()
        DriverManager.getConnection("jdbc:duckdb:").use { reports.add(capture(it, "embedded")) }
        val properties = Properties().apply {
            setProperty("token", System.getenv("FUNCTION_AUDIT_QUACK_TOKEN") ?: "duckbridge-doris-token")
        }
        DriverManager.getConnection(args[1], properties).use { reports.add(capture(it, "quack")) }
        val output = Path.of(args[0])
        Files.createDirectories(output.parent)
        output.writeText(JsonArray(reports).toString())
        println("Captured independent JDBC/Quack boundary contracts; no new type mapping enabled")
    }

    private fun capture(connection: Connection, transport: String): JsonObject {
        GeneratedSqlAudit.verifyDuck(connection)
        val driver = connection.metaData.driverVersion
        // duckdb_jdbc 2.0.0.0 still reports the metadata stub "1.0"; pin the real artifact bytes.
        val jar = Path.of(connection.javaClass.protectionDomain.codeSource.location.toURI())
        val sha256 = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
        val expected = if (transport == "quack") System.getProperty("duckbridge.qualification.quack.sha256",
            "7c5889640b38e494a39c0bf818f1e0e35fb68152a00e0dcc9f273a5f29935ff3")
            else "5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186"
        check(sha256 == expected) { "Unqualified $transport driver bytes: $jar ($sha256)" }
        if (transport == "quack") check(driver == System.getProperty("duckbridge.qualification.quack.version", "0.11.0")) {
            "Unexpected Quack driver: $driver"
        }
        connection.createStatement().use { statement ->
            statement.execute("CREATE TEMP TABLE duckbridge_audit_boundary_null AS SELECT NULL AS v, CAST(NULL AS INTEGER) AS typed_v")
        }
        val observations = cases().map { (label, sql) -> buildJsonObject {
            put("case", label)
            put("sql", sql)
            put("result", query(connection, sql))
        } }
        GeneratedSqlAudit.verifyDuck(connection)
        return buildJsonObject {
            put("transport", transport)
            put("driver", driver)
            put("driver_jar", jar.fileName.toString())
            put("driver_sha256", sha256)
            put("observations", JsonArray(observations))
            put("ctas_columns", columns(connection))
            put("scope", "JDBC metadata/getters only; Arrow and host mapping qualification remain separate")
        }
    }

    private fun cases(): Map<String, String> = buildMap {
        put("untyped_null", "SELECT NULL AS v")
        put("values_null", "VALUES (NULL)")
        put("typed_null", "SELECT CAST(NULL AS INTEGER) AS v")
        put("all_null_array", "SELECT [NULL,NULL] AS v")
        put("all_null_struct_field", "SELECT struct_pack(a:=NULL) AS v")
        put("all_null_tuple", "SELECT row(NULL,NULL) AS v")
        put("ctas_null", "SELECT v,typed_v,typeof(v) AS logical_type FROM duckbridge_audit_boundary_null")
        put("empty_result", "SELECT CAST(NULL AS INTEGER) AS v WHERE false")
        put("cast_via_microseconds", "SELECT CAST(TIMESTAMP '2024-01-02 03:04:05.123456789' AS TIMESTAMP_NS) AS via_micros," +
            "CAST('2024-01-02 03:04:05.123456789' AS TIMESTAMP_NS) AS direct_nanos")
        for (type in listOf("TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP", "TIMESTAMP_NS", "TIMESTAMPTZ", "TIMESTAMPTZ_NS")) {
            for (value in listOf("2024-01-02 03:04:05.123456789", "1969-12-31 23:59:59.999999999")) {
                val literal = if (type.startsWith("TIMESTAMPTZ")) "$value+00" else value
                val temporal = "CAST('$literal' AS $type)"
                put("$type/$value", "SELECT v,typeof(v) AS logical_type,CAST(v AS VARCHAR) AS server_text FROM (SELECT $temporal AS v)")
            }
        }
        for (type in listOf("TIME", "TIME_NS", "TIMETZ")) {
            put(type, "SELECT v,typeof(v) AS logical_type,CAST(v AS VARCHAR) AS server_text FROM (SELECT CAST('12:34:56.123456789+05:30' AS $type) AS v)")
        }
        put("settings", "SELECT name,value,typeof(value) AS value_type FROM duckdb_settings() " +
            "WHERE name IN ('preserve_identifier_case','default_transaction_invalidation_policy','legacy_disable_null_type','TimeZone') ORDER BY name")
    }

    private fun query(connection: Connection, sql: String): JsonObject = try {
        connection.createStatement().use { statement -> statement.executeQuery(sql).use { rows ->
            val metadata = rows.metaData
            val columns = (1..metadata.columnCount).map { index -> buildJsonObject {
                put("name", metadata.getColumnName(index))
                put("jdbc_type", metadata.getColumnType(index))
                put("type_name", metadata.getColumnTypeName(index))
                put("class_name", getter { metadata.getColumnClassName(index) })
                put("precision", metadata.getPrecision(index))
                put("scale", metadata.getScale(index))
                put("nullable", metadata.isNullable(index))
            } }
            buildJsonObject {
                put("status", "ok")
                put("columns", JsonArray(columns))
                put("rows", resultRows(rows))
            }
        } }
    } catch (error: SQLException) { error(error) }

    private fun resultRows(rows: ResultSet): JsonArray = JsonArray(buildList {
        while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { index -> cell(rows, index) }))
    })

    private fun cell(rows: ResultSet, index: Int): JsonObject = buildJsonObject {
        put("object", getter { rows.getObject(index) })
        put("string", getter { rows.getString(index) })
        if (rows.metaData.getColumnType(index) == java.sql.Types.TIMESTAMP) {
            put("timestamp", getter { rows.getTimestamp(index) })
        }
    }

    private fun columns(connection: Connection): JsonElement = try {
        connection.metaData.getColumns(null, null, "duckbridge_audit_boundary_null", "%").use { rows ->
            JsonArray(buildList {
                while (rows.next()) add(column(rows))
            })
        }
    } catch (error: SQLException) { error(error) }

    private fun column(rows: ResultSet): JsonObject = buildJsonObject {
        for (name in listOf("TABLE_CAT", "TABLE_SCHEM", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "DECIMAL_DIGITS")) {
            put(name, rows.getString(name)?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    }

    private fun getter(read: () -> Any?): JsonObject = try {
        val value = read()
        buildJsonObject {
            put("class", value?.javaClass?.name?.let { JsonPrimitive(it) } ?: JsonNull)
            put("value", value?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    } catch (error: SQLException) { error(error) }

    private fun error(error: SQLException): JsonObject = buildJsonObject {
        put("status", "error")
        put("sql_state", error.sqlState?.let { JsonPrimitive(it) } ?: JsonNull)
        put("diagnostic", error.message.orEmpty())
    }
}
