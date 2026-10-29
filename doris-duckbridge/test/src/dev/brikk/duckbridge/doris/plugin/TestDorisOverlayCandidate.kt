package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.spi.ConnectorType
import org.apache.doris.connector.spi.pushdown.ConnectorAnd
import org.apache.doris.connector.spi.pushdown.ConnectorColumnRef
import org.apache.doris.connector.spi.pushdown.ConnectorComparison
import org.apache.doris.connector.spi.pushdown.ConnectorExpression
import org.apache.doris.connector.spi.pushdown.ConnectorFunctionCall
import org.apache.doris.connector.spi.pushdown.ConnectorLiteral
import org.apache.doris.connector.spi.pushdown.ConnectorNot
import org.apache.doris.connector.spi.pushdown.ConnectorOr
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TestDorisOverlayCandidate {
    private val string = ConnectorType.of("STRING")
    private val source = ConnectorColumnRef("source", string)
    private val replacement = ConnectorColumnRef("replacement", string)
    private val types = mapOf("source" to "VARCHAR", "replacement" to "VARCHAR")

    private fun call(
        start: ConnectorExpression = ConnectorLiteral.ofInt(2),
        count: ConnectorExpression = ConnectorLiteral.ofInt(1),
        input: ConnectorExpression = source,
        insert: ConnectorExpression = replacement,
    ) = ConnectorFunctionCall("overlay", string, listOf(input, start, count, insert))

    private fun predicate(fn: ConnectorFunctionCall) =
        ConnectorComparison(ConnectorComparison.Operator.EQ, fn, ConnectorLiteral.ofString("aXcd"))

    private fun productionQuery(filter: ConnectorExpression): String =
        DuckBridgeQueryBuilder.buildQuery(
            "memory", "sales", "overlay_inputs",
            listOf(DuckBridgeColumnHandle("id", ConnectorType.of("INT"), "INTEGER", false, 0)),
            filter, 10, types,
        )

    private fun render(fn: ConnectorFunctionCall, rawTypes: Map<String, String> = types): String? =
        DorisOverlayCandidate.render(fn, rawTypes) { argument -> when (argument) {
            is ConnectorColumnRef -> "\"${argument.columnName}\""
            is ConnectorLiteral -> when (val value = argument.value) {
                null -> "NULL"
                is String -> if ('\u0000' in value) null else "'${value.replace("'", "''")}'"
                else -> value.toString()
            }
            else -> null
        } }

    @Test
    fun rendersSourceAwareBoundariesAndStrictNulls() {
        assertThat(render(call())).isEqualTo(
            """CASE WHEN "source" IS NULL OR "replacement" IS NULL THEN NULL """ +
                """WHEN length("source") < 2 THEN "source" """ +
                """ELSE overlay("source" PLACING "replacement" FROM 2 FOR 1) END""",
        )
        assertThat(render(call(insert = ConnectorLiteral.ofString("x'😀"))))
            .contains("PLACING 'x''😀'")
    }

    @Test
    fun rejectsUnqualifiedBoundsAndIntegerCoercions() {
        val rejected = listOf(
            call(start = ConnectorLiteral.ofInt(0)), call(start = ConnectorLiteral.ofInt(-1)),
            call(start = ConnectorLiteral.ofLong(Int.MIN_VALUE.toLong())),
            call(start = ConnectorLiteral.ofLong(Int.MAX_VALUE.toLong() + 1)),
            call(count = ConnectorLiteral.ofInt(-1)),
            call(count = ConnectorLiteral.ofLong(Int.MAX_VALUE.toLong() + 1)),
            call(start = ConnectorLiteral.ofInt(2), count = ConnectorLiteral.ofInt(Int.MAX_VALUE)),
            call(start = ConnectorColumnRef("id", ConnectorType.of("INT"))),
            call(count = ConnectorColumnRef("id", ConnectorType.of("INT"))),
            call(start = ConnectorLiteral.ofNull(ConnectorType.of("INT"))),
            call(count = ConnectorLiteral.ofNull(ConnectorType.of("INT"))),
            call(start = ConnectorLiteral.ofDecimal(BigDecimal("2.0"), 2, 1)),
            call(start = ConnectorLiteral.ofString("2")), call(count = ConnectorLiteral.ofDouble(1.0)),
        )
        for (fn in rejected) assertThat(render(fn)).describedAs(fn.toString()).isNull()
        assertThat(render(call(start = ConnectorLiteral.ofInt(1), count = ConnectorLiteral.ofInt(Int.MAX_VALUE))))
            .contains("FOR 2147483647")
        assertThat(render(call(start = ConnectorLiteral.ofInt(Int.MAX_VALUE), count = ConnectorLiteral.ofInt(1))))
            .contains("FROM 2147483647 FOR 1")
    }

    @Test
    fun rejectsUnknownOrNonVarcharAndNestedInputs() {
        for (type in listOf("JSON", "CHAR", "BLOB", "VARCHAR[]", "VARIANT", "")) {
            assertThat(render(call(), types + ("source" to type))).isNull()
            assertThat(render(call(), types + ("replacement" to type))).isNull()
        }
        assertThat(render(call(), emptyMap())).isNull()
        val nested = ConnectorFunctionCall("substring", string, listOf(source, ConnectorLiteral.ofInt(1)))
        assertThat(render(call(input = nested))).isNull()
        assertThat(render(call(insert = nested))).isNull()
        assertThat(render(call(input = ConnectorColumnRef("source", ConnectorType.of("CHAR"))))).isNull()
        assertThat(render(call(insert = ConnectorLiteral.ofString("a\u0000b")))).isNull()
    }

    @Test
    fun checksArityReturnTypeAndKeepsBooleanAndLimitSafety() {
        val args = call().arguments
        for (arity in listOf(0, 1, 2, 3, 5)) {
            val fn = ConnectorFunctionCall("overlay", string, (args + source).take(arity))
            assertThat(render(fn)).isNull()
        }
        assertThat(render(ConnectorFunctionCall("overlay", ConnectorType.of("INT"), args))).isNull()
        val valid = predicate(call(count = ConnectorLiteral.ofInt(0)))
        val invalid = predicate(call(start = ConnectorLiteral.ofInt(0)))
        assertThat(productionQuery(valid)).doesNotContain("WHERE", "LIMIT")
        assertThat(productionQuery(ConnectorOr(listOf(valid, invalid)))).doesNotContain("WHERE", "LIMIT")
        assertThat(productionQuery(ConnectorNot(invalid))).doesNotContain("WHERE", "LIMIT")
        assertThat(productionQuery(ConnectorAnd(listOf(valid, invalid)))).doesNotContain("WHERE", "LIMIT")
    }
}
