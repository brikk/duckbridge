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

import io.airlift.slice.Slice
import io.airlift.slice.Slices
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.spi.connector.ColumnHandle
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.Variable
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.DateType.DATE
import io.trino.spi.type.DoubleType.DOUBLE
import io.trino.spi.type.TimeZoneKey
import io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS
import io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MILLIS
import io.trino.spi.type.Type
import io.trino.spi.type.VarcharType.VARCHAR
import io.trino.testing.TestingConnectorSession
import java.sql.Types
import java.time.LocalDate
import java.util.Optional

/**
 * Cross-engine semantic fixtures for every non-ALIAS [DuckBridgeExpressionTranslator.Emission] entry,
 * plus the ALIAS natives.
 *
 * Each [Fixture] is a Trino [Call] over literal [Constant] arguments (or a bound `ts` / `tstz`
 * variable). The harness ([TestPushdownSemanticFixtures]) evaluates it TWICE:
 *  1. on **Trino itself** — the Call is rendered to Trino SQL ([trinoSql]) and run through the
 *     Trino query runner, producing the authoritative expected value (or an error);
 *  2. on **DuckDB** — the Call is run through the PRODUCTION translator to obtain the exact SQL the
 *     connector would push, which is executed on embedded DuckDB (extension LOADed for ALIAS).
 * The two outcomes must agree: same value, or both errors. No expectation is hand-written, so a
 * wrong belief about Trino cannot make a fixture green (the failure mode behind EV-A1..A5 and EV-E1
 * in dev-docs/TODO-rectify-from-eval.md).
 *
 * [NotPushed] fixtures pin the gates: the translator must decline them (they'd be wrong if pushed).
 *
 * Both engines run in the same non-UTC session zone ([ZONE]) so zone-sensitive emissions are
 * exercised honestly.
 */
object SemanticFixtures {
    /** Session zone for both engines. Non-UTC on purpose (to_unixtime, with_timezone). */
    const val ZONE: String = "America/New_York"

    val TIME_ZONE_KEY: TimeZoneKey = TimeZoneKey.getTimeZoneKey(ZONE)

    sealed interface Case {
        val name: String
        val arity: Int
        val label: String
    }

    /**
     * @param expr the Trino Call to evaluate on both engines.
     * @param wrapTrino / [wrapDuck] wrap the expression before `SELECT` on each side — identity for
     *   most; used to project VARBINARY results to hex, or a DATE-typed result to a boolean.
     * @param trinoSql explicit Trino SQL for the expression when the auto-rendering can't express it
     *   (e.g. VARBINARY arguments Trino needs a CAST for).
     * @param fromClause optional `SELECT ... AS ts` subquery binding the `ts`/`tstz` variables — the same
     *   SQL text is used on both engines, so only constructs both accept belong here.
     */
    open class Fixture(
        override val name: String,
        override val arity: Int,
        override val label: String,
        val expr: ConnectorExpression,
        val wrapTrino: (String) -> String = { it },
        val wrapDuck: (String) -> String = { it },
        val trinoSql: String? = null,
        val fromClause: From? = null,
    ) : Case {
        open fun emittedDuckSql(): String? = DuckBridgeExpressionTranslator.translate(expr, ASSIGNMENTS, SESSION, aliasAvailable = true)

        fun duckQuery(): String? = emittedDuckSql()?.let { query(wrapDuck(it), fromClause?.duck) }

        fun trinoQuery(): String = query(wrapTrino(trinoSql ?: TrinoSqlRenderer.render(expr)), fromClause?.trino)

        private fun query(projected: String, from: String?): String =
            if (from == null) "SELECT $projected" else "SELECT $projected FROM ($from)"
    }

    /** Per-engine FROM subquery binding the `ts`/`ts2`/`tstz` variables (literal syntax differs for zoned values). */
    data class From(val trino: String, val duck: String) {
        constructor(both: String) : this(both, both)
    }

    /** The translator must refuse to push this shape (a gate is doing its job). */
    class NotPushed(
        override val name: String,
        override val arity: Int,
        override val label: String,
        val expr: ConnectorExpression,
    ) : Case {
        fun emittedDuckSql(): String? = DuckBridgeExpressionTranslator.translate(expr, ASSIGNMENTS, SESSION, aliasAvailable = true)
    }

    @Suppress("LongMethod")
    fun all(): List<Case> =
        buildList {
            addAll(stringFixtures())
            addAll(numericFixtures())
            addAll(regexFixtures())
            addAll(dateTimeFixtures())
            addAll(encodingAndHashFixtures())
            addAll(castAndMiscFixtures())
            addAll(aliasFixtures())
        }

    // ---- String ------------------------------------------------------------------------------

