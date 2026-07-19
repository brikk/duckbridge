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
import org.apache.doris.connector.api.pushdown.ConnectorColumnRef
import org.apache.doris.connector.api.pushdown.ConnectorComparison
import org.apache.doris.connector.api.pushdown.ConnectorExpression
import org.apache.doris.connector.api.pushdown.ConnectorFunctionCall
import org.apache.doris.connector.api.pushdown.ConnectorLiteral
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * P1 drift canary. For every allowlisted (audited-IDENTICAL) function, this:
 *   1. builds the DuckDB SQL the connector WOULD emit for `WHERE f(col) <op> <lit>` (via
 *      [DuckBridgeQueryBuilder]) — proving the rendering,
 *   2. runs it over a real quack/DuckDB server and asserts the **exact rows Doris produced in the
 *      P1 audit** (`dev-docs/REPORT-doris-duckdb-function-divergence.md`) come back.
 *
 * The audited expected values are hard-coded here, so a **DuckDB pin bump that changes any of them
 * re-proves (or breaks) alignment** — the drift-canary contract from the plan §Parity oracle 3.
 * Requires Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeFunctionPushdownCanary {

    private lateinit var server: TestingQuackServer

    @BeforeAll
    fun setUp() {
        server = TestingQuackServer()
        server.exec(
            "CREATE SCHEMA sales",
            // A fixture table exercising the audited functions: ascii, unicode (byte≠codepoint),
            // a date, an int.
            """
            CREATE TABLE sales.t (
                id INTEGER, name VARCHAR, s DATE
            )
            """.trimIndent(),
            "INSERT INTO sales.t VALUES " +
                "(5,  'hello',  DATE '2020-03-15')," +   // ascii
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

    /** Build `SELECT id FROM sales.t WHERE <cmp>` and return the id column of the resulting rows. */
    private fun idsWhere(cmp: ConnectorExpression): List<Int> {
        val sql = DuckBridgeQueryBuilder.buildQuery("memory", "sales", "t", cols("id"), cmp, -1)
        // Sanity: the predicate MUST have been pushed (this is a canary — a silent drop is a bug).
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

    // ---- audited expected values baked in (drift canary) ----

    @Test
    fun characterLength_codepoints() {
        // char_length('straße')=6, char_length('δοκιμή')=6, char_length('hello')=5.
        assertThat(idsWhere(eq(fn("character_length", ConnectorType.of("INT"), nameStr), ConnectorLiteral.ofLong(6))))
            .containsExactly(-3, 9)
    }

    @Test
    fun length_bytes() {
        // length('straße')=7 bytes; 'δοκιμή'=12; 'hello'=5. Only straße is 7.
        assertThat(idsWhere(eq(fn("length", ConnectorType.of("INT"), nameStr), ConnectorLiteral.ofLong(7))))
            .containsExactly(-3)
    }

    @Test
    fun locate_argSwap() {
        // Doris locate('ß', name) → DuckDB strpos(name,'ß'). 'straße' has ß at code-point 5.
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
        // substring('straße',2,3)='raß' ; substring('δοκιμή',2,3)='οκι' ; substring('hello',2,3)='ell'
        val call = fn(
            "substring", ConnectorType.of("STRING"),
            nameStr, ConnectorLiteral.ofLong(2), ConnectorLiteral.ofLong(3),
        )
        assertThat(idsWhere(eq(call, ConnectorLiteral.ofString("ell")))).containsExactly(5)
    }

    @Test
    fun abs_int() {
        // abs(-3)=3, abs(5)=5, abs(9)=9. Match abs=3 → id -3.
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
