/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.brikk.duckbridge.trino.plugin

import io.airlift.slice.Slices
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.spi.connector.ColumnHandle
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.Variable
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.DateType.DATE
import io.trino.spi.type.DoubleType.DOUBLE
import io.trino.spi.type.IntegerType.INTEGER
import io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS
import io.trino.spi.type.Type
import io.trino.spi.type.VarcharType.VARCHAR
import org.assertj.core.api.Assertions.assertThat
import java.sql.Types
import java.time.LocalDate
import java.util.Optional

/**
 * The per-entry cross-engine semantic fixtures for every NON-ALIAS
 * [DuckBridgeExpressionTranslator.Emission] entry (Bare/Rename/Operator/Inline).
 *
 * Each [Fixture] builds a Trino [io.trino.spi.expression.Call] from literal [Constant] arguments,
 * runs it through the PRODUCTION [DuckBridgeExpressionTranslator] to obtain the exact DuckDB SQL the
 * connector would push, and pairs it with the Trino-side expected value. The test harness
 * ([TestTrinoFunctionAliases.nonAliasSemanticFixtures]) evaluates that SQL against embedded DuckDB
 * and asserts equality — proving the natively-emitted form (bare built-in, rename, operator, inline
 * transform) stays byte-for-byte aligned with Trino on every DuckDB pin bump.
 *
 * Coverage deliberately includes unicode, NULL, and edge inputs where alignment was historically
 * in question (code-point length, ISO week/day-of-week, NULL propagation, empty-match regex).
 */
object SemanticFixtures {
    /**
     * @param name/[arity] the pushable entry under test.
     * @param label a short human tag for the dynamic-test name.
     * @param expr the Trino Call to translate (with literal args).
     * @param wrap wraps the emitted SQL before `SELECT` — identity for most; `to_hex(...)` for the
     *   VARBINARY-returning hashes so the assertion compares a stable hex string, not a JDBC blob.
     * @param expected the value DuckDB must return (matched loosely across numeric widths).
     */
    class Fixture(
        val name: String,
        val arity: Int,
        val label: String,
        private val expr: ConnectorExpression,
        private val wrap: (String) -> String = { it },
        private val expected: Any?,
        private val fromClause: String? = null,
    ) {
        fun emittedSql(): String? = DuckBridgeExpressionTranslator.translate(expr, ASSIGNMENTS, null, aliasAvailable = false)

        /** The full `SELECT ...` query: emitted SQL, optionally wrapped, over an optional binding subquery. */
        fun query(sql: String): String {
            val projected = "SELECT ${wrap.invoke(sql)}"
            return if (fromClause == null) projected else "$projected FROM ($fromClause)"
        }

        fun assertMatches(actual: Any?) {
            when (val e = expected) {
                null -> assertThat(actual).`as`("%s/%d [%s]", name, arity, label).isNull()
                is Number -> assertThat((actual as Number).toDouble()).`as`("%s/%d [%s]", name, arity, label).isEqualTo(e.toDouble())
                else -> assertThat(actual.toString()).`as`("%s/%d [%s]", name, arity, label).isEqualTo(e.toString())
            }
        }
    }