    @Suppress("LongMethod")
    private fun stringFixtures(): List<Case> =
        buildList {
            add(fx("length", 1, "code-point count (cyrillic)", call("length", BIGINT, str("пингвин"))))
            add(fx("length", 1, "astral + combining", call("length", BIGINT, str("a\uD83D\uDE00e\u0301"))))
            add(fx("length", 1, "empty", call("length", BIGINT, str(""))))
            add(fx("substring", 2, "start≥1 constant", call("substring", VARCHAR, str("hello"), int(2))))
            add(fx("substring", 2, "start past end", call("substring", VARCHAR, str("hello"), int(9))))
            add(fx("substring", 2, "unicode start", call("substring", VARCHAR, str("δοκιμή"), int(3))))
            add(fx("substring", 3, "start+len constant", call("substring", VARCHAR, str("hello"), int(2), int(3))))
            add(fx("substring", 3, "len past end", call("substring", VARCHAR, str("hello"), int(4), int(10))))
            add(fx("substring", 3, "zero length", call("substring", VARCHAR, str("hello"), int(2), int(0))))
            add(np("substring", 3, "negative length not pushed (Trino '' vs DuckDB slice)", call("substring", VARCHAR, str("hello"), int(2), int(-1))))
            add(np("substring", 2, "start 0 not pushed", call("substring", VARCHAR, str("hello"), int(0))))
            add(np("substring", 2, "negative start not pushed", call("substring", VARCHAR, str("hello"), int(-2))))
            add(fx("replace", 3, "basic", call("replace", VARCHAR, str("aXbXc"), str("X"), str("-"))))
            add(np("replace", 3, "empty search not pushed (Trino interleaves, DuckDB no-op)", call("replace", VARCHAR, str("abc"), str(""), str("x"))))
            add(fx("replace", 3, "no occurrence", call("replace", VARCHAR, str("abc"), str("z"), str("x"))))
            add(fx("replace", 3, "empty replacement deletes", call("replace", VARCHAR, str("abcabc"), str("b"), str(""))))
            add(fx("replace", 3, "unicode", call("replace", VARCHAR, str("straße"), str("ß"), str("ss"))))
            add(fx("strpos", 2, "found", call("strpos", BIGINT, str("hello"), str("ll"))))
            add(fx("strpos", 2, "not found", call("strpos", BIGINT, str("hello"), str("z"))))
            add(fx("strpos", 2, "empty needle", call("strpos", BIGINT, str("hello"), str(""))))
            add(fx("strpos", 2, "code-point position after astral", call("strpos", BIGINT, str("\uD83D\uDE00abc"), str("b"))))
            add(fx("starts_with", 2, "true", call("starts_with", BOOLEAN, str("hello"), str("he"))))
            add(fx("starts_with", 2, "empty prefix", call("starts_with", BOOLEAN, str("hello"), str(""))))
            add(fx("lpad", 3, "constant non-empty pad", call("lpad", VARCHAR, str("x"), int(5), str("-"))))
            add(fx("lpad", 3, "truncates when size < length", call("lpad", VARCHAR, str("hello"), int(2), str("-"))))
            add(fx("lpad", 3, "multi-char pad cycles", call("lpad", VARCHAR, str("x"), int(6), str("ab"))))
            add(fx("lpad", 3, "unicode pad", call("lpad", VARCHAR, str("x"), int(3), str("é"))))
            add(fx("lpad", 3, "size 0", call("lpad", VARCHAR, str("abc"), int(0), str("x"))))
            add(np("lpad", 3, "negative size not pushed (Trino errors, DuckDB '')", call("lpad", VARCHAR, str("abc"), int(-1), str("x"))))
            add(fx("rpad", 3, "constant non-empty pad", call("rpad", VARCHAR, str("x"), int(5), str("-"))))
            add(fx("rpad", 3, "truncates", call("rpad", VARCHAR, str("hello"), int(2), str("-"))))
            add(np("lpad", 3, "empty pad not pushed", call("lpad", VARCHAR, str("x"), int(5), str(""))))
            add(fx("concat_ws", 2, "sep+1", call("concat_ws", VARCHAR, str("-"), str("a"))))
            add(fx("concat_ws", 3, "sep+2", call("concat_ws", VARCHAR, str("-"), str("a"), str("b"))))
            add(fx("concat_ws", 3, "NULL arg skipped", call("concat_ws", VARCHAR, str("-"), str("a"), nullOf(VARCHAR))))
            add(fx("concat_ws", 3, "empty strings kept", call("concat_ws", VARCHAR, str("-"), str(""), str("b"))))
            add(fx("concat_ws", 4, "sep+3", call("concat_ws", VARCHAR, str("-"), str("a"), str("b"), str("c"))))
            add(fx("concat_ws", 5, "sep+4", call("concat_ws", VARCHAR, str("-"), str("a"), str("b"), str("c"), str("d"))))
            add(fx("translate", 3, "basic", call("translate", VARCHAR, str("abc"), str("bc"), str("xy"))))
            add(fx("translate", 3, "shorter `to` deletes", call("translate", VARCHAR, str("abcabc"), str("abc"), str("x"))))
            add(fx("translate", 3, "unicode", call("translate", VARCHAR, str("ääö"), str("äö"), str("ao"))))
            add(fx("chr", 1, "code point", call("chr", VARCHAR, int(233))))
            add(fx("chr", 1, "astral", call("chr", VARCHAR, int(0x1F600))))
            add(np("levenshtein_distance", 2, "not pushed (DuckDB counts bytes)", call("levenshtein_distance", BIGINT, str("äö"), str("ab"))))
            add(np("hamming_distance", 2, "not pushed (DuckDB counts bytes)", call("hamming_distance", BIGINT, str("äö"), str("ab"))))
        }

    // ---- Numeric -----------------------------------------------------------------------------

