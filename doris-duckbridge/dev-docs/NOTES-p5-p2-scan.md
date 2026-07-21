# Probe P5 (planScan inputs) + P2 (scan-range count / pool) — findings

Date: 2026-07-19. Evidence read from the SPI + FE source at the pin
(`5f009592035…`, fresh temp clone `/tmp/opencode/doris-pin-read`, discarded) and the
in-tree `fe-connector-jdbc` reference. This is the authoritative shape at our pin.

## P5 — what the SPI hands `planScan`, and what the FE retains

### The `planScan` overloads (fe-connector-api `ConnectorScanPlanProvider`)
```
planScan(session, handle, columns, Optional<filter>)                              // 4-arg (base, abstract)
planScan(session, handle, columns, Optional<filter>, long limit)                 // 5-arg (+ limit)
planScan(session, handle, columns, Optional<filter>, limit, List<String> parts)  // 6-arg (+ requiredPartitions)
planScan(session, handle, columns, Optional<filter>, limit, parts, boolean)      // 7-arg (+ countPushdown)
```
The default overloads fold DOWN to the base: 6-arg → 5-arg → 4-arg, and the 4-arg is
the only `abstract` one. The in-tree JDBC connector overrides **4-arg and 5-arg**; the
4-arg delegates to `planScan(…, limit = -1)`. **We do the same: override 4-arg + 5-arg.**

