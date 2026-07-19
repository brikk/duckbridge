# Probes P3 + P6 — timezone / session-zone semantics

**Date:** 2026-07-19
**FE source:** fresh temp clone of the fork baseline at the pin (`5f009592035…`), discarded.
**Driver:** `com.gizmodata:quack-jdbc:0.2.0-alpha.4`. **Server:** DuckDB `v1.5.4` (compose quack image),
run in both `Etc/UTC` and `America/Los_Angeles` container zones. Empirical probes via direct
quack-jdbc JDBC (`/tmp/opencode/tzprobe/*`, discarded).

The v1 stance was option 3: **no tz-sensitive pushdown**. These probes decide whether options 1
(driver session-init `SET TimeZone`) or 2 (zone-explicit SQL) are viable, and whether the plugin can
see the Doris session zone. Governing rule: tz pushdown stays OFF unless PROVEN sound including pool
reuse.

---

## P6 — can the plugin see the Doris session `time_zone` at plan time?

**VERDICT: YES.** `ConnectorSession.getTimeZone()` exposes it, threaded end-to-end:
- `ConnectorSession` (fe-connector-api) declares `String getTimeZone()` — *"Returns the session time
  zone identifier (e.g. `Asia/Shanghai`)."*
- `ConnectorSessionBuilder.from(ctx)` sets `b.timeZone = ctx.getSessionVariable().getTimeZone()` — the
  Doris session `time_zone` variable.
- `PluginDrivenScanNode` holds that `connectorSession` and passes it as the FIRST arg to
  `scanProvider.planScan(connectorSession, …)`.

So the plugin **can** read the session zone at plan time. (This is necessary but NOT sufficient — see
the pool-poisoning and column-type problems below.)

### DATETIME predicate semantics — which shapes are zone-INDEPENDENT
Precisely, from live probes (server run at `Etc/UTC` AND `America/Los_Angeles`, identical rows required):

| Predicate shape | Zone-independent? | Why |
|---|---|---|
| naive `TIMESTAMP` col `>=` naive literal | **YES** | wall-clock vs wall-clock; DuckDB does no zone conversion for a naive TIMESTAMP. Rows `{1,2}` at both server zones. |
| `DATE` col `>=` `DATE` literal | **YES** | date vs date; no zone. Rows `{1,2}` at both. |
| `year()/month()/day()`… on naive TIMESTAMP | **YES** | wall-clock component extraction; no zone. |
| **`TIMESTAMPTZ` col `>=` explicit-UTC literal** (`TIMESTAMPTZ '…+00'`) | **YES** | instant vs instant; the literal carries its zone. Rows `{1,2}` at both server zones. |
| **`TIMESTAMPTZ` col `>=` NAIVE literal** (`TIMESTAMP '…'`) | **NO** ⚠ | DuckDB casts the naive literal into the SESSION zone before comparing. Rows `{1,2}` @UTC but `{}` @LA — **zone-DEPENDENT, silently wrong**. |

The last row is the crux: a Doris `WHERE dt >= '2024-06-01 06:30:00'` folds to a **naive** literal, and
P4 maps a DuckDB `TIMESTAMPTZ` column to Doris `DATETIMEV2` — so a naive naive-literal comparison
against that column is exactly the zone-DEPENDENT form. **This was a latent correctness bug in the
pre-P3 query builder** (it pushed all `DATETIMEV2` comparisons with a naive literal).

---

## P3 — driver session-init + zone-explicit rendering

### Option 1 (driver session-init `SET TimeZone`) — DEAD, two independent reasons.

1. **quack-jdbc has NO session-init / timezone property.** Its full property set (from
   `getPropertyInfo` in the jar): `connectTimeout`, `requestTimeout`, `tls`, `token`, `tokenEnv`,
   `tokenFile`. No `TimeZone`, no init-SQL, no connection-init-statement property. Its `Connection`
   has `setCatalog`/`setSchema` but no time-zone hook.
2. **The BE gives us no hook to set one anyway.** `JdbcJniScanner.initializeClassLoaderAndDataSource`
   configures HikariCP with a FIXED set: `setDriverClassName`, `setJdbcUrl`, `setUsername`,
   `setPassword`, pool sizing. **No `setConnectionInitSql`, no `addDataSourceProperty`** anywhere in
   the jdbc-scanner. So even a driver that supported an init-SQL property couldn't be told to run one.

### Multi-statement `SET; SELECT` in one `query_sql` — WORKS at the driver, but POISONS the pool.
- **Multi-statement accepted:** `SET TimeZone='Asia/Shanghai'; SELECT current_setting('TimeZone')`
  runs in a single quack-jdbc statement and returns `Asia/Shanghai`. So we COULD smuggle a `SET` into
  `query_sql`.
