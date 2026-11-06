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

import org.apache.doris.connector.spi.ConnectorType
import org.apache.doris.connector.spi.handle.ConnectorColumnHandle
import org.apache.doris.connector.spi.pushdown.ConnectorColumnRef
import org.apache.doris.connector.spi.pushdown.ConnectorComparison
import org.apache.doris.connector.spi.pushdown.ConnectorExpression
import org.apache.doris.connector.spi.pushdown.ConnectorFunctionCall
import org.apache.doris.connector.spi.pushdown.ConnectorLiteral
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeFunctionPushdownCanary {

    private lateinit var server: TestingQuackServer

    @BeforeAll
    fun setUp() {
        server = TestingQuackServer()
        server.exec(
            "CREATE SCHEMA sales",
            """
            CREATE TABLE sales.t (
                id INTEGER, name VARCHAR, s DATE
            )
            """.trimIndent(),
            "INSERT INTO sales.t VALUES " +
                "(5,  'hello',  DATE '2020-03-15')," +
                "(-3, 'straße', DATE '2021-06-30')," +   // byte(7)≠codepoint(6)
                "(9,  'δοκιμή', DATE '2000-01-01')",     // greek: byte(12) codepoint(6)
        )
    }

    @AfterAll
    fun tearDown() {
        if (::server.isInitialized) server.close()
    }

    private fun cols(vararg names: String): List<ConnectorColumnHandle> =
        names.map { DuckBridgeColumnHandle(it, ConnectorType.of("STRING"), "VARCHAR", true, 0) }

    private fun colRef(name: String, type: ConnectorType) = ConnectorColumnRef(name, type)
    private fun fn(name: String, ret: ConnectorType, vararg args: ConnectorExpression) =
        ConnectorFunctionCall(name, ret, args.toList())

    private fun idsWhere(cmp: ConnectorExpression): List<Int> {
        val sql = DuckBridgeQueryBuilder.buildQuery("memory", "sales", "t", cols("id"), cmp, -1)
        assertThat(sql).describedAs("predicate must push: $sql").contains("WHERE")
        val out = ArrayList<Int>()
        server.openConnection().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("$sql ORDER BY id").use { rs ->
                    while (rs.next()) out.add(rs.getInt(1))
                }
            }
        }
        return out
    }

    private fun eq(call: ConnectorFunctionCall, lit: ConnectorLiteral) =
        ConnectorComparison(ConnectorComparison.Operator.EQ, call, lit)

    private val nameStr = colRef("name", ConnectorType.of("STRING"))
    private val idInt = colRef("id", ConnectorType.of("INT"))
    private val sDate = colRef("s", ConnectorType.of("DATEV2"))

    @Test
    fun characterLength_codepoints() {
        assertThat(idsWhere(eq(fn("character_length", ConnectorType.of("INT"), nameStr), ConnectorLiteral.ofLong(6))))
            .containsExactly(-3, 9)
    }

    @Test
    fun length_bytes() {
        assertThat(idsWhere(eq(fn("length", ConnectorType.of("INT"), nameStr), ConnectorLiteral.ofLong(7))))
            .containsExactly(-3)
    }

    @Test
    fun locate_argSwap() {
        val call = fn("locate", ConnectorType.of("INT"), ConnectorLiteral.ofString("ß"), nameStr)
        assertThat(idsWhere(eq(call, ConnectorLiteral.ofLong(5)))).containsExactly(-3)
    }

    @Test
    fun instr_sameOrder() {
        val call = fn("instr", ConnectorType.of("INT"), nameStr, ConnectorLiteral.ofString("ell"))
        assertThat(idsWhere(eq(call, ConnectorLiteral.ofLong(2)))).containsExactly(5)
    }

    @Test
    fun startsWith() {
        val call = fn("starts_with", ConnectorType.of("BOOLEAN"), nameStr, ConnectorLiteral.ofString("δ"))
        assertThat(idsWhere(call)).containsExactly(9)
    }

    @Test
    fun substring_constStart() {
        val call = fn(
            "substring", ConnectorType.of("STRING"),
            nameStr, ConnectorLiteral.ofLong(2), ConnectorLiteral.ofLong(3),
        )
        assertThat(idsWhere(eq(call, ConnectorLiteral.ofString("ell")))).containsExactly(5)
    }

    @Test
    fun abs_int() {
        assertThat(idsWhere(eq(fn("abs", ConnectorType.of("INT"), idInt), ConnectorLiteral.ofLong(3))))
            .containsExactly(-3)
    }

    @Test
    fun dateExtraction() {
        assertThat(idsWhere(eq(fn("year", ConnectorType.of("INT"), sDate), ConnectorLiteral.ofLong(2020))))
            .containsExactly(5)
        assertThat(idsWhere(eq(fn("month", ConnectorType.of("INT"), sDate), ConnectorLiteral.ofLong(6))))
            .containsExactly(-3)
        assertThat(idsWhere(eq(fn("day", ConnectorType.of("INT"), sDate), ConnectorLiteral.ofLong(1))))
            .containsExactly(9)
    }
}
