# TODO: DuckDB 2.0 readiness

**Status:** DuckDB 2.0.0 intake is qualified for Trino and the matching Doris-master
API-27/JNI-6 lane (2026-11-02). Both select released Quack JDBC 0.12.0 with negotiated-v3
regression checks; see the [October-31 adoption](../../trino-duckbridge/dev-docs/archive/REPORT-quack-0.12-release-2026-10-31.md).
This tracks remaining stable-ABI, storage, dedicated protocol-conformance and advanced-feature work.
See [the November-2 qualification](../../doris-duckbridge/dev-docs/QUALIFICATION-api27-2026-11-02.md).
The categorized [selection backlog](../TODO-backlog.md), not every unchecked preview
item below, controls new release work.

**Current Trino close-out (2026-11-03):** selected floating/DATE/unit repairs,
local ATAN/ATAN2/COSH, known-zone sub-day truncation/YEAR and exact fixed-offset
WITH_TIMEZONE consumers are implemented. Named-zone attachment stays local.
The user also approved local DATE-input DATE_TRUNC (including common values),
release with the explicitly reproduced Trino REAL membership / signed-zero
limitation, and no further attach-conflict investigation for this cut. Source
types/guards remain intact. The owned audit bundle is being finalized, followed
by a clean Trino build and verified plugin packaging; see the
[release preparation receipt](../../trino-duckbridge/dev-docs/archive/REPORT-release-closeout-2026-11-03.md).
No upstream/native API change, broad new audit or Doris release is selected.
Earlier counts and pending dispositions below are dated history, not current gates.

Duckbridge is exposed to this release in more places than the embedded JDBC
version alone:

- Trino production reads use either `duckdb_jdbc` (embedded) or the independent
  pure-Java `quack-jdbc` driver (remote).
- Trino's experimental columnar engines use `DuckDBResultSet.arrowExportStream`.
  The remote variant embeds a local DuckDB client, attaches Quack, then wraps the
  real query in `quack_query_by_name(...)`.
- Doris is remote-only today: the FE metadata plane and BE `JdbcJniScanner` use
  `quack-jdbc`; result decoding depends on our Doris `DuckDbTypeHandler` patch.
- Trino pushdown depends on a version-pinned native `trino_parity` extension.
  Doris instead has its own small, audited SQL-function allowlist.

The maintained lanes pin DuckDB/JDBC `2.0.0.0`, `quack-jdbc` `0.12.0`, parity source
`b216dc13a9`, and signed `trino_parity` 0.5.1 community binaries for Linux amd64/arm64, macOS arm64
and Windows amd64. Doris is separately qualified on master `4bc7f426828`, API 27 / filesystem 7 / JNI 6,
DuckDB 2.0.0 / Quack JDBC 0.12.0. Its API-24 and old API-13 / 1.5.6 / 0.8 artifacts remain rollback;
this is master-development ASAN correctness evidence, not a published Doris-5.x release rollout.

## Reconciled current state — 2026-10-31

The four requested rounds are complete: exact flat-namespace routing (`e5075bd`),
temporary boundary guards (`cc899bc`), released Quack 0.12.0 plus storage/trigger
safety (`10a69d4`), and test-only scan benchmarks (`b224686`). The subsequent
[tracker reconciliation and arithmetic regression](../../trino-duckbridge/dev-docs/archive/REPORT-readiness-reconciliation-2026-10-31.md)
passes **920 Trino tests**, zero failures/errors/skips, and assembly verification.
Doris checks remain green against the unchanged 89-test baseline (test task
up-to-date). This pass does not widen runtime capabilities or rerun external gates.

The later [POWER audit/fix](../../trino-duckbridge/dev-docs/archive/REPORT-power-edge-audit-2026-10-31.md)
finds real finite/NaN wrong-result pushdown and applies the user-approved local
Trino fallback. **926 Trino tests** and assembly verification pass; all 713 scalar
pairs and 14 production predicate row sets match Trino after that fix. Doris is
unchanged and not rerun by this Trino-only task.

November-1 [Doris API-26 re-vendor](../../doris-duckbridge/dev-docs/archive/QUALIFICATION-api26-2026-11-01.md)
subsequently passes **90 Doris tests**, exact complete JNI packaging, 182 selected
upstream tests (18 unchanged external-database skips) and full live JDBC/Quack
smoke. Trino code/dependency pins are unchanged by that re-vendor; the 926-test
Trino result above remains its own prior qualification, not a new run.

November-1 [four-area pushdown audits](../../trino-duckbridge/dev-docs/archive/REPORT-pushdown-edge-audits-2026-11-01.md)
subsequently find uncorrected floating domain/expression wrong rows, temporal
type/unit/zone errors, finite DATE binding/text differences and narrowing CAST
error-code differences. LIKE matches its bounded corpus. Subsequent user-approved
[narrow DATE/unit fixes](../../trino-duckbridge/dev-docs/archive/QUALIFICATION-narrow-date-unit-fixes-2026-11-01.md)
repair finite DATE writes and invalid-unit admission (623 scoped LA-JVM tests
pass); the remaining common-case pushdown disposition is pending. New audit tests
still expose the other defects. Earlier green
module qualifications do not erase these newly demonstrated supported-surface bugs.

November-2 [targeted semantic fixes](../../trino-duckbridge/dev-docs/archive/QUALIFICATION-targeted-pushdown-fixes-2026-11-02.md)
preserve common floating ranges and DATE text pushdown while repairing NaN domains,
IEEE comparisons/NULLIF and floating SIGN. User explicitly accepts only the
documented decimal narrowing-overflow JDBC_ERROR limitation. Full Trino run:
945 tests, 942 pass, three new audit failures, no errors/skips; assembly succeeds.
Finite ATAN/ATAN2/COSH, DATE/zoned truncation and WITH_TIMEZONE remain open; a new
connector-free Trino REAL IN signed-zero inconsistency is preserved as a red
diagnostic. No broader local fallback is silently applied.

**Open external/environment acceptance issues, separate from those module checks:**

- Published-port HTTP streaming remains intermittently red; the actual host
  networking correction is unidentified/unapplied. See the
  [HTTP investigation](../../trino-duckbridge/dev-docs/REPORT-http-response-truncation-2026-10-30.md).
- Autonomous orphan-session reaping remains red against signed core `f96420ed46`.
  Healthy heartbeat renewal and bounded client teardown are qualified, not a
  substitute for server expiry. See the
  [lifecycle report](../../trino-duckbridge/dev-docs/REPORT-lifecycle-qualification-2026-10-29.md).
- Native JDBC's TUPLE-list string getter still crashes in the isolated negative
  control. The requested post-round revisit is **done**; the guard and underlying
  type/getter TODO remain open. See the
  [boundary policy](../../trino-duckbridge/dev-docs/REPORT-boundary-type-policy-2026-10-30.md).
- Doris JNI's ordinary-UTF-8/modified-UTF-8 mismatch still blocks OVERLAY
  qualification. Await the native correction; no local rebuild/bypass is planned.
  See the [OVERLAY report](../../doris-duckbridge/dev-docs/REPORT-overlay-qualification-2026-10-29.md).

**Local capability/qualification backlog:** faithful new-type mappings, full nested
schema addressability, remaining temporal/optimizer/storage-fault coverage and
dedicated protocol conformance. Stable extension ABI, CONNECT, Java table functions
and a production chunk reader are optional designs, not automatic upgrades.
Current unsafe paths remain refused; benchmark results did not change defaults.

The priority list below originated in the preview scan; its gate labels apply to
the capability/platform being enabled, not every historical proposal. Checked
items have evidence for their stated scope. Unchecked combined/wider items must
not erase completed subgates or imply a supported capability is absent. Dated
reports preserve their original artifact identities; current pins are above.

## Source material