    @Suppress("LongMethod")
    private fun numericFixtures(): List<Case> =
        buildList {
            add(fx("abs", 1, "negative", call("abs", BIGINT, int(-5))))
            add(fx("abs", 1, "double", call("abs", DOUBLE, dbl(-2.5))))
            add(fx("abs", 1, "MIN_VALUE (both error)", call("abs", BIGINT, int(Long.MIN_VALUE))))
            add(fx("ceil", 1, "up", call("ceil", DOUBLE, dbl(1.2))))
            add(fx("ceil", 1, "negative", call("ceil", DOUBLE, dbl(-1.2))))
            add(fx("floor", 1, "down", call("floor", DOUBLE, dbl(1.8))))
            add(fx("floor", 1, "negative", call("floor", DOUBLE, dbl(-1.2))))
            add(fx("mod", 2, "integer", call("mod", BIGINT, int(7), int(3))))
            add(fx("mod", 2, "negative dividend", call("mod", BIGINT, int(-7), int(3))))
            add(fx("mod", 2, "negative divisor", call("mod", BIGINT, int(7), int(-3))))
            add(np("mod", 2, "zero divisor not pushed", call("mod", BIGINT, int(7), int(0))))
            add(fx("power", 2, "2^10", call("power", DOUBLE, dbl(2.0), dbl(10.0))))
            add(fx("power", 2, "negative exponent", call("power", DOUBLE, dbl(2.0), dbl(-1.0))))
            add(fx("power", 2, "fractional root of negative (NaN)", call("power", DOUBLE, dbl(-8.0), dbl(1.0 / 3))))
            add(np("sqrt", 1, "not pushed: NaN filter ordering diverges", call("sqrt", DOUBLE, dbl(-4.0))))
            add(fx("exp", 1, "of 0", call("exp", DOUBLE, dbl(0.0))))
            add(fx("exp", 1, "of 1", call("exp", DOUBLE, dbl(1.0))))
            add(np("ln", 1, "not pushed: NaN filter ordering diverges", call("ln", DOUBLE, dbl(-1.0))))
            add(np("log2", 1, "not pushed: NaN filter ordering diverges", call("log2", DOUBLE, dbl(-1.0))))
            add(np("log10", 1, "not pushed: NaN filter ordering diverges", call("log10", DOUBLE, dbl(-1.0))))
            add(fx("sin", 1, "of 0", call("sin", DOUBLE, dbl(0.0))))
            add(fx("sin", 1, "of pi/2", call("sin", DOUBLE, dbl(Math.PI / 2))))
            add(fx("cos", 1, "of 0", call("cos", DOUBLE, dbl(0.0))))
            add(fx("tan", 1, "of 0", call("tan", DOUBLE, dbl(0.0))))
            add(np("asin", 1, "not pushed: NaN filter ordering diverges", call("asin", DOUBLE, dbl(2.0))))
            add(np("acos", 1, "not pushed: NaN filter ordering diverges", call("acos", DOUBLE, dbl(-2.0))))
            add(fx("atan", 1, "of 0", call("atan", DOUBLE, dbl(0.0))))
            add(fx("atan2", 2, "of (0,1)", call("atan2", DOUBLE, dbl(0.0), dbl(1.0))))
            add(fx("atan2", 2, "of (1,-1)", call("atan2", DOUBLE, dbl(1.0), dbl(-1.0))))
            add(fx("sinh", 1, "of 0", call("sinh", DOUBLE, dbl(0.0))))
            add(fx("cosh", 1, "of 0", call("cosh", DOUBLE, dbl(0.0))))
            add(fx("tanh", 1, "of 0", call("tanh", DOUBLE, dbl(0.0))))
            add(fx("degrees", 1, "of pi", call("degrees", DOUBLE, dbl(Math.PI))))
            add(fx("radians", 1, "of 180", call("radians", DOUBLE, dbl(180.0))))
            add(np("cbrt", 1, "not pushed (DuckDB inexact on perfect cubes)", call("cbrt", DOUBLE, dbl(-27.0))))
            add(fx("sign", 1, "negative", call("sign", DOUBLE, dbl(-3.0))))
            add(fx("sign", 1, "zero", call("sign", DOUBLE, dbl(0.0))))
            add(fx("sign", 1, "bigint", call("sign", BIGINT, int(42))))
            add(fx("pi", 0, "constant", call("pi", DOUBLE)))
            add(fx("truncate", 1, "→trunc", call("truncate", DOUBLE, dbl(3.7))))
            add(fx("truncate", 1, "negative", call("truncate", DOUBLE, dbl(-3.7))))
            add(fx("bitwise_and", 2, "&", call("bitwise_and", BIGINT, int(5), int(3))))
            add(fx("bitwise_and", 2, "negative", call("bitwise_and", BIGINT, int(-1), int(255))))
            add(fx("bitwise_or", 2, "|", call("bitwise_or", BIGINT, int(5), int(2))))
            add(fx("bitwise_or", 2, "negative", call("bitwise_or", BIGINT, int(-256), int(1))))
            add(fx("bitwise_xor", 2, "→xor", call("bitwise_xor", BIGINT, int(5), int(3))))
            add(fx("bitwise_xor", 2, "negative", call("bitwise_xor", BIGINT, int(-1), int(1))))
            add(fx("bitwise_not", 1, "~", call("bitwise_not", BIGINT, int(5))))
            add(fx("bitwise_not", 1, "~0", call("bitwise_not", BIGINT, int(0))))
        }

    // ---- Regex -------------------------------------------------------------------------------

