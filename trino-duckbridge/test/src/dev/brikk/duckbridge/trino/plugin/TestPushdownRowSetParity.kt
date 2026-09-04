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

import io.trino.plugin.jdbc.JdbcPlugin
import io.trino.spi.type.TimeZoneKey
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import io.trino.testing.TestingSession.testSessionBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance

/**
 * End-to-end pushed-vs-local row-set parity (EV-B2).
 *
 * Two catalogs point at the SAME DuckDB file. [PUSHED] is the production connector; [LOCAL] uses
 * the same client/data path but an empty expression rewriter, so every function predicate remains a
 * Trino filter. For each predicate we prove the production plan removed that filter, the
 * baseline retained it, then compare sorted row IDs. This covers the complete WHERE path — planner
 * conversion, per-conjunct split, SQL emission, connection init, remote evaluation, JDBC decoding
 * and SQL three-valued logic over rows containing NULL — which scalar fixtures cannot prove.
 *
 * The corpus is deliberately risk-focused: every emission class and every EV-A rewrite/gate that is
 * reachable through table columns has an end-to-end representative. [TestPushdownSemanticFixtures]
 * remains the exhaustive per-entry scalar semantic proof.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestPushdownRowSetParity : AbstractTestQueryFramework() {
    private data class Case(val label: String, val predicate: String)

    override fun createQueryRunner(): QueryRunner {
        val connectionUrl = DuckBridgeQueryRunner.freshDatabaseUrl()
        val session =
            testSessionBuilder()
                .setCatalog(PUSHED)
                .setSchema(SCHEMA)
                // Fixed offset: to_unixtime(TIMESTAMP) is safely pushable (EV-A4/A13).
                .setTimeZoneKey(TimeZoneKey.UTC_KEY)
                .build()
        val runner = io.trino.testing.DistributedQueryRunner.builder(session).build()
        try {
            runner.installPlugin(DuckBridgePlugin())
            runner.installPlugin(JdbcPlugin(LOCAL_CONNECTOR) { DuckBridgeClientModule(expressionPushdownEnabled = false) })
            val properties =
                buildMap {
                    put("connection-url", connectionUrl)
                    if (DuckBridgeQueryRunner.bundledParityIsUnsigned()) {
                        put("duckbridge.allow-unsigned-extensions", "true")
                    }
                }
            runner.createCatalog(PUSHED, "duckbridge", properties)
            runner.createCatalog(LOCAL, LOCAL_CONNECTOR, properties)
            runner.execute("CREATE SCHEMA $PUSHED.$SCHEMA")
            return runner
        } catch (e: Throwable) {
            runner.close()
            throw e
        }
    }

    @BeforeAll
    fun setUpData() {
        computeActual(
            """
            CREATE TABLE rows (
                id bigint,
                s varchar,
                s2 varchar,
                n bigint,
                x double,
                d date,
                d2 date,
                ts timestamp(3)
            )
            """.trimIndent(),
        )
        computeActual(
            """
            INSERT INTO rows VALUES
                (1,  'Alice',   'A',  7,  9.0, DATE '2020-01-31', DATE '2020-02-01', TIMESTAMP '2024-01-01 00:00:05.123'),
                (2,  'straße',  'B', -7, -4.0, DATE '2020-01-15', DATE '2020-03-15', TIMESTAMP '1970-01-01 00:00:01.000'),
                (3,  'abc123',  NULL, 6,  2.0, DATE '2024-01-07', DATE '2024-01-08', TIMESTAMP '2024-06-01 12:30:00.250'),
                (4,  'İ',       'i',  0,  0.0, DATE '2021-01-01', DATE '2021-01-01', TIMESTAMP '2024-01-01 23:59:59.999'),
                (5,  '',        '',   3, -2.0, DATE '2024-02-29', DATE '2025-02-28', TIMESTAMP '2024-01-01 00:00:00.000'),
                (6,  NULL,      'N', NULL, NULL, NULL, NULL, NULL),
                (7,  'δοκιμή',  'D', 10,  1.0, DATE '2000-02-29', DATE '2001-02-28', TIMESTAMP '2000-02-29 01:02:03.456'),
                (8,  'abc' || chr(10), 'L', 8, 4.0, DATE '2020-12-31', DATE '2021-01-01', TIMESTAMP '2024-11-03 01:30:00.000')
            """.trimIndent(),
        )
    }

    @AfterAll
    fun tearDownData() {
        computeActual("DROP TABLE IF EXISTS rows")
    }

    @TestFactory
    fun pushedAndLocalFiltersReturnIdenticalRows(): List<DynamicTest> =
        cases().map { case ->
            DynamicTest.dynamicTest(case.label) {
                val pushedSql = "SELECT id FROM $PUSHED.$SCHEMA.rows WHERE ${case.predicate}"
                val localSql = "SELECT id FROM $LOCAL.$SCHEMA.rows WHERE ${case.predicate}"

                // Do not use QueryAssert.isFullyPushedDown here: it also evaluates the query against
                // Trino's reference runner, whose table/functions are unrelated to these two catalogs.
                val pushedPlan = explain(pushedSql)
                val localPlan = explain(localSql)
                assertThat(pushedPlan)
                    .`as`("production predicate must actually push: %s", case.predicate)
                    .doesNotContain("filterPredicate")
                assertThat(localPlan)
                    .`as`("baseline predicate must remain in Trino: %s", case.predicate)
                    .contains("filterPredicate")

                assertThat(ids(pushedSql))
                    .`as`("pushed/local row-set mismatch for: %s", case.predicate)
                    .containsExactlyElementsOf(ids(localSql))
            }
        }

    private fun ids(sql: String): List<Long> =
        computeActual(sql).materializedRows.map { it.getField(0) as Long }.sorted()

    private fun explain(sql: String): String =
        computeActual("EXPLAIN (TYPE DISTRIBUTED) $sql")
            .materializedRows
            .joinToString("\n") { it.getField(0).toString() }

    @Suppress("LongMethod")
    private fun cases(): List<Case> =
        listOf(
            Case("BARE string + unicode + NULL", "length(s) = 6"),
            Case("ALIAS upper simple mapping", "upper(s) = U&'STRA\\00DFE'"),
            Case("ALIAS lower dotted I", "lower(s) = 'i'"),
            Case("substring gated safe shape", "substring(s, 2, 2) = 'tr'"),
            Case("replace gated safe shape", "replace(s, 'a', 'x') = 'xbc123'"),
            Case("lpad gated safe shape", "lpad(s, 3, '-') = '--İ'"),
            Case("concat_ws all-VARCHAR gate", "concat_ws('-', s, s2) = 'Alice-A'"),
            Case("strpos code-point semantics", "strpos(s, '123') > 0"),
            Case("mod non-zero divisor gate", "mod(n, 3) = 1"),
            Case("OPERATOR bitwise_and", "bitwise_and(n, 1) = 1"),
            Case("INLINE regexp_extract no-match NULL", "regexp_extract(s, '[0-9]+') IS NULL"),
            Case("RENAME regexp_like RE2-safe", "regexp_like(s, '^[a-z]+')"),
            Case("INLINE regexp_replace global", "regexp_replace(s, '[0-9]', '_') = 'abc___'"),
            Case("date_diff complete month units", "date_diff('month', d, d2) = 0"),
            Case("millisecond is millis-of-second", "millisecond(ts) = 123"),
            Case(
                "to_unixtime over explicit fixed zone",
                "to_unixtime(with_timezone(ts, 'UTC')) < 100000000",
            ),
            Case("three-valued nested OR", "length(s) = 5 OR mod(n, 3) = 0"),
            Case("partial-risk AND, both pushable", "upper(s) = 'ALICE' AND year(d) = 2020"),
        )

    private companion object {
        const val PUSHED: String = "pushed"
        const val LOCAL: String = "local"
        const val LOCAL_CONNECTOR: String = "duckbridgenopushdown"
        const val SCHEMA: String = "parity"
    }
}
