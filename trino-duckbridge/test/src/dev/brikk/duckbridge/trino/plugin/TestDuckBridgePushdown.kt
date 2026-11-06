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
import io.trino.sql.planner.plan.LimitNode
import io.trino.sql.planner.plan.TopNNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgePushdown : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        val runner = DuckBridgeQueryRunner.create(DuckBridgeQueryRunner.freshDatabaseUrl())
        runner.execute("CREATE SCHEMA ${DuckBridgeQueryRunner.CATALOG}.${DuckBridgeQueryRunner.SCHEMA}")
        return runner
    }

    @BeforeAll
    fun setUpData() {
        computeActual(
            """
            CREATE TABLE people (id bigint, name varchar, birth date, weight double)
            """.trimIndent(),
        )
        computeActual(
            """
            INSERT INTO people VALUES
                (1, 'Alice',   DATE '1990-05-01', 60.5),
                (2, 'bob',     DATE '1985-12-30', 80.0),
                (3, 'CAROL',   DATE '2000-01-15', 55.25),
                (4, 'straße',  DATE '1970-01-01', 72.0),
                (5, 'δοκιμή',  DATE '2024-02-29', 90.9)
            """.trimIndent(),
        )
    }

    @AfterAll
    fun tearDownData() {
        computeActual("DROP TABLE IF EXISTS people")
    }


    @Test
    fun lengthPredicateIsFullyPushedDown() {
        assertThat(query("SELECT id FROM people WHERE length(name) = 5"))
            .isFullyPushedDown()
    }

    @Test
    fun upperPredicateIsFullyPushedDown() {
        assertThat(query("SELECT id FROM people WHERE upper(name) = 'BOB'"))
            .isFullyPushedDown()
    }

    @Test
    fun yearOnDatePredicateIsFullyPushedDown() {
        assertThat(query("SELECT id FROM people WHERE year(birth) = 2000"))
            .isFullyPushedDown()
    }

    @Test
    fun likePredicateIsFullyPushedDown() {
        assertThat(query("SELECT id FROM people WHERE name LIKE 'str%'"))
            .isFullyPushedDown()
            .matches("VALUES BIGINT '4'")
        assertThat(query("SELECT id FROM people WHERE name LIKE '%o_'"))
            .isFullyPushedDown()
            .matches("VALUES BIGINT '2'")
    }

    @Test
    fun likeWithEscapeIsFullyPushedDown() {
        assertThat(query("SELECT id FROM people WHERE name LIKE 'b!_%' ESCAPE '!'"))
            .isFullyPushedDown()
            .returnsEmptyResult()
        assertThat(query("SELECT id FROM people WHERE name LIKE '%δ%' ESCAPE '!'"))
            .isFullyPushedDown()
            .matches("VALUES BIGINT '5'")
    }

    @Test
    fun notLikePredicateIsFullyPushedDown() {
        assertThat(query("SELECT id FROM people WHERE name NOT LIKE '%o%'"))
            .isFullyPushedDown()
            .matches("VALUES BIGINT '1', BIGINT '3', BIGINT '4', BIGINT '5'")
    }

    @Test
    fun partialPushdownLeavesUnsupportedConjunctAboveScan() {
        // Integer division differs between Trino and DuckDB, so id / 2 must leave a FilterNode above the scan.
        assertThat(query("SELECT id FROM people WHERE length(name) = 5 AND id / 2 = 1"))
            .isNotFullyPushedDown(FilterNode::class.java)
    }

    @Test
    fun unsupportedFunctionPredicateStaysAboveScan() {
        assertThat(query("SELECT id FROM people WHERE to_iso8601(birth) = '2000-01-15'"))
            .isNotFullyPushedDown(FilterNode::class.java)
    }


    @Test
    fun unsettableSessionZoneFailsLoudWhenTzPushdownEnabled() {
        // '+05:30' is a fractional bare offset: Trino accepts it as a session zone, DuckDB's
        // SET TimeZone rejects it. With pushdown_timestamp_with_timezone enabled (the default),
        // tz-sensitive functions may already be pushed, so the connection must fail loud rather
        // than evaluate them in a mismatched zone.
        val session =
            io.trino.testing.TestingSession.testSessionBuilder()
                .setCatalog(DuckBridgeQueryRunner.CATALOG)
                .setSchema(DuckBridgeQueryRunner.SCHEMA)
                .setTimeZoneKey(io.trino.spi.type.TimeZoneKey.getTimeZoneKey("+05:30"))
                .build()
        assertQueryFails(
            session,
            "SELECT id FROM people WHERE id = 1",
            ".*DuckDB rejected SET TimeZone = '\\+05:30'.*pushdown_timestamp_with_timezone.*",
        )
    }

    @Test
    fun unsettableSessionZoneIsToleratedWhenTzPushdownDisabled() {
        val session =
            io.trino.testing.TestingSession.testSessionBuilder()
                .setCatalog(DuckBridgeQueryRunner.CATALOG)
                .setSchema(DuckBridgeQueryRunner.SCHEMA)
                .setTimeZoneKey(io.trino.spi.type.TimeZoneKey.getTimeZoneKey("+05:30"))
                .setCatalogSessionProperty(DuckBridgeQueryRunner.CATALOG, "pushdown_timestamp_with_timezone", "false")
                .build()
        assertQuery(session, "SELECT id FROM people WHERE id = 1", "VALUES 1")
    }


    @Test
    fun limitIsPushedIntoTableScan() {
        val plan = explain("SELECT id FROM people LIMIT 2")
        assertThat(plan)
            .`as`("DuckDB LIMIT should be pushed onto the TableScan handle")
            .contains("limit=2")
        assertThat(plan)
            .`as`("guaranteed LIMIT pushdown should remove Trino's own Limit node")
            .doesNotContain("Limit[")
    }

    @Test
    fun topNIsPushedIntoTableScan() {
        val plan = explain("SELECT id FROM people ORDER BY weight DESC LIMIT 3")
        assertThat(plan)
            .`as`("ORDER BY ... LIMIT should push sortOrder + limit onto the TableScan handle")
            .contains("limit=3")
        assertThat(plan).contains("sortOrder=[weight")
    }

    private fun explain(sql: String): String =
        computeActual("EXPLAIN (TYPE DISTRIBUTED) $sql")
            .materializedRows
            .joinToString("\n") { it.getField(0).toString() }


    @Test
    fun pushedPredicateReturnsCorrectRows() {
        val ids =
            computeActual("SELECT id FROM people WHERE length(name) = 5 ORDER BY id")
                .materializedRows
                .map { it.getField(0) as Long }
        assertThat(ids).containsExactly(1L, 3L)
    }

    @Test
    fun pushedTopNReturnsCorrectOrder() {
        val names =
            computeActual("SELECT name FROM people ORDER BY weight DESC LIMIT 2")
                .materializedRows
                .map { it.getField(0) as String }
        assertThat(names).containsExactly("δοκιμή", "bob")
    }

    @Suppress("unused")
    private val planNodeClasses = listOf(LimitNode::class.java, TopNNode::class.java)
}
