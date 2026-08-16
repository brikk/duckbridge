# DuckBridge-on-Doris — Friction log

Running log of SPI / FE / BE surprises hit while implementing the `duckbridge`
`fe-connector` plugin (Route J: JDBC-over-Quack) against the `branch-catalog-spi`
line — now **apache/doris `master`** (the SPI is upstreamed; the pre-merge `branch-catalog-spi`
fork is retired), pinned at `doris-patches/BASELINE` (`PIN_SHA=b119273e3f0…`; earlier entries
were written at `a82564ced5d…` / `ded91fb9fb3…` / `0da96f1ad3e…` / `a0c10f0672b…` / `5f009592035…`).

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

> **Re-verified at apache/doris `master` `b119273e3f0` (2026-08-16).** The three entries below are
> still open (re-checked in the master BE source): the jdbc scanner
> (`be/src/exec/scan/jdbc_scanner.cpp`) still has no `TPushAggOp`/count-pushdown path;
> `JdbcTypeHandlerFactory` is still a hardcoded `switch` with no `ServiceLoader` seam (so the BE
> `DuckDbTypeHandler` patch is still required — proven live on master); and the jdbc-scanner still
> has no `connectionInitSql` hook.
>
> **Removed this pass — the "Route-J ceiling" (no BE Arrow/ADBC transport) is resolved upstream.**
> Master now ships a first-class **ADBC/Arrow** connector: `fe/fe-connector/fe-connector-adbc` (FE)
> plus a BE ADBC reader (`be/src/util/adbc_driver_registry.cpp` and a `table_format_type == "adbc"`
> dispatch in `be/src/exec/operator/file_scan_operator.cpp`). That is exactly the Arrow transport the
> old ceiling entry asked upstream to add, so it is no longer a pickable upstream friction. Whether
> **duckbridge** migrates off the per-value JDBC path (`JdbcJniScanner`) to ADBC is a duckbridge/quack
> roadmap item (gated on quack shipping an ADBC driver), tracked in
> [`PLAN-doris-duckbridge.md` §Ceiling of Route J](../../dev-docs/PLAN-doris-duckbridge.md), not here.
>
> Note: the #66407 `fe-connector-api`→`fe-connector-spi` merge and the plugin API-version `1`→`5`
> bump (both in `ded91fb9fb3..a82564ced5d`) were mechanical upstream evolution absorbed in the
> re-vendor (`../../doris-patches/PATCHES.md` §Re-vendor log 2026-08-06), not frictions. The two
> master BE fixes (`COUNT(<nullable col>)` `colUniqueId=-1`, position-delete OPTIONAL nullability)
> were never duckbridge paths — see `../../dev-docs/NOTE-catalog-spi-upstreamed-master.md`.

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
the starkest example of the Route-J transport tax. The BE-side fix is the cleaner one
— it needs no FE-side blocking query at plan time and benefits the in-tree JDBC
connector too. We are **not** taking it now because it would mean a *third* BE patch
(against the goal of shedding patches, not adding them); `SELECT 1` is the honest
patch-free interim. The bigger lever is transport: master now has an ADBC/Arrow
connector (see the header note), so a future duckbridge migration off the JDBC path
would sidestep this entirely — tracked in
[`PLAN-doris-duckbridge.md` §Ceiling of Route J](../../dev-docs/PLAN-doris-duckbridge.md).

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
