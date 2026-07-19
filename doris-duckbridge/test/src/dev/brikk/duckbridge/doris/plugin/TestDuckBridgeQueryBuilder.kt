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
package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.api.ConnectorType
import org.apache.doris.connector.api.handle.ConnectorColumnHandle
import org.apache.doris.connector.api.pushdown.ConnectorAnd
import org.apache.doris.connector.api.pushdown.ConnectorColumnRef
import org.apache.doris.connector.api.pushdown.ConnectorComparison
import org.apache.doris.connector.api.pushdown.ConnectorExpression
import org.apache.doris.connector.api.pushdown.ConnectorFunctionCall
import org.apache.doris.connector.api.pushdown.ConnectorIn
import org.apache.doris.connector.api.pushdown.ConnectorIsNull
import org.apache.doris.connector.api.pushdown.ConnectorLiteral
import org.apache.doris.connector.api.pushdown.ConnectorOr
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Golden-SQL unit tests for [DuckBridgeQueryBuilder] — projection, the domain-floor predicate
 * shapes, LIMIT gating, and identifier/literal escaping (unicode-safe, NUL-refusing). No Docker.
 */
class TestDuckBridgeQueryBuilder {

    private fun intCol(name: String) =
        DuckBridgeColumnHandle(name, ConnectorType.of("INT"), "INTEGER", true, 0)

    private fun stringCol(name: String) =
        DuckBridgeColumnHandle(name, ConnectorType.of("STRING"), "VARCHAR", true, 0)

    private fun arrayCol(name: String) =
        DuckBridgeColumnHandle(name, ConnectorType.arrayOf(ConnectorType.of("INT")), "INTEGER[]", true, 0)

    private fun build(
        columns: List<ConnectorColumnHandle>,
        filter: ConnectorExpression? = null,
        limit: Long = -1,
        columnDuckdbTypes: Map<String, String> = emptyMap(),
    ) = DuckBridgeQueryBuilder.buildQuery(
        "memory", "sales", "customers", columns, filter, limit, columnDuckdbTypes,
    )

    private fun colRef(name: String, type: ConnectorType = ConnectorType.of("INT")) =
        ConnectorColumnRef(name, type)

    private fun eq(col: ConnectorColumnRef, lit: ConnectorLiteral) =
        ConnectorComparison(ConnectorComparison.Operator.EQ, col, lit)