    private fun regexFixtures(): List<Case> =
        buildList {
            add(fx("regexp_like", 2, "→regexp_matches", call("regexp_like", BOOLEAN, str("abc123"), str("[0-9]+"))))
            add(fx("regexp_like", 2, "no match", call("regexp_like", BOOLEAN, str("abc"), str("[0-9]+"))))
            add(fx("regexp_like", 2, "anchored ^ on multi-line input", call("regexp_like", BOOLEAN, str("x\nabc"), str("^abc"))))
            add(fx("regexp_like", 2, "unicode class \\p{L}", call("regexp_like", BOOLEAN, str("é"), str("^\\p{L}"))))
            add(fx("regexp_like", 2, "\\w on non-ASCII", call("regexp_like", BOOLEAN, str("é"), str("\\w"))))
            add(fx("regexp_like", 2, "dot vs newline", call("regexp_like", BOOLEAN, str("a\nb"), str("a.b"))))
            add(fx("regexp_like", 2, "dot vs carriage return", call("regexp_like", BOOLEAN, str("a\rb"), str("a.b"))))
            add(fx("regexp_like", 2, "non-capturing group + lazy", call("regexp_like", BOOLEAN, str("aaab"), str("^(?:a+?)b"))))
            add(np("regexp_like", 2, "`$` not pushed", call("regexp_like", BOOLEAN, str("abc\n"), str("c$"))))
            add(np("regexp_like", 2, "lookahead not pushed", call("regexp_like", BOOLEAN, str("abc"), str("a(?=b)"))))
            add(np("regexp_like", 2, "inline flag not pushed", call("regexp_like", BOOLEAN, str("ABC"), str("(?i)abc"))))
            add(fx("regexp_extract", 2, "whole match", call("regexp_extract", VARCHAR, str("abc123"), str("[0-9]+"))))
            add(fx("regexp_extract", 2, "no match → NULL", call("regexp_extract", VARCHAR, str("abc"), str("x"))))
            add(fx("regexp_extract", 2, "empty match → ''", call("regexp_extract", VARCHAR, str("abc"), str("b*"))))
            add(fx("regexp_extract", 3, "group", call("regexp_extract", VARCHAR, str("abc123"), str("([a-z]+)([0-9]+)"), int(2))))
            add(fx("regexp_extract", 3, "group 0", call("regexp_extract", VARCHAR, str("abc123"), str("([a-z]+)([0-9]+)"), int(0))))
            add(fx("regexp_extract", 3, "no match → NULL", call("regexp_extract", VARCHAR, str("abc"), str("(x)"), int(1))))
            add(fx("regexp_replace", 2, "'' + 'g' flag (removes all)", call("regexp_replace", VARCHAR, str("abcabc"), str("b"))))
            add(fx("regexp_replace", 2, "no match", call("regexp_replace", VARCHAR, str("abc"), str("x"))))
            add(fx("regexp_replace", 3, "repl + 'g' flag (all)", call("regexp_replace", VARCHAR, str("abcabc"), str("b"), str("_"))))
            add(fx("regexp_replace", 3, "empty-match replacement", call("regexp_replace", VARCHAR, str("abc"), str("x*"), str("-"))))
            add(np("regexp_replace", 3, "`$1` replacement not pushed", call("regexp_replace", VARCHAR, str("abc"), str("(b)"), str("[$1]"))))
            add(np("regexp_replace", 3, "backslash replacement not pushed", call("regexp_replace", VARCHAR, str("abc"), str("(b)"), str("\\1"))))
        }

    // ---- Date / time -------------------------------------------------------------------------

