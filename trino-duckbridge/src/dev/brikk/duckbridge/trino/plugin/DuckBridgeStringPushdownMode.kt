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

/**
 * String predicate pushdown mode — catalog config `duckbridge.string-pushdown.mode`, per-query
 * override via session property `string_pushdown_mode` (either direction: tighten or loosen).
 *
 * Design borrowed from the sibling trino-doris connector's mode dial (grounded in a live
 * byte-semantics probe; see `dev-docs/REPORT-string-comparison-probe-duckdb-1.5.4.md`, whose format
 * mirrors doris's `REPORT-string-comparison-probe-4.1.3.md`). This dial REPLACES the old
 * `duckbridge.parity.enabled` boolean: it encodes two independent trust axes.
 *
 * **Comparison trust** (string `=`/`<`/IN/ranges/ORDER BY):
 *  - [NULL_ONLY]: push null-ness only; retain the full domain locally.
 *  - [GUARDED]: push the domain remotely AND retain it locally (prefilter + keep) — exact, no
 *    semantic assumptions; NUL-bearing domains are skipped entirely (the probed under-return hazard).
 *  - [BINARY]: full pushdown, backed by a live byte-comparison probe at connection init.
 *  - [FULL]: full pushdown, caller-asserted (no probe).
 *  - [PARITY]: BINARY comparison trust PLUS the `trino_parity` extension (the 10 ICU/hash ALIAS
 *    natives). The default.
 *
 * **Function-semantics trust**: BARE/RENAME/OPERATOR/INLINE translator emissions are fixture-proven
 * and extension-free — they push in EVERY mode when they don't compare a string operand. ALIAS (the
 * 10 natives) needs the extension and is available ONLY in [PARITY]. Function predicates are never
 * "guarded": a diverging function prefilter under-returns, which a retained filter cannot repair, so
 * a string-comparing function conjunct is binary — proven (≥ BINARY) or not pushed.
 */
enum class DuckBridgeStringPushdownMode {
    /** String domains push null-ness only (`IS [NOT] NULL`); string comparisons stay in Trino. */
    NULL_ONLY,

    /**
     * VARCHAR equality/inequality/range/IN domains push as SUPERSET pre-filters with the exact Trino
     * filter RETAINED locally. Domains whose values contain a 0x00 byte are skipped entirely (NUL is
     * the probed under-return hazard class — skipping is always correct; a retained filter cannot
     * resurrect rows a remote pre-filter wrongly dropped). String-comparing function conjuncts, LIKE,
     * and string TopN stay in Trino. Result-identical to [NULL_ONLY] by construction.
     */
    GUARDED,

    /**
     * Full string pushdown under VERIFIED byte-comparison semantics — the connection-init probe
     * proves DuckDB's `default_collation` is binary and the comparison/ordering canary matches Trino.
     * Unlocks string equality/range domains (no retained filter), string LIKE, and string TopN.
     * ALIAS functions still stay off (they need the extension → [PARITY]).
     */
    BINARY,

    /**
     * Full string pushdown, caller-asserted (no init probe). Same comparison rendering and unlocks as
     * [BINARY]; ALIAS functions still off.
     */
    FULL,

    /**
     * [BINARY] comparison trust PLUS the `trino_parity` extension: the 10 ICU/hash ALIAS natives
     * (`lower`, `upper`, `reverse`, trim family, `normalize/1`, `xxhash64`, `sha512`, `hmac_sha256`)
     * push. The init probe runs AND the extension is LOADed + probed (fail-loud if missing). The
     * default mode.
     */
    PARITY,
    ;

    /** BINARY/FULL/PARITY: full string comparison pushdown (domains, LIKE, string TopN). */
    val allowsFullStringComparison: Boolean
        get() = this == BINARY || this == FULL || this == PARITY

    /** GUARDED: push string domains as a retained superset pre-filter. */
    val guardsStringDomains: Boolean
        get() = this == GUARDED

    /** Only PARITY makes the extension-backed ALIAS functions pushable. */
    val aliasAvailable: Boolean
        get() = this == PARITY

    /** BINARY and PARITY assert verified byte semantics — the connection-init probe must run. */
    val requiresComparisonProbe: Boolean
        get() = this == BINARY || this == PARITY

    /** PARITY additionally requires the `trino_parity` extension to be LOADed + probed. */
    val requiresParityExtension: Boolean
        get() = this == PARITY
}