    @Test
    fun projectionAndTableQualification() {
        assertThat(build(emptyList()))
            .isEqualTo("""SELECT * FROM "memory"."sales"."customers"""")
        assertThat(build(listOf(intCol("id"), stringCol("name"))))
            .isEqualTo("""SELECT "id", "name" FROM "memory"."sales"."customers"""")
    }

    @Test
    fun comparisonPredicates() {
        assertThat(build(listOf(intCol("id")), eq(colRef("id"), ConnectorLiteral.ofLong(5))))
            .isEqualTo("""SELECT "id" FROM "memory"."sales"."customers" WHERE ("id" = 5)""")
        assertThat(
            build(
                listOf(intCol("id")),
                ConnectorComparison(ConnectorComparison.Operator.GE, colRef("id"), ConnectorLiteral.ofLong(3)),
            ),
        ).contains(""""id" >= 3""")
        assertThat(
            build(
                listOf(intCol("id")),
                ConnectorComparison(ConnectorComparison.Operator.NE, colRef("id"), ConnectorLiteral.ofLong(3)),
            ),
        ).contains(""""id" <> 3""")
    }

    @Test
    fun stringLiteralEscapingUnicodeSafe() {
        // Single-quote doubling + unicode preserved verbatim.
        val f = eq(colRef("name", ConnectorType.of("STRING")), ConnectorLiteral.ofString("straße'; DROP"))
        assertThat(build(listOf(stringCol("name")), f))
            .isEqualTo("""SELECT "name" FROM "memory"."sales"."customers" WHERE ("name" = 'straße''; DROP')""")
        // Astral plane / non-latin round-trips.
        val g = eq(colRef("name", ConnectorType.of("STRING")), ConnectorLiteral.ofString("δοκιμή"))
        assertThat(build(listOf(stringCol("name")), g)).contains("'δοκιμή'")
    }

    @Test
    fun identifierQuotingEscapesEmbeddedQuote() {
        val col = DuckBridgeColumnHandle("we\"ird", ConnectorType.of("INT"), "INTEGER", true, 0)
        assertThat(build(listOf(col))).contains(""""we""ird"""")
    }

    @Test
    fun nulLiteralIsRefusedAndConjunctDropped() {
        // A string literal containing U+0000 must NOT be pushed — the conjunct is dropped (no WHERE),
        // Doris re-filters above. Never silently mangled.
        val f = eq(colRef("name", ConnectorType.of("STRING")), ConnectorLiteral.ofString("a\u0000b"))
        assertThat(build(listOf(stringCol("name")), f))
            .isEqualTo("""SELECT "name" FROM "memory"."sales"."customers"""")
    }

    @Test
    fun inAndIsNull() {
        val inExpr = ConnectorIn(
            colRef("id"),
            listOf(ConnectorLiteral.ofLong(1), ConnectorLiteral.ofLong(2), ConnectorLiteral.ofLong(3)),
            false,
        )
        assertThat(build(listOf(intCol("id")), inExpr)).contains(""""id" IN (1, 2, 3)""")
        val notIn = ConnectorIn(colRef("id"), listOf(ConnectorLiteral.ofLong(9)), true)
        assertThat(build(listOf(intCol("id")), notIn)).contains(""""id" NOT IN (9)""")
        assertThat(build(listOf(intCol("id")), ConnectorIsNull(colRef("id"), false)))
            .contains(""""id" IS NULL""")
        assertThat(build(listOf(intCol("id")), ConnectorIsNull(colRef("id"), true)))
            .contains(""""id" IS NOT NULL""")
    }

    @Test
    fun booleanCombinatorsAndDate() {
        val and = ConnectorAnd(
            listOf(
                ConnectorComparison(ConnectorComparison.Operator.GE, colRef("id"), ConnectorLiteral.ofLong(1)),
                ConnectorComparison(
                    ConnectorComparison.Operator.EQ,
                    colRef("signup", ConnectorType.of("DATEV2")),
                    ConnectorLiteral.ofDate(LocalDate.of(2020, 1, 15)),
                ),
            ),
        )
        // Provide the signup column's DuckDB type (naive DATE) so the date comparison pushes
        // zone-safely (P3/P6). Without a known type a temporal comparison drops (fail-safe).
        val sql = build(listOf(intCol("id")), and, columnDuckdbTypes = mapOf("signup" to "DATE"))
        // Top-level AND flattens into WHERE (a) AND (b).
        assertThat(sql).contains(""""id" >= 1""").contains("""DATE '2020-01-15'""").contains(" AND ")
        val or = ConnectorOr(
            listOf(
                eq(colRef("id"), ConnectorLiteral.ofLong(1)),
                eq(colRef("id"), ConnectorLiteral.ofLong(2)),
            ),
        )
        assertThat(build(listOf(intCol("id")), or)).contains("(\"id\" = 1) OR (\"id\" = 2)")
    }

    @Test
    fun limitPushedOnlyWhenAllFiltersRendered() {
        // No filter → LIMIT pushes.
        assertThat(build(listOf(intCol("id")), null, 10)).endsWith(" LIMIT 10")
        // Fully-pushable filter → LIMIT pushes.
        assertThat(build(listOf(intCol("id")), eq(colRef("id"), ConnectorLiteral.ofLong(1)), 10))
            .endsWith(" LIMIT 10")
        // A dropped conjunct (a DIVERGENT function → unpushable) → LIMIT withheld (would under-return).
        val divergent = ConnectorComparison(
            ConnectorComparison.Operator.EQ,
            ConnectorFunctionCall("upper", ConnectorType.of("STRING"), listOf(colRef("name", ConnectorType.of("STRING")))),
            ConnectorLiteral.ofString("X"),
        )
        val withFn = ConnectorAnd(listOf(eq(colRef("id"), ConnectorLiteral.ofLong(1)), divergent))
        val sql = build(listOf(intCol("id")), withFn, 10)
        assertThat(sql).doesNotContain("LIMIT")
        // The renderable conjunct still pushes.
        assertThat(sql).contains(""""id" = 1""")
    }

    @Test
    fun nonScalarColumnPredicateIsDropped() {
        // A predicate on an ARRAY column is not the domain floor → dropped (no WHERE).
        val f = ConnectorIsNull(colRef("tags", ConnectorType.arrayOf(ConnectorType.of("STRING"))), false)
        assertThat(build(listOf(arrayCol("tags")), f))
            .isEqualTo("""SELECT "tags" FROM "memory"."sales"."customers"""")
    }

    // ---- P1 function allowlist: DIVERGENT functions stay unpushed ----

    private fun strColRef(name: String) = colRef(name, ConnectorType.of("STRING"))
    private fun fn(name: String, ret: ConnectorType, vararg args: ConnectorExpression) =
        ConnectorFunctionCall(name, ret, args.toList())
    private fun fnEq(call: ConnectorFunctionCall, lit: ConnectorLiteral) =
        ConnectorComparison(ConnectorComparison.Operator.EQ, call, lit)

    @Test
    fun divergentFunctionsStayUnpushed() {
        // upper/lower/reverse/concat/power are DIVERGENT in the P1 audit → never pushed.
        val nameRef = strColRef("name")
        val cases = listOf(
            fnEq(fn("upper", ConnectorType.of("STRING"), nameRef), ConnectorLiteral.ofString("X")),
            fnEq(fn("lower", ConnectorType.of("STRING"), nameRef), ConnectorLiteral.ofString("x")),
            fnEq(fn("reverse", ConnectorType.of("STRING"), nameRef), ConnectorLiteral.ofString("x")),
            fnEq(
                fn("concat", ConnectorType.of("STRING"), nameRef, ConnectorLiteral.ofString("y")),
                ConnectorLiteral.ofString("xy"),
            ),
        )
        for (c in cases) {
            assertThat(build(listOf(stringCol("name")), c))
                .describedAs("divergent fn dropped")
                .isEqualTo("""SELECT "name" FROM "memory"."sales"."customers"""")
        }
    }

    // ---- P1 function allowlist: IDENTICAL functions push with the audited rendering ----

    private fun buildFnWhere(call: ConnectorFunctionCall, lit: ConnectorLiteral): String =
        build(listOf(intCol("id")), fnEq(call, lit))

    @Test
    fun characterLengthRendersAsDuckDbLength() {
        // Doris character_length (code points) → DuckDB length.
        val sql = buildFnWhere(
            fn("character_length", ConnectorType.of("INT"), strColRef("name")),
            ConnectorLiteral.ofLong(6),
        )
        assertThat(sql).contains("""WHERE (length("name") = 6)""")
    }

    @Test
    fun lengthRendersAsDuckDbStrlenBytes() {
        // Doris length (BYTES) → DuckDB strlen — NOT DuckDB length (code points).
        val sql = buildFnWhere(
            fn("length", ConnectorType.of("INT"), strColRef("name")),
            ConnectorLiteral.ofLong(7),
        )
        assertThat(sql).contains("""WHERE (strlen("name") = 7)""")
        assertThat(sql).doesNotContain("length(") // must not use bare DuckDB length
    }

    @Test
    fun locateSwapsArgsToStrpos() {
        // Doris locate(needle, hay) → DuckDB strpos(hay, needle).
        val call = fn(
            "locate", ConnectorType.of("INT"),
            ConnectorLiteral.ofString("lo"), strColRef("name"),
        )
        assertThat(buildFnWhere(call, ConnectorLiteral.ofLong(1)))
            .contains("""WHERE (strpos("name", 'lo') = 1)""")
    }

    @Test
    fun instrKeepsArgOrder() {
        val call = fn(
            "instr", ConnectorType.of("INT"),
            strColRef("name"), ConnectorLiteral.ofString("lo"),
        )
        assertThat(buildFnWhere(call, ConnectorLiteral.ofLong(3)))
            .contains("""WHERE (instr("name", 'lo') = 3)""")
    }

    @Test
    fun startsWithPushes() {
        val call = fn(
            "starts_with", ConnectorType.of("BOOLEAN"),
            strColRef("name"), ConnectorLiteral.ofString("δ"),
        )
        // starts_with is a boolean-returning conjunct on its own.
        assertThat(build(listOf(intCol("id")), call))
            .contains("""WHERE (starts_with("name", 'δ'))""")
    }

    @Test
    fun dateExtractionAndAbsPush() {
        assertThat(
            buildFnWhere(
                fn("year", ConnectorType.of("INT"), colRef("signup", ConnectorType.of("DATEV2"))),
                ConnectorLiteral.ofLong(2020),
            ),
        ).contains("""WHERE (year("signup") = 2020)""")
        assertThat(
            buildFnWhere(fn("abs", ConnectorType.of("INT"), colRef("id")), ConnectorLiteral.ofLong(5)),
        ).contains("""WHERE (abs("id") = 5)""")
    }

    @Test
    fun substringPushesOnlyWithConstantStartNonZero() {
        val nameRef = strColRef("name")
        // start = 2 (constant, ≠ 0) → pushes.
        assertThat(
            build(
                listOf(stringCol("name")),
                fnEq(
                    fn("substring", ConnectorType.of("STRING"), nameRef, ConnectorLiteral.ofLong(2), ConnectorLiteral.ofLong(3)),
                    ConnectorLiteral.ofString("abc"),
                ),
            ),
        ).contains("""substring("name", 2, 3)""")
        // start = 0 → DIVERGENT (Doris NULL vs DuckDB 'he') → dropped.
        assertThat(
            build(
                listOf(stringCol("name")),
                fnEq(
                    fn("substring", ConnectorType.of("STRING"), nameRef, ConnectorLiteral.ofLong(0), ConnectorLiteral.ofLong(3)),
                    ConnectorLiteral.ofString("abc"),
                ),
            ),
        ).isEqualTo("""SELECT "name" FROM "memory"."sales"."customers"""")
    }

    @Test
    fun unAuditedFunctionIsDropped() {
        // A function not in the allowlist (e.g. sqrt — float-returning, excluded) → dropped.
        val call = fn("sqrt", ConnectorType.of("DOUBLE"), colRef("id"))
        assertThat(build(listOf(intCol("id")), fnEq(call, ConnectorLiteral.ofDouble(2.0))))
            .isEqualTo("""SELECT "id" FROM "memory"."sales"."customers"""")
    }

    // ---- P3/P6 timezone-safe temporal rendering ----

    private val dtLit = ConnectorLiteral.ofDatetime(java.time.LocalDateTime.of(2024, 6, 1, 6, 30, 0))
    private val dateLit = ConnectorLiteral.ofDate(LocalDate.of(2024, 6, 1))
    private fun dtCmp(col: String) = ConnectorComparison(
        ConnectorComparison.Operator.GE, colRef(col, ConnectorType.of("DATETIMEV2", 6, 0)), dtLit,
    )

    @Test
    fun naiveTimestampColumnPushesNaiveLiteral() {
        // A DuckDB naive TIMESTAMP column → naive literal (wall-clock, zone-independent).
        val sql = build(
            listOf(intCol("id")),
            dtCmp("dt"),
            columnDuckdbTypes = mapOf("dt" to "TIMESTAMP"),
        )
        assertThat(sql).contains("""WHERE ("dt" >= TIMESTAMP '2024-06-01 06:30:00')""")
        assertThat(sql).doesNotContain("+00")
    }

    @Test
    fun timestamptzColumnPushesExplicitUtcLiteral() {
        // A DuckDB TIMESTAMPTZ column → explicit-UTC literal (option 2, proven zone-independent).
        val sql = build(
            listOf(intCol("id")),
            dtCmp("ts"),
            columnDuckdbTypes = mapOf("ts" to "TIMESTAMP WITH TIME ZONE"),
        )
        assertThat(sql).contains("""WHERE ("ts" >= TIMESTAMPTZ '2024-06-01 06:30:00+00')""")
    }

    @Test
    fun timestamptzColumnDateLiteralPushesExplicitUtcMidnight() {
        val cmp = ConnectorComparison(
            ConnectorComparison.Operator.GE, colRef("ts", ConnectorType.of("DATETIMEV2", 6, 0)), dateLit,
        )
        val sql = build(
            listOf(intCol("id")), cmp, columnDuckdbTypes = mapOf("ts" to "TIMESTAMPTZ"),
        )
        assertThat(sql).contains("""WHERE ("ts" >= TIMESTAMPTZ '2024-06-01 00:00:00+00')""")
    }

    @Test
    fun unknownColumnTypeDropsTemporalComparison() {
        // Fail-safe: a datetime comparison against a column whose DuckDB type is UNKNOWN (not in the
        // map) DROPS — never the zone-dependent naive-literal-vs-TIMESTAMPTZ form.
        val sql = build(listOf(intCol("id")), dtCmp("mystery"), columnDuckdbTypes = emptyMap())
        assertThat(sql).isEqualTo("""SELECT "id" FROM "memory"."sales"."customers"""")
    }

    @Test
    fun naiveDateColumnPushesDateLiteral() {
        val cmp = ConnectorComparison(
            ConnectorComparison.Operator.GE, colRef("d", ConnectorType.of("DATEV2")), dateLit,
        )
        val sql = build(listOf(intCol("id")), cmp, columnDuckdbTypes = mapOf("d" to "DATE"))
        assertThat(sql).contains("""WHERE ("d" >= DATE '2024-06-01')""")
    }

    @Test
    fun timestamptzInListRendersExplicitUtc() {
        val inExpr = ConnectorIn(
            colRef("ts", ConnectorType.of("DATETIMEV2", 6, 0)),
            listOf(dtLit, ConnectorLiteral.ofDatetime(java.time.LocalDateTime.of(2024, 6, 1, 8, 0, 0))),
            false,
        )
        val sql = build(listOf(intCol("id")), inExpr, columnDuckdbTypes = mapOf("ts" to "TIMESTAMPTZ"))
        assertThat(sql).contains("TIMESTAMPTZ '2024-06-01 06:30:00+00'")
        assertThat(sql).contains("TIMESTAMPTZ '2024-06-01 08:00:00+00'")
    }
}