- **BUT it poisons the pooled connection.** Proven: a `SET TimeZone` on a connection **persists for
  every subsequent query on that same connection** (c3: query-A's SET → query-B sees
  `Asia/Shanghai`). The BE's HikariCP pool reuses `Connection` objects across scans, and we do NOT
  control which connection a scan gets. So a `SET` smuggled into one scan's `query_sql` silently
  changes the zone for the NEXT scan that reuses that connection → **silently wrong**. (A fresh
  connection does NOT inherit it — the state is per-connection, not server-shared — but "per-pooled-
  connection persistence" is exactly the poisoning hazard.)

**Conclusion: option 1 (and its `query_sql`-smuggled variant) is UNSAFE — rejected.** No `SET`
approach survives pool reuse.

### Option 2 (zone-explicit rendering) — VIABLE, and needs NO session state at all.
- **Server-zone independence proven:** with the server run at `America/Los_Angeles`, a
  `TIMESTAMPTZ '…+00'` comparison and a naive-`TIMESTAMP`/`DATE` comparison both return the SAME rows
  as at UTC. The rendered literal carries all the zone info it needs; the DuckDB session/server zone
  never enters.
- **The wire VALUE of a TIMESTAMPTZ IS server-zone-dependent** (LA server renders
  `2024-05-31 23:30:00-07` vs UTC's `…06:30:00+00`) — but that is the BE `DuckDbTypeHandler` decode's
  concern, not the FILTER's, and P4 already maps TIMESTAMPTZ→DATETIMEV2 reading the UTC instant. Our
  filter correctness does not depend on it.

**So option 2 is the mechanism we adopt** — but ONLY where we can render zone-explicitly, which
requires knowing the column's DuckDB type.

---

## Decision matrix + what turned ON

| Column DuckDB type (from P4 metadata) | Temporal predicate rendering | Status |
|---|---|---|
| naive `TIMESTAMP` / `TIMESTAMP_S/MS/NS` / `DATETIME` | naive literal (`TIMESTAMP '…'`) | **ON** — zone-independent (proven) |
| `DATE` | `DATE '…'` literal | **ON** — zone-independent |
| `TIMESTAMPTZ` / `TIMESTAMP WITH TIME ZONE` | explicit-UTC literal (`TIMESTAMPTZ '…+00'`) | **ON** — option 2, zone-independent (proven) |
| temporal column of UNKNOWN DuckDB type (not resolvable) | — | **OFF (drop)** — fail-safe; never the zone-dependent form |
| any `SET TimeZone` mechanism | — | **OFF** — pool-poisoning / no BE hook |

**What turned ON (this milestone):** the query builder now renders temporal comparison/`IN` literals
by the column's DuckDB type (threaded from `planScan`'s column handles as `columnDuckdbTypes`):
naive columns → naive literals; TIMESTAMPTZ columns → explicit-UTC `+00` literals; unknown columns →
drop. This **fixes the latent bug** (naive-literal vs TIMESTAMPTZ was zone-dependent) AND **enables**
safe TIMESTAMPTZ instant-comparison pushdown.

**What stayed OFF:**
- Any `SET TimeZone` (option 1) — no safe mechanism under pool reuse; no BE hook; quack has no property.
- Nothing tz-*sensitive* that needs the session zone: because our two temporal forms are both
  zone-independent, we never need to read `getTimeZone()` for pushdown correctness. (P6 proved it's
  visible; we don't need it for the enabled predicates.)
- `AT TIME ZONE` rewrites / display-rezone functions — not in the domain floor; deferred (would need
  the session zone AND a function-shape audit).

**Drift canary:** `TestDuckBridgeTimezonePushdownCanary` re-runs the connector-rendered predicates
against a UTC server AND an `America/Los_Angeles` server, asserting identical rows — a regression to a
zone-dependent form (or a DuckDB pin bump that changed the semantics) turns it red. Golden-SQL unit
tests pin the exact rendering per column class.

---

## Upstream SPI ask (documented, not patched)

The plugin CAN see the session zone (P6), but has **no sound way to align a per-scan session zone on
the BE**: the `JdbcJniScanner` HikariCP path exposes no `connectionInitSql` / per-scan connection
property, and pooled connections make any `SET` poison-prone. **For any FUTURE tz-*sensitive* pushdown
that genuinely needs the server session zone aligned** (e.g. pushing `at_timezone`-style rewrites, or
functions whose result depends on the session zone), Doris would need an SPI/BE seam to run a
per-connection init statement (or a per-scan connection property) that HikariCP applies on
checkout/reset — so the zone can't leak across pooled scans. This is a **noted future upstream ask**,
recorded in `doris-patches/PATCHES.md`. It is NOT needed for anything enabled here (our enabled
predicates are all zone-independent), so no patch is proposed now.
