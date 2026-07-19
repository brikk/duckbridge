# doris-duckbridge — scaffold notes

WIP scaffold for the Doris sibling of `trino-duckbridge` (Route J: JDBC-over-Quack). The point of
this pass is that the **SPI wiring compiles and the provider loads** — not behavior. Everything
past listing-nothing fails loud with a pointer to the plan's open probes. See
`../../dev-docs/PLAN-doris-duckbridge.md` for the authoritative design.

## What compiles against what

- **Kotlin, JDK 17 target.** The plugin runs inside the Doris FE (JDK 17 ABI), so this module
  overrides the repo-wide JDK-25 toolchain to `jvmToolchain(17)` in its `build.gradle.kts`. Gradle's
  foojay-resolver (root `settings.gradle.kts`) downloads the 17 toolchain on demand.
- **Doris SPI jars** (`org.apache.doris:fe-connector-api`, `fe-connector-spi`, `fe-thrift`, all
  `1.2-SNAPSHOT`) are `compileOnly` — the FE supplies them via the parent classloader at runtime, so
  the plugin jar must not bundle a shadowing copy.
  - **Self-contained bootstrap (not `~/.m2`).** They resolve from a **project-local** maven repo at
    `doris-duckbridge/doris-m2/` (gitignored), scoped to the `org.apache.doris` group via
    `exclusiveContent`. `doris-m2/` is populated by `tools/doris-baseline.sh --install-spi-jars`,
    which builds the jars from **our** pin (`doris-patches/BASELINE`). We deliberately do **not** use
    `mavenLocal()`: `~/.m2` is shared, and `doris-ducklake` publishes the same `1.2-SNAPSHOT`
    coordinates from a **different** pin (last-build-wins clobbering). Project-local = deterministic,
    fresh-clone-buildable, isolated from other projects. See `doris-patches/PATCHES.md §Bootstrap`
    for the reactor set (`-pl fe-connector/fe-connector-spi -am -P flatten`), thrift 0.16.0 handling,
    and parallelism.
  - **Not bootstrapped?** A task-graph guard in `build.gradle.kts` fails any compile/test/detekt task
    with the exact command `tools/doris-baseline.sh --install-spi-jars` — never a cryptic
    "could not resolve org.apache.doris:…".
- **quack-jdbc** (`com.gizmodata:quack-jdbc`, version from the repo-wide `libs.versions.toml`) is a
  real `implementation` dep (bundled in the plugin zip) — the pure-JVM DuckDB/Quack driver the FE
  will use for metadata resolution and the BE for the scan. Not yet *used* in the scaffold.
- **Tests** run on the JDK 17 toolchain (matches how the plugin runs in the FE) with the SPI jars on
  the test classpath (the provider-load test needs the SPI types).

## What's real vs stubbed

Real (mechanics):
- `DuckBridgeConnectorProvider` — `ServiceLoader`-registered
  (`resources/META-INF/services/org.apache.doris.connector.spi.ConnectorProvider`), `getType() ==
  "duckbridge"`. Gated at the FE by the `SPI_READY_TYPES` whitelist patch.
- `DuckBridgeConnector` — composes the SPI object graph (metadata + scan-plan seam).
- `DuckBridgeConnectorConfig` — parses the DuckDB/Quack JDBC connection coordinates off the catalog
  properties (real, tested).
- **`DuckBridgeDorisMetadata` — REAL as of P4 (2026-07-19).** Resolves schemas/tables/columns over
  quack-jdbc `DatabaseMetaData` (short-lived per-call connections, `DuckBridgeQuackConnections`) with
  the probe-decided type map (`DuckDbToDorisTypeMapper`, keyed off the faithful `TYPE_NAME`).
  Schema→database flattening, main-catalog-only, system objects excluded. Unmappable types
  (UHUGEINT, INTERVAL) fail loud naming the column. Evidence + decided map:
  `dev-docs/REPORT-quack-jdbc-metadata-probe.md`. Proven live in the compose smoke (DESC shows
  `HUGEINT→largeint`, `VARCHAR[]→array<text>`, `DECIMAL(18,2)`).

- **`DuckBridgeScanPlanProvider` — REAL as of P5/P2 (2026-07-19).** `planScan` composes a DuckDB
  `SELECT` (`DuckBridgeQueryBuilder`) and emits ONE JDBC scan range (`DuckBridgeJdbcScanRange`:
  `FILE_SCAN` + `tableFormatType="jdbc"` + `table_type=DUCKDB`) so rows flow through the BE
  `JdbcJniScanner` + our `DuckDbTypeHandler`. Predicate pushdown is the **domain floor only**
  (comparisons/`IN`/`IS NULL`, boolean combinators, over scalar columns, faithfully escaped,
  NUL-refusing); function-shape pushdown waits on P1. The FE re-evaluates every conjunct above the
  scan (we do not implement `applyFilter`), so pushed predicates are a pure optimization — dropped
  ones are re-filtered, and only exactly-faithful ones are pushed. Details:
  `dev-docs/NOTES-p5-p2-scan.md`.

