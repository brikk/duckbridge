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
 * See `dev-docs/REPORT-string-comparison-probe-duckdb-1.5.5.md`.
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

    /** One-row result of the consolidated connection-init query. */
    data class ProbeResult(
        val collation: String,
        val parityMetaRows: Int?,
        val canaryPasses: List<Boolean>,
    )

    /**
     * Run every connection-init check in ONE statement / round trip. With [includeParityMeta], the
     * same SELECT also resolves and counts `trino_meta()` — so default PARITY init no longer runs a
     * metadata query plus 13 individual comparison queries (especially costly over Quack HTTP).
     *
     * This intentionally runs per connection rather than using a process-wide URL/mode cache. A
     * cache cannot identify a restarted Quack/DuckDB instance: after restart the extension may no
     * longer be loaded or `default_collation` may have changed, and skipping validation could return
     * wrong rows. One fresh statement is the small, correctness-preserving cost (EV-C1).
     */
    @Throws(SQLException::class)
    fun probe(connection: Connection, includeParityMeta: Boolean): ProbeResult {
        connection.createStatement().use { stmt ->
            stmt.executeQuery(probeSql(includeParityMeta)).use { rs ->
                if (!rs.next()) {
                    throw SQLException("DuckBridge consolidated comparison probe returned no row")
                }
                var column = 1
                val collation = rs.getString(column++) ?: ""
                val parityRows =
                    if (includeParityMeta) {
                        val count = rs.getInt(column++)
                        if (rs.wasNull()) null else count
                    } else {
                        null
                    }
                val passes = CANARIES.map { rs.getBoolean(column++) && !rs.wasNull() }
                return ProbeResult(collation, parityRows, passes)
            }
        }
    }

    /** The single SELECT used by [probe], data-exposed for statement-count / shape tests. */
    internal fun probeSql(includeParityMeta: Boolean): String =
        buildString {
            append("SELECT (SELECT value FROM duckdb_settings() WHERE name = 'default_collation') AS default_collation")
            if (includeParityMeta) {
                append(", (SELECT count(*) FROM trino_meta()) AS parity_meta_rows")
            }
            CANARIES.forEachIndexed { index, canary ->
                append(", (").append(canary.sql).append(") AS canary_").append(index)
            }
        }

    /**
     * Verify the connection honors Trino-aligned byte comparison, or throw. FULL mode does NOT call
     * this (caller-asserted); only BINARY and PARITY do.
     */
    @Throws(SQLException::class)
    fun verifyOrThrow(connection: Connection, mode: DuckBridgeStringPushdownMode) {
        verifyOrThrow(probe(connection, includeParityMeta = false), mode)
    }

    /** Validate a consolidated [ProbeResult], naming the first failed hazard class. */
    fun verifyOrThrow(result: ProbeResult, mode: DuckBridgeStringPushdownMode) {
        if (!isBinaryCollation(result.collation)) {
            throw divergence(
                mode,
                "DuckDB default_collation is '${result.collation}' (not binary/empty) — string equality and range " +
                    "pushdown would return wrong rows under a non-binary collation",
            )
        }
        val failed = result.canaryPasses.indexOfFirst { !it }
        if (failed >= 0) {
            throw divergence(mode, "byte-comparison canary failed for hazard class: ${CANARIES[failed].hazard}")
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
