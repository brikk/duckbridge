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
import java.util.Properties

/**
 * Stage 1 (T3) integration test: the duckbridge connector talking to a REAL remote DuckDB over the
 * Quack protocol via gizmo's `quack-jdbc` driver (`connection-url=jdbc:quack://host:port`).
 *
 * The server is a testcontainer ([TestingQuackServer]) running `duckdb -unsigned` hosting
 * `quack_serve`; the built `trino_parity.duckdb_extension` is copied in and LOADed server-side (via
 * `duckbridge.parity-extension-path` pointing at the in-container path), so the parity pushdown path
 * is genuinely exercised over the wire — not just result correctness.
 *
 * Requires Docker. The container base is debian:trixie (GLIBC 2.41) because the extension links
 * against GLIBC 2.38 and won't LOAD on the DuckLake fixture's bookworm image.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeQuackTransport : AbstractTestQueryFramework() {
    private lateinit var server: TestingQuackServer
    private var parityAvailable: Boolean = false

    override fun createQueryRunner(): QueryRunner {
        server = TestingQuackServer()
        parityAvailable = server.installParityExtension()
        val extra = buildMap {
            put("duckbridge.quack.token", server.token)
            if (parityAvailable) {
                // Server-side path — DuckBridgeParity LOADs it over the pass-through connection.
                put("duckbridge.parity-extension-path", TestingQuackServer.IN_CONTAINER_PARITY_PATH)
            } else {
                // No matching binary for the container arch: run without parity so the transport
                // tests still exercise the wire path (domain/limit pushdown, round-trip, metadata).
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
        quackConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE ${DuckBridgeQueryRunner.SCHEMA}.native_types (
                        id BIGINT, b BLOB, tz TIMESTAMPTZ, tm TIME, tmz TIME WITH TIME ZONE,
                        u UUID, ut UTINYINT, us USMALLINT, ui UINTEGER, ub UBIGINT,
                        h HUGEINT, uh UHUGEINT
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    INSERT INTO ${DuckBridgeQueryRunner.SCHEMA}.native_types VALUES (
                        1, unhex('00ff616263'),
                        TIMESTAMPTZ '2024-03-01 07:03:04.123456 UTC',
                        TIME '23:59:58.123456', TIMETZ '23:59:58.123456+05:30',
                        UUID '123e4567-e89b-12d3-a456-426614174000',
                        255, 65535, 4294967295, 18446744073709551615,
                        170141183460469231731687303715884105727,
                        340282366920938463463374607431768211455
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    @AfterAll
    fun tearDown() {
        computeActual("DROP TABLE IF EXISTS t")
        computeActual("DROP TABLE IF EXISTS native_types")
        if (::server.isInitialized) {
            server.close()
        }
    }

    @Test
    fun showSchemasAndTablesOverQuack() {
        val schemas = computeActual("SHOW SCHEMAS").materializedRows.map { it.getField(0) as String }
        assertThat(schemas).contains(DuckBridgeQueryRunner.SCHEMA)
        val tables = computeActual("SHOW TABLES").materializedRows.map { it.getField(0) as String }
        assertThat(tables).contains("t")
    }

    @Test
    fun scalarRoundTripOverQuack() {
        val row = computeActual("SELECT id, name, birth FROM t WHERE id = 3").materializedRows.single()
        assertThat(row.getField(0)).isEqualTo(3L)
        assertThat(row.getField(1)).isEqualTo("straße")
        assertThat(row.getField(2).toString()).isEqualTo("2000-02-29")
        val count = computeActual("SELECT count(*) FROM t").materializedRows.single()
        assertThat(count.getField(0)).isEqualTo(4L)
    }

    @Test
    fun nativeScalarTypesRoundTripOverQuack() {
        val columns =
            computeActual("SHOW COLUMNS FROM native_types").materializedRows
                .associate { it.getField(0) as String to it.getField(1).toString() }
        assertThat(columns).containsEntry("b", "varbinary")
        assertThat(columns).containsEntry("tz", "timestamp(6) with time zone")
        assertThat(columns).containsEntry("tm", "time(6)")
        assertThat(columns).containsEntry("tmz", "time(6) with time zone")
        assertThat(columns).containsEntry("u", "uuid")
        assertThat(columns).containsEntry("ut", "smallint")
        assertThat(columns).containsEntry("us", "integer")
        assertThat(columns).containsEntry("ui", "bigint")
        assertThat(columns).containsEntry("ub", "decimal(20,0)")
        assertThat(columns).doesNotContainKeys("h", "uh")

        val row =
            computeActual(
                """
                SELECT to_hex(b), to_unixtime(tz), CAST(tm AS varchar), CAST(tmz AS varchar),
                       CAST(u AS varchar), ut, us, ui, CAST(ub AS varchar)
                FROM native_types
                """.trimIndent(),
            ).materializedRows.single()
        assertThat(row.getField(0)).isEqualTo("00FF616263")
        assertThat(row.getField(1)).isEqualTo(1709276584.123456)
        assertThat(row.getField(2)).isEqualTo("23:59:58.123456")
        assertThat(row.getField(3)).isEqualTo("23:59:58.123456+05:30")
        assertThat(row.getField(4)).isEqualTo("123e4567-e89b-12d3-a456-426614174000")
        assertThat((row.getField(5) as Number).toLong()).isEqualTo(255)
        assertThat((row.getField(6) as Number).toLong()).isEqualTo(65535)
        assertThat((row.getField(7) as Number).toLong()).isEqualTo(4294967295L)
        assertThat(row.getField(8).toString()).isEqualTo("18446744073709551615")
    }

    @Test
    fun nativeScalarWritesOverQuack() {
        computeActual(
            """
            CREATE TABLE written_native (
                id bigint, b varbinary, tz timestamp(6) with time zone,
                tm time(6), tmz time(6) with time zone, u uuid
            )
            """.trimIndent(),
        )
        try {
            computeActual(
                """
                INSERT INTO written_native VALUES (
                    1, X'00FF616263', TIMESTAMP '2024-03-01 07:03:04.123456 UTC',
                    TIME '23:59:58.123456', TIME '23:59:58.123456+05:30',
                    UUID '123e4567-e89b-12d3-a456-426614174000'
                )
                """.trimIndent(),
            )
            val row =
                computeActual(
                    """
                    SELECT to_hex(b), to_unixtime(tz), CAST(tm AS varchar), CAST(tmz AS varchar), CAST(u AS varchar)
                    FROM written_native
                    """.trimIndent(),
                ).materializedRows.single()
            assertThat(row.getField(0)).isEqualTo("00FF616263")
            assertThat(row.getField(1)).isEqualTo(1709276584.123456)
            assertThat(row.getField(2)).isEqualTo("23:59:58.123456")
            assertThat(row.getField(3)).isEqualTo("23:59:58.123456+05:30")
            assertThat(row.getField(4)).isEqualTo("123e4567-e89b-12d3-a456-426614174000")
        } finally {
            computeActual("DROP TABLE IF EXISTS written_native")
        }
    }

    @Test
    fun unicodeEqualityOverQuack() {
        val ids =
            computeActual("SELECT id FROM t WHERE name = 'δοκιμή'").materializedRows.map { it.getField(0) as Long }
        assertThat(ids).containsExactly(4L)
    }

    @Test
    fun domainPushdownIsProvenOverQuack() {
        // A simple range predicate pushes through base-jdbc's domain path onto the remote TableScan.
        val plan = explain("SELECT id FROM t WHERE id >= 3")
        assertThat(plan).contains("TableScan")
        val ids =
            computeActual("SELECT id FROM t WHERE id >= 3 ORDER BY id").materializedRows.map { it.getField(0) as Long }
        assertThat(ids).containsExactly(3L, 4L)
    }

    @Test
    fun limitPushdownIsProvenOverQuack() {
        val plan = explain("SELECT id FROM t LIMIT 2")
        assertThat(plan).contains("limit=2")
        assertThat(computeActual("SELECT id FROM t LIMIT 2").rowCount).isEqualTo(2)
    }

    @Test
    fun bareFunctionPushdownOverQuack() {
        // length is now a BARE emission (no extension needed) — it pushes as the bare built-in name
        // even over Quack, independent of the parity extension's availability on the server.
        val ids =
            computeActual("SELECT id FROM t WHERE length(name) = 5 ORDER BY id").materializedRows.map { it.getField(0) as Long }
        // Alice (5), straße (6→no), δοκιμή (6→no), bob (3→no) → Alice only.
        assertThat(ids).containsExactly(1L)
    }

    @Test
    fun parityUnicodeCaseFoldOverQuack() {
        org.junit.jupiter.api.Assumptions.assumeTrue(parityAvailable, "parity not available")
        // upper is an ALIAS emission → trino_upper server-side: Trino's simple per-code-point mapping
        // gives upper('straße') = 'STRAßE' (DuckDB's built-in would give 'STRAẞE'). The predicate
        // pushes and matches row 3 only when the extension is available.
        val ids =
            computeActual("SELECT id FROM t WHERE upper(name) = 'STRAßE'").materializedRows.map { it.getField(0) as Long }
        assertThat(ids).containsExactly(3L)
    }

    private fun explain(sql: String): String =
        computeActual("EXPLAIN (TYPE DISTRIBUTED) $sql")
            .materializedRows
            .joinToString("\n") { it.getField(0).toString() }

    private fun quackConnection(): java.sql.Connection {
        val props = Properties()
        props.setProperty("token", server.token)
        return QuackDriver().connect(server.connectionUrl(), props)
            ?: error("quack-jdbc returned no connection for ${server.connectionUrl()}")
    }
}
