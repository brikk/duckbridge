package dev.brikk.duckbridge.doris.audit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.writeText

/** Discovery only: matching errors or one matching path never establishes a JSON pushdown contract. */
object JsonMutationAudit {
    private val inputs = listOf(
        """{"a":1,"b":null,"arr":[null,2],"nested":{"z":0,"a":1}}""",
        "{}", null, """{"a":1,"a":2}""", """{"a.b":1}""", """[1,null,{"a":1}]""", "invalid",
    )
    private val paths = listOf(
        "$", "$.a", "$.missing", "$.nested.a", "$.nested.missing", "$.\"a.b\"",
        "$.arr[0]", "$.arr[2]", "$.arr[-1]", "$.arr[#-1]", "$[0]", "$.*", "invalid", "", null,
    )
    private val values = listOf("CAST('9' AS JSON)", "CAST('null' AS JSON)", "CAST('\"x\"' AS JSON)",
        "CAST('[1,null]' AS JSON)", "CAST(NULL AS JSON)", "9")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Expected output, Doris JDBC URL, exact Doris version" }
        Class.forName("org.mariadb.jdbc.Driver")
        Class.forName("org.duckdb.DuckDBDriver")
        DriverManager.getConnection(args[1], "root", "").use { host ->
            GeneratedSqlAudit.verifySource(host, args[2])
            verifyInputs(host)
            DriverManager.getConnection("jdbc:duckdb:").use { duck ->
                val version = GeneratedSqlAudit.verifyDuck(duck)
                control(host)
                control(duck)
                val observations = observeAll(host, duck)
                control(host)
                control(duck)
                GeneratedSqlAudit.verifySource(host, args[2])
                val report = buildJsonObject {
                    put("doris_version", args[2])
                    put("duckdb_version", version)
                    put("pushdown_qualified", false)
                    put("observations", JsonArray(observations))
                }
                val output = Path.of(args[0])
                Files.createDirectories(output.parent)
                output.writeText(report.toString())
                println("Recorded ${observations.size} native JSON mutation observations; no new pushdown enabled")
            }
        }
    }

    private fun verifyInputs(host: Connection) {
        host.createStatement().use { statement ->
            statement.executeQuery("SELECT id,payload FROM duckbridge_test.sales.function_inputs ORDER BY id").use { rows ->
                for ((index, expected) in inputs.withIndex()) {
                    check(rows.next() && rows.getInt(1) == index + 1 && rows.getString(2) == expected) { "Input fixture differs at $index" }
                }
                check(!rows.next()) { "Unexpected extra input rows" }
            }
        }
    }

    private fun control(connection: Connection) {
        val result = GeneratedSqlAudit.scalarObservation(connection, "SELECT 1")
        check(result["status"]?.jsonPrimitive?.content == "ok" && result["hex"]?.jsonPrimitive?.content == "31") {
            "Dead/poisoned oracle, not a semantic observation: $result"
        }
    }

    private fun observeAll(host: Connection, duck: Connection): List<JsonObject> = buildList {
        for ((index, input) in inputs.withIndex()) {
            for (path in paths) {
                val arguments = listOf<String?>(null) + values
                for (value in arguments) add(observe(host, duck, index, input, path, value))
            }
        }
    }

    private fun observe(host: Connection, duck: Connection, index: Int, input: String?, path: String?, value: String?): JsonObject {
        val function = if (value == null) "json_remove" else "json_insert"
        val tail = ",${literal(path)}" + if (value == null) "" else ",$value"
        val hostSql = "SELECT $function(CAST(payload AS JSON)$tail) FROM duckbridge_test.sales.function_inputs WHERE id=${index + 1}"
        val duckSql = "SELECT $function(CAST(${literal(input)} AS JSON)$tail)"
        val source = GeneratedSqlAudit.scalarObservation(host, hostSql)
        val remote = GeneratedSqlAudit.scalarObservation(duck, duckSql)
        val sourceOk = source["status"]?.jsonPrimitive?.content == "ok"
        val remoteOk = remote["status"]?.jsonPrimitive?.content == "ok"
        val classification = when {
            !sourceOk || !remoteOk -> "ERROR_REQUIRES_REVIEW"
            source["hex"] != remote["hex"] || source["sql_null"] != remote["sql_null"] -> "DIFFERENT_VALUE_OR_NULL"
            else -> "MATCHING_FIXTURE_NOT_QUALIFIED"
        }
        return buildJsonObject {
            put("function", function)
            put("input_id", index + 1)
            put("doris_sql", hostSql)
            put("duckdb_sql", duckSql)
            put("doris", source)
            put("duckdb", remote)
            put("observation", classification)
        }
    }

    private fun literal(value: String?): String = if (value == null) "NULL" else "'${value.replace("'", "''")}'"
}
