# Doris API 24 / DuckDB 2 / Quack JDBC 0.10 qualification

## Status and frozen scope

**Local correctness qualification passed:** exact-pin FE/native/complete JNI
builds, both connector suites, full JDBC/Quack live smoke and read-only function
intake. No production rollout or new function enablement is claimed.

- Doris master at review time: `082736138b7f0e767f966cbb1434bc9ba85f29ec`.
  Connector API **24.0**, JNI API **6.0**, BE execution version **19**.
- DuckDB CLI/server **2.0.0**, native JDBC **2.0.0.0** (already the Trino pin).
- Both connectors now select `dev.brikk.duckdb:quack-jdbc:0.10.0`. Its released
  contract preserves ordinary-query recovery and invalidates sessions when a
  Quack-v3 error sets `must_invalidate`; no connector SQL-retry policy was added.
- Trino remains **483**, signed parity extension **0.5.1**, unchanged native
  extension source/binaries. No new function pushdown is enabled by availability.
  This run exercises the available signed Linux-amd64 pair; it does not rebuild
  or requalify the other published native platforms.
- No production deployment, release publication, push, broad cleanup or old-volume
  migration. This remains a local master-development ASAN correctness lane.

## Source, build and rollback evidence

See [the source review](REVIEW-doris-api24-2026-10-28.md) for the API/security/wire
audit, exact-pin patch and unchanged capability gates. Separate source worktree:
`/home/jayson/DEV/OSS/doris-duckbridge-api24-08273613`.

All raw evidence below is under that worktree's `output/`:

- `duckbridge-api24-build.sh`: immutable build environment and phase commands,
  sequential bounded FE, full JNI and ASAN native builds. SDK and dependency
  staging stay project/pin-local, not `~/.m2`.
- `duckbridge-api24-{fe,java,be,jdbc-test,jni-test}-build.log`: build/test evidence.
  All nine plugins pass the six upstream layout checks (`*-jni-layout.log`).
  JNI-bootstrap adds **50 passed** to JNI-SPI **26**, toolkit **55**, JDBC **51**;
  **18 unchanged upstream external-database integration skips** remain explicit.
- `duckbridge-api24-engine-manifest.json`: 3,231 FE/native/shared/plugin artifact
  paths/hashes and source/native/build/patch provenance. The native binary embeds
  the new pin honestly; no old engine or API-5 scanner was relabeled/reused.
- `duckbridge-api24-sdk-host-identity.json`: actual FE and compile SDK class bytes
  match across SPI, Thrift, foundation, extension-SPI and filesystem API.
- `rollback-api13/`: previous project-local Doris coordinates, plugin distributions,
  image identities and byte-exact shared staged Doris patch inventory. Existing
  API-13 tags/volumes and DuckLake services are preserved.
- `baseline-v1/`: frozen first API-24 harness/ZIP on DuckDB 1.5.6 / Quack 0.8.
  This lane was **not live-qualified**: its run correctly stopped at the CREATE
  traversal-policy assertion. Its volumes/logs remain; no failed result was erased.

The upstream native build normalized two datasketches binary test fixtures
(`theta_compact_estimation_from_java_v{1,2}.sk`); no native production source was
changed. The shared staged Doris checkout remains byte-identical to preflight.

The first image build created complete images but its wrapper failed after the
recipe was edited while it was running. `duckbridge-api24-images.log` retains
that failure; the unchanged artifacts were baked again successfully with the
finished script (`duckbridge-api24-be-image-confirm.log`).

## Issues found and real fixes

1. **CREATE validation order:** Doris invokes `preCreateValidation` before
   provider-property validation. Our first live test therefore reached the
   configurable driver-path policy before the mandatory traversal grammar.
   Apply upstream `JdbcDriverUrlSecurity.check` in both statement-time hooks,
   as upstream JDBC does; reconstruction/replay remain compatible. A regression
   proves an unsafe URL never reaches the operator gate. The smoke's required
   rejection assertions were not loosened.
2. **DuckDB-2 test-server TLS default:** the original Doris fixture started a TLS
   listener while the test's `jdbc:quack://` URL used plain HTTP, producing a
   malformed HTTP-status/EOF failure during handshake. Both isolated development
   fixtures now explicitly serve plain HTTP (`disable_ssl := true`), matching
   the existing Trino fixture. Production URL/TLS policy is unchanged. Only the
   signed core Quack extension loads; unsigned-extension mode is no longer enabled.
3. **Negative-test readiness:** after invalidating the run-owned JDBC manifest,
   native HTTP was ready before FE heartbeat registration. The first isolation
   query failed with "no available backends", correctly failing the assertion.
   Wait for native HTTP **and** FE registration before requiring a JDBC-specific
   rejection and `jdbc=FAILED` status. The final smoke passes without broadening
   the expected error or disabling the probe.
