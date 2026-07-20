# DuckBridge-on-Doris — Friction log

Running log of SPI / FE / BE surprises hit while implementing the `duckbridge`
`fe-connector` plugin (Route J: JDBC-over-Quack) against the `branch-catalog-spi`
line, pinned at `doris-patches/BASELINE` (`PIN_SHA=5f009592035…`).

For Doris fe-connector / BE maintainers — each entry has a pickable upstream
fix. For future plugin authors — read top-to-bottom before starting; saves
hours of debugging.

Sister docs: [`../../dev-docs/PLAN-doris-duckbridge.md`](../../dev-docs/PLAN-doris-duckbridge.md)
(canonical plan), [`NOTES-p5-p2-scan.md`](./NOTES-p5-p2-scan.md) (scan-seam +
pool findings), [`NOTES-scaffold.md`](./NOTES-scaffold.md) (module wiring),
[`REPORT-doris-timezone-probe.md`](./REPORT-doris-timezone-probe.md) (P3/P6 zone
probe), [`REPORT-quack-jdbc-metadata-probe.md`](./REPORT-quack-jdbc-metadata-probe.md)
(P4 metadata fidelity), [`../../doris-patches/PATCHES.md`](../../doris-patches/PATCHES.md)
(the two patches + pin discipline).

Entry shape: **Symptom** → **Root cause** (file:line) → **Workaround**
→ **Fix** (small, pickable). Newest first.

---

## Patches we carry — and want to DELETE

The connector runs **only** on our patched FE + BE until upstream closes these
gaps. Both are visible diffs under [`../../doris-patches/`](../../doris-patches/);
`tools/doris-baseline.sh --check-only` proves they apply at the pin. Each has a
friction entry below with its exit criteria. **The goal is a stock Doris (SPI +
release BE) that runs `duckbridge` unpatched — at which point both patches are
deleted and `BASELINE` points at the release tag.**

