# DuckBridge P3 — Quack data plane notes

## Transport matrix status

| Transport | connection-url | Driver | Data plane | Status |
|-----------|----------------|--------|-----------|--------|
| T1 embedded | `jdbc:duckdb:...` | `org.duckdb.DuckDBDriver` | JDBC record set (default) OR T2 Arrow | ✅ works |
| T3 remote (pass-through) | `jdbc:quack://host:port` | `com.gizmodata.quack.jdbc.sql.QuackDriver` | JDBC record set | ✅ works, integration-tested |
| T2 Arrow (DUCKDB_LOCAL) | `jdbc:duckdb:...` + `execution-engine=DUCKDB_LOCAL` | DuckDB JDBC | Arrow page source | ✅ works, integration-tested |
| T2 Arrow (QUACK) | — + `execution-engine=QUACK` | DuckDB JDBC + `quack` ext + `quack_query_by_name` | Arrow page source | ⚠️ ported + unit-tested, NOT live-wired (pool gate) |

Transport is chosen by the **connection-url prefix** (`DuckBridgeTransport`), URL-first per the plan.
Auth/tuning come from `duckbridge.quack.*` config (kept out of the copy-pasteable URL).

### Why quack-jdbc (T3) can't do the Arrow (T2) path

`quack-jdbc` is a pure-JVM pass-through driver with **no Arrow surface** (`javap` confirms its
`QuackResultSet` has no `arrowExportStream`). The T2 Arrow data plane needs
`org.duckdb.DuckDBResultSet.arrowExportStream`, which only the **DuckDB JDBC driver** exposes. So:

- T3 (quack-jdbc) is the interim remote default and uses base-jdbc's row-by-row `JdbcRecordSetProvider`.
- T2-over-Quack must use the DuckDB driver + `quack` DuckDB extension + `quack_query_by_name` wrapper
  (the DuckLake approach), NOT quack-jdbc. That's `QuackDuckBridgeExecutor`.

## Auth mechanism

`duckbridge.quack.token` → quack-jdbc `token` property; also `duckbridge.quack.token-env` (`tokenEnv`),
`duckbridge.quack.token-file` (`tokenFile`), `duckbridge.quack.tls` (`tls`). Host/port from the URL.
Token resolution order is the driver's (token → tokenEnv → tokenFile). Verified end-to-end against a
real server in `TestDuckBridgeQuackTransport`.

## Parity over Quack — VERIFIED

`DuckBridgeParity` is transport-aware:
- EMBEDDED: resolve a worker-local binary (bundled/extracted) and `LOAD '<local-path>'`.
- QUACK (T3): the worker can't extract for the remote server, so `duckbridge.parity-extension-path` is
  treated as a **server-side** path and LOADed over the pass-through connection; if unset, we probe
  `trino_meta()` assuming the server pre-loaded it. Either way, failure throws with server-side install
  instructions (fail loud).

`TestDuckBridgeQuackTransport.parityFunctionPushdownOverQuack` / `parityUnicodeCaseFoldOverQuack` run
GREEN against the real built extension LOADed server-side (`upper('straße')` → `STRASSE`,
`length` pushdown), so the full `trino_*` pushdown path works over Quack.

## SET TimeZone over Quack — VERIFIED

`SET TimeZone = '...'` executes fine over quack-jdbc (probed directly + the client's `getConnection`
applies it on every connection). No gating needed — the T3 path sets it like the embedded path.

## GLIBC gotcha (test-image base)

The DuckLake test fixture uses `debian:bookworm-slim` (GLIBC 2.36). The locally-built
`trino_parity.duckdb_extension` links against **GLIBC 2.38**, so it FAILS to LOAD on bookworm
("GLIBC_2.38 not found"). The duckbridge test image (`test/resources/docker/quack-server/Dockerfile`)
uses `debian:trixie-slim` (GLIBC 2.41) instead. If the extension's glibc floor rises again, bump the
base image. (In-process P2/P1 tests were unaffected — the host has GLIBC 2.43.)

## T2 status — what runs, what's gated

- **DUCKDB_LOCAL: LIVE.** `DuckBridgePageSourceProvider` overrides base-jdbc's
  `ConnectorPageSourceProvider` (via `OptionalBinder.setBinding`) and, for this engine, runs the split's
  SQL — rendered by base-jdbc's own `JdbcClient.buildSql` on the executor's connection (so
  projection/predicate/limit/parity pushdown are identical to the default path) — through
  `InProcessDuckBridgeExecutor`, decoding `arrowExportStream` batches via
  `DuckBridgeArrowToPageConverter`. Integration-tested end-to-end (`TestDuckBridgeArrowEngine`:
  full scan, projection, domain + parity pushdown, count(*)).
