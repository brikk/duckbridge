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

import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import java.sql.DriverManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeArrowEngine : AbstractTestQueryFramework() {
    private lateinit var connectionUrl: String

    override fun createQueryRunner(): QueryRunner {
        connectionUrl = DuckBridgeQueryRunner.freshDatabaseUrl()
        val runner =
            DuckBridgeQueryRunner.create(
                connectionUrl,
                mapOf("duckbridge.execution-engine" to "DUCKDB_LOCAL"),
            )
        runner.execute("CREATE SCHEMA ${DuckBridgeQueryRunner.CATALOG}.${DuckBridgeQueryRunner.SCHEMA}")
        return runner
    }

    @BeforeAll
    fun createData() {
        computeActual("CREATE TABLE nums (id bigint, name varchar, d double)")
        computeActual(
            "INSERT INTO nums VALUES " +
                "(1, 'alpha', 1.5), (2, 'béta', 2.5), (3, 'γάμμα', 3.5), (4, 'straße', 4.5)",
        )
    }

    @Test
    fun fullScanThroughArrowEngine() {
        val rows = computeActual("SELECT id, name, d FROM nums ORDER BY id").materializedRows
        assertThat(rows.map { it.getField(0) as Long }).containsExactly(1L, 2L, 3L, 4L)
        assertThat(rows.map { it.getField(1) as String }).containsExactly("alpha", "béta", "γάμμα", "straße")
        assertThat(rows.map { it.getField(2) as Double }).containsExactly(1.5, 2.5, 3.5, 4.5)
    }

    @Test
    fun projectionThroughArrowEngine() {
        val names = computeActual("SELECT name FROM nums ORDER BY id").materializedRows.map { it.getField(0) as String }
        assertThat(names).containsExactly("alpha", "béta", "γάμμα", "straße")
    }

    @Test
    fun domainPredicatePushdownThroughArrowEngine() {
        val ids =
            computeActual("SELECT id FROM nums WHERE id >= 3 ORDER BY id").materializedRows.map { it.getField(0) as Long }
        assertThat(ids).containsExactly(3L, 4L)
    }

    @Test
    fun parityPredicatePushdownThroughArrowEngine() {
        // Trino's upper('straße') is 'STRAßE'; DuckDB's built-in gives 'STRAẞE' and would miss row 4.
        // Matching it distinguishes the extension path from the built-in.
        val ids =
            computeActual("SELECT id FROM nums WHERE upper(name) = 'STRAßE' ORDER BY id")
                .materializedRows
                .map { it.getField(0) as Long }
        assertThat(ids).containsExactly(4L)
    }

    @Test
    fun variableWidthValuesThroughArrowEngine() {
        val long = "x".repeat(5000)
        val schema = DuckBridgeQueryRunner.SCHEMA
        DriverManager.getConnection(connectionUrl, DuckBridgeQueryRunner.duckJdbcProperties()).use { duck ->
            duck.createStatement().use { statement ->
                statement.execute("CREATE TABLE $schema.varlen (id BIGINT, s VARCHAR, b BLOB, tags VARCHAR[])")
                statement.execute(
                    "INSERT INTO $schema.varlen VALUES " +
                        "(1, '', ''::BLOB, ['', 'a']), " +
                        "(2, NULL, NULL, NULL), " +
                        "(3, '$long', '\\x00\\xFF\\x10'::BLOB, ['$long', NULL, 'ü']), " +
                        "(4, 'short', '\\x7F'::BLOB, [])",
                )
            }
        }
        try {
            val rows = computeActual("SELECT s, b, tags FROM varlen ORDER BY id").materializedRows
            assertThat(rows.map { it.getField(0) }).containsExactly("", null, long, "short")
            assertThat(rows.map { (it.getField(1) as ByteArray?)?.toList() })
                .containsExactly(listOf(), null, listOf<Byte>(0, -1, 16), listOf<Byte>(127))
            assertThat(rows.map { it.getField(2) })
                .containsExactly(listOf("", "a"), null, listOf(long, null, "ü"), emptyList<String>())
        } finally {
            computeActual("DROP TABLE varlen")
        }
    }

    @Test
    fun countThroughArrowEngine() {
        assertThat(computeActual("SELECT count(*) FROM nums").materializedRows.single().getField(0)).isEqualTo(4L)
    }
}