    @Suppress("LongMethod")
    private fun dateTimeFixtures(): List<Case> =
        buildList {
            add(fx("year", 1, "on DATE", call("year", BIGINT, date("2024-02-29"))))
            add(fx("year", 1, "on TIMESTAMP", call("year", BIGINT, tsVar()), fromClause = fromTs("2024-12-31 23:59:59.999")))
            add(fx("year", 1, "on TIMESTAMP WITH TIME ZONE (session zone)", call("year", BIGINT, tstzVar()), fromClause = fromTstz("2024-12-31 23:30:00")))
            add(fx("month", 1, "on DATE", call("month", BIGINT, date("2024-02-29"))))
            add(fx("day", 1, "on DATE", call("day", BIGINT, date("2024-02-29"))))
            add(fx("day", 1, "on TIMESTAMP WITH TIME ZONE (session zone)", call("day", BIGINT, tstzVar()), fromClause = fromTstz("2024-03-01 02:00:00")))
            add(fx("quarter", 1, "on DATE", call("quarter", BIGINT, date("2024-02-29"))))
            add(fx("quarter", 1, "Q4", call("quarter", BIGINT, date("2024-12-01"))))
            add(
                fx(
                    "date_trunc", 2, "month on DATE (result-safe)",
                    call("date_trunc", DATE, str("month"), date("2000-01-15")),
                    wrapBoth = { "($it = DATE '2000-01-01')" },
                ),
            )
            add(
                fx(
                    "date_trunc", 2, "week on DATE (Monday)",
                    call("date_trunc", DATE, str("week"), date("2024-01-07")),
                    wrapBoth = { "($it = DATE '2024-01-01')" },
                ),
            )
            add(
                fx(
                    "date_trunc", 2, "quarter on DATE",
                    call("date_trunc", DATE, str("quarter"), date("2024-05-17")),
                    wrapBoth = { "($it = DATE '2024-04-01')" },
                ),
            )
            add(fx("date_trunc", 2, "year on DATE", call("date_trunc", DATE, str("year"), date("2024-05-17")), wrapBoth = { "($it = DATE '2024-01-01')" }))
            add(
                fx(
                    "date_trunc", 2, "hour on TIMESTAMP",
                    call("date_trunc", TIMESTAMP_MILLIS, str("hour"), tsVar()),
                    wrapBoth = { "($it = TIMESTAMP '2024-01-01 13:00:00')" }, fromClause = fromTs("2024-01-01 13:45:30.123"),
                ),
            )
            add(fx("date_diff", 3, "day", call("date_diff", BIGINT, str("day"), date("2024-01-01"), date("2024-01-08"))))
            add(fx("date_diff", 3, "day negative", call("date_diff", BIGINT, str("day"), date("2024-01-08"), date("2024-01-01"))))
            add(fx("date_diff", 3, "month across boundary, < 1 month", call("date_diff", BIGINT, str("month"), date("2020-01-31"), date("2020-02-01"))))
            add(fx("date_diff", 3, "month end-of-month clamp", call("date_diff", BIGINT, str("month"), date("2020-01-31"), date("2020-02-29"))))
            add(fx("date_diff", 3, "month exact", call("date_diff", BIGINT, str("month"), date("2020-01-15"), date("2020-03-15"))))
            add(fx("date_diff", 3, "week Sun→Mon", call("date_diff", BIGINT, str("week"), date("2024-01-07"), date("2024-01-08"))))
            add(fx("date_diff", 3, "week 14 days", call("date_diff", BIGINT, str("week"), date("2024-01-01"), date("2024-01-15"))))
            add(fx("date_diff", 3, "quarter", call("date_diff", BIGINT, str("quarter"), date("2020-01-01"), date("2020-06-30"))))
            add(fx("date_diff", 3, "year across boundary", call("date_diff", BIGINT, str("year"), date("2020-12-31"), date("2021-01-01"))))
            add(
                fx(
                    "date_diff", 3, "day on TIMESTAMP < 24h",
                    call("date_diff", BIGINT, str("day"), tsVar(), ts2Var()),
                    fromClause = fromTs2("2020-01-01 23:00:00", "2020-01-02 01:00:00"),
                ),
            )
            add(
                fx(
                    "date_diff", 3, "hour on TIMESTAMP",
                    call("date_diff", BIGINT, str("hour"), tsVar(), ts2Var()),
                    fromClause = fromTs2("2020-01-01 00:30:00", "2020-01-01 03:15:00"),
                ),
            )
            add(
                fx(
                    "date_diff", 3, "minute on TIMESTAMP",
                    call("date_diff", BIGINT, str("minute"), tsVar(), ts2Var()),
                    fromClause = fromTs2("2020-01-01 00:00:30", "2020-01-01 00:02:15"),
                ),
            )
            add(
                fx(
                    "date_diff", 3, "second on TIMESTAMP (fraction)",
                    call("date_diff", BIGINT, str("second"), tsVar(), ts2Var()),
                    fromClause = fromTs2("2020-01-01 00:00:00.900", "2020-01-01 00:00:01.500"),
                ),
            )
            add(
                fx(
                    "date_diff", 3, "millisecond on TIMESTAMP",
                    call("date_diff", BIGINT, str("millisecond"), tsVar(), ts2Var()),
                    fromClause = fromTs2("2020-01-01 00:00:00", "2020-01-01 00:00:01.500"),
                ),
            )
            add(np("date_diff", 3, "unknown unit not pushed", call("date_diff", BIGINT, str("fortnight"), date("2024-01-01"), date("2024-01-15"))))
            add(fx("week", 1, "ISO week 53", call("week", BIGINT, date("2021-01-01"))))
            add(fx("week", 1, "ISO week 1 spanning year", call("week", BIGINT, date("2024-12-30"))))
            add(fx("week_of_year", 1, "→week", call("week_of_year", BIGINT, date("2021-01-01"))))
            add(fx("day_of_week", 1, "ISO Sun=7", call("day_of_week", BIGINT, date("2024-01-07"))))
            add(fx("day_of_week", 1, "ISO Mon=1", call("day_of_week", BIGINT, date("2024-01-08"))))
            add(fx("day_of_year", 1, "→dayofyear", call("day_of_year", BIGINT, date("2024-03-01"))))
            add(fx("last_day_of_month", 1, "→last_day (leap)", call("last_day_of_month", DATE, date("2024-02-10"))))
            add(fx("last_day_of_month", 1, "→last_day (Dec)", call("last_day_of_month", DATE, date("2023-12-01"))))
            add(fx("year_of_week", 1, "ISO isoyear", call("year_of_week", BIGINT, date("2024-12-30"))))
            add(fx("year_of_week", 1, "ISO isoyear early Jan", call("year_of_week", BIGINT, date("2021-01-01"))))
            add(fx("yow", 1, "ISO isoyear", call("yow", BIGINT, date("2024-12-30"))))
            add(fx("hour", 1, "on TIMESTAMP", call("hour", BIGINT, tsVar()), fromClause = fromTs("2024-01-01 13:45:30")))
            add(fx("hour", 1, "on TIMESTAMP WITH TIME ZONE (session zone)", call("hour", BIGINT, tstzVar()), fromClause = fromTstz("2024-01-01 13:45:30")))
            add(fx("minute", 1, "on TIMESTAMP", call("minute", BIGINT, tsVar()), fromClause = fromTs("2024-01-01 13:45:30")))
            add(fx("second", 1, "on TIMESTAMP", call("second", BIGINT, tsVar()), fromClause = fromTs("2024-01-01 13:45:30")))
            add(fx("second", 1, "fraction dropped", call("second", BIGINT, tsVar()), fromClause = fromTs("2024-01-01 13:45:30.999")))
            add(fx("millisecond", 1, "millis-of-second (seconds≠0)", call("millisecond", BIGINT, tsVar()), fromClause = fromTs("2024-01-01 00:00:05.123")))
            add(fx("millisecond", 1, "zero", call("millisecond", BIGINT, tsVar()), fromClause = fromTs("2024-01-01 00:00:59")))
            // Naive TIMESTAMP under a DST zone is declined (Trino and ICU disagree on the ambiguous
            // autumn hour); fixed-offset sessions push — pinned in TestDuckBridgeExpressionTranslator.
            add(np("to_unixtime", 1, "naive TIMESTAMP under DST session zone not pushed", call("to_unixtime", DOUBLE, tsVar())))
            add(fx("to_unixtime", 1, "TIMESTAMP WITH TIME ZONE", call("to_unixtime", DOUBLE, tstzVar()), fromClause = fromTstz("2024-06-01 12:00:00")))
            add(
                fx(
                    "from_unixtime", 1, "→to_timestamp epoch",
                    call("from_unixtime", TIMESTAMP_TZ_MILLIS, dbl(0.0)),
                    wrapTrino = { "to_unixtime($it)" }, wrapDuck = { "CAST(epoch($it) AS DOUBLE)" },
                ),
            )
            add(
                fx(
                    "from_unixtime", 1, "fractional",
                    call("from_unixtime", TIMESTAMP_TZ_MILLIS, dbl(1717243200.5)),
                    wrapTrino = { "to_unixtime($it)" }, wrapDuck = { "CAST(epoch($it) AS DOUBLE)" },
                ),
            )
            add(
                fx(
                    "with_timezone", 2, "arg-order flip → instant",
                    call("with_timezone", TIMESTAMP_TZ_MILLIS, tsVar(), str("America/Los_Angeles")),
                    wrapTrino = { "to_unixtime($it)" }, wrapDuck = { "CAST(epoch($it) AS DOUBLE)" },
                    fromClause = fromTs("2024-01-01 12:00:00"),
                ),
            )
        }