    @Suppress("LongMethod")
    fun all(): List<Fixture> =
        buildList {
            // ---- BARE: string ----
            add(fx("length", 1, "code-point count (unicode)", call("length", BIGINT, str("пингвин")), expected = 7L))
            add(fx("substring", 2, "start≥1 constant", call("substring", VARCHAR, str("hello"), int(2)), expected = "ello"))
            add(fx("substring", 3, "start+len constant", call("substring", VARCHAR, str("hello"), int(2), int(3)), expected = "ell"))
            add(fx("replace", 3, "basic", call("replace", VARCHAR, str("aXbXc"), str("X"), str("-")), expected = "a-b-c"))
            add(fx("strpos", 2, "found", call("strpos", BIGINT, str("hello"), str("ll")), expected = 3L))
            add(fx("starts_with", 2, "true", call("starts_with", BIGINT, str("hello"), str("he")), expected = true))
            add(fx("lpad", 3, "constant non-empty pad", call("lpad", VARCHAR, str("x"), int(5), str("-")), expected = "----x"))
            add(fx("rpad", 3, "constant non-empty pad", call("rpad", VARCHAR, str("x"), int(5), str("-")), expected = "x----"))
            add(fx("concat_ws", 2, "sep+1", call("concat_ws", VARCHAR, str("-"), str("a")), expected = "a"))
            add(fx("concat_ws", 3, "sep+2", call("concat_ws", VARCHAR, str("-"), str("a"), str("b")), expected = "a-b"))
            add(fx("concat_ws", 4, "sep+3", call("concat_ws", VARCHAR, str("-"), str("a"), str("b"), str("c")), expected = "a-b-c"))
            add(fx("concat_ws", 5, "sep+4", call("concat_ws", VARCHAR, str("-"), str("a"), str("b"), str("c"), str("d")), expected = "a-b-c-d"))
            add(fx("translate", 3, "basic", call("translate", VARCHAR, str("abc"), str("bc"), str("xy")), expected = "axy"))
            add(fx("chr", 1, "code point", call("chr", VARCHAR, int(233)), expected = "é"))
            add(fx("bit_length", 1, "ascii", call("bit_length", BIGINT, str("abc")), expected = 24L))
            add(fx("url_encode", 1, "space", call("url_encode", VARCHAR, str("a b")), expected = "a%20b"))
            add(fx("url_decode", 1, "percent", call("url_decode", VARCHAR, str("a%20b")), expected = "a b"))
            add(fx("to_base64", 1, "basic", call("to_base64", VARCHAR, str("abc")), expected = "YWJj"))
            add(fx("from_base64", 1, "roundtrip", call("from_base64", VARCHAR, str("YWJj")), wrap = { "CAST($it AS VARCHAR)" }, expected = "abc"))
            // ---- BARE: numeric ----
            add(fx("abs", 1, "negative", call("abs", INTEGER, int(-5)), expected = 5L))
            add(fx("ceil", 1, "up", call("ceil", DOUBLE, dbl(1.2)), expected = 2.0))
            add(fx("floor", 1, "down", call("floor", DOUBLE, dbl(1.8)), expected = 1.0))
            add(fx("mod", 2, "integer only", call("mod", INTEGER, int(7), int(3)), expected = 1L))
            add(fx("power", 2, "2^10", call("power", DOUBLE, int(2), int(10)), expected = 1024.0))
            add(fx("sqrt", 1, "of 9", call("sqrt", DOUBLE, dbl(9.0)), expected = 3.0))
            add(fx("exp", 1, "of 0", call("exp", DOUBLE, dbl(0.0)), expected = 1.0))
            add(fx("ln", 1, "of 1", call("ln", DOUBLE, dbl(1.0)), expected = 0.0))
            add(fx("log2", 1, "of 8", call("log2", DOUBLE, dbl(8.0)), expected = 3.0))
            add(fx("log10", 1, "of 1000 (NOT bare log)", call("log10", DOUBLE, dbl(1000.0)), expected = 3.0))
            add(fx("sin", 1, "of 0", call("sin", DOUBLE, dbl(0.0)), expected = 0.0))
            add(fx("cos", 1, "of 0", call("cos", DOUBLE, dbl(0.0)), expected = 1.0))
            add(fx("tan", 1, "of 0", call("tan", DOUBLE, dbl(0.0)), expected = 0.0))
            add(fx("asin", 1, "of 0", call("asin", DOUBLE, dbl(0.0)), expected = 0.0))
            add(fx("acos", 1, "of 1", call("acos", DOUBLE, dbl(1.0)), expected = 0.0))
            add(fx("atan", 1, "of 0", call("atan", DOUBLE, dbl(0.0)), expected = 0.0))
            add(fx("atan2", 2, "of (0,1)", call("atan2", DOUBLE, dbl(0.0), dbl(1.0)), expected = 0.0))
            add(fx("sinh", 1, "of 0", call("sinh", DOUBLE, dbl(0.0)), expected = 0.0))
            add(fx("cosh", 1, "of 0", call("cosh", DOUBLE, dbl(0.0)), expected = 1.0))
            add(fx("tanh", 1, "of 0", call("tanh", DOUBLE, dbl(0.0)), expected = 0.0))
            add(fx("degrees", 1, "of 0", call("degrees", DOUBLE, dbl(0.0)), expected = 0.0))
            add(fx("radians", 1, "of 0", call("radians", DOUBLE, dbl(0.0)), expected = 0.0))
            add(fx("cbrt", 1, "of 8", call("cbrt", DOUBLE, dbl(8.0)), expected = 2.0))
            add(fx("sign", 1, "negative", call("sign", DOUBLE, dbl(-3.0)), expected = -1.0))
            add(fx("pi", 0, "constant", call("pi", DOUBLE), expected = Math.PI))
            // ---- BARE: regex ----
            add(fx("regexp_extract", 2, "whole match", call("regexp_extract", VARCHAR, str("abc123"), str("[0-9]+")), expected = "123"))
            add(fx("regexp_extract", 3, "group", call("regexp_extract", VARCHAR, str("abc123"), str("([a-z]+)([0-9]+)"), int(2)), expected = "123"))
            // ---- BARE: date ----
            add(fx("year", 1, "on DATE", call("year", BIGINT, date("2024-02-29")), expected = 2024L))
            add(fx("month", 1, "on DATE", call("month", BIGINT, date("2024-02-29")), expected = 2L))
            add(fx("day", 1, "on DATE", call("day", BIGINT, date("2024-02-29")), expected = 29L))
            add(fx("quarter", 1, "on DATE", call("quarter", BIGINT, date("2024-02-29")), expected = 1L))
            add(
                fx(
                    "date_trunc", 2, "DATE-input result-safe",
                    call("date_trunc", DATE, str("month"), date("2000-01-15")),
                    wrap = { "($it = DATE '2000-01-01')" }, expected = true,
                ),
            )
            add(
                fx(
                    "date_diff", 3, "day boundary count",
                    call("date_diff", BIGINT, str("day"), date("2024-01-01"), date("2024-01-08")),
                    expected = 7L,
                ),
            )
            add(fx("week", 1, "ISO week 53", call("week", BIGINT, date("2021-01-01")), expected = 53L))
            add(fx("hour", 1, "on TIMESTAMP", call("hour", BIGINT, tsVar()), expected = 13L, fromClause = fromTs("2024-01-01 13:45:30")))
            add(fx("minute", 1, "on TIMESTAMP", call("minute", BIGINT, tsVar()), expected = 45L, fromClause = fromTs("2024-01-01 13:45:30")))
            add(fx("second", 1, "on TIMESTAMP", call("second", BIGINT, tsVar()), expected = 30L, fromClause = fromTs("2024-01-01 13:45:30")))

            // ---- RENAME ----
            add(fx("to_hex", 1, "→hex", call("to_hex", VARCHAR, str("abc")), expected = "616263"))
            add(fx("from_hex", 1, "→unhex roundtrip", call("from_hex", VARCHAR, str("616263")), wrap = { "CAST($it AS VARCHAR)" }, expected = "abc"))
            add(fx("levenshtein_distance", 2, "→levenshtein", call("levenshtein_distance", BIGINT, str("kitten"), str("sitting")), expected = 3L))
            add(fx("hamming_distance", 2, "→hamming", call("hamming_distance", BIGINT, str("karolin"), str("kathrin")), expected = 3L))
            add(fx("truncate", 1, "→trunc", call("truncate", DOUBLE, dbl(3.7)), expected = 3.0))
            add(fx("bitwise_xor", 2, "→xor", call("bitwise_xor", BIGINT, int(5), int(3)), expected = 6L))
            add(fx("regexp_like", 2, "→regexp_matches", call("regexp_like", BIGINT, str("abc123"), str("[0-9]+")), expected = true))
            add(fx("day_of_year", 1, "→dayofyear", call("day_of_year", BIGINT, date("2024-03-01")), expected = 61L))
            add(fx("last_day_of_month", 1, "→last_day", call("last_day_of_month", DATE, date("2024-02-10")), expected = "2024-02-29"))
            add(fx("week_of_year", 1, "→week", call("week_of_year", BIGINT, date("2021-01-01")), expected = 53L))
            add(
                fx(
                    "from_unixtime", 1, "→to_timestamp epoch",
                    call("from_unixtime", TIMESTAMP_MILLIS, dbl(0.0)),
                    wrap = { "CAST(epoch($it) AS DOUBLE)" }, expected = 0.0,
                ),
            )

            // ---- OPERATOR ----
            add(fx("bitwise_and", 2, "&", call("bitwise_and", BIGINT, int(5), int(3)), expected = 1L))
            add(fx("bitwise_or", 2, "|", call("bitwise_or", BIGINT, int(5), int(2)), expected = 7L))
            add(fx("bitwise_left_shift", 2, "<<", call("bitwise_left_shift", BIGINT, int(1), int(4)), expected = 16L))
            add(fx("bitwise_right_shift", 2, ">>", call("bitwise_right_shift", BIGINT, int(16), int(2)), expected = 4L))
            add(fx("bitwise_not", 1, "~", call("bitwise_not", INTEGER, int(5)), expected = -6L))

            // ---- INLINE ----
            add(fx("regexp_replace", 2, "'' + 'g' flag (removes all)", call("regexp_replace", VARCHAR, str("abcabc"), str("b")), expected = "acac"))
            add(fx("regexp_replace", 3, "repl + 'g' flag (all)", call("regexp_replace", VARCHAR, str("abcabc"), str("b"), str("_")), expected = "a_ca_c"))
            add(fx("md5", 1, "unhex-wrapped", call("md5", VARCHAR, str("abc")), wrap = { "to_hex($it)" }, expected = "900150983CD24FB0D6963F7D28E17F72"))
            add(
                fx(
                    "sha1", 1, "unhex-wrapped",
                    call("sha1", VARCHAR, str("abc")),
                    wrap = { "to_hex($it)" }, expected = "A9993E364706816ABA3E25717850C26C9CD0D89D",
                ),
            )
            add(
                fx(
                    "sha256", 1, "unhex-wrapped",
                    call("sha256", VARCHAR, str("abc")),
                    wrap = { "to_hex($it)" },
                    expected = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
                ),
            )
            add(fx("if", 2, "false→NULL", call("if", INTEGER, bool(false), int(1)), expected = null))
            add(fx("if", 3, "true→then (bare)", call("if", INTEGER, bool(true), int(1), int(2)), expected = 1L))
            add(fx("day_of_week", 1, "ISO Sun=7", call("day_of_week", BIGINT, date("2024-01-07")), expected = 7L))
            add(fx("year_of_week", 1, "ISO isoyear", call("year_of_week", BIGINT, date("2024-12-30")), expected = 2025L))
            add(fx("yow", 1, "ISO isoyear", call("yow", BIGINT, date("2024-12-30")), expected = 2025L))
            add(fx("millisecond", 1, "millis-of-second", call("millisecond", BIGINT, tsVar()), expected = 123L, fromClause = fromTs("2024-01-01 00:00:00.123")))
            add(fx("to_unixtime", 1, "epoch seconds", call("to_unixtime", DOUBLE, tsVar()), expected = 1.0, fromClause = fromTs("1970-01-01 00:00:01")))
            add(
                fx(
                    "with_timezone", 2, "arg-order flip → non-null",
                    call("with_timezone", TIMESTAMP_MILLIS, tsVar(), str("America/Los_Angeles")),
                    wrap = { "($it IS NOT NULL)" }, expected = true, fromClause = fromTs("2024-01-01 12:00:00"),
                ),
            )
        }

