# PLAN — `doris-duckbridge` (Route J: JDBC-over-Quack)

Status: exploratory plan. Targets Apache Doris's **new `fe-connector` catalog SPI**
(the `branch-catalog-spi` line / PR #62767), not yet in any Doris release.

## Goal & shape

A standalone `doris-duckbridge` connector that lets Doris push scans, projections,
and predicates down into **DuckDB running as a user-launched Quack server**, reached
over gizmo's pure-JVM `quack-jdbc` driver (`jdbc:quack://…`). This is the Doris sibling
to `trino-duckbridge` and reuses its most valuable asset — the **parity-backed pushdown
core** (translate an expression to DuckDB SQL only when DuckDB evaluates it with
engine-identical semantics). The division of labor is "FE does the predicate magic, the
JDBC/Quack side does the rest": our plugin composes the pushed-down DuckDB query on the
Doris FE, and the user's own DuckDB process executes it. We never read files ourselves —
which is why formats the Doris BE can't read (`.db`, vortex, lance, plus deletes /
inlined data) come for free: real DuckDB does the reading.

## The SPI we plug into

Doris's `fe-connector` SPI is a runtime plugin surface: a connector is an out-of-tree jar
(a `ServiceLoader`-discovered `ConnectorProvider`) dropped into `plugins/connector/<name>/`
and loaded at FE startup. The in-tree **`fe-connector-jdbc`** module is our structural
template — it already accepts an arbitrary `driver_class` + `driver_url` (whitelisted /
checksum-gated against `jdbc_drivers_dir`), supports **passthrough queries**, and emits a
`JDBC_SCAN` scan range. The FE plugin's job is metadata (schemas/tables/columns, resolved
by querying DuckDB via the driver at plan time) and **`planScan`**; the actual scan runs on
the **BE**, whose existing `JdbcJniReader` + `BaseJdbcExecutor` load the JDBC driver inside
the BE's embedded JVM and stream rows back. Crucially, **Route J needs zero BE changes** —
we ride the BE's existing `jdbc` reader.

## How we plug in (mechanics)

We implement the SPI's `ConnectorProvider` / `Connector` / `ConnectorMetadata` /
`ConnectorScanPlanProvider`. In `planScan`, the parity core translates the projected
columns + pushed-down filter (and pushable functions) into a DuckDB `SELECT`, and we return
a `ConnectorScanRange` of type **`JDBC_SCAN`** with `getTableFormatType() == "jdbc"` and
`getFileFormat() == "jni"`, carrying in its `jdbc_params` map the `{driver_class,
driver_url, jdbc_url (jdbc:quack://user-host), query}`. The FE bridge (`PluginDrivenScanNode`)
passes `table_format_type="jdbc"` through to BE thrift verbatim, so the BE dispatches
`JdbcJniReader`, runs our query via `quack-jdbc` in its JVM, and returns JNI off-heap
columnar batches. Anything we can't prove-safe to push stays out of the query and is
re-evaluated by Doris above the scan (passthrough) — same correctness contract as the
Trino side.

## The dialect gap — pick the BE `TypeHandler`, don't "masquerade as Trino"

Doris's *stock* JDBC catalog is a closed dialect system — `JdbcDbType.parseFromUrl`
(`fe-connector-jdbc/.../JdbcDbType.java:58-82`) hard-`switch`es over a fixed DB set and
**throws** on an unknown URL prefix (`jdbc:quack://` is rejected outright), and it drives the
in-tree `JdbcQueryBuilder` / `JdbcIdentifierQuoter` / `JdbcFunctionPushdownConfig`. **But we
never touch that path.** Because we write our own SPI `ConnectorScanPlanProvider`, we compose
the DuckDB SQL with the parity engine and emit our own `JDBC_SCAN` range — so `JdbcDbType`,
`JdbcQueryBuilder`, and the URL-prefix gate are all bypassed. What actually runs our scan on
the BE is **`JdbcJniScanner`** (`be/src/format/table/jdbc_jni_reader.cpp:38` → the Java
`org/apache/doris/jdbc/JdbcJniScanner`), and it reads a plain string map: `jdbc_url`,
`jdbc_user`, `jdbc_password`, `jdbc_driver_class`, `jdbc_driver_url`, **`query_sql` (our
verbatim SELECT, run as-is)**, and `table_type`.

