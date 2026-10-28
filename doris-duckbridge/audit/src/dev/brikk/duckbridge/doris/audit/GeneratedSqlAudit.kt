package dev.brikk.duckbridge.doris.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Locale
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Read real JDBC result types; do not assume the host implements DuckDB's TYPEOF function. */
object GeneratedSqlAudit {
    private sealed interface Outcome {
        data class Value(val type: String, val precision: Int, val scale: Int, val nullable: Int, val hex: String?) : Outcome
        data class Error(val state: String?, val code: Int, val diagnostic: String) : Outcome
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Expected metadata directory, Doris JDBC URL, and exact Doris version" }
        val directory = Path.of(args[0])
        val fixtures = Json.parseToJsonElement(directory.resolve("generated.json").readText()).jsonArray
        Class.forName("org.mariadb.jdbc.Driver")
        Class.forName("org.duckdb.DuckDBDriver")
        DriverManager.getConnection(args[1], "root", "").use { host ->
            verifySource(host, args[2])
            DriverManager.getConnection("jdbc:duckdb:").use { duck ->
                val duckVersion = verifyDuck(duck)
                control(host)
                control(duck)
                val results = fixtures.map { it.jsonObject }.filter { it.string("source") == "doris" }.map { fixture ->
                    observe(fixture, host, duck)
                }
                check(results.isNotEmpty()) { "No Doris fixtures were evaluated" }
                control(host)
                control(duck)
                verifySource(host, args[2])
                directory.resolve("published-doris-observations.json").writeText(JsonArray(results).toString())
                directory.resolve("oracle-versions.tsv").writeText(
                    "engine\tversion\tverified\ndoris-fe-be\t${args[2]}\ttrue\nduckdb\t$duckVersion\ttrue\n",
                )
                println("Recorded ${results.size} typed Doris/DuckDB observations; no pushdown enabled")
            }
        }
    }

    private fun verifyDuck(duck: Connection): String = duck.createStatement().use { statement ->
        statement.executeQuery("SELECT version()").use { rows ->
            check(rows.next()) { "DuckDB oracle returned no version" }
            rows.getString(1).also { check(it == "v2.0.0") { "Expected DuckDB v2.0.0 oracle, got $it" } }
        }
    }

    private fun verifySource(host: Connection, expected: String) {
        for (statement in listOf("SHOW FRONTENDS", "SHOW BACKENDS")) {
            host.createStatement().use { query ->
                query.executeQuery(statement).use { rows ->
                    verifyAliveRows(rows, expected)
                }
            }
        }
    }

    private fun verifyAliveRows(rows: ResultSet, expected: String) {
        var alive = 0
        while (rows.next()) {
            if (rows.getBoolean("Alive")) {
                check(rows.getString("Version") == expected) { "Oracle contains a wrong-source engine" }
                alive++
            }
        }
        check(alive > 0) { "Oracle contains no alive exact-pin engine" }
    }

    private fun control(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT 1").use { rows ->
                check(rows.next() && rows.getInt(1) == 1 && !rows.next()) { "Oracle control failed, not a function divergence" }
            }
        }
    }

    private fun observe(fixture: JsonObject, host: Connection, duck: Connection): JsonObject {
        if (fixture.string("status") != "EMITTED") {
            return buildJsonObject {
                put("case", fixture.string("case"))
                put("observation", "GENERATOR_REFUSED")
                put("diagnostic", fixture.string("diagnostic"))
                put("pushdown_qualified", false)
            }
        }
        val sourceSql = fixture.string("input_sql")
        val targetSql = fixture.string("duckdb_sql")
        val source = execute(host, sourceSql)
        val remote = execute(duck, targetSql)
        val classification = when {
            source !is Outcome.Value || remote !is Outcome.Value -> "ERROR_REQUIRES_REVIEW"
            source.hex != remote.hex -> "DIFFERENT_RESULT_BYTES"
            source != remote -> "MATCHING_BYTES_METADATA_DIFFERS_NOT_QUALIFIED"
            else -> "MATCHING_FIXTURE_NOT_QUALIFIED"
        }
        return buildJsonObject {
            put("case", fixture.string("case"))
            put("source_sql", sourceSql)
            put("duckdb_sql", targetSql)
            put("doris", describe(source))
            put("duckdb", describe(remote))
            put("observation", classification)
            put("pushdown_qualified", false)
        }
    }

    private fun execute(connection: Connection, sql: String): Outcome {
        require(sql.startsWith("SELECT ") && ';' !in sql) { "Only reviewed scalar SELECT fixtures are accepted" }
        return try {
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.metaData.columnCount == 1 && rows.next()) { "Expected one scalar column and row" }
                    val metadata = rows.metaData
                    val text = rows.getString(1)
                    val hex = if (rows.wasNull()) null else text.toByteArray(StandardCharsets.UTF_8).joinToString("") { "%02X".format(it) }
                    val value = Outcome.Value(metadata.getColumnTypeName(1).lowercase(Locale.ROOT),
                        metadata.getPrecision(1), metadata.getScale(1), metadata.isNullable(1), hex)
                    check(!rows.next()) { "Expected one scalar row" }
                    value
                }
            }
        } catch (failure: SQLException) {
            Outcome.Error(failure.sqlState, failure.errorCode, failure.message.orEmpty())
        }
    }

    private fun describe(outcome: Outcome): JsonElement = buildJsonObject {
        when (outcome) {
            is Outcome.Value -> {
                put("status", "ok")
                put("type", outcome.type)
                put("precision", outcome.precision)
                put("scale", outcome.scale)
                put("nullable", outcome.nullable)
                put("sql_null", outcome.hex == null)
                put("hex", outcome.hex?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            is Outcome.Error -> {
                put("status", "error")
                put("sql_state", outcome.state?.let { JsonPrimitive(it) } ?: JsonNull)
                put("vendor_code", outcome.code)
                put("diagnostic", outcome.diagnostic)
            }
        }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
