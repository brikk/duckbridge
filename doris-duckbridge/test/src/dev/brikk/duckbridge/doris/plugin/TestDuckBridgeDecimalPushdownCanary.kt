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
import org.apache.doris.connector.spi.pushdown.ConnectorComparison.Operator
import org.apache.doris.connector.spi.pushdown.ConnectorLiteral
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Decimal literals must reach DuckDB typed as DECIMAL, not DOUBLE. The seeded values differ only
 * past the 16th significant digit, so a literal that DuckDB parses as DOUBLE collapses them and
 * returns the wrong rows.
 */
class TestDuckBridgeDecimalPushdownCanary {

    private val idColumn: List<ConnectorColumnHandle> =
        listOf(DuckBridgeColumnHandle("id", ConnectorType.of("INT"), "INTEGER", true, 0))

    private fun idsMatching(server: TestingQuackServer, op: Operator, literal: String): List<Int> {
        val predicate = ConnectorComparison(
            op,
            ConnectorColumnRef("d", ConnectorType.of("DECIMALV3", 38, 30)),
            ConnectorLiteral.ofDecimal(BigDecimal(literal), 38, 30),
        )
        val sql = DuckBridgeQueryBuilder.buildQuery(
            "memory", "sales", "t", idColumn, predicate, -1, mapOf("d" to "DECIMAL(38,30)"),
        )
        assertThat(sql).describedAs("decimal predicate must push: $sql").contains("WHERE")
        val out = ArrayList<Int>()
        server.openConnection().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("$sql ORDER BY id").use { rs -> while (rs.next()) out.add(rs.getInt(1)) }
            }
        }
        return out
    }

    @Test
    fun decimalLiteralsCompareExactly() {
        TestingQuackServer().use { server ->
            server.exec(
                "CREATE SCHEMA sales",
                "CREATE TABLE sales.t (id INTEGER, d DECIMAL(38,30))",
                "INSERT INTO sales.t VALUES " +
                    "(1, 0.000000100000000000000000000000)," +
                    "(2, 0.000000100000000000000000000001)," +
                    "(3, 0.000000100000000000000000000002)," +
                    "(4, 1000)",
            )
            val tiny = "0.000000100000000000000000000001"
            assertThat(idsMatching(server, Operator.EQ, tiny)).describedAs("=").containsExactly(2)
            assertThat(idsMatching(server, Operator.GT, tiny)).describedAs(">").containsExactly(3, 4)
            assertThat(idsMatching(server, Operator.LT, tiny)).describedAs("<").containsExactly(1)
            assertThat(idsMatching(server, Operator.EQ, "1E+3")).describedAs("negative scale").containsExactly(4)
        }
    }
}
