# P6 — string-pushdown mode dial

## What shipped

A configurable string-pushdown dial `duckbridge.string-pushdown.mode` ∈
`NULL_ONLY | GUARDED | BINARY | FULL | PARITY` (default **PARITY**), with a
`string_pushdown_mode` session-property override. It **replaces** the old
`duckbridge.parity.enabled` boolean (folded away; `duckbridge.parity-extension-path` kept).

The dial encodes two trust axes:

- **Comparison trust** (string `=`/`<`/IN/range/`LIKE`/`ORDER BY`): NULL_ONLY = none;
  GUARDED = remote pre-filter + retained local filter (exact, no semantic assumption,
  0x00-domains skipped); BINARY = full pushdown backed by a live init probe; FULL = full
  pushdown, caller-asserted; PARITY = BINARY.
- **Function-semantics trust**: BARE/RENAME/OPERATOR/INLINE emissions are fixture-proven
  and extension-free (they push in every mode when not comparing a string); ALIAS (the 10
  ICU/hash natives) needs the extension and is available only in PARITY. Function
  predicates are never "guarded" — a diverging function pre-filter under-returns, which a
  retained filter cannot repair, so a string-comparing function conjunct is binary: proven
  (≥ BINARY) or not pushed.

## Design borrow — credit

Adopted wholesale from the sibling **trino-doris** connector's string-pushdown work:

- The **mode taxonomy** (NULL_ONLY / GUARDED / BINARY / FULL) and the
  "GUARDED iff no undetectable under-return hazard" default criterion —
  `dev.brikk.trino.doris.DorisStringPushdownMode` and `DorisTypeMapping.VARCHAR_PUSHDOWN`.
  duckbridge adds a 5th mode, **PARITY**, that folds in the extension-backed ALIAS
  functions (doris has no equivalent extension layer), and makes PARITY the default
  because the extension has been the connector's historical posture.
- The **probe methodology + report format** — modeled on
  `trino-doris-connector/dev-docs/REPORT-string-comparison-probe-4.1.3.md` (the format
  source). Our generated equivalent is
  `REPORT-string-comparison-probe-duckdb-1.5.4.md`, produced live by
  `TestDuckBridgeStringComparisonProbe` over BOTH embedded DuckDB and the Quack transport.
- The **0x00 (NUL) domain skip** in GUARDED (doris observed a transient wrong-empty on a
  NUL literal; skipping is always correct) and the **CHAR exclusion reasoning** (doris
  excludes CHAR for trailing-space under-return). For duckbridge, CHAR is a non-issue:
  DuckDB has no CHAR padding (CHAR ≡ VARCHAR) and the read mappings never produce
  `CharType`, verified and noted in the report.

## Probe verdict (DuckDB 1.5.x)

Both transports report `default_collation = ''` (binary) and every canary byte-exact,
including NUL over quack-jdbc — so BINARY/PARITY render identically to FULL on this
DuckDB, and GUARDED's guards never actually fire on the adversarial fixture (they exist
for a hypothetical non-binary remote). The init probe is the guard that turns a future
non-binary DuckDB/Quack into a loud failure instead of silent wrong rows.

## Notable implementation points

- `DuckBridgeExpressionTranslator` gained a `stringComparisonAllowed` trust axis alongside
  `aliasAvailable` (bundled in a private `Ctx`); the rule derives both from the session
  mode at rewrite time, so the session override applies.
- `DuckBridgeStringPredicatePushdown.VARCHAR_PUSHDOWN` is the mode-aware
  `PredicatePushdownController` wired onto the VARCHAR column mapping in `DuckBridgeClient`.
- `DuckBridgeParity.ensureInitialised(connection, session)` now gates the extension
  LOAD+probe on PARITY and runs `DuckBridgeStringComparisonProbe.verifyOrThrow` on
  ≥ BINARY.
- `isTopNGuaranteed` / `supportsTopN` are mode-gated for string sort keys.

## Tests

- `TestDuckBridgeStringPushdownModes` — per-mode plan-shape (EXPLAIN) assertions
  (`constraint on [name]` = pushed domain; `filterPredicate =` = retained filter). Uses
  `computeActual(session, "EXPLAIN ...")` rather than `query().isFullyPushedDown()`: the
  latter's transaction path rejects catalog session properties in this test harness, while
  the EXPLAIN text exposes the exact push/retain split we assert.
- `TestDuckBridgeStringComparisonProbe` — the canary matrix over embedded + quack, writes
  the report, and proves the fail-loud path via `SET default_collation='nocase'`.
- The former `TestDuckBridgeParityDisabledPushdown` became this class's GUARDED-mode cases.
