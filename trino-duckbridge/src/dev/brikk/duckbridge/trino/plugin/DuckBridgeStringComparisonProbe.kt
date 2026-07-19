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
import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.TrinoException
import java.sql.Connection
import java.sql.SQLException

/**
 * Connection-init byte-comparison canary for string-pushdown modes >= BINARY (BINARY, PARITY).
 *
 * The dial's BINARY/PARITY contract is "DuckDB compares and orders VARCHAR by raw UTF-8 bytes,
 * identical to Trino's VARCHAR codepoint semantics." That contract must be VERIFIED per connection,
 * not assumed: a remote Quack server (or a future DuckDB build) configured with a case-insensitive
 * or otherwise non-binary `default_collation` would silently break equality/range pushdown —
 * returning wrong rows, the exact failure the doris string-probe work was built to prevent.
 *
 * Two checks, both fail loud:
 *  (a) `default_collation` is empty/binary (not e.g. `nocase`).
 *  (b) a small comparison + ordering canary over adversarial pairs: case-pair inequality,
 *      trailing-space inequality, NFC != NFD, astral (4-byte) ordering, zero-width inequality.
 *
 * On any failure, throws a [TrinoException] telling the operator to select GUARDED (or fix the
 * server's collation). GUARDED / NULL_ONLY never call this — they make no byte-alignment claim.
 *
 * See `dev-docs/REPORT-string-comparison-probe-duckdb-1.5.4.md`.
 */
internal object DuckBridgeStringComparisonProbe {
    private val log: Logger = Logger.get(DuckBridgeStringComparisonProbe::class.java)

    /**
     * A single canary: a boolean SQL predicate that MUST evaluate to true under Trino-aligned byte
     * semantics. [hazard] names the class for the error/report.
     */
    data class Canary(val hazard: String, val sql: String)

    /**
     * The comparison/ordering canary set. Each predicate is true iff DuckDB matches Trino's VARCHAR
     * byte semantics. Kept as data so the probe report test can enumerate and print verdicts.
     */
    val CANARIES: List<Canary> =
        listOf(
            Canary("case-pair inequality (no case fold)", "('a' <> 'A') AND ('a' > 'A')"),
            Canary("trailing-space inequality (no trim/pad)", "('a' <> 'a ') AND ('a' < 'a ')"),
            Canary("leading-space ordering", "(' a' < 'a')"),
            Canary("NFC != NFD (no normalization)", "('\u00e9' <> 'e\u0301')"),
            Canary("astral 4-byte ordering below/above BMP", "('a' < '\ud83d\ude00') AND ('\ud83d\ude00' > 'z')"),
            Canary("zero-width inequality (U+200B)", "('ab' <> 'a\u200bb')"),
            Canary("control-char (tab) inequality", "('a\tb' <> 'ab')"),
            // NUL (U+0000)-bearing literal comparison: byte-exact in the probe, but GUARDED skips
            // 0x00 domains as defense-in-depth. In >= BINARY we assert byte-equality holds.
            Canary("NUL-bearing equality is byte-exact", "(chr(0) = chr(0)) AND ('a' || chr(0) || 'b' <> 'ab')"),
            // ORDER BY byte order incl. NULLS placement. Asserted via scalar list-index probes rather
            // than an array-literal equality (which is fragile to non-ASCII literal round-tripping over
            // some transports): ascending puts the byte-min ('A' < 'a') first and NULL last; descending
            // NULLS FIRST puts NULL first; and the astral emoji sorts after ASCII 'z' (above the BMP).
            Canary(
                "ORDER BY ascending byte-min first",
                "(SELECT list(v ORDER BY v ASC NULLS LAST)[1] = 'A' " +
                    "FROM (VALUES ('a'), ('A'), ('z'), ('a ')) AS t(v))",
            ),
            Canary(
                "ORDER BY ascending NULLS LAST placement",
                "(SELECT list(v ORDER BY v ASC NULLS LAST)[4] IS NULL " +
                    "FROM (VALUES ('a'), ('A'), ('z'), (NULL)) AS t(v))",
            ),
            Canary(
                "ORDER BY descending NULLS FIRST placement",
                "(SELECT list(v ORDER BY v DESC NULLS FIRST)[1] IS NULL " +
                    "FROM (VALUES ('a'), ('A'), (NULL)) AS t(v))",
            ),
            Canary(
                "ORDER BY astral sorts after BMP ASCII",
                "(SELECT list(v ORDER BY v ASC)[3] = '\ud83d\ude00' " +
                    "FROM (VALUES ('a'), ('z'), ('\ud83d\ude00')) AS t(v))",
            ),
        )

    /**
     * Verify the connection honors Trino-aligned byte comparison, or throw. FULL mode does NOT call
     * this (caller-asserted); only BINARY and PARITY do.
     */
    @Throws(SQLException::class)
    fun verifyOrThrow(connection: Connection, mode: DuckBridgeStringPushdownMode) {
        val collation = readDefaultCollation(connection)
        if (!isBinaryCollation(collation)) {
            throw divergence(
                mode,
                "DuckDB default_collation is '$collation' (not binary/empty) — string equality and range " +
                    "pushdown would return wrong rows under a non-binary collation",
            )
        }
        connection.createStatement().use { stmt ->
            for (canary in CANARIES) {
                val ok =
                    stmt.executeQuery("SELECT (${canary.sql})").use { rs ->
                        rs.next() && rs.getBoolean(1) && !rs.wasNull()
                    }
                if (!ok) {
                    throw divergence(mode, "byte-comparison canary failed for hazard class: ${canary.hazard}")
                }
            }
        }
    }

    /** Reads `default_collation` from `duckdb_settings()`; empty string when unset. */
    @Throws(SQLException::class)
    fun readDefaultCollation(connection: Connection): String {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT value FROM duckdb_settings() WHERE name = 'default_collation'").use { rs ->
                return if (rs.next()) rs.getString(1) ?: "" else ""
            }
        }
    }

    /** Binary collation is the empty/unset value or an explicit `binary`; anything else diverges. */
    fun isBinaryCollation(collation: String): Boolean {
        val normalized = collation.trim().lowercase()
        return normalized.isEmpty() || normalized == "binary"
    }

    private fun divergence(mode: DuckBridgeStringPushdownMode, reason: String): TrinoException {
        log.error("duckbridge: string-comparison probe failed in %s mode — %s", mode, reason)
        return TrinoException(
            NOT_SUPPORTED,
            "DuckBridge string-pushdown mode $mode requires verified byte-comparison semantics, but the probe " +
                "failed: $reason. Set duckbridge.string-pushdown.mode=GUARDED (or session property " +
                "string_pushdown_mode=GUARDED) for extension-free exact pushdown with a retained filter, or fix the " +
                "remote DuckDB/Quack server's collation.",
        )
    }
}