- **columns**: `List<ConnectorColumnHandle>` — the PROJECTED columns (already pruned by
  the FE's `applyProjection` fixed-point, in output order). Empty ⇒ `SELECT 1` (a row-count-only
  scan, e.g. no-grouping `COUNT(*)` — NOT `SELECT *`, which would marshal every column per row to
  be discarded; see the friction doc's COUNT(*) entry). These are our `DuckBridgeColumnHandle`s
  (carry `columnName`, mapped Doris type, raw DuckDB type).
- **filter**: `Optional<ConnectorExpression>` — a fully-typed expression tree, NOT raw
  Doris `Expr`. Node types available (fe-connector-api `pushdown` package):
  `ConnectorColumnRef(name,type)`, `ConnectorLiteral(type,value; isNull; ofString/ofLong/…)`,
  `ConnectorComparison(op ∈ {EQ,NE,LT,LE,GT,GE,EQ_FOR_NULL}, left, right)`,
  `ConnectorIn(value, inList, negated)`, `ConnectorIsNull(operand, negated)`,
  `ConnectorAnd(conjuncts)`, `ConnectorOr(disjuncts)`, `ConnectorNot(operand)`,
  `ConnectorBetween`, `ConnectorLike(op ∈ {LIKE,REGEXP})`, `ConnectorFunctionCall(name,retType,args)`.
  Literals carry a real Java value (`String`, `Long`, `Integer`, `Double`, `BigDecimal`,
  `Boolean`, `LocalDate`, `LocalDateTime`) — so we render from the typed value, not a string.
- **limit** (5-arg): `long`, `-1` = none.

### Does the FE retain (re-evaluate) the filter?  → YES, unless we say otherwise.
`PluginDrivenScanNode.convertPredicate()` (FE) drives pushdown via
`ConnectorMetadata.applyFilter(...)`, which returns
`FilterApplicationResult(handle, remainingFilter, precalcStats)`:
- **`remainingFilter == null`** ⇒ FE **clears** all conjuncts (trusts the connector to
  have fully applied the predicate remotely).
- **`remainingFilter != null`** (or `applyFilter` returns `Optional.empty()`, the DEFAULT)
  ⇒ FE **keeps ALL conjuncts** for BE-side re-evaluation above the scan
  (comment: *"Partial or full remaining: keep all conjuncts … keeping conjuncts"*).

**Decision: we do NOT override `applyFilter` (default → empty).** Therefore the FE keeps
every conjunct and the BE re-filters above the JDBC scan. The `WHERE` we render into
`query_sql` is then a **pure optimization** (narrow the remote read); the FE guarantees
final-result correctness by re-applying the full predicate. This is the safe split the
plan mandates and matches how the in-tree JDBC connector reasons (`JdbcQueryBuilder`
tracks `allFiltersCollected` and only suppresses LIMIT pushdown when a conjunct was
dropped — proving dropped conjuncts are re-evaluated locally).

**BUT** — the retention only saves us from *under-pushing* (dropping a conjunct). It does
NOT save us from *mis-rendering* a pushed predicate: if our `WHERE` wrongly EXCLUDES a row
that should match (bad escaping, wrong operator), the BE re-filter cannot resurrect a row
the remote never returned → silent under-return. **So every predicate we DO push must be
exactly equivalent (or provably a superset) of the Doris semantics.** The domain floor
(scalar `=,<>,<,<=,>,>=`, `IN`, `IS [NOT] NULL`, `AND`/`OR`/`NOT` over those) on scalar
columns with faithfully-escaped literals meets that bar; anything else we DROP (FE
re-filters). No function-shape pushdown (that's P1) — `ConnectorFunctionCall` and
`ConnectorLike`/`REGEXP` are NOT pushed in this milestone.

### LIMIT
The 5-arg hands `limit`. We push it into `query_sql` (`LIMIT n`) **only when the entire
filter was rendered** (no conjunct dropped) — otherwise a remote `LIMIT` truncates before
the FE's above-scan re-filter runs, under-returning. This mirrors
`JdbcQueryBuilder.shouldPushDownLimit`. DuckDB supports `LIMIT n` natively.

### Emitting the JDBC_SCAN range — the CORRECTED mechanics
The plan says `getRangeType()==JDBC_SCAN`. **The pin says otherwise** — the in-tree
`JdbcScanRange` returns **`FILE_SCAN`** with `getTableFormatType()=="jdbc"` and
`getFileFormat()=="jni"` (the `ConnectorScanRange` defaults). Trust the source:
- `getRangeType()` = **`FILE_SCAN`** (NOT `JDBC_SCAN`; the JDBC_SCAN enum value exists but
  the JDBC connector does not use it).
- `getTableFormatType()` = **`"jdbc"`** → `PluginDrivenScanNode` line 1670
  `setTableFormatType(scanRange.getTableFormatType())` → BE dispatches the JDBC JNI reader.
- `getFileFormat()` = **`"jni"`** (the `ConnectorScanRange` default) → `getFileFormatType()`
  returns `FORMAT_JNI` (default when no `file_format_type` scan-node prop is set). We do
  NOT set `file_format_type`.
- `getScanRangeType()` on the provider = `FILE_SCAN` (so `DuckBridgeScanPlanProvider`
  changes from `JDBC_SCAN` → `FILE_SCAN`).
- Properties → the default `ConnectorScanRange.populateRangeParams` copies the whole
  `getProperties()` map into `formatDesc.setJdbcParams(...)` (plus `connector_scan_range_type`,
  `connector_file_format`). So the `jdbc_params` the BE reads ARE our range's properties.

### `jdbc_params` keys the BE `JdbcJniScanner` reads (verified in the BE Java + C++)
`jdbc_url`, `jdbc_user`, `jdbc_password`, `jdbc_driver_class`, `jdbc_driver_url`,
`query_sql`, `table_type`, `catalog_id`, `connection_pool_*`. **`required_fields` and
`columns_types` are built by the BE C++ `jdbc_jni_reader.cpp` from the scan slot
descriptors — the FE range does NOT set them.** (`table_type=DUCKDB` selects our patched
`DuckDbTypeHandler`.)

### Driver-jar deployment (BE `JdbcUtils::resolve_driver_url`, verified)
- `jdbc_driver_url` containing `":/"` (e.g. `file:///opt/.../quack-jdbc.jar`) → used
  as-is. **No checksum gate on the JNI-reader path** (the checksum gate lives only in the
  legacy `internal_service.cpp` executor path we don't use).
- A bare filename → resolved under `${DORIS_HOME}/plugins/jdbc_drivers/<name>` (or the
  configured `jdbc_drivers_dir`).
- **Compose choice:** mount the quack-jdbc jar into the BE at a fixed path and pass a
  `file://` absolute URL via the catalog `driver_url` property. Documented in smoke.sh +
  the compose file. **Deployment expectation:** operators put quack-jdbc where the BE can
  read it (drivers dir or a shared path) and set `driver_url` accordingly.

### Credentials / token
quack-jdbc's `getPropertyInfo` declares **`password` as an explicit alias for the auth
token** (*"Quack authentication token alias for tools that provide a password field"*).
The BE maps `jdbc_password` → HikariCP `password` → quack-jdbc reads it as the token. **So
the standard `jdbc_password` field carries the Quack token; no dedicated `token` param is
needed.** We map the catalog `password` property to `jdbc_password`.
### Token-leak audit (Item 2, resolved 2026-07-19)
The token rides `jdbc_password`. Where does it ACTUALLY leak at our pin? Audited live:

| Surface | Leaks token? | Owner |
|---|---|---|
| `EXPLAIN VERBOSE` of a scan | **No** — 0 occurrences of the token value | FE-core |
| `SHOW CREATE CATALOG` | **No** — password rendered as `"*XXX"` | FE-core (already masks) |
| FE `fe.log` / `fe.audit.log` | **No** — 0 occurrences | FE-core |
| Our `DuckBridgeConnectorConfig.toString()` | **WAS yes** (data-class default prints `password=<token>`) | **OURS → redacted** |
| Our `DuckBridgeJdbcScanRange.toString()` | default is object-identity (no leak), but any props-dump would | **OURS → redacted defensively** |

**Redacted (ours):**
- `DuckBridgeConnectorConfig.toString()` — overridden to mask `password=***` (`null` when unset)
  while keeping url/user/driver for debuggability.
- `DuckBridgeJdbcScanRange.toString()` — masks `jdbc_password=***`; `getProperties()` is unchanged
  (the BE needs the real value — only the rendering masks).
- Test: `TestDuckBridgeTokenRedaction` asserts neither toString contains the token, and that
  `getProperties()` still carries it for the wire.

**Documented, NOT patched (upstream / not ours):** FE-core already masks `password` in
`SHOW CREATE CATALOG` (`*XXX`), EXPLAIN, and the audit log at our pin, so there is **no FE-core leak
to patch**. If a future FE change ever surfaces `jdbc_password` in a plan/log, that is an upstream
concern (the masking lives in fe-core's catalog-property rendering, not in our connector) — not
something to patch Doris for from here.

## P2 — scan-range count + connection-pool behavior

### Range count (static): 1 per query.
The in-tree JDBC connector emits **exactly one** `JdbcScanRange` per query
(`Collections.singletonList`, `estimateScanRangeCount()==1`) — "the remote query cannot be
partitioned." We do the same (one range carrying one `query_sql`). So Route J is
**one JDBC_SCAN range per SELECT**, NOT per-split churn. This is the good case the plan
hoped for (§Quack operational notes: "if it's one range per query (likely for a JDBC
scan), we're fine").

### BE connection behavior.
The BE `JdbcJniScanner` uses a **HikariCP pool per (catalog, params) on each BE**, sized by
`connection_pool_min_size`/`max_size` (defaults 1/10) and keyed so repeated scans reuse the
pool. One range ⇒ one `getConnection()` from that pool ⇒ one server-side Quack connection
in flight per concurrent scan on that BE. With a single BE and one range per query,
sequential SELECTs reuse one pooled connection; concurrency N uses up to N (≤ max_size)
server-side connections.

### Empirical (compose, quack 1.5.4) — smoke.sh "P2" block, run 2026-07-19.
**VERDICT: GREEN — no pool exhaustion.**
- **20 sequential** `SELECT count(*)` → 0 failures (pool reuse works).
- **8 concurrent** full-row `SELECT … ORDER BY id` (≤8 per the run constraint) → 8/8 ok, 0 failures.
- One JDBC_SCAN range per query (confirmed via EXPLAIN: a single scan, `QUERY: SELECT … `), so
  concurrency N drives at most N server-side Quack connections — well under quack 1.5.4's fixed
  pool at this scale.

**Conclusion:** Route J at one-range-per-query does **not** hit the fixed-pool exhaustion that
gated trino-duckbridge's per-split engine. No connection-reuse work is needed before real use at
this cardinality. Re-measure if a future change fans a query into multiple ranges, or under
higher concurrency than 8; watch the upstream pool rework (quack 1.5.5-ish) regardless.

### End-to-end proof (same run)
`SELECT id, name, big_id, tags FROM sales.customers ORDER BY id` returned all rows with the BE
`DuckDbTypeHandler` decoding **unicode VARCHAR** (`straße`, `δοκιμή`), **LARGEINT** (HUGEINT max
`170141183460469231731687303715884105727`, `-5`, `0`), and **ARRAY** (`["vip", "eu"]`, `["de"]`,
`[]`). `WHERE id >= 2` returned exactly `{2,3}`. EXPLAIN carried the composed pushed query
`SELECT "id" FROM "memory"."sales"."customers" WHERE ("id" >= 2)` **and** a FE-retained
`PREDICATES: (id[#0] >= 2)` — visual confirmation of the P5 retention split (we push as an
optimization; the FE re-filters above the scan).