The key finding: on this scanner path **`table_type` is just a free string that selects a
value-coercion `JdbcTypeHandler`**, via `JdbcTypeHandlerFactory.create(tableType)` whose
`default:` returns `DefaultTypeHandler` (`JdbcTypeHandlerFactory.java`). There is **no
`TOdbcTableType` enum gate and no throwing `default`** here — the throwing
`JdbcExecutorFactory` is the *legacy* executor path, not `JdbcJniScanner`. So "masquerade as
an enum" is the wrong frame; we simply choose the handler whose ResultSet→Doris coercions
best fit DuckDB. Coverage of the handlers' `getColumnValue` (what each can decode into a
declared Doris column):

| `table_type` → handler | LARGEINT (int128) | ARRAY (list) | VARBINARY (blob) | STRUCT / MAP |
|---|:--:|:--:|:--:|:--:|
| *(empty/unknown)* → `DefaultTypeHandler` | ✅ | ❌ | ✅ | ❌ |
| `CLICKHOUSE` → `ClickHouseTypeHandler` | ✅ | ✅ | ❌ | ❌ |
| `TRINO`/`PRESTO` → `TrinoTypeHandler` | ❌ | ✅ | ✅ | ❌ |

So **Trino is the wrong pick** — it's the one handler that drops `LARGEINT` (the FE declares
DuckDB `HUGEINT` → Doris `LARGEINT`, and Trino's handler throws on it). Recommendation:

- **v1 scalar core: send an empty `table_type` → `DefaultTypeHandler`.** It already covers
  `LARGEINT` **and** `VARBINARY` plus all scalars and `DECIMAL(≤38)` — strictly better than
  Trino for DuckDB, no masquerade needed.
- **When `LIST`/arrays matter: `table_type = CLICKHOUSE`.** Keeps `LARGEINT` and adds `ARRAY`
  (its element decoder already handles `BigInteger` lists, matching DuckDB int128/wide-unsigned
  lists). Cost: it has no `VARBINARY` arm, so declare `BLOB` columns as `STRING`/hex on the FE.
- **`STRUCT`/`MAP`: no handler decodes them at all.** For v1, map DuckDB `STRUCT`/`MAP`/`JSON`
  to `STRING`/`JSON` on the FE. First-class nested needs a custom handler (below).

**Decision: we patch in our own `DuckDbTypeHandler` from the start.** Rather than live with
the split above (`Default` = int128+blob but no arrays; `CLICKHOUSE` = int128+arrays but no
blob; `TRINO` = no int128), we add a first-class `DuckDbTypeHandler` to the BE `jdbc-scanner`
and a `case "DUCKDB"` in `JdbcTypeHandlerFactory`, then emit `table_type = DUCKDB` from our
connector. This is a **BE-only, be-java-extension change** (one new class + one string case):
it needs **no thrift `TOdbcTableType` change and no FE `JdbcDbType` change** — those bind only
the legacy stock-catalog path we don't use — so it stays a small, self-contained patch. One
handler then covers `LARGEINT` + `ARRAY` + `VARBINARY` + (eventually) `STRUCT`/`MAP` with
DuckDB-faithful coercions, instead of contorting the FE type map to fit someone else's dialect.

**Patch posture (until release):** this `DuckDbTypeHandler` (and the FE `SPI_READY_TYPES`
whitelist entry) live in **our patched FE/BE builds** — the same "we only run on our own
patched Doris" stance `doris-ducklake` takes. Both are kept as clean, reapplyable patches and
**broken out into an upstream Doris PR before we head toward release**, so a stock Doris
carrying the fe-connector SPI + our handler can eventually run the plugin unpatched. Until
that PR lands, the connector runs **only** on our branch-built, patched FE **and** BE.
(`DefaultTypeHandler`/`CLICKHOUSE` stay the zero-patch fallback for early spikes before the
handler exists.)

## Which Doris? We need the new-SPI line, for BOTH FE and BE

