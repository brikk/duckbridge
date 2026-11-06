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

import com.gizmodata.quack.jdbc.sql.QuackDriver
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import java.util.Properties

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeQuackPassThroughQuery : AbstractTestQueryFramework() {
    private lateinit var server: TestingQuackServer
    private val schema = DuckBridgeQueryRunner.SCHEMA

    override fun createQueryRunner(): QueryRunner {
        server = TestingQuackServer()
        // Pass-through SQL is user-authored and not planned by Trino, so parity/extension state is
        // irrelevant here; run without it to keep the fixture minimal.
        val extra =
            mapOf(
                "duckbridge.execution-engine" to "QUACK",
                "duckbridge.quack.token" to server.token,
                "duckbridge.string-pushdown.mode" to "GUARDED",
            )
        val runner = DuckBridgeQueryRunner.create(server.connectionUrl(), extra)
        runner.execute("CREATE SCHEMA ${DuckBridgeQueryRunner.CATALOG}.$schema")
        return runner
    }

    @BeforeAll
    fun createData() {
        computeActual("CREATE TABLE t (id bigint, name varchar)")
        computeActual("INSERT INTO t VALUES (1, 'Alice'), (2, 'bob'), (3, 'straße'), (4, 'δοκιμή')")
    }

    @AfterAll
    fun tearDown() {
        computeActual("DROP TABLE IF EXISTS t")
        if (::server.isInitialized) {
            server.close()
        }
    }

    private fun passThrough(sql: String): String =
        "SELECT * FROM TABLE(${DuckBridgeQueryRunner.CATALOG}.system.query(query => '${sql.replace("'", "''")}'))"

    @Test
    fun simpleSelectThroughPassThrough() {
        val ids =
            computeActual(passThrough("SELECT id, name FROM $schema.t WHERE id >= 3 ORDER BY id"))
                .materializedRows
                .map { it.getField(0) as Long }
        assertThat(ids).containsExactly(3L, 4L)
    }

    @Test
    fun quoteAndUnicodeSurviveNestedWrapping() {
        // The user SQL becomes a single-quoted argument to quack_query_by_name, so its embedded quote
        // is doubled once more.
        val names =
            computeActual(passThrough("SELECT name FROM $schema.t WHERE name = 'straße'"))
                .materializedRows
                .map { it.getField(0) as String }
        assertThat(names).containsExactly("straße")
    }

    @Test
    fun duckDbNativeScalarThroughPassThrough() {
        val labels =
            computeActual(passThrough("SELECT printf('%d=%s', id, name) AS label FROM $schema.t ORDER BY id"))
                .materializedRows
                .map { it.getField(0) as String }
        assertThat(labels).containsExactly("1=Alice", "2=bob", "3=straße", "4=δοκιμή")
    }

    @Test
    fun arrayElementTypePreservedByQuackJdbc() {
        val listQuery = "SELECT list(x) AS a FROM (VALUES (1),(2)) t(x)"
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(listQuery).use { rs ->
                    assertThat(rs.metaData.getColumnTypeName(1)).isEqualTo("INTEGER[]")
                }
            }
        }
        val props = Properties().apply { setProperty("token", server.token) }
        QuackDriver().connect(server.connectionUrl(), props).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(listQuery).use { rs ->
                    assertThat(rs.metaData.getColumnTypeName(1)).isEqualTo("INTEGER[]")
                }
            }
        }
    }

    @Test
    fun listAggregateThroughPassThroughReturnsArray() {
        @Suppress("UNCHECKED_CAST")
        val ids =
            computeActual(passThrough("SELECT list(id ORDER BY id) AS ids FROM $schema.t"))
                .materializedRows
                .single()
                .getField(0) as List<Long>
        assertThat(ids).containsExactly(1L, 2L, 3L, 4L)
    }

    @Test
    fun joinThroughPassThroughCompletesInPushdownMode() {
        val ids =
            computeActual(
                passThrough(
                    "SELECT a.id FROM $schema.t a JOIN $schema.t b ON a.id = b.id WHERE a.id IN (2, 4) ORDER BY a.id",
                ),
            ).materializedRows.map { it.getField(0) as Long }
        assertThat(ids).containsExactly(2L, 4L)
    }
}
