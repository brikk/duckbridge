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

import io.trino.sql.planner.plan.FilterNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Division and modulo stay in Trino because integer truncation and divide-by-zero semantics differ. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeArithmeticPushdownParity : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        val runner = DuckBridgeQueryRunner.create(DuckBridgeQueryRunner.freshDatabaseUrl())
        runner.execute("CREATE SCHEMA ${DuckBridgeQueryRunner.CATALOG}.${DuckBridgeQueryRunner.SCHEMA}")
        return runner
    }

    @Test
    fun additionPredicatePushesFully() {
        withTable("add_push", "SELECT * FROM (VALUES (4), (5), (6)) AS t(id)") {
            assertThat(query("SELECT id FROM add_push WHERE id + 1 = 6")).isFullyPushedDown()
        }
    }

    @Test
    fun integerDividePredicateStaysAboveScanAndMatchesTrino() {
        withTable("div_trunc", "SELECT * FROM (VALUES (4), (5), (3), (6)) AS t(id)") {
            assertThat(query("SELECT id FROM div_trunc WHERE id / 2 = 2"))
                .isNotFullyPushedDown(FilterNode::class.java)
            val ids =
                computeActual("SELECT id FROM div_trunc WHERE id / 2 = 2 ORDER BY id")
                    .materializedRows
                    .map { it.getField(0) as Int }
            assertThat(ids).containsExactly(4, 5)
        }
    }

    @Test
    fun integerDivideByZeroThrowsEndToEnd() {
        withTable("div_zero", "SELECT * FROM (VALUES (10, 2), (20, 0), (30, 5)) AS t(id, divisor)") {
            val thrown =
                catchThrowable {
                    drain("SELECT id FROM div_zero WHERE id / divisor = 5")
                }
            assertThat(thrown).`as`("Trino integer divide-by-zero must throw end to end").isNotNull()
            assertThat(thrown.message?.lowercase().orEmpty())
                .containsAnyOf("division by zero", "divide by zero", "/ by zero")
        }
    }

    @Test
    fun integerModuloMatchesTrinoSemantics() {
        withTable("mod_basic", "SELECT * FROM (VALUES (10), (11), (12), (13)) AS t(id)") {
            assertThat(query("SELECT id FROM mod_basic WHERE id % 3 = 1"))
                .isNotFullyPushedDown(FilterNode::class.java)
            val ids =
                computeActual("SELECT id FROM mod_basic WHERE id % 3 = 1 ORDER BY id")
                    .materializedRows
                    .map { it.getField(0) as Int }
            assertThat(ids).containsExactly(10, 13)
        }
    }

    @Test
    fun integerModuloByZeroThrowsEndToEnd() {
        withTable("mod_zero", "SELECT * FROM (VALUES (10, 2), (20, 0), (30, 5)) AS t(id, divisor)") {
            val thrown =
                catchThrowable {
                    drain("SELECT id FROM mod_zero WHERE id % divisor = 0")
                }
            assertThat(thrown).`as`("Trino integer modulo-by-zero must throw end to end").isNotNull()
            assertThat(thrown.message?.lowercase().orEmpty())
                .containsAnyOf("division by zero", "divide by zero", "/ by zero")
        }
    }

    @Test
    fun functionModuloZeroAndDynamicDivisorsStayLocalAndThrowEndToEnd() {
        withTable("mod_function_zero", "SELECT * FROM (VALUES (10, 2), (20, 0), (30, 5)) AS t(id, divisor)") {
            for (divisor in listOf("0", "divisor")) {
                val sql = "SELECT id FROM mod_function_zero WHERE mod(id, $divisor) = 0"
                val plan = computeActual("EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()
                assertThat(plan).describedAs("mod(id, %s) must retain the Trino filter", divisor)
                    .contains("filterPredicate")
                val thrown = catchThrowable { drain(sql) }
                assertThat(thrown).describedAs("mod(id, %s) must preserve division-by-zero failure", divisor)
                    .isNotNull()
                assertThat(thrown.message?.lowercase().orEmpty())
                    .containsAnyOf("division by zero", "divide by zero", "/ by zero")
            }
        }
    }

    private fun drain(sql: String) {
        val rows = computeActual(sql).materializedRows
        for (row in rows) {
            for (i in 0 until row.fieldCount) {
                row.getField(i)
            }
        }
    }

    private fun withTable(name: String, asSelect: String, body: () -> Unit) {
        computeActual("CREATE TABLE $name AS $asSelect")
        try {
            body()
        } finally {
            computeActual("DROP TABLE IF EXISTS $name")
        }
    }
}
