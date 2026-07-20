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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import java.util.Properties

/**
 * The Trino base-jdbc `query()` pass-through table function over the T2 QUACK engine
 * (`duckbridge.execution-engine=QUACK`): raw DuckDB SQL is handed straight to the source, bypassing
 * Trino's planner, and executed server-side through `quack_query_by_name`.
 *
 * This is the interesting case for duckdb-quack#150: pass-through is the one place a user can send
 * multi-scan SQL (joins, DuckDB-native constructs). Because the QUACK executor ships the whole query
 * server-side (PUSHDOWN mode), the join runs remotely as a single local table-function scan and does
 * NOT hit the ATTACH-mode "multiple streaming scans" wall — so joins that DNF in attach mode complete
 * here. Requires Docker.
 */
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
        // is doubled once more. This canary proves that nested escaping (and unicode) round-trips.
        val names =
            computeActual(passThrough("SELECT name FROM $schema.t WHERE name = 'straße'"))
                .materializedRows
                .map { it.getField(0) as String }
        assertThat(names).containsExactly("straße")
    }

    @Test
    fun duckDbNativeScalarThroughPassThrough() {
        // DuckDB's printf — a construct Trino would not push down — proves the escape hatch delivers
        // native DuckDB SQL through the Arrow data plane (result is a plain VARCHAR).
        val labels =
            computeActual(passThrough("SELECT printf('%d=%s', id, name) AS label FROM $schema.t ORDER BY id"))
                .materializedRows
                .map { it.getField(0) as String }
        assertThat(labels).containsExactly("1=Alice", "2=bob", "3=straße", "4=δοκιμή")
    }

    /**
     * WATCH CANARY for the upstream fix. The `bareListResultTypeFailsLoud` failure is a **quack-jdbc
     * client-driver bug**, not ours: for a LIST/array result column, DuckDB's own JDBC driver reports
     * the element type in the column type name (`INTEGER[]`), but quack-jdbc collapses every list/array
     * to bare `LIST`. It is NOT the protocol/server — the Quack RPC `PrepareResponse` serializes a full
     * DuckDB `LogicalType` (which carries the LIST child type), so the element type is on the wire;
     * quack-jdbc simply doesn't surface it (`getColumnTypeName` = `LIST`; values arrive as a plain
     * `java.util.ArrayList`, not a typed `java.sql.Array`). This test pins both sides; when quack-jdbc
     * starts reporting `...[]`, the quack-jdbc assertion here flips and prompts us to enable array
     * support over the Quack transport. Reproduction for a gizmodata/quack-jdbc bug report.
     */
    @Test
    fun arrayElementTypeDroppedByQuackJdbc_upstreamNotOurs() {
        val listQuery = "SELECT list(x) AS a FROM (VALUES (1),(2)) t(x)"
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(listQuery).use { rs ->
                    // DuckDB-JDBC preserves the element type.
                    assertThat(rs.metaData.getColumnTypeName(1)).isEqualTo("INTEGER[]")
                }
            }
        }
        val props = Properties().apply { setProperty("token", server.token) }
        QuackDriver().connect(server.connectionUrl(), props).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(listQuery).use { rs ->
                    // quack-jdbc drops it to bare LIST — the bug. Flip this when upstream fixes it.
                    assertThat(rs.metaData.getColumnTypeName(1)).isEqualTo("LIST")
                }
            }
        }
    }

    @Test
    fun bareListResultTypeFailsLoud() {
        // KNOWN LIMITATION: a bare DuckDB LIST result column (e.g. from list()) is described over
        // quack-jdbc with no element type, so base-jdbc's column mapping can't resolve it. This must
        // fail loud at analysis (never silently drop/mis-type the column). Resolving it needs
        // array-element-type inference from the describe path — a separate task. See P3-NOTES.
        assertThatThrownBy { computeActual(passThrough("SELECT list(id) AS ids FROM $schema.t")) }
            .hasMessageContaining("Unsupported type")
            .hasMessageContaining("LIST")
    }

    @Test
    fun joinThroughPassThroughCompletesInPushdownMode() {
        // A self-join: multiple streaming scans in ATTACH mode would DNF (duckdb-quack#150), but here
        // the whole join runs server-side via quack_query_by_name, so it completes.
        val ids =
            computeActual(
                passThrough(
                    "SELECT a.id FROM $schema.t a JOIN $schema.t b ON a.id = b.id WHERE a.id IN (2, 4) ORDER BY a.id",
                ),
            ).materializedRows.map { it.getField(0) as Long }
        assertThat(ids).containsExactly(2L, 4L)
    }
}
