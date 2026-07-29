# DuckBridge-on-Doris — Friction log

Running log of SPI / FE / BE surprises hit while implementing the `duckbridge`
`fe-connector` plugin (Route J: JDBC-over-Quack) against the `branch-catalog-spi`
line, pinned at `doris-patches/BASELINE` (`PIN_SHA=0da96f1ad3e…`; earlier entries
were written at `a0c10f0672b…` / `5f009592035…` / `568c4bb457…`).

For Doris fe-connector / BE maintainers — each entry has a pickable upstream
fix. For future plugin authors — read top-to-bottom before starting; saves
hours of debugging.

Sister docs: [`../../dev-docs/PLAN-doris-duckbridge.md`](../../dev-docs/PLAN-doris-duckbridge.md)
(canonical plan), [`NOTES-p5-p2-scan.md`](./NOTES-p5-p2-scan.md) (scan-seam +
pool findings), [`NOTES-scaffold.md`](./NOTES-scaffold.md) (module wiring),
[`REPORT-doris-timezone-probe.md`](./REPORT-doris-timezone-probe.md) (P3/P6 zone
probe), [`REPORT-quack-jdbc-metadata-probe.md`](./REPORT-quack-jdbc-metadata-probe.md)
(P4 metadata fidelity), [`../../doris-patches/PATCHES.md`](../../doris-patches/PATCHES.md)
(the remaining BE patch + pin discipline).

Entry shape: **Symptom** → **Root cause** (file:line) → **Workaround**
→ **Fix** (small, pickable). Newest first.

---

## Patches we carry — and want to DELETE

The connector runs **only** on our patched BE until upstream closes this gap (the
FE runs it unpatched since #66135 — see the note below the table). The remaining
diff is under [`../../doris-patches/`](../../doris-patches/);
`tools/doris-baseline.sh --check-only` proves it applies at the pin. It has a
friction entry below with its exit criteria. **The goal is a stock Doris (SPI +
release BE) that runs `duckbridge` unpatched — at which point the patch is
deleted and `BASELINE` points at the release tag.**

| Patch | Touches | Deletes when… | Entry |
|---|---|---|---|
| `be/0001-duckdb-type-handler.patch` | BE `be-java-extensions/jdbc-scanner` (`DuckDbTypeHandler` + `JdbcTypeHandlerFactory` case) | the BE `jdbc-scanner` gains a pluggable `TypeHandler` seam **or** ships a DuckDB handler | [2026-07-20 · jdbc-scanner TypeHandler seam](#2026-07-20--be-jdbc-scanner-has-no-registration-seam-for-a-dialect-typehandler-we-carry-a-be-patch) |

> **The FE patch is retired (no longer an open item).** Upstream #66135 (`fce5af4e041`,
> at pin `a0c10f0672b`) removed `CatalogFactory.SPI_READY_TYPES`; the FE now routes
> `CREATE CATALOG type="duckbridge"` by registered `ConnectorProvider` type, so the FE
> runs the plugin patch-free. The old FE-patch friction and the `ConnectorScanRangeType`
> scan-discriminator friction are both closed by #66135 — full record in
> `../../doris-patches/PATCHES.md` §Re-vendor log (2026-07-28). This log carries **open
> items only**.

---

## 2026-07-21 · COUNT(*) rides the row-by-row JDBC path — no precomputed-count pushdown for a JDBC-riding connector

**Symptom.** Not a crash — wasted work. `SELECT COUNT(*) FROM t` hands `planScan`
an **empty** projected-column list; the BE then reads one row per table row over
the JDBC/JNI transport just to count them. On our side we've taken the patch-free
win — emit `SELECT 1 FROM t` instead of `SELECT *` for an empty projection
(`DuckBridgeQueryBuilder`), so DuckDB reads no columns and quack-jdbc marshals a
constant instead of every column of every row (`TestDuckBridgeQueryBuilder`
`projectionAndTableQualification`). But we **still drag N rows across the JNI
boundary** for a count that DuckDB could answer with a single number.

**Root cause.** The SPI's count-star pushdown (`FileScanNode.isTableLevelCountStarPushdown()`
→ `PluginDrivenScanNode` `countPushdown` → `ConnectorScanRange.getPushDownRowCount()`)
is a **file-metadata** mechanism: a connector returns a range carrying a precomputed
row count and the BE emits it without reading data (iceberg reads the snapshot
`total-records`; paimon a merged count). Two facts make it unreachable for a
JDBC-riding connector at the pin:

