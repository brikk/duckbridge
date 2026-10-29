package dev.brikk.duckbridge.doris.audit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.writeText

/** Native signatures and NULL propagation; the larger BE/EXPLAIN matrix is in overlay_qualification.py. */
object OverlayTypeAudit {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Expected output, Doris URL, exact Doris version" }
        Class.forName("org.mariadb.jdbc.Driver")
        Class.forName("org.duckdb.DuckDBDriver")
        val inputs = listOf(
            "'abcd'" to "'X'", "'a😀bc'" to "'界'", "''" to "'X'",
            "CAST(NULL AS VARCHAR)" to "'X'", "'abcd'" to "CAST(NULL AS VARCHAR)",
        )
        val fixtures = listOf("VARCHAR(32)", "STRING").flatMap { type -> inputs.map { (source, replacement) ->
            "CAST($source AS $type)" to "CAST($replacement AS $type)"
        } }
        val results = mutableListOf<JsonObject>()
        DriverManager.getConnection(args[1], "root", "").use { host ->
            GeneratedSqlAudit.verifySource(host, args[2])
            DriverManager.getConnection("jdbc:duckdb:").use { duck ->
                GeneratedSqlAudit.verifyDuck(duck)
                for ((input, insert) in fixtures) {
                    val sql = "SELECT overlay($input,2,1,$insert)"
                    val target = "SELECT CASE WHEN $input IS NULL OR $insert IS NULL THEN NULL " +
                        "WHEN length($input)<2 THEN $input ELSE overlay($input PLACING $insert FROM 2 FOR 1) END"
                    val native = GeneratedSqlAudit.scalarObservation(host, sql)
                    val remote = GeneratedSqlAudit.scalarObservation(duck, target)
                    check(native["status"]?.jsonPrimitive?.content == "ok" && remote["status"]?.jsonPrimitive?.content == "ok") {
                        "An engine failed; not a matching return contract: $native / $remote"
                    }
                    check(native["hex"] == remote["hex"] && native["sql_null"] == remote["sql_null"]) { "Different values: $native / $remote" }
                    check(native["type"]?.jsonPrimitive?.content in setOf("varchar", "char", "text") &&
                        remote["type"]?.jsonPrimitive?.content == "varchar") { "Unexpected return type: $native / $remote" }
                    results.add(buildJsonObject {
                        put("doris_sql", sql)
                        put("duckdb_sql", target)
                        put("doris", native)
                        put("duckdb", remote)
                        put("scope", "String predicate values only; no computed projection/return-metadata pushdown")
                    })
                }
            }
            GeneratedSqlAudit.verifySource(host, args[2])
        }
        val output = Path.of(args[0])
        Files.createDirectories(output.parent)
        output.writeText(JsonArray(results).toString())
        println("Verified ${results.size} native OVERLAY string/NULL return contracts")
    }
}
