# TODO: DuckDB 2.0 readiness

**Status:** research and issue collection, 2026-08-28. DuckDB 2.0 is still a
preview targeted for fall 2026; preview APIs and the final breaking-change list
may move. This is a readiness tracker, not an instruction to upgrade production
before the release is cut.

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

Current pins are DuckDB/JDBC `1.5.5.0`, `quack-jdbc` `0.6.0`, and a parity
extension built against DuckDB `v1.5.5` (`gradle/libs.versions.toml` and the
`duckdb-trino-parity-extension` submodule).

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
| Quack wire protocol is now v3; our `quack-jdbc` is v1 | `duckdb-quack` PRs [#229](https://github.com/duckdb/duckdb-quack/pull/229), [#245](https://github.com/duckdb/duckdb-quack/pull/245); `QuackConstants.QUACK_VERSION = 1` in our fork | Direct remote Trino and all Doris scans need a real driver protocol port before 2.0. |
| Untyped `NULL` remains `SQLNULL` at result/table boundaries | DuckDB [#23017](https://github.com/duckdb/duckdb/pull/23017), `b0f224ed` | New JDBC/Arrow/Quack metadata and vector type; current host maps do not handle it explicitly. |
| Every error invalidates an explicit transaction by default | DuckDB [#22674](https://github.com/duckdb/duckdb/pull/22674), `a2044776` | Retry and pooled-connection code must rollback/reset or replace connections after failure. |
| Prepared statements now use SQL `PREPARE`/`EXECUTE`/`DEALLOCATE` internally | DuckDB [#24273](https://github.com/duckdb/duckdb/pull/24273), `b5e4f5be` | Re-test bindings, schema changes, close/cancel, and Quack execution timing. |
| Strong timestamp types plus `TIMESTAMPTZ_NS` | DuckDB [#22489](https://github.com/duckdb/duckdb/pull/22489), [#22412](https://github.com/duckdb/duckdb/pull/22412) | New type spelling/metadata and changed downcast rounding at our microsecond boundary. |
| Integer modulo/division by zero now throws by default | DuckDB [#25004](https://github.com/duckdb/duckdb/pull/25004), `5efbc8a5` | 2.0 aligns with Trino, but exposes an existing unsafe `mod/2` pushdown on the 1.5.5 lane. |
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

- [ ] Create a DuckDB-2.0 preview lane without replacing the 1.5.5 release lane.
- [ ] Pin and record all four artifacts independently: `duckdb_jdbc`, DuckDB CLI/
  server, Quack extension, and `quack-jdbc`.
- [ ] Treat `quack-jdbc` as a code blocker, not just a Maven-version lookup.
  Released `0.6.0` and current `0.7.0-SNAPSHOT` declare wire protocol v1;
  DuckDB 2.0 Quack declares v3. Port and release the driver before claiming any
  direct-Quack support.
- [ ] Implement/prove the v2/v3 protocol additions in `quack-jdbc`: indexed and
  acknowledged fetch batches, terminal batch counts, read-ahead ordering, the
  heartbeat request/lease handshake, and new error/cleanup behavior. Do not
  simply change the version constant.
- [ ] Re-vendor `duckdb-trino-parity-extension/duckdb` and its extension CI tools
  at refs intended for 2.0; do not infer one ref from the other.
- [ ] Exercise Trino embedded JDBC, Trino direct `quack-jdbc`, Trino embedded
  Arrow, Trino Quack/Arrow, Doris metadata-over-Quack, and Doris scan-over-Quack.
- [ ] Probe 1.5-client/2.0-server and 2.0-client/1.5-server Quack combinations.
  Support only combinations the protocol and parity-extension results prove;
  otherwise reject/document mixed versions clearly.
- [ ] Bump both Quack test-server Dockerfiles, CI extension fetch paths, bundled
  binaries, and compatibility documentation together.
- [ ] Independently assess `duckdb_jdbc:1.5.5.1` for the maintained 1.5 lane; it
  fixes a full-materialized-result leak when callers use `execute()` followed by
  `getResultSet()`.

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
build system, and removal of the amalgamation build. Expect a source port even if
the entrypoint itself still exists.

- [ ] First make the existing implementation compile and pass unchanged in
  meaning against 2.0. Do not combine semantic changes with the mechanical port.
- [ ] Build/load/test the extension on every shipped native platform and Wasm
  target. Confirm `trino_meta()` reports exactly the expected ten functions.
- [ ] Confirm whether the new stable API covers vectorized scalar functions,
  BLOB/VARCHAR access, exception propagation, and a zero-argument metadata table
  function well enough to replace the internal APIs.
- [ ] If table macros are not stable-API-covered, consider replacing only
  `trino_meta()` with a real table function. Do not retain an internal API solely
  to preserve its implementation as a macro.
- [ ] Port to the stable ABI as a separate follow-up and prove whether one binary
  really loads across later DuckDB versions before changing packaging policy.
- [ ] Keep exact-version binaries until that cross-version proof exists.
- [ ] Decide whether extension-schema APIs change registration/search-path
  behavior. Keep `trino_*` and `trino_meta()` unqualified only if loading the
  extension normally and under an alias resolves them identically.

**Exit for 2.0:** a freshly built 2.0 binary loads locally and server-side and
passes the connector's metadata-lockstep and semantic fixtures. Stable-ABI
migration is desirable but not a reason to delay a correct version-specific
build.

## DB20-03: ICU removal and parity semantics

**Why:** DuckDB 2.0 removes the ICU *library*. Its still-named `icu` extension
implements timezones, calendars, and collations itself from IANA and native
DuckDB code. Our parity extension separately vendors an old minimal ICU snapshot
because Trino's Java semantics differ from DuckDB's built-ins. DuckDB's removal
does not prove that we can remove our copy.

- [ ] Keep vendored ICU for the first 2.0-compatible parity build unless the
  existing build becomes impossible. A switch to DuckDB's native Unicode code is
  a semantic change and needs separate proof.
- [ ] Re-run every current smoking gun: dotted-I lowercasing, sharp-S uppercasing,
  combining-mark and ZWJ reversal, Java-whitespace trim, and NFC normalization.
- [ ] Add a generated Unicode corpus comparing `trino_lower`, `trino_upper`, trim,
  and normalize directly with Trino/Java, not merely with DuckDB 1.5.
- [ ] Record the provenance/version/security-maintenance plan for the now
  independently vendored ICU sources. They can no longer be described as a copy
  of DuckDB's current bundled implementation after 2.0.
- [ ] Decide deliberately whether to stay on the 1.5.5-vendored ICU snapshot or
  refresh it. Main passed through ICU 77.1 and 78.3 before removing the library;
  copying either update changes Unicode data and requires the same Trino corpus
  proof as replacing ICU entirely.
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

- [ ] Qualify `quack-jdbc` 0.6.0 (or its 2.0-compatible successor) against the 2.0
  server: metadata, prepared parameters, cancellation, errors, auth/TLS, arrays,
  wide integers, timestamps, JSON, and concurrent result streams.
- [ ] Block the direct-Quack test lanes until a protocol-v3 driver exists. The
  current protocol-v1 driver cannot be made compatible by configuration.
- [ ] Re-run pool/churn and long-running-result tests; use the new Quack metrics
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

- [ ] Run the generated-SQL suites against a 2.0 preview. While still useful,
  also run 1.5.5 with the experimental PEG parser (`CALL enable_peg_parser()`).
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

**Why:** DuckDB 2.0 changes the default on-disk storage format. Trino embedded
deployments can open operator-owned database files directly; an accidental write
may affect rollback even when a read query was the intent. Remote storage is the
server operator's responsibility but needs the same compatibility statement.

- [ ] Build fixtures for: 1.5-created read-only in 2.0, 1.5-created then written/
  checkpointed in 2.0, 2.0-created reopened in 2.0, and attempted 1.5 rollback.
- [ ] Include wide tables, ART indexes, deletes, `DICT_FSST` strings, corruption
  detection, and concurrent open/write cases called out by the new format.
- [ ] Determine exactly which operation upgrades a 1.5 file and whether a 2.0
  compatibility setting can create/write an old-format file. Document observed
  behavior; do not assume opening alone is harmless.
- [ ] Add a deployment runbook: backup, stop writers, upgrade, validate, and the
  point after which binary rollback requires restoring the backup.
- [ ] Keep a persistent-file compatibility test in CI or a release probe rather
  than testing only `jdbc:duckdb:` in-memory databases.

**Exit:** operators know whether rollback is possible before the connector opens
a production file, and the test suite pins that claim.

## DB20-07: chunked Java query results

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

- [ ] Add a scalar-only prototype for Trino embedded using the same
  base-jdbc-built prepared SQL and bindings as `DuckBridgeArrowPageSource`.
- [ ] Benchmark row JDBC vs chunked vs Arrow on narrow/wide, primitive/text,
  selective/non-selective, small/large, and cancellation workloads. Measure
  native memory, Java heap, JNI calls, and conversion CPU.
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

**Why:** DuckDB 2.0 permits schemas inside schemas. Both connectors currently
model DuckDB's traditional catalog/schema/table namespace and quote a schema name
as one component. A nested schema can become inaccessible or ambiguously
flattened even if no query uses the new DDL.

- [ ] Probe JDBC and Quack metadata for `finance.reports.q3`: `TABLE_CAT`,
  `TABLE_SCHEM`, `TABLE_NAME`, search patterns, and the SQL needed to reference it.
- [ ] Account for Quack's current representation: schemas from the server's
  default catalog stay at the attached root, while another remote catalog is
  exposed as a parent schema. Same-named schemas are distinguished by OID, not
  their leaf name (`duckdb-quack` #261).
- [ ] Test the hard naming limit found in Quack's own suite: a nested table is
  referenced as `s1.child.table` only after making the attached catalog current;
  adding an explicit catalog would need a fourth name part. Current Trino/Doris
  SQL always assumes a catalog/schema/table triple and cannot blindly reuse this.
- [ ] Decide whether to represent, encode, flatten, or explicitly reject nested
  schemas in Trino and Doris. Do not silently merge distinct schema paths.
- [ ] Test listing, resolving, quoting, creating, renaming, and querying objects
  whose path components themselves contain dots and keyword names.
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

**Why:** Trino's embedded/direct-JDBC path supports DDL and writes. DuckDB 2.0
adds row/statement, before/after triggers and transition tables. Doris Duckbridge
is read-only, but a remote server may still expose triggered tables.

- [ ] Smoke-test Trino insert/update/delete against tables with BEFORE/AFTER and
  row/statement triggers, including trigger failures and audit-table side effects.
- [ ] Verify transaction/rollback behavior and affected-row reporting through
  embedded and Quack transports.
- [ ] Confirm metadata listing does not misclassify trigger-created objects and
  that trigger side effects do not break temporary-table/rename-based writes.
- [ ] Treat trigger semantics as DuckDB behavior; do not claim trigger pushdown or
  management support unless connector APIs explicitly expose it.

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

**Why:** 2.0 adds functions and syntax, but availability in DuckDB is not evidence
of Trino/Doris parity.

- [ ] Inventory `json_set`, `json_insert`, `json_replace`, `json_remove`,
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
- [ ] Add an explicit SQLNULL mapping or a clear unsupported error. Do not let
  `Types.OTHER` fall into the array-name special case or an accidental VARCHAR
  conversion.
- [ ] Use `legacy_disable_null_type=true` only as a differential diagnostic. Do
  not make a global legacy setting the permanent substitute for type support.

DuckDB #22489 changes timestamp internals and downcast rounding; #22412 adds
`TIMESTAMPTZ_NS`. The current Trino JDBC map assumes every JDBC `TIMESTAMP` is
microsecond `TIMESTAMP`, has no explicit `TIMESTAMPTZ` arm, while Doris recognizes
`TIMESTAMP_NS` but not a nanosecond-with-zone spelling.

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

**Why:** DuckDB #22674 makes every parser/binder/execution error invalidate an
explicit transaction by default, and #24273 reimplements C++ prepared statements
through SQL `PREPARE`/`EXECUTE`/`DEALLOCATE`. Both changes affect lifecycle and
error recovery even when query results are unchanged.

- [ ] Re-run `DuckDbCatalogWriteRetry` with catalog conflicts and injected
  parser/binder/execution errors. A fresh `Statement` on the same connection may
  no longer be enough; prove rollback/autocommit state or replace the connection.
- [ ] Verify Trino connection-pool and Doris Hikari return paths never pool an
  aborted transaction. The next borrower must get a clean session or a hard
  failure, not delayed "transaction is aborted" errors.
- [ ] Test multi-statement init and cleanup sequences: extension load, probe,
  timezone setup, tuning, secret creation, attach/connect, query, and detach.
- [ ] Test prepared SELECT reuse across table/schema changes, stale statistics,
  parameter type changes, cancellation, result close, statement close,
  connection close, and concurrent close.
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

### Urgent 1.5.5 issue: `mod/2`

The translator correctly refuses operator `$divide` and `$modulo`, because
DuckDB 1.5.5 returns `NULL` on a zero divisor while Trino throws. However,
`NameArity("mod", 2)` is separately emitted as a bare DuckDB function and its
fixture only covers `mod(7, 3)`. DuckDB's `mod` is an alias of `%`, so
`mod(x, 0)` has the same unsafe 1.5.5 behavior. DuckDB #25004 changes 2.0 to throw
by default, but that does not make the maintained 1.5 lane safe.

- [ ] Add a live `mod(x, 0)` cross-engine predicate fixture immediately.
- [ ] On 1.5.5, remove `mod/2` from the pushable catalog or back it with a parity
  function that throws. Do not wait for the 2.0 upgrade to close this hole.
- [ ] On 2.0, require `null_on_division_by_zero=false` before `mod/2` can push;
  an operator-set compatibility value of `true` restores the unsafe NULL result.
- [ ] Keep integer `/` unpushed after 2.0: DuckDB true division still differs
  from Trino truncation even though zero-divisor errors now align.

### Other arithmetic changes

- [ ] Re-prove bare `power/2` after DuckDB #22753 made `pow` IEEE-754 compliant.
  Include `-0.0`, odd/even exponents, negative base with fractional exponent,
  zero to negative powers, NaN, infinities, and overflow; compare exact Trino
  values and error/null behavior.
- [ ] Re-probe decimal literal binding and casts after unbound DECIMAL literals,
  automatic promotion past BIGINT, float-to-decimal precision fixes, and stricter
  rejection of non-integral `DECIMAL(p,s)` parameters.
- [ ] Re-probe pushed `LIKE` with embedded NUL/escape and non-ASCII prefix
  boundaries. Main fixed NUL escape handling and switched prefix pruning to a
  UTF-8 successor; a pruning optimization must not change the truth set.
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
- [ ] Run `./gradlew :trino-duckbridge:test` and the parity extension's full CI
  platform matrix against the final tag.
- [ ] With Doris baseline artifacts bootstrapped, run
  `./gradlew :doris-duckbridge:test` plus the compose smoke against the final
  Quack server and driver.
- [ ] Run the protocol-v3 conformance suite against both the DuckDB-extension
  client and the released `quack-jdbc`; direct Quack remains unsupported until
  this is green.
- [ ] Preserve the 1.5.5 lane until storage rollback, remote protocol support,
  and extension availability are documented and the 2.0 artifacts are published.
- [ ] Update `README.md`, deployment examples, extension install instructions,
  Docker pins, and the old `TODO-duckdb-1.5.5-upgrade.md` status together.

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
- Prior context: `trino-duckbridge/dev-docs/TODO-duckdb-1.5.5-upgrade.md`,
  `TODO-upstream-quack-jdbc.md`, `TODO-datetime-timezones.md`, and Doris's
  `REPORT-quack-jdbc-metadata-probe.md` / `REPORT-doris-timezone-probe.md`.