1. **The BE JDBC scanner has no push-down-agg path.** `be/src/exec/scan/jdbc_scanner.cpp`
   and `jdbc_scan_operator.cpp` carry **zero** `TPushAggOp` / count handling — the
   reader just runs `query_sql` and returns rows. A precomputed `getPushDownRowCount()`
   on our range would be dropped on the floor (nothing on the JDBC path consumes it;
   only the native ORC/Parquet readers honor the metadata count).
2. **The reference `fe-connector-jdbc` doesn't implement it either** — `JdbcScanPlanProvider`
   overrides only the 4-arg/5-arg `planScan`, no `countPushdown` overload, no count-SQL
   rewrite. So there is no in-tree pattern for a JDBC-family connector to push a count.

(Note the rebase commit `e697837760`, port of upstream #65548, correctly gates this to
`COUNT(*)` only: `COUNT(col)` keeps all splits and the BE reads the column and counts
non-nulls. So there is nothing to do for `COUNT(col)` — and nothing the SPI even
offers for it, since a table-level row count ≠ a per-column non-null count.)

**Workaround.** `SELECT 1` row-count hygiene (above). Correct and cheap, but O(rows)
on the wire — not the O(1) a real count pushdown gives.

**Fix (pickable upstream changes — a genuine improvement we'd like Doris to make).**
Give a JDBC-riding connector a way to answer `COUNT(*)` without materializing rows.
Two shapes, either of which we'd adopt (and which would delete a chunk of transport
cost for **every** JDBC-family connector, not just duckbridge):
- **BE:** teach the JDBC scanner to honor `push_down_agg_type_opt == COUNT` with an
  empty `push_down_count_slot_ids` — run a `SELECT COUNT(*)` form of `query_sql` (or
  emit the count as the single pushed-down aggregate row the plan already expects),
  the JDBC analogue of the native readers' metadata-count path. Then a connector needs
  no precomputed FE count at all.
- **SPI/FE:** let a connector that opts into `countPushdown` return a precomputed count
  that is honored on the JDBC path too (today `getPushDownRowCount()` is consumed only
  for the file-metadata/native path). The connector would compute it FE-side with a
  `SELECT COUNT(*)` over its own driver — **but only when the ENTIRE predicate was
  pushed** (a dropped conjunct means the count would over-count, since a precomputed
  count leaves no above-scan re-filter; same gate as our LIMIT pushdown,
  [`NOTES-p5-p2-scan.md`](./NOTES-p5-p2-scan.md#limit)). When the connector can't push
  safely it declines and the BE counts by reading — already a first-class path
  (`PluginDrivenScanNode.resolvePushDownRowCount` returns the `-1` sentinel).

**Opinion (design).** For a columnar engine like DuckDB/Quack, `COUNT(*)` is the
canonical O(1)-from-metadata query, and forcing it through a row-by-row JDBC scan is
the starkest example of the Route-J transport tax (see the 2026-07-20 ceiling entry).
The BE-side fix is the cleaner one — it needs no FE-side blocking query at plan time
and benefits the in-tree JDBC connector too. We are **not** taking it now because it
would mean a *third* BE patch (against the goal of shedding patches, not adding them);
`SELECT 1` is the honest patch-free interim. Revisit if count-heavy workloads land, or
bundle it with the generic `plugin_driven` BE work from the 2026-07-20 ceiling entry.

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
3. **No SPI seam selects an in-memory/Arrow/custom transport.** #66135 removed the
   `ConnectorScanRangeType` discriminator entirely — every range is a file scan and the
   BE reader is chosen purely by `tableFormatType` + the `file_scanner.cpp` allowlist
   (item 1). So there is still no typed way for a plugin to say "read me over Arrow";
   removing the dead enum didn't add a transport, it just confirmed `tableFormatType`
   is the only selector ([`NOTES-p5-p2-scan.md`](./NOTES-p5-p2-scan.md)).

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