- **QUACK T2: WIRED (experimental), green end-to-end.** `execution-engine=QUACK` now diverts to the
  `QuackDuckBridgeExecutor` in `DuckBridgePageSourceProvider.createQuackArrowPageSource`. The startup
  rejection (`isExecutionEngineOperational`) is gone. Flow: `DuckBridgeClient.renderSplitQuery` produces
  base-jdbc's `PreparedQuery` (SQL + typed params) for the split → `QuackParameterInliner` renders the
  params to DuckDB literals (fail loud on any type it can't render exactly) → the executor ships the
  literal SQL server-side via `quack_query_by_name` and streams Arrow back through
  `DuckBridgeExecutorPageSource`. The executor's host/port come from the `jdbc:quack://host:port`
  connection-url (or explicit `duckbridge.quack.host/port`). Proven by `TestDuckBridgeQuackArrowEngine`
  (full scan, projection, count(*), bigint/varchar/date predicate inlining, and `upper→trino_upper`
  parity pushdown — all server-side over the wire). `QuackParameterInliner` inlines
  bigint/int/smallint/tinyint, boolean, double, real, decimal (short + long/Int128), date, timestamp
  (≤ µs, zone-free wall clock), and varchar/char; TIMESTAMP WITH TIME ZONE, sub-µs timestamps, and any
  other predicate-param type throw `NOT_SUPPORTED` (fall back to `execution-engine=JDBC`) rather than
  risk a wrong literal. JDBC stays the default.
  - **Inherited cautions — UNVERIFIED here, measure don't assume.** Both came from trino-ducklake's
    ATTACH-mode, per-file parallel-split usage, which duckbridge does not replicate:
    - *Server pool exhaustion — MEASURED, not a problem here.* DuckLake opened many concurrent server
      connections via per-file streaming scans (170+ files across 4 tables). duckbridge uses base-jdbc's
      DEFAULT single-split-per-query model (no custom split manager) → one server-side query per scan.
      `TestDuckBridgeQuackArrowEngine.perQueryChurnDoesNotAccumulateServerConnections` runs 20 sequential
      QUACK Arrow scans and then reads `quack_active_connections()`: the count settles to **1** (just the
      reading connection) — churn does not accumulate toward the ceiling. (Server pool is a hardcoded
      httplib `ThreadPool(128)` + `keep_alive_max_count(128)`, `keep_alive_timeout(10)`; unchanged on the
      1.5.5-bound branch and not operator-configurable via `quack_serve` — but we do not approach it.)
    - *"Multiple streaming scans ... not currently supported" (duckdb-quack#150).* This is an ATTACH-mode
      LOCAL-optimizer check (`quack_optimizer.cpp` throws when a local plan has >1 quack streaming scan or
      scan+insert against one connection). It does NOT fire in pushdown mode: T2 ships the whole query via
      `quack_query_by_name` (server-side, single local TF scan) and T3 quack-jdbc executes server-side with
      no local optimizer. base-jdbc also never pushes joins to the source. So this wall is off our read
      path; it would only surface under a direct ATTACH-and-query-remote-tables model (we don't use one).
      The `rctruta/sql-benchmarks-dagster#10` benchmark confirms: "quack attach" DNFs multi-table joins,
      "quack pushdown" (the wrapper) completes.
  - **`query()` pass-through over QUACK works, incl. joins.** The base-jdbc `query()` PTF (user-authored
    raw DuckDB SQL) flows through the same QUACK page source, so it is executed server-side via
    `quack_query_by_name`. `TestDuckBridgeQuackPassThroughQuery` proves: simple select, quote+unicode
    round-trip through the extra `quack_query_by_name` quoting layer, a DuckDB-native scalar (`printf`),
    and a **self-join** — the join *completes* because pushdown mode runs it server-side as a single
    local TF scan (exactly the attach-mode DNF from #150 that pushdown avoids).
    - *KNOWN LIMITATION — quack-jdbc metadata bug (UPSTREAM, not ours):* a LIST/array result column is
      described over quack-jdbc with **no element type**, so `DuckBridgeClient.toColumnMapping` can't
      resolve it and the query fails loud at analysis ("Unsupported type ... LIST"). Probed both drivers
      on the same queries (`arrayElementTypeDroppedByQuackJdbc_upstreamNotOurs`):
      | query | duckdb-jdbc `getColumnTypeName` | quack-jdbc |
      |---|---|---|
      | `CAST([1,2] AS BIGINT[])` | `BIGINT[]` | `LIST` |
      | `list(x)` / `list(x ORDER BY x)` | `INTEGER[]` | `LIST` |
      DuckDB's own JDBC preserves the element type; **quack-jdbc collapses every list/array to bare
      `LIST`**, dropping it. So this is a quack-jdbc fidelity bug to report upstream, not a duckbridge or
      DuckDB-core bug, and it affects declared ARRAY columns too (not just computed `list()`) *over the
      Quack transport*. Our fail-loud behavior is correct (never wrong data). Pinned by
      `bareListResultTypeFailsLoud`; `arrayElementTypeDroppedByQuackJdbc_upstreamNotOurs` is the
      watch-canary that flips green→red when upstream starts reporting `...[]`. (Declared ARRAY columns
      over the embedded/DUCKDB_LOCAL path are unaffected — that path uses duckdb-jdbc, which is faithful.)
- **QUACK parameter inlining covers the full pushdown surface (tstz is moot).** The expression-pushdown
  path (`DuckBridgeExpressionTranslator.translateConstant`) renders constants INLINE as SQL text, not as
  `?` params. So the only source of bound parameters is base-jdbc's domain pushdown via `toColumnMapping`,
  whose pushable types are exactly boolean / tinyint / smallint / integer / bigint / real / double /
  decimal / date / timestamp / varchar·char — precisely what `QuackParameterInliner` renders. TIMESTAMP
  WITH TIME ZONE has NO `toColumnMapping` branch (no domain pushdown), so it never arrives as a `?`
  param; a tstz constant inside a pushed expression is either inlined as literal SQL or the expression is
  retained by Trino. The inliner's `NOT_SUPPORTED` throw is therefore a defense-in-depth net, not an
  expected path.
- Default is `execution-engine=JDBC` (production).

## Dropped from the ported executor surface

The DuckLake executor is built around a file-scan / schema-evolution model that a base-jdbc connector
doesn't have. Dropped (stay in trino-ducklake):

- `DucklakeDuckDbExecutor.ExecutionRequest` (projectedColumns/pushedPredicate/fileColumnNamesById/
  structReshapePlans/promotedColumnIds) — base-jdbc's `JdbcTableHandle` + `buildSql` render the SQL.
  Replaced by a trivial `DuckBridgeExecutor.ExecutionRequest(sql, duckDbTimeZone)`.
- `DuckDbAttachTarget` (LocalPath/HttpfsS3/FileScan) + `DuckDbSelectSqlBuilder` — DuckLake's ATTACH-a-.db
  / read-via-scan-function machinery. Not applicable: our SQL already names its tables.
- `DucklakeExecutionEngine.SWANLAKE` (Arrow Flight SQL) — not in scope.
- `DucklakeJsonSupport` — only its trivial `isJson(type)` was needed; inlined into
  `DuckBridgeArrowToPageConverter`.

Ported (renamed, DuckLake-stripped): the executor interface, `InProcessDuckBridgeExecutor`,
`QuackDuckBridgeExecutor`, `DuckBridgeExecutorFactory`, `DuckBridgeExecutionEngine`, `DuckDbTuning`,
`DuckDbTuningSql`, `DuckDbS3Config`, `DuckDbCatalogWriteRetry`, `DuckBridgeArrowToPageConverter`.

## S3 credential story — OPEN

The current test env has no MinIO/S3, so `DuckDbS3Config` ships **ported but unexercised**. It renders a
`CREATE SECRET IF NOT EXISTS duckbridge_s3 (TYPE S3, ...)` from the standard `s3.*` catalog keys, ready
for the T2 executors to attach `s3://` URLs, but there is no integration test wiring an object store.
If/when S3 is needed, add a MinIO container to the T2 integration test and thread the secret SQL into the
executor's connection init (the DuckLake `InProcessDuckDbExecutor.prepareSource` HttpfsS3 branch is the
reference). The `renderCreateSecretSql()` is unit-tested.

## For P4+/P5

- `DuckBridgeArrowToPageConverter` is public and handles the full scalar + ARRAY/ROW/MAP/JSON surface;
  P5's lance PTFs can reuse it directly.
- The `openPreparedConnection` seam on the in-process executor (tuning+parity+TZ applied, then
  `client.buildSql`) is the clean pattern for any future custom scan that needs base-jdbc's SQL rendering
  on a DuckDB Arrow connection.