| Patch | Touches | Deletes when… | Entry |
|---|---|---|---|
| `fe/0001-spi-ready-types-duckbridge.patch` | `fe-core` `CatalogFactory.java` `SPI_READY_TYPES` | the FE gains a connector-declared registration seam (drop the hardcoded allowlist) | [2026-07-20 · SPI_READY_TYPES](#2026-07-20--spi_ready_types-is-a-hardcoded-fe-allowlist-we-carry-an-fe-patch) |
| `be/0001-duckdb-type-handler.patch` | BE `be-java-extensions/jdbc-scanner` (`DuckDbTypeHandler` + `JdbcTypeHandlerFactory` case) | the BE `jdbc-scanner` gains a pluggable `TypeHandler` seam **or** ships a DuckDB handler | [2026-07-20 · jdbc-scanner TypeHandler seam](#2026-07-20--be-jdbc-scanner-has-no-registration-seam-for-a-dialect-typehandler-we-carry-a-be-patch) |

---

## 2026-07-20 · No BE transport for a plugin's OWN scanner (ADBC/Arrow, or direct-Quack→Arrow) — the Route-J ceiling

**Symptom.** Not a crash — a wall. Route J ships rows FE→BE by reporting
`table_format_type="jdbc"` and riding the **stock BE `JdbcJniScanner`**, which
pulls rows out of the quack-jdbc `ResultSet` one JDBC value at a time and copies
them into the BE's JNI off-heap columns via a `TypeHandler`
([`NOTES-p5-p2-scan.md:85`](./NOTES-p5-p2-scan.md), our `DuckDbTypeHandler`).
That per-value JDBC hop is the whole transport. There is **no way for a plugin to
supply its own BE scanner** — e.g. one that reads Quack's native result stream as
**Arrow** (via `adbc-driver-quack`, or by deserializing the Quack wire protocol to
Arrow directly the way the JDBC-quack driver does internally, minus the JDBC
`ResultSet` layer) and hands the BE columnar batches with no per-value marshalling.

**Root cause.** Two BE dispatchers are **hardcoded allowlists**, and the one Arrow
ingestion path is hardwired to Doris→Doris (surveyed at the pin; see
[`PLAN-doris-duckbridge.md` §Ceiling of Route J](../../dev-docs/PLAN-doris-duckbridge.md)):

1. **No generic plugin JNI scanner.** The SPI's `"plugin_driven"` table-format
   default has **no BE reader behind it**. The FE→BE JNI sys-table mechanism that
   *does* let the BE materialize connector-supplied rows (iceberg/paimon via
   `FORMAT_JNI` + `serialized_split` + a per-format be-java-extension scanner) is
   gated by a **hardcoded `table_format_type` switch** in `be/src/exec/scan/file_scanner.cpp`
   (only `max_compute`/`paimon`/`hudi`/`trino_connector`/`jdbc`/`iceberg`, no
   `plugin_driven` case). An SPI connector can't reach it without a BE patch **and**
   its own be-java-extension scanner jar. (Same root gap the ducklake log records
   for inlined-data reads; here it blocks a *custom transport*, not just custom rows.)
2. **No zero-copy Arrow ingestion for a plugin.** The BE has an Arrow-Flight client
   reader, but it's hardwired to Doris→Doris federation (`remote_doris`) — there is
   no SPI surface to point it (or any ADBC/Arrow reader) at a plugin-chosen endpoint.
3. **`ConnectorScanRangeType` has no in-memory/Arrow/custom-transport variant** that
   the BE honors: `FILE_SCAN` / `JDBC_SCAN` / `REMOTE_OLAP_SCAN` / `CUSTOM`, and
   only `FILE_SCAN` (+ `tableFormatType`) actually routes to a reader today
   ([`NOTES-p5-p2-scan.md:68-83`](./NOTES-p5-p2-scan.md); `JDBC_SCAN` itself is dead —
   see the 2026-07-19 entry).

So the pieces an Arrow/ADBC (or direct-Quack) transport would need — a BE reader
that consumes Arrow batches from a plugin-named source, and an SPI way to select
it — **do not exist**. The stock `JdbcJniScanner` is the only shared reader an SPI
connector can ride, and it is JDBC-`ResultSet`-shaped by construction.

**Workaround.** Ride the `jdbc` BE reader (report `table_format_type="jdbc"`,
`table_type=DUCKDB`, one range/query) and keep all cleverness — dialect, pushdown,
parity, type mapping — on the FE. Accept the per-value JDBC marshalling cost; it is
green and correct at our cardinality ([`NOTES-p5-p2-scan.md:134-172`](./NOTES-p5-p2-scan.md)).
This is deliberately the "reuse a shared BE reader" move (the ducklake analogue rides
`iceberg`), not a custom transport.

**Fix (pickable upstream changes, rising scope).**
- **Smallest:** a generic `FORMAT_JNI` `table_format_type == "plugin_driven"`
  dispatch in `file_scanner.cpp` that routes to a **connector-declared** JNI
  scanner class (mirrors the iceberg sys-table path, but registration-driven, not a
  hardcoded case). That alone lets a plugin ship an Arrow/ADBC-backed
  be-java-extension scanner (`adbc-driver-quack`, or a direct Quack-protocol→Arrow
  decoder) and skip the JDBC `ResultSet` hop.
- **Cleaner:** an Arrow-native ingestion transport reachable from the SPI — either
  an ADBC/Arrow-Flight `ConnectorScanRangeType` that carries a plugin-chosen endpoint,
  or unhardwiring the existing Arrow-Flight reader from `remote_doris` so a plugin can
  target it. Gives columnar batches end-to-end, no per-value marshalling.
- **Either way:** make the BE reader selection **registration-driven** (a connector
  declares its transport/reader) instead of the two hardcoded allowlists, so new
  transports don't need a BE source edit per connector.

**Opinion (design).** A JDBC `ResultSet` is the wrong seam for a columnar engine
talking to a columnar engine: Quack already speaks Arrow, DuckDB is columnar, and
the BE is columnar off-heap — Route J's one non-columnar link is the JDBC hop we're
forced through purely because it's the only *reachable* shared BE reader. The right
long-term shape is an SPI-selectable Arrow/ADBC transport; until the BE offers one,
"ride `jdbc`" is the honest pragmatic cut, not the ceiling of what's *possible*.
Tracked in [`PLAN-doris-duckbridge.md` §Ceiling of Route J](../../dev-docs/PLAN-doris-duckbridge.md).

---

## 2026-07-20 · BE `jdbc-scanner` has no registration seam for a dialect TypeHandler (we carry a BE patch)

**Symptom.** DuckDB over quack-jdbc returns HUGEINT/UBIGINT (→ Doris LARGEINT),
BLOB (→ VARBINARY), and LIST (→ ARRAY). **No stock BE `TypeHandler` decodes all
three:** `DefaultTypeHandler` has LARGEINT + VARBINARY but **no ARRAY**;
`ClickHouseTypeHandler` has LARGEINT + ARRAY but **no VARBINARY**;
`TrinoTypeHandler` has ARRAY + VARBINARY but **drops LARGEINT** (throws). Any single
stock `table_type` mis-decodes at least one DuckDB column family.

**Root cause.** The BE `JdbcJniScanner` selects its value-coercion handler from a
**hardcoded `switch`** on the free-string `table_type`:
`fe/be-java-extensions/jdbc-scanner/src/main/java/org/apache/doris/jdbc/JdbcTypeHandlerFactory.java`
(`case "CLICKHOUSE"` / `"POSTGRESQL"` / … ; default → `DefaultTypeHandler`, no
throw). There is no way for a connector to **register** its own handler — you must
add a `case` to that factory, which means editing Doris.

**Workaround.** [`be/0001-duckdb-type-handler.patch`](../../doris-patches/be/0001-duckdb-type-handler.patch):
a first-class `DuckDbTypeHandler extends DefaultTypeHandler` (LARGEINT via
`getObject`→BigInteger normalize, VARBINARY via `getBytes`, ARRAY via
`getArray()`→list with ClickHouse-style element widening; STRUCT/MAP land as
STRING/JSON in v1) + a single `case "DUCKDB"` in the factory. The FE emits
`table_type=DUCKDB` from `planScan`. BE-only, no thrift/FE-enum change (the throwing
`TOdbcTableType` gate is the legacy `JdbcExecutorFactory` path we bypass — see the
patch header for why this is safe and self-contained).

**Fix (pickable upstream changes).**
- **Best:** a pluggable/registration seam in the `jdbc-scanner` — resolve the
  `TypeHandler` by `ServiceLoader` (or a connector-declared class name on
  `jdbc_params`) instead of the hardcoded `JdbcTypeHandlerFactory` switch. Then a
  connector supplies its dialect handler with **no** BE edit. This is the entry that
  deletes our BE patch.
- **Interim:** upstream `DuckDbTypeHandler` + `case "DUCKDB"` as-is (it's a clean,
  self-contained addition; the split it covers is real and no stock handler fills it).

**Exit criteria.** When either lands in a Doris release, delete
`be/0001-duckdb-type-handler.patch` and repoint `BASELINE`.

---

## 2026-07-20 · `SPI_READY_TYPES` is a hardcoded FE allowlist (we carry an FE patch)

**Symptom.** `DuckBridgeConnectorProvider` is `ServiceLoader`-registered
(`META-INF/services/org.apache.doris.connector.spi.ConnectorProvider`), `getType()`
returns `"duckbridge"`, the jar is on the FE classpath — and `CREATE CATALOG …
type="duckbridge"` is still rejected as an unknown type, with **no FE log line**
saying a registered provider was discovered-but-skipped.

**Root cause.** `fe/fe-core/src/main/java/org/apache/doris/datasource/CatalogFactory.java`
hardcodes `SPI_READY_TYPES` (at the pin:
`{jdbc, es, trino-connector, max_compute, paimon, iceberg, hms}`). A provider whose
`getType()` is outside that set falls through to the legacy switch and is silently
dropped — there is no connector-declared registration seam. (Identical to the
ducklake log's `SPI_READY_TYPES` entry; the whitelist is still hardcoded at our
newer pin, just with more members.)

**Workaround.** [`fe/0001-spi-ready-types-duckbridge.patch`](../../doris-patches/fe/0001-spi-ready-types-duckbridge.patch):
one-line add of `"duckbridge"` to the set, re-applied on every Doris build we deploy.
We use a **distinct** type (`"duckbridge"`, not `"jdbc"`) so we route to our provider,
not the in-tree `fe-connector-jdbc`. Read-only connector ⇒ this is the *only* FE patch
we need (no `pluginCatalogTypeToEngine` CREATE-TABLE patch, unlike ducklake).

**Fix (pickable upstream changes).**
- **End state:** drop the whitelist — any `ServiceLoader`-registered
  `ConnectorProvider` wins (or a connector declares readiness via a capability), so a
  new SPI full-adopter needs **no** FE source edit. This deletes our FE patch.
- **Until then:** at minimum, **log a warning** when discovery skips a registered
  provider. The silent drop is the worst part.

**Exit criteria.** When the registration seam ships in a Doris release, delete
`fe/0001-spi-ready-types-duckbridge.patch` and repoint `BASELINE`.

---

## 2026-07-19 · `JDBC_SCAN` range type is dead; the JDBC path rides `FILE_SCAN` + `tableFormatType="jdbc"` + `FORMAT_JNI`

**Symptom.** The plan (and the enum) suggested a JDBC scan emits
`getRangeType() == JDBC_SCAN`. Building to that is wrong: the in-tree
`fe-connector-jdbc` reference at the pin returns **`FILE_SCAN`** with
`getTableFormatType() == "jdbc"` and `getFileFormat() == "jni"` (the
`ConnectorScanRange` defaults). The `JDBC_SCAN` enum value **exists but is unused** —
building against it silently produces a range no BE reader picks up.

**Root cause.** `PluginDrivenScanNode` dispatches the BE reader off
`setTableFormatType(scanRange.getTableFormatType())` (→ the JDBC JNI reader when
`"jdbc"`), and `getFileFormatType()` defaults to `FORMAT_JNI` when no
`file_format_type` scan-node prop is set. The `ConnectorScanRangeType.JDBC_SCAN`
member is never consulted on this path. Documented in detail at
[`NOTES-p5-p2-scan.md:68-90`](./NOTES-p5-p2-scan.md).

**Workaround.** `DuckBridgeJdbcScanRange` returns `FILE_SCAN`, sets
`tableFormatType="jdbc"`, puts everything (incl. `table_type=DUCKDB`, `query_sql`,
`jdbc_url`, `jdbc_password`) into `getProperties()` — the default
`populateRangeParams` copies that whole map into `setJdbcParams(...)`. We do **not**
set `file_format_type`. Green end-to-end ([`NOTES-scaffold.md:108-120`](./NOTES-scaffold.md)).

**Fix (pickable upstream changes).**
- Either **honor `JDBC_SCAN`** in `PluginDrivenScanNode` (route it to the JDBC JNI
  reader) so the typed enum is truthful, **or** remove/deprecate the dead member and
  document that JDBC rides `FILE_SCAN` + `tableFormatType="jdbc"` in the
  `ConnectorScanRange` javadoc. The current state — a typed enum value that silently
  does nothing — is a trap for the next connector author.

---

## 2026-07-19 · No BE hook to set a per-scan session zone without poisoning the pooled connection (blocks tz-sensitive pushdown)

**Symptom.** To push a timezone-sensitive predicate we'd need the remote DuckDB/Quack
session zone aligned to the Doris session `time_zone`. There is no clean way to set
it per scan: quack-jdbc exposes **no** `TimeZone` connection property, and the BE's
HikariCP pool exposes **no `connectionInitSql` hook**. Smuggling `SET TimeZone; SELECT …`
into `query_sql` works once but **poisons the pooled connection** — the zone leaks to
the next unrelated query that reuses it.

**Root cause.** The BE `JdbcJniScanner` uses a HikariCP pool per `(catalog, params)`
and hands back a connection with no per-checkout init step
([`NOTES-p5-p2-scan.md:144-150`](./NOTES-p5-p2-scan.md)); quack-jdbc has no zone
property (P3 probe, [`REPORT-doris-timezone-probe.md`](./REPORT-doris-timezone-probe.md)).
The Doris session zone **is** visible to the plugin (`ConnectorSession.getTimeZone()`,
P6) — the gap is purely applying it on the remote side per scan.

**Workaround.** Render temporal predicates **zone-explicitly** and
server-zone-independently instead of relying on a session zone: naive columns →
naive literal, `TIMESTAMPTZ` columns → explicit-UTC (`…+00`) literal, unknown →
**drop the conjunct** (FE re-filters). This is enabled and correct without any
session-init; it also fixed a latent naive-vs-TIMESTAMPTZ zone bug (P3).

**Fix (pickable upstream changes).**
- A **per-scan/per-checkout `connectionInitSql`** (or a `session_properties`
  passthrough) on the BE JDBC HikariCP pool, so a connector can set the remote zone
  for the scope of one scan without leaking it to pooled reuse. Would unblock
  tz-*sensitive* pushdown for any JDBC-riding connector, not just duckbridge.

---

## How to add an entry

When you hit the next one:

1. Date the entry; insert at the top (above the older dated entries).
2. **Symptom** — paste the literal error / SQL output / wall. No paraphrasing.
3. **Root cause** — file path + line. Quote the offending code if small. Say
   whether it's ours, FE, or BE.
4. **Workaround** — code snippet or config line on our side, with a pointer to the
   NOTES/REPORT that proves it.
5. **Fix** — bullet list of pickable upstream changes, small enough to be one PR.
   If it deletes a patch, name the patch + exit criteria.
