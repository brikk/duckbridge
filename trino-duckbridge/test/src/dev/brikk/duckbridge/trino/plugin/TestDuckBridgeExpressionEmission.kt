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

import com.google.common.collect.ImmutableMap
import io.airlift.slice.Slices
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.spi.connector.ColumnHandle
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.StandardFunctions
import io.trino.spi.expression.Variable
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.Type
import io.trino.spi.type.VarcharType.VARCHAR
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Types
import java.util.Optional

class TestDuckBridgeExpressionEmission {

    @Test
    fun testBareEmitsPlainBuiltinName() {
        val expr =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("abs"), BIGINT, Variable("id", BIGINT)),
                Constant(5L, BIGINT),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(expr, ASSIGNMENTS))
            .containsExactly("(abs(\"id\") = 5)")
    }

    @Test
    fun testRenameEmitsDuckDbName() {
        val toHex =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("to_hex"), VARCHAR, Variable("name", VARCHAR)),
                varcharConst("6162"),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(toHex, ASSIGNMENTS))
            .containsExactly("(hex(\"name\") = '6162')")

        val regexpLike =
            call(
                FunctionName("regexp_like"),
                BOOLEAN,
                Variable("name", VARCHAR),
                varcharConst("[0-9]+"),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(regexpLike, ASSIGNMENTS))
            .containsExactly("regexp_matches(\"name\", '[0-9]+')")

        val bxor =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("bitwise_xor"), BIGINT, Variable("id", BIGINT), Constant(3L, BIGINT)),
                Constant(6L, BIGINT),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(bxor, ASSIGNMENTS))
            .containsExactly("(xor(\"id\", 3) = 6)")
    }

    @Test
    fun testOperatorEmitsParenthesizedInfixAndPrefix() {
        val band =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("bitwise_and"), BIGINT, Variable("id", BIGINT), Constant(3L, BIGINT)),
                Constant(1L, BIGINT),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(band, ASSIGNMENTS))
            .containsExactly("((\"id\" & 3) = 1)")

        val bnot =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("bitwise_not"), BIGINT, Variable("id", BIGINT)),
                Constant(-1L, BIGINT),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(bnot, ASSIGNMENTS))
            .containsExactly("((~\"id\") = -1)")

        // Shifts are not pushed: Trino's bitwise_right_shift is logical (zero-fill)
        // where DuckDB's >> is arithmetic, and Trino's left shift wraps to the operand width / yields 0
        // for shift >= width where DuckDB throws.
        val bshift =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("bitwise_left_shift"), BIGINT, Variable("id", BIGINT), Constant(2L, BIGINT)),
                Constant(4L, BIGINT),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(bshift, ASSIGNMENTS)).isEmpty()
    }

    @Test
    fun testInlineEmitsTransformTemplate() {
        val rr2 =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("regexp_replace"), VARCHAR, Variable("name", VARCHAR), varcharConst("x")),
                varcharConst("y"),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(rr2, ASSIGNMENTS))
            .containsExactly("(regexp_replace(\"name\", 'x', '', 'g') = 'y')")

        val rr3 =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("regexp_replace"), VARCHAR, Variable("name", VARCHAR), varcharConst("x"), varcharConst("z")),
                varcharConst("y"),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(rr3, ASSIGNMENTS))
            .containsExactly("(regexp_replace(\"name\", 'x', 'z', 'g') = 'y')")

        val md5 =
            call(
                StandardFunctions.IS_NULL_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("md5"), VARCHAR, Variable("name", VARCHAR)),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(md5, ASSIGNMENTS))
            .containsExactly("(unhex(md5(\"name\")) IS NULL)")

        val if2 =
            call(
                StandardFunctions.IS_NULL_FUNCTION_NAME,
                BOOLEAN,
                Call(
                    BIGINT,
                    FunctionName("if"),
                    listOf(
                        call(StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME, BOOLEAN, Variable("id", BIGINT), Constant(1L, BIGINT)),
                        Constant(9L, BIGINT),
                    ),
                ),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(if2, ASSIGNMENTS))
            .containsExactly("(if((\"id\" = 1), 9, NULL) IS NULL)")

        val if3 =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                Call(
                    BIGINT,
                    FunctionName("if"),
                    listOf(
                        call(StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME, BOOLEAN, Variable("id", BIGINT), Constant(1L, BIGINT)),
                        Constant(9L, BIGINT),
                        Constant(8L, BIGINT),
                    ),
                ),
                Constant(9L, BIGINT),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(if3, ASSIGNMENTS))
            .containsExactly("(if((\"id\" = 1), 9, 8) = 9)")
    }

    @Test
    fun testAliasEmitsTrinoPrefixWhenAvailable() {
        val expr =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("lower"), VARCHAR, Variable("name", VARCHAR)),
                varcharConst("apple"),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(expr, ASSIGNMENTS, null, true))
            .containsExactly("(trino_lower(\"name\") = 'apple')")
    }

    @Test
    fun testAliasNotPushedWhenExtensionUnavailable() {
        val expr =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("lower"), VARCHAR, Variable("name", VARCHAR)),
                varcharConst("apple"),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(expr, ASSIGNMENTS, null, false)).isEmpty()
    }

    @Test
    fun testBareStillPushesWhenExtensionUnavailable() {
        val expr =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("length"), BIGINT, Variable("name", VARCHAR)),
                Constant(5L, BIGINT),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(expr, ASSIGNMENTS, null, false))
            .containsExactly("(length(\"name\") = 5)")
    }


    @Test
    fun testLpadPushesWithConstantNonEmptyPad() {
        val expr =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("lpad"), VARCHAR, Variable("name", VARCHAR), Constant(10L, BIGINT), varcharConst("-")),
                varcharConst("---apple"),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(expr, ASSIGNMENTS))
            .containsExactly("(lpad(\"name\", 10, '-') = '---apple')")
    }

    @Test
    fun testLpadNotPushedWithEmptyPad() {
        val expr =
            call(
                StandardFunctions.IS_NULL_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("lpad"), VARCHAR, Variable("name", VARCHAR), Constant(10L, BIGINT), varcharConst("")),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(expr, ASSIGNMENTS)).isEmpty()
    }

    @Test
    fun testLpadNotPushedWithNonConstantPad() {
        val expr =
            call(
                StandardFunctions.IS_NULL_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("lpad"), VARCHAR, Variable("name", VARCHAR), Constant(10L, BIGINT), Variable("name", VARCHAR)),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(expr, ASSIGNMENTS)).isEmpty()
    }

    @Test
    fun testRpadNotPushedWithEmptyPad() {
        val expr =
            call(
                StandardFunctions.IS_NULL_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("rpad"), VARCHAR, Variable("name", VARCHAR), Constant(10L, BIGINT), varcharConst("")),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(expr, ASSIGNMENTS)).isEmpty()
    }

    @Test
    fun testSubstringPushesWithConstantStartAtLeastOne() {
        val expr =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("substring"), VARCHAR, Variable("name", VARCHAR), Constant(2L, BIGINT)),
                varcharConst("pple"),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(expr, ASSIGNMENTS))
            .containsExactly("(substring(\"name\", 2) = 'pple')")
    }

    @Test
    fun testSubstringThreeArgPushesWithConstantStartAtLeastOne() {
        val expr =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("substring"), VARCHAR, Variable("name", VARCHAR), Constant(1L, BIGINT), Constant(3L, BIGINT)),
                varcharConst("App"),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(expr, ASSIGNMENTS))
            .containsExactly("(substring(\"name\", 1, 3) = 'App')")
    }

    @Test
    fun testSubstringNotPushedWithStartZero() {
        val expr =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("substring"), VARCHAR, Variable("name", VARCHAR), Constant(0L, BIGINT)),
                varcharConst("Apple"),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(expr, ASSIGNMENTS)).isEmpty()
    }

    @Test
    fun testSubstringNotPushedWithNonConstantStart() {
        val expr =
            call(
                StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME,
                BOOLEAN,
                call(FunctionName("substring"), VARCHAR, Variable("name", VARCHAR), Variable("id", BIGINT)),
                varcharConst("Apple"),
            )
        assertThat(DuckBridgeExpressionTranslator.translateConjuncts(expr, ASSIGNMENTS)).isEmpty()
    }

    companion object {
        private val NAME_COLUMN = jdbcColumn("name", VARCHAR)
        private val ID_COLUMN = jdbcColumn("id", BIGINT)
        private val ASSIGNMENTS: Map<String, ColumnHandle> =
            ImmutableMap.of("name", NAME_COLUMN, "id", ID_COLUMN)

        private fun jdbcColumn(name: String, type: Type): JdbcColumnHandle {
            val typeHandle =
                JdbcTypeHandle(
                    Types.OTHER,
                    Optional.of(type.displayName),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                )
            return JdbcColumnHandle(name, typeHandle, type)
        }

        private fun call(name: FunctionName, returnType: Type, vararg args: ConnectorExpression): ConnectorExpression =
            Call(returnType, name, listOf(*args))

        private fun varcharConst(s: String): ConnectorExpression = Constant(Slices.utf8Slice(s), VARCHAR)
    }
}
