package dev.brikk.duckbridge.trino.plugin

import java.math.BigDecimal
import java.sql.Connection
import java.sql.Types
import java.time.LocalDate

internal object PushdownEdgeFixtures {
    private val numbers = listOf(null, Double.NEGATIVE_INFINITY, -Double.MAX_VALUE, -1000.0, -710.0, -Math.PI,
        -2.0, -1.0, -Math.nextDown(1.0), -0.5, -java.lang.Double.MIN_NORMAL, -Double.MIN_VALUE, -0.0, 0.0,
        Double.MIN_VALUE, java.lang.Double.MIN_NORMAL, 0.1, 0.5, Math.nextDown(1.0), 1.0, Math.nextUp(1.0),
        2.0, Math.E, Math.PI, 709.0, 710.0, 1000.0, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NaN)
    private val special = listOf(null, Double.NEGATIVE_INFINITY, -1.0, -0.0, 0.0, 1.0, Double.POSITIVE_INFINITY, Double.NaN)
    val math = numbers.map { it to 1.0 } + special.flatMap { x -> special.map { x to it } }
    val decimals = listOf(null, "0", "0.000000000000000001", "-0.000000000000000001", "0.49", "0.5", "-0.49", "-0.5",
        "1.25", "127.49", "127.5", "-128.49", "-128.5", "32767.5", "-32768.5", "2147483647.5", "-2147483648.5",
        "9223372036854775807.49", "9223372036854775807.5", "-9223372036854775808.5",
        "99999999999999999999.999999999999999999", "-99999999999999999999.999999999999999999")
    val strings = listOf(null, "", "a", "A", "ab", "abc", "abcd", "a_b", "a%b", "a'b", "a\\b", "a\u0000b", "a\u0000",
        "abc\u0000tail", "é", "éx", "é", "😀", "😀a", "😀\u0000a", "\n", "a\nb", "a\rb", "ÿz", "Ā", "a\uD7FF", "a\uE000",
        "a\uFFFF", String(Character.toChars(0x10FFFF)), String(Character.toChars(0x10FFFF)) + "x", "100%", "under_score")
    val dateDays = listOf(null, LocalDate.of(1, 1, 1).toEpochDay(), LocalDate.of(0, 1, 1).toEpochDay(),
        LocalDate.of(-1, 1, 1).toEpochDay(), LocalDate.of(9999, 12, 31).toEpochDay(), LocalDate.of(10000, 1, 1).toEpochDay(),
        -2147483646L, 2147483646L)
    data class Temporal(val date: String?, val timestamp: String?, val zoned: String?)
    val temporal = listOf(
        Temporal(null, null, null),
        Temporal("0001-01-01", "0001-01-01 00:00:00.000001", "0001-01-01 00:00:00.000001"),
        Temporal("1582-10-04", "1582-10-04 23:59:59.999999", "1582-10-04 23:59:59.999999"),
        Temporal("1677-09-21", "1677-09-21 00:12:43.145224", "1677-09-21 00:12:43.145224"),
        Temporal("1969-12-31", "1969-12-31 23:59:59.999999", "1969-12-31 23:59:59.999999"),
        Temporal("1970-01-01", "1970-01-01 00:00:00.000001", "1970-01-01 00:00:00.000001"),
        Temporal("1986-01-01", "1986-01-01 00:10:00.123456", "1985-12-31 18:30:00.123456"),
        Temporal("2000-02-29", "2000-02-29 12:34:56.123456", "2000-02-29 12:34:56.123456"),
        Temporal("2024-03-10", "2024-03-10 02:30:00.123456", "2024-03-10 07:30:00.123456"),
        Temporal("2024-11-03", "2024-11-03 01:30:00.123456", "2024-11-03 05:30:00.123456"),
        Temporal("2024-11-03", "2024-11-03 01:30:00.123456", "2024-11-03 06:30:00.123456"),
        Temporal("2024-04-07", "2024-04-07 01:45:00.123456", "2024-04-06 15:15:00.123456"),
        Temporal("2024-10-06", "2024-10-06 02:15:00.123456", "2024-10-05 15:45:00.123456"),
        Temporal("9999-12-31", "9999-12-31 23:59:59.999999", "9999-12-31 23:59:59.999999"),
    )