    // ---- Encoding / hash ---------------------------------------------------------------------

    private fun encodingAndHashFixtures(): List<Case> =
        buildList {
            add(fx("to_hex", 1, "→hex", call("to_hex", VARCHAR, str("abc")), trinoSql = "to_hex(CAST('abc' AS VARBINARY))"))
            add(fx("to_hex", 1, "multi-byte", call("to_hex", VARCHAR, str("é")), trinoSql = "to_hex(CAST('é' AS VARBINARY))"))
            add(np("from_hex", 1, "not pushed (odd length: Trino errors, DuckDB pads)", call("from_hex", VARCHAR, str("abc"))))
            add(fx("to_base64", 1, "basic", call("to_base64", VARCHAR, str("abc")), trinoSql = "to_base64(CAST('abc' AS VARBINARY))"))
            add(fx("to_base64", 1, "padding", call("to_base64", VARCHAR, str("ab")), trinoSql = "to_base64(CAST('ab' AS VARBINARY))"))
            add(np("from_base64", 1, "not pushed (unpadded: Trino decodes, DuckDB errors)", call("from_base64", VARCHAR, str("YWI"))))
            add(fx("md5", 1, "unhex-wrapped", call("md5", VARCHAR, str("abc")), wrapBoth = { "to_hex($it)" }, trinoSql = "md5(CAST('abc' AS VARBINARY))"))
            add(fx("md5", 1, "empty", call("md5", VARCHAR, str("")), wrapBoth = { "to_hex($it)" }, trinoSql = "md5(CAST('' AS VARBINARY))"))
            add(fx("sha1", 1, "unhex-wrapped", call("sha1", VARCHAR, str("abc")), wrapBoth = { "to_hex($it)" }, trinoSql = "sha1(CAST('abc' AS VARBINARY))"))
            add(
                fx(
                    "sha256", 1, "unhex-wrapped",
                    call("sha256", VARCHAR, str("abc")),
                    wrapBoth = { "to_hex($it)" }, trinoSql = "sha256(CAST('abc' AS VARBINARY))",
                ),
            )
            add(
                fx(
                    "sha256", 1, "unicode bytes",
                    call("sha256", VARCHAR, str("straße")),
                    wrapBoth = { "to_hex($it)" }, trinoSql = "sha256(CAST('straße' AS VARBINARY))",
                ),
            )
        }

    // ---- CAST / conditional ------------------------------------------------------------------

    private fun castAndMiscFixtures(): List<Case> =
        buildList {
            add(fx("if", 2, "false→NULL", call("if", BIGINT, bool(false), int(1))))
            add(fx("if", 2, "true→then", call("if", BIGINT, bool(true), int(1))))
            add(fx("if", 3, "true→then (bare)", call("if", BIGINT, bool(true), int(1), int(2))))
            add(fx("if", 3, "false→else", call("if", BIGINT, bool(false), int(1), int(2))))
            add(fx("\$cast", 1, "BIGINT→VARCHAR", cast(VARCHAR, int(1234567))))
            add(fx("\$cast", 1, "BOOLEAN→VARCHAR", cast(VARCHAR, bool(true))))
            add(fx("\$cast", 1, "DATE→VARCHAR", cast(VARCHAR, date("2024-02-29"))))
            add(fx("\$cast", 1, "DOUBLE→BIGINT rounds half away", cast(BIGINT, dbl(2.5))))
            add(fx("\$cast", 1, "DOUBLE→BIGINT negative half", cast(BIGINT, dbl(-2.5))))
            add(fx("\$cast", 1, "BIGINT→DOUBLE", cast(DOUBLE, int(3))))
            add(fx("\$cast", 1, "BIGINT→BOOLEAN", cast(BOOLEAN, int(2))))
            add(fx("\$cast", 1, "DOUBLE→INTEGER overflow (both error)", cast(io.trino.spi.type.IntegerType.INTEGER, dbl(1e12))))
            add(np("\$cast", 1, "DOUBLE→VARCHAR not pushed", cast(VARCHAR, dbl(1e7))))
            add(np("\$cast", 1, "VARCHAR→INTEGER not pushed", cast(io.trino.spi.type.IntegerType.INTEGER, str("1.0"))))
            add(np("\$try_cast", 1, "VARCHAR→DATE not pushed", tryCast(DATE, str("2020/01/01"))))
            add(np("\$try_cast", 1, "VARCHAR→BOOLEAN not pushed", tryCast(BOOLEAN, str("yes"))))
            add(fx("\$try_cast", 1, "DOUBLE→INTEGER overflow → NULL", tryCast(io.trino.spi.type.IntegerType.INTEGER, dbl(1e12))))
        }

    // ---- ALIAS natives (extension) — the EV-E corpus ----------------------------------------

