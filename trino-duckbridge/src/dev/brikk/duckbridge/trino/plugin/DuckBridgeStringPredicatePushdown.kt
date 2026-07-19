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

import io.airlift.slice.Slice
import io.trino.plugin.jdbc.JdbcMetadataSessionProperties.getDomainCompactionThreshold
import io.trino.plugin.jdbc.PredicatePushdownController
import io.trino.plugin.jdbc.PredicatePushdownController.DISABLE_PUSHDOWN
import io.trino.plugin.jdbc.PredicatePushdownController.DomainPushdownResult
import io.trino.plugin.jdbc.PredicatePushdownController.FULL_PUSHDOWN
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.predicate.Domain

/**
 * Mode-resolved [PredicatePushdownController] for VARCHAR (and CHAR-as-VARCHAR) domains. The
 * per-query [DuckBridgeStringPushdownMode] (session property override of the catalog default)
 * decides how a string-column [TupleDomain][io.trino.spi.predicate.TupleDomain] constraint is
 * split between the remote DuckDB WHERE clause and Trino's retained filter.
 *
 * Evidence: `dev-docs/REPORT-string-comparison-probe-duckdb-1.5.4.md` — DuckDB VARCHAR comparison /
 * range / IN / ORDER BY are pure byte (memcmp) semantics over UTF-8, identical to Trino's VARCHAR
 * semantics, with NUL (U+0000) as the one probed under-return hazard class handled by GUARDED.
 *
 * | mode | VARCHAR value domains | retained filter |
 * |------|------------------------|-----------------|
 * | NULL_ONLY | not pushed (IS [NOT] NULL only) | n/a |
 * | GUARDED | pushed as superset pre-filter; 0x00-bearing domains skipped entirely | **yes** |
 * | BINARY/FULL/PARITY | full pushdown (probe-verified byte semantics) | no |
 *
 * Non-string columns keep their standard [FULL_PUSHDOWN] controller in every mode (numeric/date/
 * boolean comparisons are byte-exact cross-engine).
 */
internal object DuckBridgeStringPredicatePushdown {
    val VARCHAR_PUSHDOWN: PredicatePushdownController =
        PredicatePushdownController { session, domain ->
            when (DuckBridgeSessionProperties.getStringPushdownMode(session)) {
                DuckBridgeStringPushdownMode.NULL_ONLY -> nullOnlyResult(session, domain)
                DuckBridgeStringPushdownMode.GUARDED -> guardedResult(session, domain)
                DuckBridgeStringPushdownMode.BINARY,
                DuckBridgeStringPushdownMode.FULL,
                DuckBridgeStringPushdownMode.PARITY,
                -> FULL_PUSHDOWN.apply(session, domain)
            }
        }

    private fun nullOnlyResult(session: ConnectorSession, domain: Domain): DomainPushdownResult {
        // Null-ness carries no collation hazard; pushing IS [NOT] NULL is exact. Any value-bearing
        // domain is retained fully.
        return if (domain.isOnlyNull || domain.values.isAll) {
            DomainPushdownResult(domain, Domain.all(domain.type))
        } else {
            DISABLE_PUSHDOWN.apply(session, domain)
        }
    }

    private fun guardedResult(session: ConnectorSession, domain: Domain): DomainPushdownResult {
        if (domain.isOnlyNull || domain.values.isAll) {
            return DomainPushdownResult(domain, Domain.all(domain.type))
        }
        if (domainValuesContainNulByte(domain)) {
            // defense-in-depth skip: a retained filter cannot resurrect rows a remote pre-filter
            // wrongly dropped, and NUL (U+0000) is the probed under-return hazard class — keeping
            // these domains local is always correct.
            return DISABLE_PUSHDOWN.apply(session, domain)
        }
        // superset pre-filter remotely + the EXACT Trino predicate retained locally (prefilter + keep)
        return DomainPushdownResult(domain.simplify(getDomainCompactionThreshold(session)), domain)
    }

    /** Scans every domain value (discrete values and range bounds) for a 0x00 byte. */
    internal fun domainValuesContainNulByte(domain: Domain): Boolean {
        val slices = ArrayList<Slice>()
        domain.values.valuesProcessor.consume(
            { ranges ->
                ranges.orderedRanges.forEach { range ->
                    range.lowValue.ifPresent { slices.add(it as Slice) }
                    range.highValue.ifPresent { slices.add(it as Slice) }
                }
            },
            { discrete -> discrete.values.forEach { slices.add(it as Slice) } },
            { /* all-or-none carries no values */ },
        )
        return slices.any { slice -> (0 until slice.length()).any { slice.getByte(it) == 0.toByte() } }
    }
}