Verified against the checkout (`/home/jayson/DEV/OSS/doris`): **the released 4.1.x line —
including the `4.1.3` tag and its rc's — does NOT have any of what Route J needs.** In `4.1.3`:
the entire `fe/fe-connector` SPI is **absent** (0 files) and there is **no `SPI_READY_TYPES`**,
so a connector plugin cannot load at all; and the BE `jdbc-scanner` ships **only** the legacy
`*JdbcExecutor` classes + the throwing `JdbcExecutorFactory` — **no `JdbcJniScanner`, no
`JdbcTypeHandlerFactory`, no `*TypeHandler`**. All of that (the plugin SPI, the `JDBC_SCAN` /
`plugin_driven` range plumbing, `JdbcJniScanner`, and the string-keyed `TypeHandler` default
path the dialect section relies on) lives on **`branch-catalog-spi`** and is only now trending
into `master`. So:

- **This is NOT a stock-BE-with-branch-FE deal like `doris-ducklake`.** Ducklake can pair a
  stock BE because it rides the BE's *native Parquet/iceberg* reader, which exists in stock.
  Route J rides `JdbcJniScanner`, which is **not in stock 4.1.x** — so we need **both** the FE
  **and** the BE built from the branch (or master once it lands there). Plan on building the
  full Doris from that line, not layering onto `apache/doris:be-4.1.x`.
- **Stock 4.1.3 alone is a non-starter for the plugin.** Its in-tree JDBC catalog
  (`fe-core .../datasource/jdbc/client/JdbcClient.java`) does driver loading + pushdown +
  passthrough, but it is not pluggable, its pushdown is fe-core's (not parity), it routes
  through the legacy throwing executor path, and its URL-prefix gate rejects `jdbc:quack`.
  Getting duckbridge behavior on 4.1.3 means **forking fe-core** — throwaway-spike only.

## Deploy shape on the SPI branch

Ship duckbridge as an out-of-tree plugin with our own `ConnectorScanPlanProvider`, so the
parity engine owns the SQL and nothing in fe-core is forked. Build FE **and** BE from the
`branch-catalog-spi` line (`build.sh --fe --be` → `output/`), install the plugin zip into the
FE's `plugins/connector/<name>/`, and carry a **one-line `SPI_READY_TYPES` whitelist patch**
in `CatalogFactory.java` for our catalog `type` (e.g. `duckdb`) — the SPI silently ignores any
provider whose `getType()` isn't whitelisted until upstream opens that list. (`jdbc` is already
whitelisted but routes to the in-tree `fe-connector-jdbc`, so we use a distinct type + the
patch rather than colliding.) A read-only connector needs only that whitelist guard; the
`pluginCatalogTypeToEngine` CREATE-TABLE patch `doris-ducklake` carries is unneeded unless we
add write/DDL.

**Bottom line:** target the `branch-catalog-spi` Doris (build FE **and** BE from it) and run
only on **our patched FE/BE** carrying two small, reapplyable patches — the FE
`SPI_READY_TYPES` whitelist entry and the BE `DuckDbTypeHandler` (+ its `JdbcTypeHandlerFactory`
case) — both slated to be **broken out into an upstream Doris PR before release**. Neither
`4.1.2` nor `4.1.3` can run this (no fe-connector SPI, no `JdbcJniScanner`); a stock Doris only
becomes a target once a release ships the SPI and our handler PR lands.

## Ceiling of Route J (and what's deliberately out of scope)

Route J transfers results via the BE's **JNI off-heap columnar** path, not Arrow — there is
no zero-copy Arrow ingestion for a plugin today. The BE *does* have an Arrow-Flight client
reader, but it's hardwired to Doris→Doris federation (`remote_doris`), and the SPI's
`"plugin_driven"` table-format default has **no BE reader behind it** (both BE dispatchers
are hardcoded allowlists). So a cleaner ADBC / Arrow-Flight transport (the analogue of
`trino-duckbridge`'s T2 Arrow engine, e.g. via `adbc-driver-quack`) or a truly custom JNI
scanner would both require upstream **BE work** and are explicitly out of scope for Route J.
The pragmatic first cut is: reuse the `jdbc` BE reader by reporting `table_format_type="jdbc"`
(the same "ride a shared BE reader" move `doris-ducklake` makes with `iceberg`), keep all the
cleverness — dialect, pushdown, parity — on the FE, and let the user's Quack/DuckDB do the
execution.