- [A Preview of DuckDB v2.0](https://duckdb.org/2026/08/17/duckdb-20-highlights)
- [Reconciling JSON in DuckDB, One Patch at a Time](https://duckdb.org/2026/08/18/reconciling-json)
- [DuckDB v2.0: Your Database Deserves a Better Parser](https://duckdb.org/2026/08/20/duckdb-20-peg-parser)
- [Chunked Query Results in the DuckDB Java Driver](https://duckdb.org/2026/08/21/chunked-query-results-java-driver)
- [DuckDB Table Functions in Java](https://duckdb.org/2026/08/25/table-functions-in-java)

The Java chunk API actually arrived in `duckdb_jdbc` 1.5.3.0. Java table
functions are also usable before 2.0. They belong here because they change the
best upgrade design, not because they are 2.0 compatibility blockers.

## Commit-history scan

Scanned 2026-08-28 from DuckDB `v1.5.5` (`d8cdaa33f`) through upstream
`origin/main` at `5b1ef771b` (2026-08-27): 11,741 reachable commits, reduced to
1,683 first-parent merged changes and supplemented with path-specific history and
diff inspection for parser, types/casts, C/C++ APIs, extensions, storage,
transactions, HTTP, Arrow, and optimizer/filter code.

Because Duckbridge depends on code that has moved out of the core repository,
the same scan also covered:

- `duckdb/duckdb-java` since JDBC `v1.5.5.0`: 95 commits through 2026-08-25.
- `duckdb/duckdb-quack` since 2026-07-22: 93 commits through 2026-08-26.
- `brikk/fork-quack-jdbc` through `v0.6.0` and current `0.7.0-SNAPSHOT`.

Material changes absent or under-specified in the five blog posts are tracked in
DB20-15 through DB20-19 and folded into the existing Quack/API issues. The most
important are:

| Change | Upstream evidence | Duckbridge consequence |
|---|---|---|
| Quack negotiates v1/v3; 0.10 fixes `must_invalidate` handling | `duckdb-quack` PRs [#229](https://github.com/duckdb/duckdb-quack/pull/229), [#245](https://github.com/duckdb/duckdb-quack/pull/245); released brikk driver 0.10.0 | Both connector suites require negotiated v3 against DuckDB 2.0. Dedicated lease/resource/conformance work remains separate. |
| Untyped `NULL` remains `SQLNULL` at result/table boundaries | DuckDB [#23017](https://github.com/duckdb/duckdb/pull/23017), `b0f224ed` | Explicit unsupported guards are in place; faithful host type support remains separate. |
| Every error invalidates an explicit transaction by default | DuckDB [#22674](https://github.com/duckdb/duckdb/pull/22674), `a2044776` | Retry and pooled-connection code must rollback/reset or replace connections after failure. |
| Prepared statements now use SQL `PREPARE`/`EXECUTE`/`DEALLOCATE` internally | DuckDB [#24273](https://github.com/duckdb/duckdb/pull/24273), `b5e4f5be` | Re-test bindings, schema changes, close/cancel, and Quack execution timing. |
| Strong timestamp types plus `TIMESTAMPTZ_NS` | DuckDB [#22489](https://github.com/duckdb/duckdb/pull/22489), [#22412](https://github.com/duckdb/duckdb/pull/22412) | New type spelling/metadata and changed downcast rounding at our microsecond boundary. |
| Integer modulo/division by zero now throws by default | DuckDB [#25004](https://github.com/duckdb/duckdb/pull/25004), `5efbc8a5` | The original `mod/2` zero-divisor hole is already guarded: only nonzero integer constant divisors push; integer `/` and `%` operators remain local. |
| `pow` changed to IEEE-754 behavior | DuckDB [#22753](https://github.com/duckdb/duckdb/pull/22753), `1b8a8819` | Re-prove the pushed `power/2` edge corpus, including signed zero, NaN, infinity, and domain errors. |
| Extension/CLI x86 CPU baseline may rise | DuckDB [#24391](https://github.com/duckdb/duckdb/pull/24391), `721f57a9` | Community parity binaries may require x86-64-v2; the CLI may require v3 and can SIGILL on old hosts. |
| Quack adds heartbeats, bounded teardown, result retention/read-ahead, and scoped secrets | `duckdb-quack` PRs [#240](https://github.com/duckdb/duckdb-quack/pull/240), [#228](https://github.com/duckdb/duckdb-quack/pull/228), [#229](https://github.com/duckdb/duckdb-quack/pull/229), [#245](https://github.com/duckdb/duckdb-quack/pull/245), [#262](https://github.com/duckdb/duckdb-quack/pull/262) | Connection lifetime, server memory, cancellation, and authentication behavior all change. |
| JDBC `v1.5.5.1` exists | `duckdb-java` tag `v1.5.5.1` | It fixes `Statement.execute()` + `getResultSet()` result cleanup; assess bumping the maintained 1.5 lane independently. |

## Priority list

| ID | Priority | Issue | Release gate? |
|---|---|---|---|
| DB20-01 | P0 | Build an explicit engine/driver/extension compatibility matrix | yes |
| DB20-02 | P0 | Rebuild `trino_parity`; assess migration off DuckDB's unstable C++ internals | yes for rebuild |
| DB20-03 | P0 | Re-prove Unicode, timezone, calendar, and collation semantics after ICU removal | yes |
| DB20-04 | P0 | Qualify stable Quack and decide whether to adopt `CONNECT` | yes |
| DB20-05 | P0 | Differential-test every generated SQL shape with the PEG parser | yes |
| DB20-06 | P0 | Define storage-v2 upgrade and rollback behavior | yes for embedded deployments |
| DB20-07 | P1 | Benchmark/adopt chunked Java results where they actually fit | no |
| DB20-08 | P1 | Add an explicit `VARIANT`/JSON/nested-value transport policy | yes if exposed by a catalog |
| DB20-09 | P1 | Decide how nested schemas map into Trino and Doris namespaces | yes if present on a server |
| DB20-10 | P1 | Revalidate pushdown under new pruning, optimizer, and async-I/O paths | yes |
| DB20-11 | P1 | Verify connector writes against triggered tables | yes for Trino write support |
| DB20-12 | P2 | Evaluate Java table functions for a concrete reverse-data-flow use case | no |
| DB20-13 | P2 | Triage new SQL/functions without widening pushdown by name | no |
| DB20-14 | P2 | Evaluate stable extension ABI, private repositories, and server observability | no |
| DB20-15 | P0 | Handle new boundary types and timestamp precision explicitly | yes |
| DB20-16 | P0 | Re-prove transaction, retry, and prepared-statement lifecycle | yes |
| DB20-17 | P0 | Resolve arithmetic/error semantic changes, including the 1.5.5 `mod` hole | yes |
| DB20-18 | P1 | Qualify new build system and CPU baselines | yes for affected hosts |
| DB20-19 | P1 | Set explicit Quack lease/cache/secret/resource policy | yes for remote paths |

## DB20-01: version and protocol matrix

**Why:** a single version bump is insufficient. The parity binary has a hard
DuckDB version pin, Quack has client/server protocol compatibility, and
`quack-jdbc` is not the DuckDB Java driver.

- [x] Adopt DuckDB 2.0.0 for Trino and the separately qualified Doris-master API-26/JNI-6 lane;
  preserve the API-24 and old Doris API-13 / 1.5.6 artifacts as rollback, not the active source pin.
- [x] Pin and record `duckdb_jdbc`, DuckDB CLI/server, Quack driver and parity extension independently
  in the 2026-10-21 qualification report.
- [x] Qualify released `quack-jdbc` 0.9.0 against the DuckDB-2.0 server's retained protocol-v1
  compatibility. Protocol-v3 features remain follow-up work.
- [x] Adopt released 0.10.0 for both connectors; prove actual driver/server identity and negotiated
  v3, existing semantic/remote/Arrow suites, ordinary-query-error recovery and Doris live JNI scans.
- [ ] Complete dedicated conformance of the released driver's v3 additions: indexed and
  acknowledged fetch batches, terminal batch counts, read-ahead ordering, the
  heartbeat request/lease handshake, and new error/cleanup behavior. Actual v3
  negotiation, healthy idle renewal and bounded CANCEL/DISCONNECT are already
  qualified; full wire/resource conformance and autonomous server reaping are not.
- [x] Re-vendor `duckdb-trino-parity-extension` to `b216dc13a9`, including DuckDB v2.0.0 and matching
  CI tools.
- [x] Exercise Trino embedded JDBC, direct `quack-jdbc`, embedded Arrow and Quack/Arrow.
- [x] Exercise Doris metadata/JNI scans on the exact master API-26/JNI-6 boundary,
  including live Java-17 smoke with released Quack 0.12.0. No published Doris-5.x rollout is claimed.
- [ ] Probe 1.5-client/2.0-server and 2.0-client/1.5-server Quack combinations.
  Support only combinations the protocol and parity-extension results prove;
  otherwise reject/document mixed versions clearly.
- [x] Bump Trino's Quack test server, CI/release extension fetch path, bundled binary and compatibility
  documentation together. Doris's native baseline remains independently qualified.
- [x] Advance the maintained lane beyond `duckdb_jdbc:1.5.5.1`: `1.5.6.0` passed the full
  Trino and Doris suites on 2026-10-10.

**Exit:** all supported cells have a live integration test; unsupported cells
fail clearly rather than returning partially decoded rows.

Relevant code: `gradle/libs.versions.toml`, both
`*/test/resources/docker/quack-server/Dockerfile`, `.github/workflows/ci.yml`,
and `duckdb-trino-parity-extension/.github/workflows/MainDistributionPipeline.yml`.

## DB20-02: parity extension and the new C API

**Why:** 2.0 has a reworked, versioned C API and a thin C++ API intended to keep
extension binaries independent of DuckDB releases. Our extension currently uses
the old unstable C++ API and internal catalog helpers directly:

- `ScalarFunction`, `LogicalType`, `ExtensionLoader`, `UnaryExecutor`, and
  `StringVector` in `src/string_functions.cpp` and `src/hash_functions.cpp`.
- `DefaultFunctionGenerator::CreateInternalMacroInfo` and
  `DefaultTableFunctionGenerator::CreateTableMacroInfo` in
  `src/alias_macros_loader.cpp`.
- `DUCKDB_CPP_EXTENSION_ENTRY` and the old extension build templates.

The history also contains sweeping pre-C-API-v2 breakage: immutable
`LogicalType`/`Value`/function sets, private query-result and prepared-statement
members, new vector writers/sizes, C++17 as the minimum, a revamped extension
build system, and removal of the amalgamation build. Exact-version qualification
below is complete; stable-ABI migration remains separate from that mechanical port.

- [x] Qualify the exact-version 2.0 extension implementation; signed 0.5.1 is bundled
  and exercised by connector tests. See the
  [signed release report](../../trino-duckbridge/dev-docs/archive/REPORT-signed-parity-0.5.1-duckdb-2.0.0-2026-10-23.md).
- [x] Install/load/smoke all ten functions and metadata on the four shipped native
  platforms in that release's required matrix. Wasm is not a JVM connector target;
  this does not claim every extension target or oldest-CPU qualification.
- [ ] Confirm whether the new stable API covers vectorized scalar functions,
  BLOB/VARCHAR access, exception propagation, and a zero-argument metadata table
  function well enough to replace the internal APIs.
- [ ] If table macros are not stable-API-covered, consider replacing only
  `trino_meta()` with a real table function. Do not retain an internal API solely
  to preserve its implementation as a macro.
- [ ] Port to the stable ABI as a separate follow-up and prove whether one binary
  really loads across later DuckDB versions before changing packaging policy.
- [x] Keep exact-version signed binaries; cross-version stable-ABI proof is not claimed.
- [ ] Decide whether extension-schema APIs change registration/search-path
  behavior. Keep `trino_*` and `trino_meta()` unqualified only if loading the
  extension normally and under an alias resolves them identically.

**Exit for 2.0:** a freshly built 2.0 binary loads locally and server-side and
passes the connector's metadata-lockstep and semantic fixtures. Stable-ABI
migration is desirable but not a reason to delay a correct version-specific
build.

## DB20-03: ICU removal and parity semantics

October-30 [Unicode recheck](../../trino-duckbridge/dev-docs/archive/REPORT-unicode-version-recheck-2026-10-30.md):
the shipped parity 0.5.1 already includes ICU 76.1 / Unicode 16. EV-E3's old data-version
gap is closed for Trino 483 / JDK 25: all 1,112,064 scalars pass nine independent JDK
checks each, with additional native-Trino and embedded/Quack predicate regressions.
This does not establish arbitrary future-JDK or every-platform compatibility.

**Why:** DuckDB 2.0 removes the ICU *library*. Its still-named `icu` extension
implements timezones, calendars, and collations itself from IANA and native
DuckDB code. Our parity extension separately vendors ICU 76.1 / Unicode 16
because Trino's Java semantics differ from DuckDB's built-ins. DuckDB's removal
does not prove that we can remove our copy.

- [x] Retain independently vendored ICU in the 2.0 parity build. Replacing it with
  DuckDB's Unicode implementation would still require separate semantic proof.
- [x] Re-run current smoking guns: dotted-I lowercasing, sharp-S uppercasing,
  combining-mark and ZWJ reversal, Java-whitespace trim, and NFC normalization.
- [x] Add a generated Unicode corpus comparing `trino_lower`, `trino_upper`, trim,
  and normalize directly with Trino/Java, not merely with DuckDB 1.5.
- [ ] Record the provenance/version/security-maintenance plan for the now
  independently vendored ICU sources. They can no longer be described as a copy
  of DuckDB's current bundled implementation after 2.0.
- [x] Verify the actual bundled ICU version and current JDK parity rather than
  retaining the stale ICU-66.1/Unicode-13 assumption. ICU 76.1 / Unicode 16 is
  qualified for Trino 483 / JDK 25; a future ICU/JDK change needs its own proof.
- [ ] Re-run the binary string-comparison canary and ordering/`IN`/TopN fixtures
  under 2.0. The new collation implementation must not change our default-binary
  assumption.
- [ ] Re-run timezone parity in UTC, Los Angeles DST gap/overlap, Kathmandu, Lord
  Howe, and representative pre-1970/future instants. Record DuckDB's bundled IANA
  tzdata version.
- [ ] Confirm `SET TimeZone`, `timezone(...)`, `AT TIME ZONE`, calendar selection,
  and `TIMESTAMPTZ` Arrow/JDBC rendering keep their 1.5 contract.
- [ ] Re-run Doris's dual-server-zone canary. Doris cannot safely set per-scan
  session state, so explicit-UTC literals against `TIMESTAMPTZ` must remain
  server-zone independent.

**Exit:** no existing pushed predicate changes its truth set. A mismatch blocks
that pushdown surface; it is not release noise.

Relevant code: `duckdb-trino-parity-extension/CMakeLists.txt`,
`src/string_functions.cpp`, `DuckBridgeStringComparisonProbe.kt`,
`TrinoTimeZoneNormaliser.kt`, and `DuckBridgeQueryBuilder.kt`.

## DB20-04: stable Quack and `CONNECT`

**Why:** Quack becomes stable in 2.0. `CONNECT` is the stated successor to the
old `remote.query(...)` workaround and may also replace our analogous
`quack_query_by_name(...)` wrapper by routing the client session to the remote
server. This affects only the local-DuckDB Quack executor directly; production
direct `quack-jdbc` and Doris still need their own protocol qualification.

- [x] Qualify `quack-jdbc` 0.9.0 against the 2.0 server for metadata, prepared parameters,
  errors, token auth, arrays, wide integers, timestamps and concurrent result streams.
- [x] Confirm DuckDB 2.0's retained protocol-v1 compatibility through the direct Quack suite.
- [x] Qualify actual server cancellation/57014, CANCEL-under-partition deadline
  and bounded DISCONNECT with released Quack 0.12.0; see the
  [release report](../../trino-duckbridge/dev-docs/archive/REPORT-quack-0.12-release-2026-10-31.md).
- [ ] Complete TLS and long-running/retained-result qualification. Published-port
  streaming and autonomous lease expiry remain separate red acceptance gates.
- [x] Verify 65-second healthy idle renewal and twenty physical-session/heartbeat
  cleanup cycles. This does not prove abandoned-server-session expiry.
- [ ] Extend pool/churn and long-running-result tests; use the new Quack metrics
  and logs to distinguish server execution, result transport, and client decode.
- [ ] Prototype `CONNECT engine` in `QuackDuckBridgeExecutor` and compare it with
  `quack_query_by_name`. Determine whether it preserves the DuckDB driver's Arrow
  export, prepared parameter binding, cancellation, and remote session state.
- [ ] If `CONNECT` removes the nested SQL-literal wrapper, delete
  `QuackParameterInliner` only after all parameter types are proven. Avoid keeping
  two production remote-SQL renderers without a concrete need.
- [ ] Verify where `LOAD trino_parity`, `SET TimeZone`, tuning, secrets, and
  extension installs execute before and after `CONNECT`; local/server state must
  never be confused.
- [ ] Pair every `CONNECT` with reliable `DISCONNECT`/connection teardown and test
  failed-query cleanup and pooled-connection reuse.
- [ ] Keep direct `quack-jdbc` as a separate path. `CONNECT` in DuckDB's JDBC
  driver does not automatically upgrade Doris's BE scanner or Trino's direct
  Quack transport.
- [ ] Track Quack mainline independently from DuckDB core: protocol and lifecycle
  behavior is landing in `duckdb-quack`, not in the core history or Java driver.

**Exit:** choose one implementation for the experimental Trino Quack/Arrow path
based on correctness and measurements. The production direct Quack paths pass
independently.

Relevant code: `QuackDuckBridgeExecutor.kt`, `QuackParameterInliner.kt`,
`DuckBridgeClientModule.kt`, and Doris `DuckBridgeQuackConnections.kt` /
`DuckBridgeScanPlanProvider.kt`.

## DB20-05: PEG parser compatibility

**Why:** 2.0 replaces the PostgreSQL-derived parser with a PEG parser while
intending to preserve DuckSQL. Duckbridge generates and sometimes nests SQL, so
"users should not notice" still needs a connector-level proof.

- [ ] Complete a dedicated old/new-parser differential campaign beyond the
  generated SQL already exercised by the final-2.0 connector suites. A preview
  bump is no longer pending; any old-parser oracle must be isolated test tooling.
- [ ] Cover quoted identifiers that are keywords, Unicode, embedded quotes,
  three-part names, every literal/cast shape, arrays, empty projections,
  `ORDER BY`/`LIMIT`, parity calls, and date/time literals.
- [ ] Cover connector setup SQL: `LOAD`, `INSTALL`, `ATTACH`, secrets,
  `SET TimeZone`, Quack wrappers, DDL, and prepared statements.
- [ ] Differentially compare parse success, bound types, result rows, and side
  effects. Error-message text may change; exception classification and fail-loud
  behavior must not.
- [ ] Run malformed/deeply nested generated input to ensure the wrapper/inliner
  cannot trigger pathological parser work.
- [ ] Do not adopt runtime grammar extensions for connector SQL without a real
  syntax requirement. Plain, quoted DuckSQL remains the smallest compatibility
  surface.
- [ ] Include DuckDB's final lambda-syntax breaking change in the release scan;
  no connector-generated lambda is expected today.

**Exit:** all SQL emitted by Trino and Doris has the same bound meaning and
result under the old and new parser.

## DB20-06: storage format v2

October-31 round 3: [storage/write-safety proof and runbook](../../trino-duckbridge/dev-docs/archive/REPORT-storage-trigger-safety-2026-10-31.md)
uses isolated 1.5.6/2.0.0 child JVMs and disposable files. Old-format read-only opens
are byte-identical; supported writes/checkpoints preserve old readability. New default
2.0 files reject old readers; explicit compatibility and copy-upgrade/backup rollback
are verified. This is not corruption/WAL crash recovery or arbitrary new-feature compatibility.

**Why:** DuckDB 2.0 changes the default on-disk storage format. Trino embedded
deployments can open operator-owned database files directly; an accidental write
may affect rollback even when a read query was the intent. Remote storage is the
server operator's responsibility but needs the same compatibility statement.

- [x] Build fixtures for: 1.5-created read-only in 2.0, 1.5-created then written/
  checkpointed in 2.0, 2.0-created reopened in 2.0, and attempted 1.5 rollback.
- [x] Include wide tables, explicit ART indexes, committed deletes and full ordered
  value digests in the isolated old/new storage fixtures.
- [ ] Add codec-specific `DICT_FSST` coverage, corruption/WAL/crash recovery and
  concurrent cross-process open/write cases; general fixture success does not prove them.
- [x] Determine exactly which operation upgrades a 1.5 file and whether a 2.0
  compatibility setting can create/write an old-format file. Document observed
  behavior; do not assume opening alone is harmless.
- [x] Add a deployment runbook: backup, stop writers, upgrade, validate, and the
  point after which binary rollback requires restoring the backup.
- [x] Keep a persistent-file compatibility test in CI or a release probe rather
  than testing only `jdbc:duckdb:` in-memory databases.

**Exit:** operators know whether rollback is possible before the connector opens
a production file, and the test suite pins that claim.

## DB20-07: chunked Java query results

October-31 round 4: [embedded scalar benchmarks](../../trino-duckbridge/dev-docs/archive/REPORT-scan-benchmarks-2026-10-31.md)
compare five public-API modes on six workloads, with two fresh forks/cell and full
value/NULL/bind/Page checks. Chunked has modest numeric gains; Arrow wins on text;
streaming is not uniformly faster. **Defaults remain unchanged.** This is a test-only,
API-to-Page measured non-adoption, not a completed base-jdbc-integrated production
page source or full type/cancellation/remote qualification; those wider items stay open.

**Why:** `DuckDBChunkedResult` exposes lazy 2,048-row column vectors from a
`DuckDBPreparedStatement`. It avoids JDBC's row/cell loop and, unlike the default
non-streaming `ResultSet`, does not materialize the whole native result. It is not
a universal replacement here.

Current fit:

| Path | Directly usable? | Notes |
|---|---|---|
| Trino embedded, default JDBC | yes, with a custom page source | Current production path delegates to base-jdbc row decoding. The repo does not set `jdbc_stream_results`; verify actual driver behavior under Trino. |
| Trino embedded Arrow | not obviously beneficial | Already lazy/columnar and supports nested Arrow values. Benchmark rather than stack APIs. |
| Trino Quack/Arrow | only in the local client, not across Quack by itself | Existing Arrow path is the stronger baseline. |
| Trino direct `quack-jdbc` | no | Different driver/API. |
| Doris `JdbcJniScanner` over `quack-jdbc` | no | Would require driver/scanner work or the separate ADBC route. |

- [x] Build and benchmark a test-only public-API scalar reader with common prepared
  SQL, binds, Pages, NULL/value checks and independent JDK expectations.
- [ ] If production adoption is requested, integrate the prototype using the same
  base-jdbc-built prepared SQL and bindings as `DuckBridgeArrowPageSource`.
- [x] Benchmark five reader modes on small/large/selective numeric, Unicode text,
  wide/nullable and early-close workloads in two fresh forks per cell. Retain
  wall/caller-CPU/allocation and separately sampled memory evidence; no universal win.
- [ ] Before production adoption, extend to cancellation workloads, instrument
  JNI transitions and complete native-peak/Trino-memory qualification. Existing
  phase samples are not complete native-memory peaks or instrumented JNI counts.
- [ ] Verify nulls, unsigned/wide integers, decimal, date/time/timestamptz, BLOB,
  UUID, metadata, empty projection, and errors at chunk boundaries.
- [ ] Preserve backpressure, early close, cancellation, statement lifetime, and
  Trino memory accounting. Laziness alone is not sufficient.
- [ ] Include concurrent parent-connection close. DuckDB Java main added an
  explicit post-fetch check after observing that native `duckdb_fetch_chunk`
  otherwise returned end-of-stream for some close races (`b1617f3b`).
- [ ] Do not replace Arrow or the JDBC array path while the chunk reader lacks
  `LIST`/`STRUCT` and other required composites. Revisit when nested vectors and
  direct UTF-8 byte access are released.
- [ ] Track an equivalent chunk API in `quack-jdbc` separately if remote row
  decode becomes the measured bottleneck.

**Exit:** adopt only on paths where it wins and covers the path's full declared
type contract; otherwise retain it as a measured non-choice.

## DB20-08: `VARIANT`, JSON, and nested transport

October-30 round 2: [temporary boundary policy](../../trino-duckbridge/dev-docs/REPORT-boundary-type-policy-2026-10-30.md)
guards unqualified VARIANT/TUPLE/SQLNULL and unsafe composite auto-VARCHAR conversion.
Native JDBC TUPLE-list getString crashes are retained as isolated child-JVM negative
controls. Explicit source CASTs are a visible text choice, not typed support. Qualified
arrays and documented Doris text mappings remain unchanged. The requested post-round
revisit of project TODO **5bdd9ba4** is complete: the isolated native crash still
reproduces. Keep the guard and underlying type/getter TODO open; do not repeat the
revisit as if pending or treat the guard as the full typed implementation below.

**Why:** 2.0 makes shredded `VARIANT` end-to-end, including Parquet reads/writes,
scan extraction pushdown, and `variant_*` functions. DuckDB also expects regular
`JSON` to use `VARIANT` internally later. Today Trino's JDBC mapping has no
explicit JSON/VARIANT arm, its Arrow converter handles JSON text and nested Arrow,
and Doris maps JSON/STRUCT/MAP to `STRING` while unknown `VARIANT` fails loud.

- [ ] Probe `DatabaseMetaData`, `ResultSetMetaData`, `getObject`/`getString`,
  Arrow schema/vectors, and Quack wire values for `JSON` and `VARIANT` on all
  transport paths.
- [ ] Add an explicit `VARIANT` policy per host. Candidates must be named
  truthfully: Trino JSON/ROW/MAP only where shape/semantics are stable, or a text
  representation; Doris STRING/JSON only after round-trip proof. Unknown must
  continue to fail loud.
- [ ] Test mixed row shapes, nested lists/structs/maps, JSON null vs SQL `NULL`,
  numeric width/scale, duplicate keys, key order, invalid UTF-8, and large values.
- [ ] Pin behavior before and after any future JSON-on-VARIANT storage change so a
  DuckDB point upgrade cannot silently alter host-visible values.
- [ ] Test `VARIANT` extraction predicates as ordinary DuckDB execution first.
  Do not advertise host-expression pushdown until each host mapping has semantic
  parity and transport coverage.

Relevant code: Trino `DuckBridgeClient.toColumnMapping`,
`DuckBridgeArrowToPageConverter`, Doris `DuckDbToDorisTypeMapper`, and the Doris
`DuckDbTypeHandler` patch.

## DB20-09: nested schemas

October-30 round 1: [namespace qualification](../../trino-duckbridge/dev-docs/archive/REPORT-namespace-routing-2026-10-30.md)
proves metadata ancestry loss in both pinned drivers, adds explicit nested-catalog refusal,
and fixes exact metadata pattern escaping/current-catalog scope. Flat dotted/quoted/keyword
names are exercised on all four Trino paths and Doris. Full nested addressability remains
a separate path-aware namespace design; no ambiguous encoding or silent flattening is enabled.

**Why:** DuckDB 2.0 permits schemas inside schemas. Both connectors currently
model DuckDB's traditional catalog/schema/table namespace and quote a schema name
as one component. A nested schema can become inaccessible or ambiguously
flattened even if no query uses the new DDL.

- [x] Probe JDBC and Quack metadata for nested paths: `TABLE_CAT`,
  `TABLE_SCHEM`, `TABLE_NAME`, search patterns, and the SQL needed to reference it.
- [ ] Account for Quack's current representation: schemas from the server's
  default catalog stay at the attached root, while another remote catalog is
  exposed as a parent schema. Same-named schemas are distinguished by OID, not
  their leaf name (`duckdb-quack` #261).
- [ ] Test the hard naming limit found in Quack's own suite: a nested table is
  referenced as `s1.child.table` only after making the attached catalog current;
  adding an explicit catalog would need a fourth name part. Current Trino/Doris
  SQL always assumes a catalog/schema/table triple and cannot blindly reuse this.
- [x] Explicitly reject normal host metadata for a current catalog containing true
  nested schemas in both connectors. Do not silently merge distinct schema paths.
- [x] Test exact flat dotted/quoted/keyword/wildcard names, current-catalog scope and
  Trino create/write/rename/drop on all four paths, plus Doris metadata/query routing.
- [ ] Design full path-aware nested-schema addressability before replacing the guard;
  flat literal dots must remain distinct from actual ancestry.
- [ ] Keep Doris's current single-user-catalog rule separate from nested-schema
  mapping; they are different namespace dimensions.

**Exit:** nested objects are either addressable without ambiguity or rejected
with an actionable message.

## DB20-10: optimizer, pruning, async I/O, and execution changes

**Why:** expanded zone-map/Bloom pruning (including `IN` and function
predicates), partition-aware planning, partial aggregates below joins, aggregate
spilling, rewritten recursive CTEs, and async file I/O can change which physical
paths exercise pushed SQL. They should be transparent, but a pushdown connector
must prove row equality rather than treating faster execution as sufficient.

- [ ] Re-run pushdown-vs-host truth-set canaries for decimals, `IN`, string
  functions, date/time functions, nulls, and row-group boundaries.
- [ ] Include the newly optimized exact shapes, not just generic columns:
  cross-column comparisons, date/time conversions, `length`/`char_length`,
  `lower`/`upper`, `contains`, prefixes/suffixes, no-op `replace`, arithmetic
  filters, nested validity, LIST/MAP extraction, and `list_contains`.
- [ ] Include Parquet, DuckDB storage, Lance, Vortex, local files, and object
  storage paths actually shipped by Duckbridge.
- [ ] Test cancellation/early close while async reads and writes are outstanding;
  assert connections, Arrow readers, chunks, and server queries are released.
- [ ] Stress memory limits with aggregate spilling and large streamed results.
- [ ] Benchmark the new `MMAP`/`DIRECT_IO` modes before exposing or recommending
  either; do not change the connector default merely because the modes exist.
- [ ] Re-measure Quack concurrency and long-lived query behavior with 2.0 metrics.
- [ ] Capture `EXPLAIN`/profile evidence where a new prune is expected, but use
  returned rows as the correctness oracle.

**Exit:** identical rows with and without connector pushdown, plus no leaked
queries/resources under cancellation.

## DB20-11: triggers and connector writes

October-31 round 3: [triggered-write qualification](../../trino-duckbridge/dev-docs/archive/REPORT-storage-trigger-safety-2026-10-31.md)
checks exact Trino INSERT/UPDATE/DELETE counts and target/audit rows, native row/statement
triggers, BEFORE/AFTER timing, transition tables, atomic failures and explicit JDBC rollback.
Released Quack 0.12.0 fixes the count classification in the driver itself; no consumer
count inference or replay is used. BEFORE ROW, row-level UPDATE and ROW-trigger RETURNING
remain engine-unsupported and are checked for explicit rejection without mutation.

**Why:** Trino's embedded/direct-JDBC path supports DDL and writes. DuckDB 2.0
adds row/statement, before/after triggers and transition tables. Doris Duckbridge
is read-only, but a remote server may still expose triggered tables.

- [x] Smoke-test Trino insert/update/delete against the supported BEFORE/AFTER
  statement and AFTER ROW trigger shapes, including failures, exact affected
  counts and audit effects. Unsupported engine combinations reject without mutation.
- [x] Verify transaction/rollback behavior and affected-row reporting through
  embedded and Quack transports.
- [x] Confirm triggers do not appear as tables and staged Trino insertion fires the
  target trigger once per affected source row.
- [ ] Extend to arbitrary temporary-table/rename-based writes and trigger shapes
  beyond the proved fixtures; no general trigger lifecycle support is claimed.
- [x] Keep trigger management as native DuckDB behavior, not a new connector API.

## DB20-12: Java table functions

**Why:** the Java driver can register a pure-Java source with bind/init/apply
callbacks and fill DuckDB vectors in 2,048-row chunks. This could move data from
a JVM-only source into embedded DuckDB without staging, but Duckbridge currently
moves data in the opposite direction.

- [ ] Require a concrete use case before adding infrastructure: for example, a
  host-side dynamic-filter build set or JVM-only source that DuckDB must join.
- [ ] Prototype only on an embedded DuckDB Java connection. Java table functions
  cannot currently be packaged as DuckDB extensions and therefore do not appear
  inside a remote Quack server merely because the client uses Java.
- [ ] Prove callback/classloader safety, bind-time side effects (`EXPLAIN` also
  binds), cancellation, resource cleanup, exception propagation, and connection
  lifetime.
- [ ] Require `DuckDBTableFunctionState` for owned bind/global/local resources.
  Cleanup may occur on native worker threads, local states close concurrently,
  ordering is unspecified, close failures are swallowed, and re-entering the
  same connection is forbidden (`duckdb-java` #803).
- [ ] Decide parallelism explicitly (`initLocal`/`setMaxThreads`) and keep remote
  SDK cursors backpressured.
- [ ] Benchmark new batched `setStrings`/`setStringUtf8Batch` writes rather than
  paying one JNI call per VARCHAR (`duckdb-java` #813).
- [ ] Do not flatten or stringify composite values merely to bypass the current
  Java vector API's lack of `LIST`/`STRUCT`; wait or define a truthful scalar
  contract.

**Exit:** either a measured design tied to a real flow, or a documented non-use.
This is an opportunity, not an upgrade requirement.

## DB20-13: new SQL and function intake

October-29 follow-up: [JSON INSERT/REMOVE](../../doris-duckbridge/dev-docs/archive/REPORT-json-mutations-2026-10-29.md)
has 735 typed native observations and remains unpushed due to concrete value/NULL/path/error
differences. [Trino REGEXP_COUNT](../../trino-duckbridge/dev-docs/archive/REPORT-regexp-count-qualification-2026-10-29.md)
now admits only the proved nonempty consuming-ASCII grammar; 728 typed native comparisons plus
embedded/Quack plan and row-set checks pass. OVERLAY is a test-only prototype, blocked by the
reported Doris JNI UTF-8 bug; it is not in the production allowlist.

**Why:** 2.0 adds functions and syntax, but availability in DuckDB is not evidence
of Trino/Doris parity.

October-28 first-pass inventory and 56 typed JSON comparisons are retained in
[the intake report](../../doris-duckbridge/dev-docs/archive/REPORT-duckdb2-function-intake-2026-10-28.md).
It found duplicate-key and invalid-JSON expression differences; no new pushdown was enabled
by that first pass. Its stale sibling catalog was superseded by the published intake below;
the broader semantic matrix remains separate from completed catalog refresh work.

Update: [published brikk-sql 0.21.0 intake](../../doris-duckbridge/dev-docs/archive/REPORT-published-function-intake-2026-10-28.md)
now compares same-policy 1.5.6/2.0 function/type contracts against a live JDBC capture,
and tests actual generated SQL on exact-pin Doris and Trino 483. Java-17 tooling is isolated
from plugin runtimes. The refreshed inventory work is complete; generic JSON, OVERLAY and
regex lowerings still have concrete value/error/type blockers, so no new pushdown is enabled.

October-31 REGEXP_COUNT closure: [producer handoff](../../trino-duckbridge/dev-docs/archive/HANDOFF-brikk-sql-regexp-count-2026-10-31.md)
assigns reusable translation qualification to brikk-sql; DuckBridge's independent
grammar guard and final pushdown/fallback enforcement are complete for the requested
scope. Broader grammar and any future producer intake require a separate request;
there is no pending local regex implementation or automatic release adoption.

- [x] Inventory `json_set`, `json_insert`, `json_replace`, `json_remove`,
  `json_merge_patch_diff`, `json_deep_merge`, `json_normalize`, and
  `json_strip_nulls` for the planned DuckDB-namespaced Trino function surface.
- [ ] For any host-equivalent function, record SQL `NULL` vs JSON `null`, key
  order, duplicate-key, path, invalid-input, and return-type semantics before
  allowing predicate pushdown.
- [ ] Keep reconciliation/mutation functions unpushed by default. They are useful
  projection features, not automatically safe predicate aliases.
- [ ] Review `NEAREST`, DML-in-CTE, variables, JSON mutation, `FETCH FIRST`,
  `OVERLAY`, `UNNEST` in `GROUP BY`, recursive `USING KEY`, and defined
  multi-match `MERGE`/`UPDATE FROM` behavior. Record concrete connector use; do
  not add syntax for release-completeness.
- [ ] Expression statements, external-resource syntax, and parser-extension
  hooks need no connector action today because Duckbridge emits ordinary SQL.
- [ ] `COPY TO ... PARTITION BY/ORDER BY` is relevant only if a future write/export
  path emits `COPY`; keep it out of the read-path upgrade.

## DB20-14: extension distribution and operations

**Why:** 2.0 plans trusted custom extension repositories, a stable ABI, and better
server metrics/logging. These can simplify parity-extension distribution and
Quack operations, but they are security/operations choices, not automatic code
changes.

- [ ] Compare the existing signed community-extension flow with a Brikk-owned
  repository: key pinning/rotation, offline installs, mirrors, auditability, and
  release latency for exact DuckDB versions.
- [ ] Do not enable arbitrary extension repositories from connector-supplied SQL.
  Repository trust remains an operator/server policy.
- [ ] Add server version, Quack version, loaded parity-extension version, active
  query count, bytes/chunks returned, cancellation, and failure class to the
  upgrade probe/operational dashboard where the new metrics expose them.
- [ ] Revisit binary bundling only after DB20-02 proves the stable ABI across real
  releases and platforms.

## DB20-15: boundary type and precision changes

Quack-0.11 update: [qualified nanos paths](../../trino-duckbridge/dev-docs/archive/REPORT-quack-0.11-nanos-2026-10-29.md)
now include Trino scalar TIMESTAMP_NS/TIME_NS on all four scan paths, time-nanos lists on all
four, timestamp-nanos lists on Arrow/Quack, and zoned nanos on Arrow/Quack. Native JDBC's
legacy timestamp-list getter and lossy historical zoned-nanos text remain refused. Nanos
predicates remain local; this is not generic date-function pushdown qualification. Doris's
native six-digit ceiling and packed-TIMETZ refusal are unchanged.

October-29 [boundary capture](../../doris-duckbridge/dev-docs/REPORT-boundaries-lifecycle-2026-10-29.md)
records actual JDBC/Quack NULL/CTAS/nested/temporal metadata and getters. Its then-pending
Trino nanos mappings were subsequently qualified by the report above; the blanket
driver-truncation/host-unsupported claim is obsolete. Doris untyped NULL errors are
actionable; packed TIMETZ is rejected before falsely advertising time text. Remaining
carrier refusals, broader temporal extremes and full host support stay separate.

**Why:** several type-system changes are not headline features but cross every
JDBC/Arrow/Quack boundary we own.

DuckDB #23017 stops coercing an untyped `NULL` result or table column to
`INTEGER`; it now remains logical type `SQLNULL` by default. Arrow and Parquet
have native null types, but our Trino JDBC mapper, Trino Arrow converter, Doris
type-string mapper, Doris BE handler, and `quack-jdbc` metadata were all written
against the old boundary behavior.

- [ ] Probe `SELECT NULL`, `VALUES (NULL)`, CTAS-from-NULL, a typed NULL, all-NULL
  arrays/struct fields, and a pass-through query whose computed column is NULL.
- [ ] Record `DATA_TYPE`, `TYPE_NAME`, class name, `getObject`/`getString`, Arrow
  vector, and Quack logical type for each path.
- [x] Add clear SQLNULL unsupported guards in both hosts. Do not let
  `Types.OTHER` fall into the array-name special case or an accidental VARCHAR
  conversion. This is explicit rejection, not typed SQLNULL support.
- [ ] Use `legacy_disable_null_type=true` only as a differential diagnostic. Do
  not make a global legacy setting the permanent substitute for type support.

DuckDB #22489 changes timestamp internals and downcast rounding; #22412 adds
`TIMESTAMPTZ_NS`. Trino now dispatches by exact native type name before JDBC type
code, with explicit seconds/millis/micros/nanos and zoned mappings on qualified
carriers. Doris's six-digit precision ceiling remains a separate host limitation.

- [ ] Capture exact JDBC and Quack type names/codes for `TIMESTAMP_S`,
  `TIMESTAMP_MS`, `TIMESTAMP`, `TIMESTAMP_NS`, `TIMESTAMPTZ`,
  `TIMESTAMPTZ_NS`, `TIME_NS`, and `TIMETZ`.
- [ ] Test positive and negative sub-microsecond values, pre-epoch instants,
  infinities, overflow, DST gaps/overlaps, and nanos-to-micros rounding.
- [ ] Decide whether each host preserves nanos, rounds with a documented mode, or
  rejects the type. Never silently label rounded nanos as lossless.
- [ ] Re-run JSON schema inference: ISO-8601 strings with offsets can now infer as
  `TIMESTAMPTZ` (DuckDB #24171), changing metadata without table DDL.
- [ ] Confirm `preserve_identifier_case`'s new VARCHAR three-state setting does
  not break connection properties or settings probes. The default remains
  `preserve_case` and quoted identifiers remain exact, but metadata reports a
  different setting type/value (DuckDB #24375).

**Exit:** every new logical type is faithfully mapped, intentionally degraded
with a named contract, or rejected before scan execution.

## DB20-16: transactions, retries, and prepared statements

October-30 update: [prepared-schema/catalog retry proof](../../trino-duckbridge/dev-docs/archive/REPORT-prepared-catalog-lifecycle-2026-10-30.md)
passes external DDL/metadata refresh and parameter-error recovery on native/Quack JDBC,
plus actual held catalog-write conflicts on embedded/direct-Quack/native-wrapper paths.
[Arrow lifecycle and idle cleanup](../../trino-duckbridge/dev-docs/REPORT-lifecycle-qualification-2026-10-29.md)
also pass their independent checks, including the fixed lazy-execute cancellation race.
The earlier CANCEL-deadline deferral was resolved by released Quack JDBC **0.11.1**;
current [0.12.0 qualification](../../trino-duckbridge/dev-docs/archive/REPORT-quack-0.12-release-2026-10-31.md)
retains strict healthy-CANCEL/57014, partition-CANCEL and bounded DISCONNECT gates,
plus live Java-17 scanner verification. Autonomous orphan-session reaping remains an
explicit **red acceptance task** against core f96420ed46. The
[HTTP investigation](../../trino-duckbridge/dev-docs/REPORT-http-response-truncation-2026-10-30.md)
reproduces pre-cut truncations and traces receive-window stalls on Docker's published-port
loopback leg, independently reproduced with raw HTTP and no SQL engine/driver/proxy.
The mapped-port streaming gate remains intermittently red; direct-bridge controls are
diagnosis only. No network mitigation, timeout relaxation or underlying fix is claimed.

Quack JDBC 0.11 passes real server query cancellation (SQLState57014), network-cut streaming
failure and independently bounded DISCONNECT acceptance tasks. The exact test contracts,
the corrected compressed-sequence probe diagnosis and the remaining race scope are in
[the 0.11 report](../../trino-duckbridge/dev-docs/archive/REPORT-quack-0.11-nanos-2026-10-29.md).

October-29 [pool subgate](../../doris-duckbridge/dev-docs/REPORT-boundaries-lifecycle-2026-10-29.md):
real Quack 0.10/Hikari 6.0 single-slot reuse passes parser/binder/execution-error recovery,
aborted-transaction rollback on pool return, prepared rebinding and early result close.
Later reports above qualify specific catalog-conflict, schema-change, Arrow and
CANCEL contracts; the full network/resource campaign is still not implied.

**Why:** DuckDB #22674 makes every parser/binder/execution error invalidate an
explicit transaction by default, and #24273 reimplements C++ prepared statements
through SQL `PREPARE`/`EXECUTE`/`DEALLOCATE`. Both changes affect lifecycle and
error recovery even when query results are unchanged.

- [x] Exercise production `DuckDbCatalogWriteRetry` under actual held/committed
  catalog conflicts on three transports; real parser/binder/execution errors do
  not retry. Autocommit recovery is proved; arbitrary write retry is not enabled.
- [ ] Verify Trino connection-pool and Doris Hikari return paths never pool an
  aborted transaction. The next borrower must get a clean session or a hard
  failure, not delayed "transaction is aborted" errors.
- [ ] Test multi-statement init and cleanup sequences: extension load, probe,
  timezone setup, tuning, secret creation, attach/connect, query, and detach.
- [x] Test prepared SELECT/SELECT * across external ADD COLUMN and drop/recreate
  with type changes, typed NULL/integer rebinds and conversion-error recovery on
  native/Quack JDBC. A failed native statement closes; fresh-statement recovery
  is distinct from Quack's same-statement reuse.
- [ ] Extend prepared reuse to stale statistics and all combined parameter,
  cancellation, result/statement/connection-close and concurrent-close races.
- [ ] Confirm prepared-statement destruction/deallocation does not keep a
  connection/database alive and that closing the connection first is safe.
- [ ] Pin when Quack starts executing work. Its current remote table-function path
  executes the server query during PREPARE/bind so it can return a schema; even
  `EXPLAIN` of pushed remote DDL can have side effects in upstream tests. Do not
  expose side-effecting prepared/EXPLAIN behavior through connector metadata.
- [ ] Test the final scope of `max_execution_time` (DuckDB #23018) on embedded and
  Quack sessions. Distinguish a per-query timeout from a server policy affecting
  every client, and map timeout errors to cancellation rather than corruption.
- [ ] Keep `default_transaction_invalidation_policy` at its safe default unless a
  documented compatibility requirement proves otherwise.

**Exit:** every retry starts from a known transaction state, prepared resources
are deterministic, and no metadata/EXPLAIN operation performs an unexpected
remote write.

## DB20-17: arithmetic and error-semantics deltas

**Why:** DuckDB main contains user-visible semantic changes in functions we push.
These need cross-engine fixtures, not only successful-query smoke tests.

### Resolved zero-divisor pushdown hole; exceptional-case follow-up

The original 1.5.5 `mod/2` finding was fixed by EV-A12. The current emission
catalog requires a **nonzero integer constant divisor**; zero, NULL and dynamic
divisors do not emit. `$divide` and `$modulo` operators also remain local.
The fix does not depend on DuckDB 2.0's division-by-zero default or require a
new setting probe while that conservative gate remains in force.

- [x] Guard `mod/2` against zero/dynamic divisors. `SemanticFixtures` retains the
  zero-divisor non-push assertion; row-set tests prove a nonzero-divisor case pushes.
- [x] Keep integer `/` and `%` operators local, with exact row/plan checks and
  end-to-end division/modulo-by-zero error regressions.
- [x] Add explicit function-call `mod(id,0)` and `mod(id,divisor)` local-plan/error
  integration coverage in `TestDuckBridgeArithmeticPushdownParity`. Both retain
  `filterPredicate` and raise Trino's division-by-zero error. Targeted class:
  seven tests passed, no failures/errors/skips; see the reconciliation report.
- [ ] If runtime-divisor pushdown is proposed later, prove exceptional inputs and
  `null_on_division_by_zero` settings first; the engine default is not sufficient.

### Other arithmetic changes

- [x] Audit bare `power/2` after DuckDB #22753: the
  [power edge report](../../trino-duckbridge/dev-docs/archive/REPORT-power-edge-audit-2026-10-31.md)
  records 713 input pairs and 14 actual production/local predicate plans/row sets.
  It finds eight scalar and eight predicate-shape differences, including finite
  rounding and NaN comparisons. Existing bare emission is **unsafe**, not qualified.
- [x] Apply the user-approved correctness fix for `power/2`: remove bare emission
  and retain native Trino evaluation, including SQL alias `pow`. The raw native
  counterexamples remain diagnostic evidence; production fallback must match exact
  Trino values and predicate row sets. Full native parity/comparison design is separate.
- [x] Run bounded decimal binding/cast probes: 22 DECIMAL(38,18) values, eight
  target shapes and 40 predicates. Value/NULL/CTAS round trips match; four plain
  narrowing CAST overflows change NUMERIC_VALUE_OUT_OF_RANGE to JDBC_ERROR.
- [x] Decide and apply the selected math/domain, temporal, DATE bind/text and
  CAST error-contract dispositions. DATE-input truncation and named-zone attachment
  are local; native Trino REAL membership and the four specific decimal-overflow
  categories have explicit accepted release limitations. See the current receipt.
- [x] Apply approved rare-case fixes for finite DATE write spelling/range and
  temporal argument-type/unit validation. Broader common-case restrictions held
  for user review, not silently applied; see the narrow-fix qualification.
- [ ] Extend decimal qualification beyond this corpus to every enabled precision,
  scale, literal-promotion and remaining rounding/overflow shape.
- [x] Audit 50 LIKE predicates over 32 strings, including NUL, escapes, Unicode
  prefixes and the maximum scalar. All returned IDs match native Trino; eight
  filters actually disappear and 42 remain. No wider grammar is admitted.
- [ ] Keep regex operator `~` out of any future name-based mapping: its default
  changed from full match to PostgreSQL partial match in DuckDB #23432. Our
  current function-based regex mappings need their own fixtures and are not
  evidence that the operator is safe.
- [ ] Compare error class/timing as well as rows for overflow, invalid regex
  replacements, invalid casts, and division errors. A pushed predicate that
  turns a host error into a filtered-out row is incorrect.

**Exit:** every pushed arithmetic/string operation has fixtures for exceptional
inputs and server settings that can alter its semantics.

## DB20-18: build system and CPU baseline

**Why:** the extension story changes below the stable-ABI headline. DuckDB main
requires C++17 (#21310), removed the amalgamation build (#22217), is revamping
extension build/patching, and currently builds x86 extensions for x86-64-v2 and
the CLI for x86-64-v3 (#24391).

- [ ] Update `extension-ci-tools` and the extension Makefile/CMake path before
  attempting local workarounds for removed amalgamation or changed templates.
- [ ] Confirm our vendored ICU/hash code and every native/Wasm target compile
  cleanly under the final C++17 toolchains and new vector/function APIs.
- [ ] Record CPU requirements for the DuckDB JDBC native library, standalone
  Quack CLI/server, community `trino_parity` binary, and our bundled binary
  separately. They may not share one baseline.
- [ ] Run parity binaries on the oldest x86 host class we claim to support. A
  load test on a modern CI runner does not detect an illegal-instruction crash.
- [ ] Decide whether to raise Duckbridge's minimum CPU, publish a generic build,
  or select artifacts by CPU capability. Ask before dropping old hardware;
  silent SIGILL is not an acceptable compatibility policy.
- [ ] Continue checking libc as well as CPU ISA. The existing Quack fixture
  already needs glibc 2.38 for one parity build; manylinux compatibility and
  x86-64-v2/v3 are independent constraints.

**Exit:** every distributed native artifact has an explicit compiler, libc,
architecture, and minimum-CPU contract verified on representative hosts.

## DB20-19: Quack connection and resource policy

**Why:** Quack mainline changed much more than `CONNECT`. Protocol v3 adds a
heartbeat lease; recent work adds bounded disconnect, indexed/acknowledged
read-ahead, producer buffers, optional reconnect result retention with TTL/row
caps, connection-scoped secrets, richer active-connection state, and several
race/cancellation fixes.

- [ ] Add a Quack protocol conformance suite shared by the DuckDB-extension client
  and `quack-jdbc`: handshake version range, heartbeat negotiation, batch index,
  cumulative ACK, total-batch terminal marker, error-after-first-batch,
  reconnect, cancellation, and disconnect.
- [ ] Define heartbeat policy for pooled clients. Upstream defaults to a 60-second
  requested lease and sends an idle heartbeat at roughly one third of the lease;
  test long idle pool entries, network partitions, server restart, and shutdown
  of the heartbeat thread on detach/close.
- [ ] Preserve bounded teardown: upstream caps the final best-effort DISCONNECT
  request at two seconds with retries disabled. Connector close/cancellation must
  not inherit an hour-long query timeout.
- [ ] Keep reconnect/result caching disabled until both peers implement ACKs and
  the memory policy is chosen. If enabled, set and monitor row caps, TTL, producer
  buffer bytes, target batch bytes, and prepare-inline rows; prove abandoned
  queries release ORDER BY/aggregate memory.
- [ ] Test LIMIT/early-close/cancel while the server producer is blocked on a full
  result buffer. Upstream added explicit abort paths to avoid deadlocking the
  next statement on the same connection.
- [ ] Use `quack_active_connections()` to assert no leaked active/idle sessions,
  cached rows, or stale query text after each connector lifecycle test. Include
  the active-connection snapshot race fixed by `duckdb-quack` #260.
- [ ] Evaluate `CREATE SECRET ... IN connection` and named/scoped Quack secrets to
  replace instance-global secret mutations where credentials are per client.
  Never move secrets to a broader scope for convenience.
- [ ] Verify HTTP retry policy explicitly. DuckDB core now refuses automatic
  retries for non-idempotent POSTs (#25047); Quack requests must not be duplicated
  blindly, but transient failures must surface as retryable/terminal in a way the
  connector can classify.
- [ ] Pin Quack settings and behavior by release. The extension has no independent
  stable tag in the scanned repository; it is built against moving DuckDB refs,
  so "DuckDB 2.0" alone is not enough provenance for a preview binary.

**Exit:** remote execution has bounded memory and teardown, deterministic lease
behavior, no duplicate non-idempotent requests, and no connection/result leaks.

## Final release gates

- [ ] Read DuckDB's final 2.0 release notes and breaking-change list; reconcile
  this preview-derived tracker, especially storage and lambda syntax.
- [ ] Complete DB20-01 through DB20-06, DB20-10/11, and DB20-15 through DB20-19
  for enabled production capabilities.
- [x] Run `./gradlew :trino-duckbridge:test` and the signed parity extension's native platform
  matrix against the final tag; v483-0.2.0 passed 642 connector tests plus four platform jobs.
- [x] With Doris baseline artifacts bootstrapped, run
  `./gradlew :doris-duckbridge:test` plus the compose smoke against the final
  Quack server and driver (API 26 / JNI 6, DuckDB 2.0.0 / driver 0.12.0: 90 tests and live smoke).
- [ ] Complete dedicated protocol-v3 conformance against both the DuckDB-extension client and
  released v3-selecting `quack-jdbc`; current connector regressions pass, but the full lease/resource
  campaign remains separate from ordinary DuckDB-2.0 support.
- [x] Preserve Doris's qualified API-24 and API-13 / 1.5.6 rollback while separately qualifying exact-pin
  master API 26 / JNI 6 on DuckDB 2.0.0. No published Doris-5.x rollout is claimed.
- [x] Update `README.md`, deployment examples, extension instructions, Docker pins and archived
  1.5.5 evidence together for the Trino lane.

## Existing canaries to extend

- Trino: `TestDuckBridgeQuackTransport`,
  `TestDuckBridgeQuackPassThroughQuery`, `TestDuckBridgeQuackArrowEngine`,
  `TestDuckBridgeArrowEngine`, `TestDuckBridgePushdown`,
  `TestDuckBridgeStringComparisonProbe`, `TestTrinoFunctionAliases`, and
  `TestDuckBridgeQuackArrayColumns`.
- Doris: `TestQuackJdbcMetadataProbe`, `TestDuckBridgeQueryOverQuack`,
  `TestDuckBridgeDorisMetadataOverQuack`,
  `TestDuckBridgeTimezonePushdownCanary`, and
  `TestDuckBridgeFunctionPushdownCanary`.
- Prior context: `trino-duckbridge/dev-docs/archive/TODO-duckdb-1.5.5-upgrade.md`,
  `trino-duckbridge/dev-docs/TODO-quack-protocol-v3.md`,
  `trino-duckbridge/dev-docs/TODO-datetime-timezones.md`, and Doris's
  `doris-duckbridge/dev-docs/REPORT-quack-jdbc-metadata-probe.md` /
  `doris-duckbridge/dev-docs/REPORT-doris-timezone-probe.md`.
