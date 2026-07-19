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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Runs the [DuckBridgeStringComparisonProbe] canary matrix (the same predicates the connection-init
 * fail-loud probe uses) against BOTH embedded DuckDB and the out-of-process Quack server, and writes
 * the verdicts to `dev-docs/REPORT-string-comparison-probe-duckdb-1.5.4.md`.
 *
 * If any hazard-class canary FAILS on a transport, this test fails — the connector's BINARY/PARITY
 * byte-semantics contract is not paper-able. (It doesn't; DuckDB is binary-collated by default on
 * both transports — see the generated report.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeStringComparisonProbe {
    private var quack: TestingQuackServer? = null

    @AfterAll
    fun tearDown() {
        quack?.close()
    }

    @Test
    fun embeddedDuckDbIsByteAligned() {
        embeddedConnection().use { conn ->
            val collation = DuckBridgeStringComparisonProbe.readDefaultCollation(conn)
            assertThat(DuckBridgeStringComparisonProbe.isBinaryCollation(collation))
                .`as`("embedded DuckDB default_collation must be binary/empty, was '%s'", collation)
                .isTrue()
            val results = runCanaries(conn)
            printSection("Embedded DuckDB (in-process, DuckDB 1.5.x)", collation, results)
            assertAllPass("embedded", results)
        }
    }

    @Test
    fun quackServerIsByteAligned() {
        val server = startQuack()
        quackConnection(server).use { conn ->
            val collation = DuckBridgeStringComparisonProbe.readDefaultCollation(conn)
            val results = runCanaries(conn)
            printSection("Quack server (out-of-process, quack-jdbc transport)", collation, results)
            assertThat(DuckBridgeStringComparisonProbe.isBinaryCollation(collation))
                .`as`("Quack server default_collation must be binary/empty, was '%s'", collation)
                .isTrue()
            assertAllPass("quack", results)
        }
    }

    @Test
    fun probeVerifyOrThrowPassesOnBinaryDuckDb() {
        embeddedConnection().use { conn ->
            // Should not throw on a clean binary-collated DuckDB.
            DuckBridgeStringComparisonProbe.verifyOrThrow(conn, DuckBridgeStringPushdownMode.BINARY)
            DuckBridgeStringComparisonProbe.verifyOrThrow(conn, DuckBridgeStringPushdownMode.PARITY)
        }
    }

    @Test
    fun probeVerifyOrThrowFailsLoudOnNonBinaryCollation() {
        embeddedConnection().use { conn ->
            conn.createStatement().use { it.execute("SET default_collation = 'nocase'") }
            // The case-pair canary (and the collation check) must trip → fail loud.
            org.assertj.core.api.Assertions.assertThatThrownBy {
                DuckBridgeStringComparisonProbe.verifyOrThrow(conn, DuckBridgeStringPushdownMode.BINARY)
            }.hasMessageContaining("string-pushdown mode BINARY")
                .hasMessageContaining("GUARDED")
        }
    }

    private data class Verdict(val hazard: String, val pass: Boolean)

    private fun runCanaries(conn: Connection): List<Verdict> {
        conn.createStatement().use { stmt ->
            return DuckBridgeStringComparisonProbe.CANARIES.map { canary ->
                val pass =
                    stmt.executeQuery("SELECT (${canary.sql})").use { rs ->
                        rs.next() && rs.getBoolean(1) && !rs.wasNull()
                    }
                Verdict(canary.hazard, pass)
            }
        }
    }

    private fun assertAllPass(transport: String, results: List<Verdict>) {
        val failures = results.filter { !it.pass }.map { it.hazard }
        assertThat(failures)
            .`as`("byte-comparison canaries that FAILED on %s (would force gating)", transport)
            .isEmpty()
    }

    private fun printSection(title: String, collation: String, results: List<Verdict>) {
        val sb = StringBuilder()
        sb.append("### ").append(title).append("\n\n")
        sb.append("`default_collation` = `'").append(collation).append("'` → ")
            .append(if (DuckBridgeStringComparisonProbe.isBinaryCollation(collation)) "binary ✔" else "NON-BINARY ✘")
            .append("\n\n")
        sb.append("| hazard class | verdict |\n|---|---|\n")
        results.forEach { sb.append("| ").append(it.hazard).append(" | ").append(if (it.pass) "byte-exact ✔" else "DIVERGENT ✘").append(" |\n") }
        REPORT_SECTIONS.add(sb.toString())
    }

    private fun embeddedConnection(): Connection {
        val props = Properties()
        props.setProperty("allow_unsigned_extensions", "true")
        return DriverManager.getConnection("jdbc:duckdb:", props)
    }

    private fun startQuack(): TestingQuackServer {
        val server = quack ?: TestingQuackServer().also { quack = it }
        return server
    }

    private fun quackConnection(server: TestingQuackServer): Connection {
        val props = Properties()
        props.setProperty("token", server.token)
        return QuackDriver().connect(server.connectionUrl(), props)
            ?: error("quack-jdbc returned no connection for ${server.connectionUrl()}")
    }

    companion object {
        private val REPORT_SECTIONS = java.util.concurrent.CopyOnWriteArrayList<String>()

        @JvmStatic
        @AfterAll
        fun writeReport() {
            if (REPORT_SECTIONS.isEmpty()) {
                return
            }
            val header =
                """
                # String comparison probe — DuckDB 1.5.x vs Trino 483 semantics

                Auto-generated by `TestDuckBridgeStringComparisonProbe` (format modeled on the sibling
                trino-doris connector's `REPORT-string-comparison-probe-4.1.3.md`). Evidence for the
                configurable string-pushdown modes (`duckbridge.string-pushdown.mode` /
                `string_pushdown_mode`). Each canary is a boolean predicate that must be TRUE under
                Trino-aligned VARCHAR byte (memcmp) semantics; a FAIL would gate the corresponding
                BINARY/PARITY behavior.

                ## Headline

                **DuckDB string comparison, range, IN, and ORDER BY are pure BYTE (memcmp) semantics
                over UTF-8** — `default_collation` is empty/binary by default — identical to Trino's
                VARCHAR semantics (codepoint order == UTF-8 byte order). This holds on BOTH the
                embedded (in-process) and the Quack (out-of-process) transports. The one probed
                under-return hazard class is a NUL (U+0000)-bearing literal: byte-exact here, but
                GUARDED skips 0x00-bearing domains as defense-in-depth (skipping is always correct; a
                retained filter cannot resurrect wrongly-dropped rows). CHAR is not a read-path hazard:
                DuckDB has no CHAR padding (CHAR ≡ VARCHAR) and the connector's read mappings never
                produce CharType.

                ## Verdicts (live)

                """.trimIndent() + "\n\n"
            val body = REPORT_SECTIONS.joinToString("\n")
            val footer =
                """

                ## Mode consequences (implemented)

                | mode | VARCHAR value domains | retained filter | string function compare | string TopN | ALIAS fns |
                |---|---|---|---|---|---|
                | NULL_ONLY | not pushed (IS [NOT] NULL only) | n/a | no | off | off |
                | GUARDED | superset pre-filter; 0x00 domains skipped | yes | no | off | off |
                | BINARY | full pushdown (verified) | no | yes | on | off |
                | FULL | full pushdown (caller-asserted) | no | yes | on | off |
                | PARITY (default) | full pushdown (verified) | no | yes | on | **on** |

                ## Reproduce

                `./gradlew :trino-duckbridge:test --tests '*TestDuckBridgeStringComparisonProbe'`.
                The canary set lives in `DuckBridgeStringComparisonProbe.CANARIES`; the connection-init
                fail-loud probe (`verifyOrThrow`) runs the identical set at mode >= BINARY.
                """.trimIndent() + "\n"
            val path = java.nio.file.Path.of("dev-docs/REPORT-string-comparison-probe-duckdb-1.5.4.md")
            runCatching {
                java.nio.file.Files.createDirectories(path.parent)
                java.nio.file.Files.writeString(path, header + body + footer)
            }
        }
    }
}
