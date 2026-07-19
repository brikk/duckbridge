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
import org.apache.doris.connector.api.pushdown.ConnectorLiteral
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection

/**
 * Proves the SQL [DuckBridgeQueryBuilder] composes is VALID DuckDB by running it over quack-jdbc
 * against a real server and asserting rows. This is the FE-side proof (the BE-side round-trip
 * through the DuckDbTypeHandler is the compose smoke). Requires Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeQueryOverQuack {

    private lateinit var server: TestingQuackServer

    @BeforeAll
    fun setUp() {
        server = TestingQuackServer()
        server.exec(
            "CREATE SCHEMA sales",
            """
            CREATE TABLE sales.customers (
                id BIGINT, name VARCHAR, big_id HUGEINT, tags VARCHAR[]
            )
            """.trimIndent(),
            "INSERT INTO sales.customers VALUES " +
                "(1, 'Alice', 170141183460469231731687303715884105727, ['vip','eu'])," +
                "(2, 'straße', -5, ['de'])," +
                "(3, 'δοκιμή', 0, [])",
        )
    }

    @AfterAll
    fun tearDown() {
        if (::server.isInitialized) server.close()
    }

    private fun cols(vararg names: String): List<ConnectorColumnHandle> =
        names.map { DuckBridgeColumnHandle(it, ConnectorType.of("STRING"), "VARCHAR", true, 0) }

    private fun <T> query(sql: String, extract: (java.sql.ResultSet) -> T): List<T> {
        val out = ArrayList<T>()
        server.openConnection().use { conn: Connection ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    while (rs.next()) out.add(extract(rs))
                }
            }
        }
        return out
    }

    @Test
    fun projectionSqlIsValidDuckDb() {
        val sql = DuckBridgeQueryBuilder.buildQuery(
            "memory", "sales", "customers", cols("id", "name"), null, -1,
        )
        val rows = query("$sql ORDER BY id") { it.getLong(1) to it.getString(2) }
        assertThat(rows).containsExactly(1L to "Alice", 2L to "straße", 3L to "δοκιμή")
    }

    @Test
    fun predicateSqlFiltersExactly() {
        // WHERE ("id" >= 2)
        val filter: ConnectorExpression = ConnectorComparison(
            ConnectorComparison.Operator.GE,
            ConnectorColumnRef("id", ConnectorType.of("BIGINT")),
            ConnectorLiteral.ofLong(2),
        )
        val sql = DuckBridgeQueryBuilder.buildQuery(
            "memory", "sales", "customers", cols("id"), filter, -1,
        )
        assertThat(sql).contains(""""id" >= 2""")
        val ids = query("$sql ORDER BY id") { it.getLong(1) }
        assertThat(ids).containsExactly(2L, 3L)
    }

    @Test
    fun unicodeEqualityPredicatePushesAndMatches() {
        val filter: ConnectorExpression = ConnectorComparison(
            ConnectorComparison.Operator.EQ,
            ConnectorColumnRef("name", ConnectorType.of("STRING")),
            ConnectorLiteral.ofString("straße"),
        )
        val sql = DuckBridgeQueryBuilder.buildQuery(
            "memory", "sales", "customers", cols("id", "name"), filter, -1,
        )
        val rows = query(sql) { it.getLong(1) }
        assertThat(rows).containsExactly(2L)
    }

    @Test
    fun limitSqlIsValidDuckDb() {
        val sql = DuckBridgeQueryBuilder.buildQuery(
            "memory", "sales", "customers", cols("id"), null, 2,
        )
        assertThat(sql).endsWith(" LIMIT 2")
        assertThat(query(sql) { it.getLong(1) }).hasSize(2)
    }

    @Test
    fun largeintAndArrayColumnsAreValidDuckDb() {
        // HUGEINT + LIST projected — proves the SELECT is valid; the BE-side decode is the smoke.
        val sql = DuckBridgeQueryBuilder.buildQuery(
            "memory", "sales", "customers", cols("id", "big_id", "tags"), null, -1,
        )
        val bigIds = query("$sql ORDER BY id") { it.getString(2) }
        assertThat(bigIds.first()).isEqualTo("170141183460469231731687303715884105727")
    }
}