    private fun fx(
        name: String,
        arity: Int,
        label: String,
        expr: ConnectorExpression,
        wrap: (String) -> String = { it },
        expected: Any?,
        fromClause: String? = null,
    ): Fixture = Fixture(name, arity, label, expr, wrap, expected, fromClause)

    // The translator can render literal VARCHAR/integer/double/DATE constants directly, but NOT
    // TIMESTAMP constants (they stay above the scan in production). So date/time fixtures over a
    // TIMESTAMP input bind the value through a `ts` Variable resolving to a JdbcColumnHandle, and the
    // fixture supplies a `FROM (SELECT TIMESTAMP '...' AS ts)` subquery that provides that column.
    private val TS_COLUMN: JdbcColumnHandle =
        JdbcColumnHandle(
            "ts",
            JdbcTypeHandle(Types.OTHER, Optional.of("timestamp"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            TIMESTAMP_MILLIS,
        )
    private val ASSIGNMENTS: Map<String, ColumnHandle> = mapOf("ts" to TS_COLUMN)

    private fun call(name: String, returnType: Type, vararg args: ConnectorExpression): ConnectorExpression =
        Call(returnType, FunctionName(name), listOf(*args))

    private fun str(s: String): ConnectorExpression = Constant(Slices.utf8Slice(s), VARCHAR)

    private fun int(v: Long): ConnectorExpression = Constant(v, BIGINT)

    private fun dbl(v: Double): ConnectorExpression = Constant(v, DOUBLE)

    private fun bool(v: Boolean): ConnectorExpression = Constant(v, io.trino.spi.type.BooleanType.BOOLEAN)

    private fun date(iso: String): ConnectorExpression = Constant(LocalDate.parse(iso).toEpochDay(), DATE)

    /** A `ts`-column [Variable] of TIMESTAMP type; pair with `fromTs(...)` to bind its value. */
    private fun tsVar(): ConnectorExpression = Variable("ts", TIMESTAMP_MILLIS)

    private fun fromTs(literal: String): String = "SELECT TIMESTAMP '$literal' AS ts"
}
