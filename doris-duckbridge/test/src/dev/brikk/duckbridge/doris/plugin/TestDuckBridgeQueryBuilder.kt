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
    ) = DuckBridgeQueryBuilder.buildQuery("memory", "sales", "customers", columns, filter, limit)

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
        val sql = build(listOf(intCol("id")), and)
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
        // A dropped conjunct (function call, out of scope) → LIMIT withheld (would under-return).
        val fn = ConnectorFunctionCall("length", ConnectorType.of("INT"), listOf(colRef("name", ConnectorType.of("STRING"))))
        val withFn = ConnectorAnd(listOf(eq(colRef("id"), ConnectorLiteral.ofLong(1)), fn))
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

    @Test
    fun functionAndLikeAreNotPushed() {
        // ConnectorFunctionCall is out of scope (P1) → dropped.
        val fn = ConnectorFunctionCall("upper", ConnectorType.of("STRING"), listOf(colRef("name", ConnectorType.of("STRING"))))
        val cmp = ConnectorComparison(ConnectorComparison.Operator.EQ, fn, ConnectorLiteral.ofString("X"))
        assertThat(build(listOf(stringCol("name")), cmp))
            .isEqualTo("""SELECT "name" FROM "memory"."sales"."customers"""")
    }
}