    @Suppress("LongMethod")
    private fun aliasFixtures(): List<Case> =
        buildList {
            val caseCorpus =
                listOf(
                    "straße", "ß", "ẞ", "İ", "I", "ı", "ΟΔΥΣΣΕΥΣ", "ΣΑΣ", "ǅ", "ǆ", "ﬁ", "ŉ", "ΐ", "ǰ", "\uD801\uDC00", "\uD801\uDC28",
                    "ᾈ", "ᾀ", "Ⅷ", "ⓐ", "\u212A", "\u212B", "ſ", "\u2126", "ǈ", "ǉ", "ﬀ", "①", "ｆｕｌｌ", "\u1E9B\u0323", "\u0130\u0307",
                    "\u01C4", "\u10D0", "\u1C90", "\uA7C5", "Café 日本", "", "abc",
                )
            for (s in caseCorpus) {
                add(fx("lower", 1, "ICU simple mapping: ${cp(s)}", call("lower", VARCHAR, str(s))))
                add(fx("upper", 1, "ICU simple mapping: ${cp(s)}", call("upper", VARCHAR, str(s))))
            }
            for (s in listOf("hello", "", "cafe\u0301", "a\uD83D\uDE00b", "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", "e\u0301f")) {
                add(fx("reverse", 1, "code points: ${cp(s)}", call("reverse", VARCHAR, str(s))))
            }
            val wsCorpus =
                listOf(
                    " x ", "\tx\n", "\u00A0x\u00A0", "\u3000x\u3000", "\u2028x\u2029", "\u001Cx\u001F", "\u0085x\u0085", "\u2007x\u2007",
                    "\u202Fx\u202F", "\u200Bx\u200B", "\u180Ex\u180E", "\u000Bx\u000C", "\uFEFFx\uFEFF", "\u2000x\u200A", "\u205Fx\u205F",
                    "\u1680x\u1680", "\r\nx\r\n", "   ", "\u3000", "",
                )
            for (s in wsCorpus) {
                add(fx("trim", 1, "Java whitespace: ${cp(s)}", call("trim", VARCHAR, str(s))))
                add(fx("ltrim", 1, "Java whitespace: ${cp(s)}", call("ltrim", VARCHAR, str(s))))
                add(fx("rtrim", 1, "Java whitespace: ${cp(s)}", call("rtrim", VARCHAR, str(s))))
            }
            // A constant containing U+0000 has no plain-literal spelling in DuckDB SQL → never pushed.
            add(np("trim", 1, "NUL-bearing constant not pushed", call("trim", VARCHAR, str("\u0000x\u0000"))))
            for (s in listOf("e\u0301", "\u00E9", "A\u030A", "\u212B", "ﬁ", "①", "")) {
                add(fx("normalize", 1, "NFC: ${cp(s)}", call("normalize", VARCHAR, str(s))))
            }
            for (h in listOf("", "00", "ff", "deadbeef", "616263", "e29c93", "41".repeat(200))) {
                add(
                    fx(
                        "xxhash64", 1, "bytes $h",
                        call("xxhash64", VARCHAR, str("")),
                        wrapBoth = { "to_hex($it)" }, trinoSql = "xxhash64(from_hex('$h'))", duckSqlOverride = "trino_xxhash64(unhex('$h'))",
                    ),
                )
                add(
                    fx(
                        "sha512", 1, "bytes $h",
                        call("sha512", VARCHAR, str("")),
                        wrapBoth = { "to_hex($it)" }, trinoSql = "sha512(from_hex('$h'))", duckSqlOverride = "trino_sha512(unhex('$h'))",
                    ),
                )
                for (k in listOf("6b6579", "00", "41".repeat(64), "41".repeat(65), "42".repeat(200))) {
                    add(
                        fx(
                            "hmac_sha256", 2, "bytes $h key ${k.take(8)}…(${k.length / 2}B)", call("hmac_sha256", VARCHAR, str(""), str("")),
                            wrapBoth = { "to_hex($it)" }, trinoSql = "hmac_sha256(from_hex('$h'), from_hex('$k'))",
                            duckSqlOverride = "trino_hmac_sha256(unhex('$h'), unhex('$k'))",
                        ),
                    )
                }
            }
            // hmac_sha256 with an EMPTY key: Trino errors ("Empty key"), the extension returns a digest —
            // EV-E2, open in the extension repo; add the fixture back when it lands.
        }

    // ---- builders ----------------------------------------------------------------------------

    @Suppress("LongParameterList")
    private fun fx(
        name: String,
        arity: Int,
        label: String,
        expr: ConnectorExpression,
        wrapTrino: (String) -> String = { it },
        wrapDuck: (String) -> String = { it },
        wrapBoth: ((String) -> String)? = null,
        trinoSql: String? = null,
        fromClause: From? = null,
        duckSqlOverride: String? = null,
    ): Fixture =
        if (duckSqlOverride == null) {
            Fixture(name, arity, label, expr, wrapBoth ?: wrapTrino, wrapBoth ?: wrapDuck, trinoSql, fromClause)
        } else {
            OverriddenFixture(name, arity, label, expr, wrapBoth ?: wrapTrino, wrapBoth ?: wrapDuck, trinoSql, fromClause, duckSqlOverride)
        }

    /** A fixture whose DuckDB SQL is given explicitly (VARBINARY inputs the translator can't render). */
    class OverriddenFixture(
        name: String,
        arity: Int,
        label: String,
        expr: ConnectorExpression,
        wrapTrino: (String) -> String,
        wrapDuck: (String) -> String,
        trinoSql: String?,
        fromClause: From?,
        private val duckSql: String,
    ) : Fixture(name, arity, label, expr, wrapTrino, wrapDuck, trinoSql, fromClause) {
        override fun emittedDuckSql(): String = duckSql
    }

