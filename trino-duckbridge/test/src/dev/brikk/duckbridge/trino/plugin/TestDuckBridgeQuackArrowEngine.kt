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

import io.airlift.log.Logger
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeQuackArrowEngine : AbstractTestQueryFramework() {
    private lateinit var server: TestingQuackServer
    private var parityAvailable: Boolean = false

    override fun createQueryRunner(): QueryRunner {
        server = TestingQuackServer()
        val parityInstallation = server.installParityExtension()
        parityAvailable = parityInstallation != null
        val extra =
            buildMap {
                put("duckbridge.execution-engine", "QUACK")
                put("duckbridge.quack.token", server.token)
                if (parityInstallation?.serverPath != null) {
                    put("duckbridge.parity-extension-path", parityInstallation.serverPath)
                } else {
                    if (!parityAvailable) {
                        // No matching binary: retain Arrow transport coverage without PARITY assertions.
                        put("duckbridge.string-pushdown.mode", "GUARDED")
                    }
                }
            }
        val runner = DuckBridgeQueryRunner.create(server.connectionUrl(), extra)
        runner.execute("CREATE SCHEMA ${DuckBridgeQueryRunner.CATALOG}.${DuckBridgeQueryRunner.SCHEMA}")
        return runner
    }

    @BeforeAll
    fun createData() {
        computeActual("CREATE TABLE t (id bigint, name varchar, birth date, price decimal(10,2), score real, ts timestamp)")
        computeActual(
            "INSERT INTO t VALUES " +
                "(1, 'Alice', DATE '1990-05-01', DECIMAL '10.50', REAL '1.5', TIMESTAMP '1990-05-01 12:30:00'), " +
                "(2, 'bob', DATE '1985-12-30', DECIMAL '20.00', REAL '2.5', TIMESTAMP '1985-12-30 00:00:00'), " +
                "(3, 'straße', DATE '2000-02-29', DECIMAL '30.25', REAL '3.5', TIMESTAMP '2000-02-29 01:02:03'), " +
                "(4, 'δοκιμή', DATE '1970-01-01', DECIMAL '40.00', REAL '4.5', TIMESTAMP '1970-01-01 00:00:00')",
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
    fun decimalPredicateInlinedOverQuackArrow() {
        val ids =
            computeActual("SELECT id FROM t WHERE price >= DECIMAL '30.00' ORDER BY id")
                .materializedRows
                .map { it.getField(0) as Long }
        assertThat(ids).containsExactly(3L, 4L)
    }

    @Test
    fun realPredicateInlinedOverQuackArrow() {
        val ids =
            computeActual("SELECT id FROM t WHERE score >= REAL '3.0' ORDER BY id")
                .materializedRows
                .map { it.getField(0) as Long }
        assertThat(ids).containsExactly(3L, 4L)
    }

    @Test
    fun floatingRangeBoundsExcludeNanWithoutLosingInfinityOverQuackArrow() {
        computeActual("CREATE TABLE floating_edges(id BIGINT,r REAL,d DOUBLE)")
        try {
            computeActual("INSERT INTO floating_edges VALUES " +
                "(1,CAST('NaN' AS REAL),nan()),(2,CAST('Infinity' AS REAL),infinity())," +
                "(3,CAST('-Infinity' AS REAL),-infinity()),(4,REAL '-0.0',DOUBLE '-0.0')," +
                "(5,REAL '0.0',DOUBLE '0.0'),(6,REAL '1.0',DOUBLE '1.0'),(7,NULL,NULL)")
            for (column in listOf("r", "d")) {
                val ids = computeActual("SELECT id FROM floating_edges WHERE $column>0 ORDER BY id")
                    .materializedRows.map { it.getField(0) as Long }
                assertThat(ids).containsExactly(2L, 6L)
                val notPositive = computeActual("SELECT id FROM floating_edges WHERE NOT ($column>0) ORDER BY id")
                    .materializedRows.map { it.getField(0) as Long }
                assertThat(notPositive).containsExactly(1L, 3L, 4L, 5L)
            }
        } finally {
            computeActual("DROP TABLE floating_edges")
        }
    }

    @Test
    fun timestampPredicateInlinedOverQuackArrow() {
        val ids =
            computeActual("SELECT id FROM t WHERE ts >= TIMESTAMP '1995-01-01 00:00:00' ORDER BY id")
                .materializedRows
                .map { it.getField(0) as Long }
        assertThat(ids).containsExactly(3L)
    }

    @Test
    fun parityPredicatePushdownOverQuackArrow() {
        assumeTrue(parityAvailable, "parity extension not available for the container arch")
        // Trino: upper('straße') = 'STRAßE' (simple mapping); only trino_upper matches, the built-in gives 'STRAẞE'.
        val ids =
            computeActual("SELECT id FROM t WHERE upper(name) = 'STRAßE'").materializedRows.map { it.getField(0) as Long }
        assertThat(ids).containsExactly(3L)
    }

    @Test
    fun perQueryChurnDoesNotAccumulateServerConnections() {
        repeat(20) {
            assertThat(computeActual("SELECT count(*) FROM t").materializedRows.single().getField(0)).isEqualTo(4L)
        }
        val active = server.activeConnectionCount()
        log.info("quack_active_connections after 20 sequential QUACK Arrow scans: %d (server ceiling 128)", active)
        // Generous bound for keep-alive lingering; the observer connection itself also counts as one.
        assertThat(active).isLessThan(32)
    }

    private companion object {
        private val log: Logger = Logger.get(TestDuckBridgeQuackArrowEngine::class.java)
    }
}
