package dev.brikk.duckbridge.doris.audit

import dev.brikk.house.sql.BrikkSql
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.metadata.DUCKDB_FUNCTIONS
import dev.brikk.house.sql.metadata.DUCKDB_TYPES
import dev.brikk.house.sql.metadata.DuckdbCapture
import dev.brikk.house.sql.metadata.DuckdbDump
import dev.brikk.house.sql.metadata.MetadataSnapshot
import dev.brikk.house.sql.metadata.bundledMetadata
import dev.brikk.house.sql.metadata.toSnapshot
import dev.brikk.house.sql.parser.ParseError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.writeText

/** Explicitly opt-in audit tooling. Nothing in this source set is a connector/runtime dependency. */
object PublishedFunctionAudit {
    private val dialects = listOf("duckdb", "doris", "trino")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Expected output directory, exact Doris version and Trino version" }
        val output = Files.createDirectories(Path.of(args[0]))
        exportCatalogs(output, args[1], args[2])
        captureDuckDb(output)
        exportGeneratedSql(output)
        exportProvenance(output)
        println("Published brikk-sql catalogs and live DuckDB registry exported to $output")
    }

    private fun exportCatalogs(output: Path, dorisVersion: String, trinoVersion: String) {
        val selections = buildString {
            appendLine("dialect\trequested\tselected\texact")
            for (dialect in dialects) {
                val metadata = checkNotNull(bundledMetadata(dialect)) { "No metadata for $dialect" }
                for (snapshot in metadata.snapshots) {
                    output.resolve("$dialect-${snapshot.engineVersion}.json")
                        .writeText(snapshot.functions.toJson(pretty = true))
                    exportTypes(snapshot, output.resolve("$dialect-${snapshot.engineVersion}-types.tsv"))
                }
                val requested = when (dialect) {
                    "duckdb" -> "v2.0.0"
                    "trino" -> trinoVersion
                    else -> dorisVersion
                }
                val selection = metadata.forServerVersion(requested)
                appendLine("$dialect\t$requested\t${selection.snapshot.engineVersion}\t${selection.exact}")
            }
        }
        output.resolve("selections.tsv").writeText(selections)
    }

    private fun captureDuckDb(output: Path) {
        Class.forName("org.duckdb.DuckDBDriver")
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val version = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version()").use { result ->
                    check(result.next()) { "DuckDB did not report a version" }
                    result.getString(1).removePrefix("v")
                }
            }
            check(version == "2.0.0") { "Audit requires released DuckDB 2.0.0, got $version" }
            val rows = capture(connection, DUCKDB_FUNCTIONS)
            val captured = DuckdbDump(engineVersion = version, functions = rows, types = capture(connection, DUCKDB_TYPES)).toSnapshot()
            output.resolve("duckdb-live-$version.json").writeText(captured.functions.toJson(pretty = true))
            exportTypes(captured, output.resolve("duckdb-live-$version-types.tsv"))
            output.resolve("capture.tsv").writeText("engine\tversion\trows\tdefinitions\nduckdb\t$version\t${rows.size}\t${captured.functions.size}\n")
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT function_name, function_type, has_side_effects, stability " +
                        "FROM duckdb_functions() WHERE function_type='scalar' ORDER BY ALL",
                ).use { result ->
                    output.resolve("duckdb-live-$version-properties.tsv").writeText(
                        buildString {
                            appendLine("name\tkind\thas_side_effects\tstability")
                            while (result.next()) appendLine((1..4).joinToString("\t") { result.getString(it) ?: "UNKNOWN" })
                        },
                    )
                }
            }
        }
    }

    private fun <T> capture(connection: Connection, capture: DuckdbCapture<T>): List<T> =
        connection.createStatement().use { statement ->
            statement.executeQuery(capture.sql).use { result ->
                buildList {
                    while (result.next()) add(capture.decode(capture.columns.indices.map { result.getString(it + 1) }))
                }
            }
        }

    private fun exportTypes(snapshot: MetadataSnapshot, path: Path) {
        path.writeText(
            "name\tlogical_type\tcategory\n" + snapshot.types.orEmpty().joinToString("\n") {
                "${it.name}\t${it.logicalType}\t${it.category.orEmpty()}"
            } + "\n",
        )
    }

    private fun exportGeneratedSql(output: Path) {
        // Generated SQL is an observation, NEVER an instruction to widen predicate pushdown.
        val cases = listOf(
            Triple("doris", "length", "SELECT LENGTH('straße')"),
            Triple("doris", "length_astral", "SELECT LENGTH('a😀b')"),
            Triple("doris", "character_length", "SELECT CHARACTER_LENGTH('straße')"),
            Triple("doris", "json_set", "SELECT JSON_SET('{\"a\":1,\"a\":2}', '$.a', 9)"),
            Triple("doris", "json_set_typed", "SELECT JSON_SET('{\"a\":1,\"a\":2}', '$.a', CAST('9' AS JSON))"),
            Triple("doris", "json_insert", "SELECT JSON_INSERT('{}', '$.a', 9)"),
            Triple("doris", "json_remove", "SELECT JSON_REMOVE('{\"a\":1}', '$.a')"),
            Triple("doris", "json_remove_invalid", "SELECT JSON_REMOVE('invalid', '$.a')"),
            Triple("doris", "overlay", "SELECT OVERLAY('abcd', 2, 1, 'X')"),
            Triple("doris", "overlay_unicode", "SELECT OVERLAY('a😀bc', 2, 1, 'X')"),
            Triple("doris", "overlay_zero_start", "SELECT OVERLAY('abcd', 0, 2, 'X')"),
            Triple("trino", "regexp_count_empty", "SELECT regexp_count('ab', '')"),
            Triple("trino", "regexp_count", "SELECT regexp_count('ababa', 'a')"),
            Triple("trino", "regexp_count_overlap", "SELECT regexp_count('ababa', 'aba')"),
            Triple("trino", "regexp_count_empty_unicode", "SELECT regexp_count('a😀b', '')"),
            Triple("trino", "regexp_count_null", "SELECT regexp_count(CAST(NULL AS VARCHAR), 'a')"),
            Triple("trino", "regexp_count_null_pattern", "SELECT regexp_count('abc', CAST(NULL AS VARCHAR))"),
            Triple("trino", "regexp_count_lookbehind", "SELECT regexp_count('ab', '(?<=a)b')"),
            Triple("trino", "regexp_count_lookahead", "SELECT regexp_count('aaa', '(?=a)')"),
            Triple("trino", "regexp_count_invalid", "SELECT regexp_count('abc', '[a')"),
            Triple("trino", "regexp_position", "SELECT regexp_position('ababc', 'b')"),
            Triple("trino", "regexp_position_empty", "SELECT regexp_position('abc', '')"),
        )
        val generated = cases.map { (dialect, label, sql) ->
            val result = generate(dialect, sql)
            listOf(dialect, label, sql, result.sql, result.status, result.diagnostic)
        }
        val fields = listOf("source", "case", "input_sql", "duckdb_sql", "status", "diagnostic")
        output.resolve("generated.tsv").writeText(fields.joinToString("\t") + "\n" + generated.joinToString("\n") { tsv(it) } + "\n")
        output.resolve("generated.json").writeText(JsonArray(generated.map { row ->
            buildJsonObject { fields.zip(row).forEach { (key, value) -> put(key, value) } }
        }).toString())
    }

    private data class Generation(val sql: String, val status: String, val diagnostic: String = "")

    private fun generate(source: String, sql: String): Generation =
        try {
            val expression = Dialects.forName(source).parseOne(sql)
            val generator = Dialects.forName("duckdb").generator(sourceDialect = source)
            val generated = generator.generate(expression)
            if (generator.unsupportedMessages.isEmpty()) {
                Generation(generated, "EMITTED")
            } else {
                Generation(generated, "REFUSED", generator.unsupportedMessages.joinToString("; "))
            }
        } catch (error: UnsupportedError) {
            Generation("", "REFUSED", error.message.orEmpty())
        } catch (error: ParseError) {
            Generation("", "SOURCE_PARSE_REJECTED", error.message.orEmpty())
        }

    private fun tsv(fields: List<String>): String = fields.joinToString("\t") { value ->
        if (value.any { it in "\t\r\n\"" }) "\"${value.replace("\"", "\"\"")}\"" else value
    }

    private fun exportProvenance(output: Path) {
        output.resolve("runtime.tsv").writeText(
            "java\tkotlin\tsqlglot\n${System.getProperty("java.version")}\t${KotlinVersion.CURRENT}\t${BrikkSql.SQLGLOT_PIN}\n",
        )
        val jars = System.getProperty("java.class.path").split(File.pathSeparator)
            .map(Path::of).filter { Files.isRegularFile(it) && it.toString().endsWith(".jar") }
        output.resolve("artifacts.tsv").writeText(
            "file\tsha256\n" + jars.sortedBy { it.fileName.toString() }.joinToString("\n") {
                "${it.fileName}\t${sha256(it)}"
            } + "\n",
        )
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var length = input.read(buffer)
            while (length >= 0) {
                digest.update(buffer, 0, length)
                length = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