    private fun np(name: String, arity: Int, label: String, expr: ConnectorExpression): NotPushed = NotPushed(name, arity, label, expr)

    private val TS_COLUMN = tsColumn("ts", TIMESTAMP_MILLIS)
    private val TS2_COLUMN = tsColumn("ts2", TIMESTAMP_MILLIS)
    private val TSTZ_COLUMN = tsColumn("tstz", TIMESTAMP_TZ_MILLIS)

    private fun tsColumn(name: String, type: Type): JdbcColumnHandle =
        JdbcColumnHandle(
            name,
            JdbcTypeHandle(Types.OTHER, Optional.of("timestamp"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            type,
        )

    private val ASSIGNMENTS: Map<String, ColumnHandle> = mapOf("ts" to TS_COLUMN, "ts2" to TS2_COLUMN, "tstz" to TSTZ_COLUMN)

    /** Translator-side session: same zone as the Trino runner, tstz pushdown on. */
    val SESSION: ConnectorSession =
        TestingConnectorSession.builder()
            .setPropertyMetadata(DuckBridgeSessionProperties(DuckBridgeConfig()).getSessionProperties())
            .setTimeZoneKey(TIME_ZONE_KEY)
            .build()

    private fun call(name: String, returnType: Type, vararg args: ConnectorExpression): ConnectorExpression =
        Call(returnType, FunctionName(name), listOf(*args))

    private fun cast(target: Type, arg: ConnectorExpression): ConnectorExpression =
        Call(target, io.trino.spi.expression.StandardFunctions.CAST_FUNCTION_NAME, listOf(arg))

    private fun tryCast(target: Type, arg: ConnectorExpression): ConnectorExpression =
        Call(target, io.trino.spi.expression.StandardFunctions.TRY_CAST_FUNCTION_NAME, listOf(arg))

    private fun str(s: String): ConnectorExpression = Constant(Slices.utf8Slice(s), VARCHAR)

    private fun nullOf(type: Type): ConnectorExpression = Constant(null, type)

    private fun int(v: Long): ConnectorExpression = Constant(v, BIGINT)

    private fun dbl(v: Double): ConnectorExpression = Constant(v, DOUBLE)

    private fun bool(v: Boolean): ConnectorExpression = Constant(v, BOOLEAN)

    private fun date(iso: String): ConnectorExpression = Constant(LocalDate.parse(iso).toEpochDay(), DATE)

    private fun tsVar(): ConnectorExpression = Variable("ts", TIMESTAMP_MILLIS)

    private fun ts2Var(): ConnectorExpression = Variable("ts2", TIMESTAMP_MILLIS)

    private fun tstzVar(): ConnectorExpression = Variable("tstz", TIMESTAMP_TZ_MILLIS)

    private fun fromTs(literal: String): From = From("SELECT TIMESTAMP '$literal' AS ts")

    private fun fromTs2(a: String, b: String): From = From("SELECT TIMESTAMP '$a' AS ts, TIMESTAMP '$b' AS ts2")

    /**
     * A zoned instant bound as `tstz`, expressed in the SESSION zone on both engines. (Trino's
     * `hour(tstz)` etc. read the value's own zone; the connector assumes column values carry the
     * session zone — so the fixture only claims parity for that case.) Trino: `TIMESTAMP '… <zone>'`;
     * DuckDB (ICU): `TIMESTAMPTZ '… <zone>'`.
     */
    private fun fromTstz(local: String): From =
        From("SELECT TIMESTAMP '$local $ZONE' AS tstz", "SELECT TIMESTAMPTZ '$local $ZONE' AS tstz")

    private fun cp(s: String): String = s.codePoints().toArray().joinToString(" ") { String.format("U+%04X", it) }

    /**
     * Renders a [ConnectorExpression] as Trino SQL for evaluation on the Trino side. Constants are
     * typed literals; VARCHAR uses `U&'...'` with `\+XXXXXX` escapes so any code point (NUL, astral,
     * controls) round-trips; variables are bare column names bound by the fixture's FROM clause.
     */
    object TrinoSqlRenderer {
        fun render(e: ConnectorExpression): String =
            when (e) {
                is Variable -> e.name
                is Constant -> constant(e)
                is Call -> call(e)
                else -> error("unsupported expression: $e")
            }

        private fun call(c: Call): String {
            val fn = c.functionName
            val args = c.arguments.map(::render)
            return when (fn) {
                io.trino.spi.expression.StandardFunctions.CAST_FUNCTION_NAME -> "CAST(${args[0]} AS ${c.type.displayName})"
                io.trino.spi.expression.StandardFunctions.TRY_CAST_FUNCTION_NAME -> "TRY_CAST(${args[0]} AS ${c.type.displayName})"
                else -> args.joinToString(", ", "${fn.name}(", ")")
            }
        }

        private fun constant(k: Constant): String {
            val v = k.value ?: return "CAST(NULL AS ${k.type.displayName})"
            return when (k.type) {
                VARCHAR -> unicodeLiteral((v as Slice).toStringUtf8())
                BIGINT -> "BIGINT '$v'"
                DOUBLE -> "DOUBLE '$v'"
                BOOLEAN -> if (v as Boolean) "TRUE" else "FALSE"
                DATE -> "DATE '${LocalDate.ofEpochDay(v as Long)}'"
                else -> error("unsupported constant type: ${k.type}")
            }
        }

        fun unicodeLiteral(s: String): String {
            val sb = StringBuilder("U&'")
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                sb.append(String.format("\\+%06X", cp))
                i += Character.charCount(cp)
            }
            return sb.append('\'').toString()
        }
    }
}