## Doris-side patches (separate from this module)

The connector runs only on our **patched FE + BE** until the SPI + our BE handler land in a Doris
release. Both patches live at the repo root under `doris-patches/`:
- `fe/0001-spi-ready-types-duckbridge.patch` — whitelist `"duckbridge"` in `SPI_READY_TYPES`.
- `be/0001-duckdb-type-handler.patch` — a first-class `DuckDbTypeHandler` in the BE `jdbc-scanner`
  (LARGEINT/HUGEINT + VARBINARY/BLOB + ARRAY/LIST; STRUCT/MAP deferred to STRING/JSON in v1) + a
  `case "DUCKDB"` in `JdbcTypeHandlerFactory`. Emit `table_type = DUCKDB` from `planScan`.

`tools/doris-baseline.sh --check-only` proves both apply at the pin.

## Next real step: the P1–P6 probes (from the plan)

Nothing behavioral should be built until these are settled — the scaffold's fail-loud throws name
them so the wiring can't be mistaken for a working connector:

| # | Probe | Gates |
|---|---|---|
| P1 | Doris vs DuckDB built-in divergence audit on the fixture corpus | how many `doris_*` parity macros (if any) — **the next work item** |
| ~~P2~~ ✅ | scan-range count / BE connection behavior vs the Quack 1.5.4 pool | **SETTLED 2026-07-19** — 1 range/query; 20 seq + 8 concurrent SELECTs, 0 pool failures; `NOTES-p5-p2-scan.md` |
| P3 | quack-jdbc session-init / zone-explicit SQL rendering | any tz-sensitive pushdown (v1 default: gated off) |
| ~~P4~~ ✅ | quack-jdbc `DatabaseMetaData` fidelity for the Doris type map | **SETTLED 2026-07-19** — metadata plane real; `REPORT-quack-jdbc-metadata-probe.md` |
| ~~P5~~ ✅ | what the SPI hands `planScan` (columns, typed predicate tree, limit); FE re-evaluates filters | **SETTLED 2026-07-19** — scan seam real; domain-floor pushdown; `NOTES-p5-p2-scan.md` |
| P6 | Doris session `time_zone` visibility from the plugin | tz options 1–2 |

**P2/P4/P5 are settled; the first end-to-end SELECT works.** Next concrete work item: **P1**
(divergence audit) to grow pushdown from the domain floor to audited scalar functions. The pushdown
translator extends `DuckBridgeQueryBuilder` (a new FE over the ported `trino-duckbridge` discipline:
per-conjunct partial pushdown, per-argument type gates, fixture-per-entry allowlist). P3/P6 gate
tz-sensitive pushdown (v1 default: off).

## Live-stack findings (smoke)

**2026-07-19 (pre-P4):** first full compose run (patched FE + patched BE + quack): plugin loads,
type `duckbridge` registered, `CREATE CATALOG` passes the SPI whitelist gate, BE registers, clean
teardown. Metadata was honestly-empty then, so the FE answered `Unknown database` from its own
cached db map without consulting the connector.

**2026-07-19 (post-P4):** with the real metadata plane, the seeded quack schema resolves
end-to-end over the live FE:
- `SHOW DATABASES FROM duckbridge_test` → lists `sales` (the seeded quack schema);
- `SHOW TABLES FROM duckbridge_test.sales` → `customers`, `orders`;
- `DESC duckbridge_test.sales.customers` → the P4 type map, verbatim from the FE:
  `id bigint`, `name text`, `balance decimal(18,2)`, `signup date`, `big_id largeint`
  (HUGEINT→LARGEINT), `tags array<text>` (VARCHAR[]→ARRAY<STRING>);
- `SELECT * FROM …` → reached `planScan` and failed loud with the P1/P5 message — the green then.

**2026-07-19 (post-P5/P2): the first working end-to-end SELECT.** `planScan` now emits a JDBC scan
range; rows flow through the BE `JdbcJniScanner` + our `DuckDbTypeHandler`:
- `SELECT id, name, big_id, tags FROM sales.customers ORDER BY id` → all rows, with the handler
  decoding **unicode VARCHAR** (`straße`, `δοκιμή`), **LARGEINT** (HUGEINT max
  `170141183460469231731687303715884105727`, `-5`, `0`), and **ARRAY** (`["vip", "eu"]`, `["de"]`,
  `[]`) — the DuckDbTypeHandler's first live exercise;
- `WHERE id >= 2` → exactly `{2,3}`; `count(*)` → `3`;
- EXPLAIN → `QUERY: SELECT "id" FROM "memory"."sales"."customers" WHERE ("id" >= 2)` **and**
  `PREDICATES: (id[#0] >= 2)` — the pushed WHERE plus the FE-retained predicate, confirming the P5
  split (push as optimization; FE re-filters above);
- **P2:** 20 sequential + 8 concurrent SELECTs, 0 pool failures.
The driver jar is staged from the plugin zip into `.be-drivers` and mounted into the BE;
`driver_url=file:///opt/duckbridge-drivers/quack-jdbc.jar`. `smoke.sh` asserts each truthfully.