4. **Trino packaging:** a Copy-based assembly retained Quack 0.9 alongside 0.10.
   Replace it with Sync over the owned generated staging directory and verify
   exactly one resolved Quack/native-JDBC JAR. The final assembly removes the stale
   driver and retains the existing host-SPI exclusions and ServiceLoader checks.

## Connector and live gates

- Java-17 Doris `check assemble --rerun-tasks`: **72 passed, zero skips/failures/errors**,
  Detekt/package passed. Tests verify actual server **v2.0.0** and driver **0.10.0**,
  negotiated Quack **v3**, ordinary autocommit-error recovery, API/wire facts and
  conservative defaults. JAR SHA-256:
  `6e8ed1b6532fe86ffa3c23fdd9526c1110f7d2a4b679bdfa5c55766491abf85e`;
  ZIP `e35760d2a2bfae99ab0159c7427db46f193d4d8de485e5a5ff053f4b2491acf5`.
  The actual deployed JAR is byte-identical to the final assembled JAR.
- New DuckDB-2 spellings without a truthful supported mapping stay fail-loud:
  SQLNULL, TIMESTAMPTZ_NS, VARIANT, BIGNUM and positional TUPLE. Existing documented
  TIMESTAMP_NS-to-microsecond and structured-text contracts are not newly widened.
- Trino full forced `check pluginAssemble`: **643 passed, zero skips/failures/errors**,
  including current semantic fixtures, remote/JDBC/Arrow paths and negotiated-v3
  checks. Final default checks plus `verifyPluginAssembly` passed after the Sync fix.
- Cluster-free bootstrap/orchestration/audit checks: **10 passed**. Shell syntax,
  Compose rendering and whitespace checks passed.
- Fresh API-24-volume live smoke: **passed** (`duckbridge-api24-smoke-duckdb2-retry1.log`).
  Required gates include CREATE/ALTER driver
  security, native exact values, lazy JDBC load, non-UTC zones, pre-epoch fractional
  timestamps, DST-overlap instants, NULLs, current pushdowns, sequential/concurrent
  scans, catalog retirement/recreation, persisted initialized-catalog restart,
  and plugin-scoped failure/recovery; all passed. FE name/address and initialized
  catalog survived the post-scan restart. The native BE stayed healthy during the
  intentional API mismatch; complete-image recreation recovered `count(*)=3`.
  Final JNI status is API 6.0, one healthy JDBC plugin, zero failed plugins.
- `runtime-duckdb2/`: live engine identities, exact running native/JNI/driver/JAR
  hashes, images, signed Quack provenance, FE/BE logs and sanitizer scan. No
  sanitizer/crash pattern was observed in the captured BE logs. The three
  run-owned containers were stopped/removed **without deleting volumes**; sibling
  DuckLake services remain running and API-13 artifacts remain untouched.

Qualified local image identities:

- `doris-fe:duckbridge-082736138b7-api24`:
  `sha256:0927da17e8af5a11d651e8a9f4159703ddaf66050eae2fcbb238384dc5635fbf`.
- `doris-be:duckbridge-082736138b7-api24-asan`:
  `sha256:e576e169fae9835bbe1208640bed547697df6bf2d4c0af4bf8426cf739e9be15`.
- `duckbridge/duckdb-quack:2.0.0`:
  `sha256:7c0069fc30f8db49f8a8b405e9d03188a6c357f756fcc72f5796638d734f72da`.

## New-function intake

The inspected sibling `brikk-sql` main was `ace33b9`; its generated DuckDB catalog
still identifies **v1.5.6**, while the DuckDB-2 refresh is pending. Do not treat
new parser/generator support or same-name functions as a semantic equivalence proof.

`compose/function_audit.py` inventories the real DuckDB-2 resolved registry and
optionally diffs names against the exact sibling catalog/commit/hash. It also
compares the eight proposed JSON mutation/reconciliation families across object,
empty-object, SQL NULL, duplicate-key, punctuation-key, array/JSON-null and invalid
JSON fixtures. Doris evaluates above a real JDBC scan; the independent DuckDB CLI
oracle uses byte-identical inputs. Text/JSON results compare exact UTF-8 hex bytes;
unrelated errors are never counted as parity. Matching fixtures are explicitly
**not** pushdown qualification. Full overload/type/path/error coverage is separate.

Both connectors have regression guards keeping these new JSON names out of
predicate pushdown. The typed matrix produced **22 matching fixture results,
2 duplicate-key differences, 32 error/availability observations**; no new alias
was qualified. See the [intake report](REPORT-duckdb2-function-intake-2026-10-28.md)
for concrete counterexamples, the name inventory and follow-up constraints.
