package dev.brikk.duckbridge.trino.plugin

import io.trino.Session
import io.trino.spi.type.SqlDate
import io.trino.spi.type.SqlDecimal
import io.trino.spi.type.SqlTimestamp
import io.trino.spi.type.SqlTimestampWithTimeZone
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Test-only outcomes: engine failures retain their actual category; arbitrary errors never establish parity. */
internal data class EdgeAuditResult(
    val types: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val errorCode: String? = null,
    val errorType: String? = null,
    val message: String? = null,
) {
    fun sameValue(other: EdgeAuditResult): Boolean = when {
        errorCode != null || other.errorCode != null -> errorCode == other.errorCode && errorType == other.errorType && message == other.message
        else -> rows == other.rows
    }
    fun describe(): String = if (errorCode == null) rows.joinToString(";") else "ERROR[$errorCode/$errorType] $message"
    fun sameContract(other: EdgeAuditResult): Boolean = sameValue(other) && types == other.types
}

internal object EdgeAuditValue {
    fun canonical(value: Any?): String = when (value) {
        null -> "NULL"
        is Double -> if (value.isNaN()) "F:NaN" else "F:${java.lang.Long.toUnsignedString(java.lang.Double.doubleToRawLongBits(value), 16)}"
        is Float -> canonical(value.toDouble())
        is Byte, is Short, is Int, is Long -> "I:$value"
        is Boolean -> "B:$value"
        is BigDecimal -> "D:${value.toPlainString()}"
        is SqlDecimal -> canonical(value.toBigDecimal())
        is String -> "S:$value"
        else -> temporal(value)
    }

    private fun temporal(value: Any): String = when (value) {
        is SqlDate -> "DATE:${value.days}"
        is LocalDate -> "DATE:${value.toEpochDay()}"
        is SqlTimestamp -> "TS:${value.epochMicros}:${value.picosOfMicros}"
        is LocalDateTime -> "TS:${value.toEpochSecond(ZoneOffset.UTC) * 1_000_000 + value.nano / 1000}:${value.nano % 1000 * 1000}"
        is SqlTimestampWithTimeZone -> "TZ:${value.epochMillis}:${value.picosOfMilli}"
        is OffsetDateTime -> "TZ:${value.toInstant().toEpochMilli()}:${value.nano % 1_000_000 * 1000}"
        is ZonedDateTime -> canonical(value.toOffsetDateTime())
        else -> error("Unsupported audit carrier: ${value.javaClass.name}")
    }

    fun display(value: String): String = value.replace("\\", "\\\\").replace("\t", "\\t")
        .replace("\r", "\\r").replace("\n", "\\n").replace("\u0000", "\\0")
}

internal class EdgeAuditContext(
    val duck: Connection,
    val session: Session,
    val output: Path,
    val host: (Session, String) -> EdgeAuditResult,
) {
    fun trino(sql: String, zone: Session = session): EdgeAuditResult = host(zone, sql)

    fun native(sql: String, doubleValue: Boolean = false): EdgeAuditResult = try {
        duck.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                val meta = rows.metaData
                val types = (1..meta.columnCount).map { "${meta.getColumnTypeName(it)}(${meta.getPrecision(it)},${meta.getScale(it)})" }
                EdgeAuditResult(types, buildList {
                    while (rows.next()) add((1..meta.columnCount).map { index ->
                        val value: Any? = when {
                            doubleValue && index > 1 -> rows.getDouble(index).let { if (rows.wasNull()) null else it }
                            meta.getColumnTypeName(index).uppercase() == "DATE" -> rows.getObject(index, LocalDate::class.java)
                            meta.getColumnTypeName(index).uppercase() == "TIMESTAMP" -> rows.getObject(index, LocalDateTime::class.java)
                            meta.getColumnTypeName(index).uppercase() in setOf("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE") ->
                                rows.getObject(index, OffsetDateTime::class.java)
                            else -> rows.getObject(index)
                        }
                        EdgeAuditValue.canonical(value)
                    })
                })
            }
        }
    } catch (failure: SQLException) {
        EdgeAuditResult(errorCode = failure.sqlState ?: "NO_SQLSTATE", errorType = failure.javaClass.name, message = failure.message)
    }

    fun controls() {
        check(trino("SELECT CAST(42 AS BIGINT)").rows == listOf(listOf("I:42"))) { "Trino oracle is not healthy" }
        check(native("SELECT CAST(42 AS BIGINT)").rows == listOf(listOf("I:42"))) { "DuckDB oracle is not healthy" }
        check(native("SELECT version()").rows == listOf(listOf("S:v2.0.0"))) { "Wrong native engine" }
        check(native("SELECT source_id,current_setting('ieee_floating_point_ops') FROM pragma_version()").rows ==
            listOf(listOf("S:d54cbbe3df", "B:true"))) { "Wrong native source/settings profile" }
    }

    fun write(name: String, rows: List<List<String>>) {
        Files.createDirectories(output)
        Files.write(output.resolve(name), rows.map { it.joinToString("\t", transform = EdgeAuditValue::display) })
    }

    /** An alternate native-Trino reference query is allowed only for an explicit, independently tested engine limitation. */
    fun predicate(table: String, predicate: String, index: String, zone: Session = session,
                  nativePredicate: String = predicate, oracleInput: String = PushdownEdgeFixtures.trinoInput(table),
                  oracleQuery: String? = null): List<String> {
        val expectedSql = oracleQuery ?: "$oracleInput SELECT id FROM input WHERE $predicate ORDER BY id"
        val expected = trino(expectedSql, zone)
        val localSql = "SELECT id FROM edge_local.audit.$table WHERE $predicate ORDER BY id"
        val productionSql = "SELECT id FROM edge_prod.audit.$table WHERE $predicate ORDER BY id"
        val local = trino(localSql, zone)
        val production = trino(productionSql, zone)
        val native = native("SELECT id FROM audit.$table WHERE $nativePredicate ORDER BY id")
        val localPlan = trino("EXPLAIN (TYPE DISTRIBUTED) $localSql", zone)
        val productionPlan = trino("EXPLAIN (TYPE DISTRIBUTED) $productionSql", zone)
        Files.createDirectories(output)
        Files.writeString(output.resolve("$index-oracle.sql"), "$expectedSql\n")
        Files.writeString(output.resolve("$index-local.plan"), localPlan.describe())
        Files.writeString(output.resolve("$index-production.plan"), productionPlan.describe())
        val retained = productionPlan.errorCode?.let { "PLAN_ERROR:$it" }
            ?: productionPlan.describe().contains("filterPredicate").toString()
        return listOf(predicate, expected.describe(), local.describe(), production.describe(),
            production.sameValue(expected).toString(), local.sameValue(expected).toString(),
            retained, native.describe())
    }

    fun verifyInput(table: String, projection: String = "*"): List<String> {
        val expected = trino("${PushdownEdgeFixtures.trinoInput(table)} SELECT $projection FROM input ORDER BY id")
        val actual = trino("SELECT $projection FROM edge_local.audit.$table ORDER BY id")
        check(expected.errorCode == null) { "Invalid source oracle: ${expected.describe()}" }
        return if (expected.sameValue(actual) && expected.types == actual.types) emptyList()
        else listOf("INPUT $table: expected=${expected.describe()} actual=${actual.describe()}")
    }
}