    fun seed(connection: Connection) {
        connection.createStatement().use { sql ->
            sql.execute("CREATE SCHEMA audit")
            sql.execute("CREATE TABLE audit.math(id BIGINT,x DOUBLE,y DOUBLE)")
            sql.execute("CREATE TABLE audit.real_numbers(id BIGINT,x FLOAT,y FLOAT)")
            sql.execute("CREATE TABLE audit.decimals(id BIGINT,d DECIMAL(38,18))")
            sql.execute("CREATE TABLE audit.strings(id BIGINT,s VARCHAR)")
            sql.execute("CREATE TABLE audit.temporal(id BIGINT,d DATE,ts TIMESTAMP,z TIMESTAMPTZ)")
            sql.execute("CREATE TABLE audit.date_boundaries(id BIGINT,d DATE)")
            sql.execute("INSERT INTO audit.date_boundaries VALUES " + dateDays.mapIndexed { id, day ->
                "(CAST($id AS BIGINT)," + (day?.let { "DATE '1970-01-01'+CAST($it AS INTEGER)" } ?: "CAST(NULL AS DATE)") + ")"
            }.joinToString(","))
        }
        connection.prepareStatement("INSERT INTO audit.math VALUES(?,?,?)").use { statement ->
            math.forEachIndexed { index, pair ->
                statement.setLong(1, index.toLong())
                listOf(pair.first, pair.second).forEachIndexed { column, value ->
                    if (value == null) statement.setNull(column + 2, Types.DOUBLE) else statement.setDouble(column + 2, value)
                }
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.prepareStatement("INSERT INTO audit.real_numbers VALUES(?,?,?)").use { statement ->
            math.forEachIndexed { index, pair ->
                statement.setLong(1, index.toLong())
                listOf(pair.first, pair.second).forEachIndexed { column, value ->
                    if (value == null) statement.setNull(column + 2, Types.FLOAT) else statement.setFloat(column + 2, value.toFloat())
                }
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.prepareStatement("INSERT INTO audit.decimals VALUES(?,?)").use { statement ->
            decimals.forEachIndexed { index, value ->
                statement.setLong(1, index.toLong())
                if (value == null) statement.setNull(2, Types.DECIMAL) else statement.setBigDecimal(2, BigDecimal(value).setScale(18))
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.prepareStatement("INSERT INTO audit.strings VALUES(?,?)").use { statement ->
            strings.forEachIndexed { index, value -> statement.setLong(1, index.toLong()); statement.setString(2, value); statement.addBatch() }
            statement.executeBatch()
        }
        connection.createStatement().use { statement ->
            statement.execute("INSERT INTO audit.temporal VALUES " + temporal.mapIndexed { index, row -> temporalRow(index, row, true) }.joinToString(","))
        }
    }

    fun trinoInput(table: String): String = when (table) {
        "math" -> "WITH input(id,x,y) AS (VALUES " + math.mapIndexed { index, (x, y) ->
            "(CAST($index AS BIGINT),${number(x)},${number(y)})"
        }.joinToString(",") + ")"
        "real_numbers" -> "WITH input(id,x,y) AS (VALUES " + math.mapIndexed { index, (x, y) ->
            "(CAST($index AS BIGINT),CAST(${x?.toFloat()?.toString()?.let(::quote) ?: "NULL"} AS REAL)," +
                "CAST(${y?.toFloat()?.toString()?.let(::quote) ?: "NULL"} AS REAL))"
        }.joinToString(",") + ")"
        "decimals" -> "WITH input(id,d) AS (VALUES " + decimals.mapIndexed { index, value ->
            "(CAST($index AS BIGINT),CAST(${value?.let(::quote) ?: "NULL"} AS DECIMAL(38,18)))"
        }.joinToString(",") + ")"
        "strings" -> "WITH input(id,s) AS (VALUES " + strings.mapIndexed { index, value ->
            "(CAST($index AS BIGINT),${value?.let(::text) ?: "CAST(NULL AS VARCHAR)"})"
        }.joinToString(",") + ")"
        "date_boundaries" -> "WITH input(id,d) AS (VALUES " + dateDays.mapIndexed { id, day ->
            "(CAST($id AS BIGINT)," + (day?.let { "date_add('day',CAST($it AS BIGINT),DATE '1970-01-01')" } ?: "CAST(NULL AS DATE)") + ")"
        }.joinToString(",") + ")"
        "temporal" -> "WITH input(id,d,ts,z) AS (VALUES " + temporal.mapIndexed { index, row -> temporalRow(index, row, false) }.joinToString(",") + ")"
        else -> error("Unknown fixture table: $table")
    }

    fun quote(value: String): String = "'${value.replace("'", "''")}'"
    /** An independent runtime input, so compiled operators can be checked separately from constant folding. */
    fun runtimeFloatingInput(table: String): String {
        val type = if (table == "math") "DOUBLE" else "REAL"
        fun column(second: Boolean): String = math.joinToString(",", "ARRAY[", "]") { pair ->
            val value = if (second) pair.second else pair.first
            val text = if (type == "REAL") value?.toFloat()?.toString() else value?.toString()
            "CAST(${text?.let(::quote) ?: "NULL"} AS $type)"
        }
        val ids = math.indices.joinToString(",", "ARRAY[", "]") { "CAST($it AS BIGINT)" }
        return "WITH input(id,x,y) AS (SELECT * FROM UNNEST($ids,${column(false)},${column(true)}))"
    }

    /** Separate equality branches prevent positive OR membership from being normalized back into compiled IN. */
    fun realMembershipEqualitySql(negated: Boolean): String {
        val operator = if (negated) "<>" else "="
        val combine = if (negated) "INTERSECT" else "UNION"
        return runtimeFloatingInput("real_numbers") + " SELECT id FROM input WHERE x $operator REAL '0' " +
            "$combine SELECT id FROM input WHERE x $operator REAL '1' ORDER BY id"
    }
    fun text(value: String): String = value.split('\u0000').joinToString(" || chr(0) || ") { quote(it) }
    private fun number(value: Double?): String = "CAST(${value?.toString()?.let(::quote) ?: "NULL"} AS DOUBLE)"
    private fun temporalRow(index: Int, row: Temporal, duck: Boolean): String {
        fun literal(value: String?, type: String): String = "CAST(${value?.let(::quote) ?: "NULL"} AS $type)"
        return "(CAST($index AS BIGINT),${literal(row.date, "DATE")},${literal(row.timestamp, if (duck) "TIMESTAMP" else "TIMESTAMP(6)")}," +
            literal(row.zoned?.plus(if (duck) "+00" else " UTC"), if (duck) "TIMESTAMPTZ" else "TIMESTAMP(6) WITH TIME ZONE") + ")"
    }
}
