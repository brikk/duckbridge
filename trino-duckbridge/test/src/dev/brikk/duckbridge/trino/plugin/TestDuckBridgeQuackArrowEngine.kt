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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * End-to-end test of the T2 QUACK Arrow data plane (`duckbridge.execution-engine=QUACK`) against a
 * REAL remote DuckDB over the Quack RPC protocol ([TestingQuackServer]).
 *
 * The read path: base-jdbc renders the split query → [QuackParameterInliner] inlines its parameters
 * → [QuackDuckBridgeExecutor] ships it server-side via `quack_query_by_name` and returns Arrow. This
 * is the pushdown model (single server-side execution per split), so it does NOT hit the ATTACH-mode
 * "multiple streaming scans" wall (duckdb-quack#150).
 *
 * Requires Docker. This is the experiment behind "let execution-engine=QUACK in and feel the pain":
 * it exercises scan, projection, count(*), and parameter-inlined predicates (bigint / varchar / date)
 * over the wire; predicate types the inliner can't yet render fail loud rather than return wrong rows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeQuackArrowEngine : AbstractTestQueryFramework() {
    private lateinit var server: TestingQuackServer
    private var parityAvailable: Boolean = false

    override fun createQueryRunner(): QueryRunner {
        server = TestingQuackServer()
        parityAvailable = server.installParityExtension()
        val extra =
            buildMap {
                put("duckbridge.execution-engine", "QUACK")
                put("duckbridge.quack.token", server.token)
                if (parityAvailable) {
                    put("duckbridge.parity-extension-path", TestingQuackServer.IN_CONTAINER_PARITY_PATH)
                } else {
                    // No matching binary for the container arch: run without the parity extension so
                    // the Arrow path still exercises scan/projection/predicate inlining over the wire.
                    put("duckbridge.string-pushdown.mode", "GUARDED")
                }
            }
        val runner = DuckBridgeQueryRunner.create(server.connectionUrl(), extra)
        runner.execute("CREATE SCHEMA ${DuckBridgeQueryRunner.CATALOG}.${DuckBridgeQueryRunner.SCHEMA}")
        return runner
    }

    @BeforeAll
    fun createData() {
        computeActual("CREATE TABLE t (id bigint, name varchar, birth date)")
        computeActual(
            "INSERT INTO t VALUES " +
                "(1, 'Alice', DATE '1990-05-01'), (2, 'bob', DATE '1985-12-30'), " +
                "(3, 'straße', DATE '2000-02-29'), (4, 'δοκιμή', DATE '1970-01-01')",
        )
    }

    @AfterAll
    fun tearDown() {
        computeActual("DROP TABLE IF EXISTS t")
        if (::server.isInitialized) {
            server.close()
        }
    }

    @Test
    fun fullScanThroughQuackArrow() {
        val rows = computeActual("SELECT id, name, birth FROM t ORDER BY id").materializedRows
        assertThat(rows.map { it.getField(0) as Long }).containsExactly(1L, 2L, 3L, 4L)
        assertThat(rows.map { it.getField(1) as String }).containsExactly("Alice", "bob", "straße", "δοκιμή")
        assertThat(rows.map { it.getField(2).toString() }).containsExactly("1990-05-01", "1985-12-30", "2000-02-29", "1970-01-01")
    }

    @Test
    fun projectionThroughQuackArrow() {
        val names = computeActual("SELECT name FROM t ORDER BY id").materializedRows.map { it.getField(0) as String }
        assertThat(names).containsExactly("Alice", "bob", "straße", "δοκιμή")
    }

    @Test
    fun countThroughQuackArrow() {
        assertThat(computeActual("SELECT count(*) FROM t").materializedRows.single().getField(0)).isEqualTo(4L)
    }

    @Test
    fun bigintPredicateInlinedOverQuackArrow() {
        val ids =
            computeActual("SELECT id FROM t WHERE id >= 3 ORDER BY id").materializedRows.map { it.getField(0) as Long }
        assertThat(ids).containsExactly(3L, 4L)
    }

    @Test
    fun varcharPredicateInlinedOverQuackArrow() {
        val ids =
            computeActual("SELECT id FROM t WHERE name = 'δοκιμή'").materializedRows.map { it.getField(0) as Long }
        assertThat(ids).containsExactly(4L)
    }

    @Test
    fun datePredicateInlinedOverQuackArrow() {
        val ids =
            computeActual("SELECT id FROM t WHERE birth >= DATE '1990-01-01' ORDER BY id")
                .materializedRows
                .map { it.getField(0) as Long }
        assertThat(ids).containsExactly(1L, 3L)
    }

    @Test
    fun parityPredicatePushdownOverQuackArrow() {
        assumeTrue(parityAvailable, "parity extension not available for the container arch")
        val ids =
            computeActual("SELECT id FROM t WHERE upper(name) = 'STRASSE'").materializedRows.map { it.getField(0) as Long }
        assertThat(ids).containsExactly(3L)
    }
}
