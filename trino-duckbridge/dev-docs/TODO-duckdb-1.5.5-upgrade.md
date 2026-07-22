# TODO: DuckDB 1.5.5 upgrade + post-release validation

**Context.** Written 2026-07-21, day before the expected v1.5.5 cut. We are pinned
at `duckdb = "1.5.4.0"` (`gradle/libs.versions.toml:3`); the parity extension is
built against v1.5.4 (`duckdb-trino-parity-extension/TODO.md:35`). Scanned all 262
commits on `v1.5-variegata` from our v1.5.4 tag (`08e34c4`, 2026-06-16) to branch
tip `d8cdaa33f` (2026-07-21). Nothing changes our semantics or forces a design
change — the bump is net-positive (better pushdown pruning correctness, fewer
concurrency crashes, friendlier Quack HTTP timeout). This file is the checklist to
run once the tag actually lands.

## 0. Mechanical bump (required — do first)

- [ ] Confirm the v1.5.5 tag exists on `duckdb/duckdb` and that the JDBC artifact
      `org.duckdb:duckdb_jdbc:1.5.5.0` is published to Maven Central.
- [ ] Bump `gradle/libs.versions.toml`: `duckdb = "1.5.5.0"`.
- [ ] Re-vendor `duckdb-trino-parity-extension/duckdb` submodule to the v1.5.5 tag
      and **rebuild the parity extension** against it. This is NOT optional: the
      extension is C++-API (`duckdb.hpp`, `namespace duckdb`), hard version-pinned —
      a v1.5.4 build refuses to load on a v1.5.5 engine. Makefile derives the target
      from the vendored submodule's `git describe`.
- [ ] Rebuild the CI matrix binaries (linux amd64/arm64, macos amd64/arm64, windows,
      wasm) — `make linux-arm64` / `linux-amd64` etc.
- [ ] Check whether `extension-ci-tools` needs its branch checked out to `v1.5.5`
      (see `duckdb-trino-parity-extension/docs/UPDATING.md`).

## 1. Pushdown-correctness canaries (our #1 concern — never over/under-return)

These 1.5.5 fixes change which rows a filtered scan returns. Add/confirm cross-engine
canaries (Trino-does-filter vs pushdown-does-filter, identical results):

- [ ] **DECIMAL(>18) row-group stats** — `#23693` fixed swapped min/max for 128-bit
      DECIMAL in `RETURN_STATS`. Canary: multi-row-group parquet with a DECIMAL(38,0)
      column, range predicate that should prune. Verify no rows dropped/added.
- [ ] **Filtered row-group min/max** — `#23517`. Same class; range predicate over a
      multi-row-group file with some groups filtered.
- [ ] **Filter combiner unsatisfiable bounds** — `#23563` (`x >= 5 AND x < 5` and the
      inclusive/exclusive boundary variants). Confirm our translated conjuncts prune
      correctly and return empty where expected.

## 2. Concurrency / catalog — re-measure the retry surface

Several race/crash fixes landed *underneath* `DuckDbCatalogWriteRetry` (`#23861`
concurrent ALTER+INSERT crash, `#23808` ALTER dependency preservation, `#23348`
dependency manager keyed by OID, concurrent schema-drop race, attached-DB
invalidation `#23468`, autocheckpoint error propagation `#23510`).

- [ ] Re-run the **400-concurrent-create probe** from `P3-NOTES.md` on the Quack path
      (one long-lived DuckDB instance). Record new conflict counts.
- [ ] Keep `DuckDbCatalogWriteRetry` (still correct) but note in P3-NOTES whether
      conflicts dropped / changed shape. Some cases we paper over may now surface as
      clean errors or not happen at all.

## 3. Quack executor (`duckdb-quack` HTTP server)

- [ ] `#23892` moved `http_timeout` from a **total** timeout to a **connection**
      timeout (CURL backend), friendlier to slow-but-live producers. Verify our
      `QuackDuckBridgeExecutor` timeout config didn't assume total-timeout semantics,
      and confirm a long-running query no longer gets killed mid-stream.

## 4. Arrow interop

- [ ] `#23534` *Fix arrow type extension bugs* (ArrowAppender vs type-extension schema
      disagreement). Smoke-test `DuckBridgeArrowPageSource` /
      `DuckBridgeArrowToPageConverter` on extension-typed columns after the bump.

## 5. Formats we ship

- [ ] **Lance** read path — `#23770`/`#23969` Rust 1.97 + `ethnum` build fixes, local
      patch dropped. Re-run the Lance read probe (see `TODO-lance.md`); confirm the
      extension still installs/loads for our platforms at v1.5.5.
- [ ] **Vortex** — `d849601` stale SUBMODULES config cleanup. Re-run the Vortex
      read+write+pushdown suite (`TODO-vortex.md`).
- [ ] Note: iceberg, DuckLake, httpfs, aws, postgres/mysql/sqlite, azure,
      unity_catalog all bumped for 1.5.5 — spot-check anything we exercise.

## 6. Parity semantics — expected unchanged (cheap validation only)

**Zero** ICU / collation / case-folding / trim / normalize / reverse / xxhash / sha /
hmac changes in the branch. Expect no behaviour movement.

- [ ] Re-run the string-comparison init probe
      (`REPORT-string-comparison-probe-duckdb-1.5.4.md`) against v1.5.5; expect
      identical results. If anything moves, STOP — that's a real divergence, not noise.
- [ ] Rename/refresh the report to `...-duckdb-1.5.5.md` if it re-passes clean.

## 7. Watch item (low risk)

- [ ] `#23790` *TryLookupEntry now uses default schema as fallback* — changes
      cross-catalog macro/function resolution. Confirm `trino_meta()` and the `trino_*`
      macros still resolve identically when the parity extension is attached under a
      non-default catalog.

## Reference: full commit scan

262 commits, v1.5.4..v1.5-variegata tip. Last week was almost entirely extension
bumps + backports (normal pre-release freeze). Key PRs cited above:
`#23693 #23517 #23563 #23861 #23808 #23348 #23892 #23534 #23770 #23969 #23790`.
