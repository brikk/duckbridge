# DuckBridge-on-Doris — Friction log

SPI / FE / BE gaps hit building the `duckbridge` `fe-connector` plugin (Route J: JDBC-over-Quack)
against apache/doris `master` (the SPI is upstreamed; the pre-merge `branch-catalog-spi` fork is
retired). Pin: `doris-patches/BASELINE`.

Each entry is a pickable upstream ask. **Ordered by what we'd most want fixed if 4.2 ships:**

| # | Ask | Why it matters to us | Blocking? |
|---|---|---|---|
| **P1** | `jdbc-scanner` `TypeHandler` registration seam (or ship a DuckDB handler) | **Deletes our only patch** → duckbridge runs on stock Doris | Yes — the one thing keeping us off a release |
| **P2** | `COUNT(*)` pushdown on the BE JDBC scanner | `COUNT(*)` is O(rows) not O(1); helps every JDBC connector | No (interim: `SELECT 1`) |
| **P3** | Per-scan `connectionInitSql` on the BE JDBC pool | Unblocks tz-sensitive predicate pushdown | No (feature gated off; we render zone-explicit) |

Status **1731787677f (2026-08-25):** all three re-verified open in the master BE source. The FE runs
patch-free (#66135 removed `SPI_READY_TYPES`); the only carried patch is BE `DuckDbTypeHandler` (P1).
The old "Route-J ceiling / no Arrow transport" ask is **resolved** — master ships an ADBC/Arrow
connector (`fe-connector-adbc` + BE `adbc_driver_registry`); a duckbridge JDBC→ADBC migration is a
roadmap call in [`PLAN-doris-duckbridge.md`](../../dev-docs/PLAN-doris-duckbridge.md), not a friction
(our perf probe found quack-jdbc actually *faster* than the alpha ADBC driver — transport isn't the
bottleneck).

Sister docs: [`PLAN`](../../dev-docs/PLAN-doris-duckbridge.md) ·
[`NOTES-p5-p2-scan`](./NOTES-p5-p2-scan.md) · [`PATCHES.md`](../../doris-patches/PATCHES.md).

---

## P1 · `jdbc-scanner` has no `TypeHandler` registration seam — we carry a BE patch  (2026-07-20)

**Gap.** The BE `JdbcJniScanner` picks its value decoder from a hardcoded `switch` on `table_type`
(`JdbcTypeHandlerFactory.create`). A connector can't register its own handler without editing Doris.
No stock handler fits DuckDB, which needs all three of LARGEINT + VARBINARY + ARRAY at once:
`DefaultTypeHandler` lacks ARRAY, `ClickHouseTypeHandler` lacks VARBINARY, `TrinoTypeHandler` drops
LARGEINT.

**What we do.** [`be/0001-duckdb-type-handler.patch`](../../doris-patches/be/0001-duckdb-type-handler.patch):
a `DuckDbTypeHandler extends DefaultTypeHandler` (HUGEINT/UBIGINT→BigInteger, BLOB→byte[], LIST→list)
+ one `case "DUCKDB"`. BE-only, self-contained, no thrift/FE change.

**Ask (either deletes the patch).** Resolve the `TypeHandler` via `ServiceLoader` / a
connector-declared class on `jdbc_params` instead of the switch — **or** just upstream
`DuckDbTypeHandler` + the `DUCKDB` case as-is. On a release with either, we delete the patch and
point `BASELINE` at the tag → **stock Doris runs duckbridge unpatched.**

---

## P2 · `COUNT(*)` rides the row-by-row JDBC path — no count pushdown  (2026-07-21)

**Gap.** `COUNT(*)` hands `planScan` an empty projection, and the BE JDBC scanner
(`be/src/exec/scan/jdbc_scanner.cpp`) has **no** `TPushAggOp`/count path — it reads every row to
count them. The SPI's `getPushDownRowCount()` is honored only by the native ORC/Parquet readers, not
on the JDBC path.

**What we do.** Emit `SELECT 1` (not `SELECT *`) for an empty projection so no column is marshalled —
still O(rows) on the wire, not the O(1) DuckDB could give.

**Ask.** Teach the JDBC scanner to honor `push_down_agg_type_opt == COUNT` (empty
`push_down_count_slot_ids`) by running a `COUNT(*)` form of `query_sql`. Benefits every JDBC-family
connector. Lower priority than it looks: our probe showed the JDBC transport is not the bottleneck
(quack's wire is already columnar), so this is a real-but-modest win, not urgent.

---

## P3 · No per-scan `connectionInitSql` on the BE JDBC pool — blocks tz-sensitive pushdown  (2026-07-19)

**Gap.** The BE `JdbcJniScanner` HikariCP pool has no per-checkout init hook, and quack-jdbc has no
`TimeZone` property. So a per-scan `SET TimeZone` can't be applied without leaking onto the next
pooled query.

**What we do.** Render temporal predicates zone-explicitly (naive→naive literal, `TIMESTAMPTZ`→
explicit-UTC `…+00`, unknown→drop) — correct without any session init. So tz-*sensitive* pushdown
stays gated off; nothing to fix for v1.

**Ask.** A per-scan/per-checkout `connectionInitSql` (or `session_properties` passthrough) on the BE
JDBC pool, so the remote session zone can be set for one scan without pool leakage. Wanted only if we
ever enable zone-dependent predicate pushdown.

---

## Adding an entry

Newest gaps go under the priority table (and get a `Pn` if they're a standing ask). Keep it to:
**Gap** (symptom + root-cause file), **What we do** (workaround), **Ask** (one pickable upstream fix;
name the patch + exit criteria if it deletes one). Small things stay small.
