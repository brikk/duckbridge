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

Stubbed (fail loud, never silently wrong):
- `DuckBridgeDorisMetadata` — listing returns empty (an honest statement about a not-yet-implemented
  resolver); anything asserting a *shape* (table handle, schema, column handles) throws
  `UnsupportedOperationException` pointing at probe **P4** (quack-jdbc `DatabaseMetaData` fidelity for
  the Doris type map). We never fabricate a column type.
- `DuckBridgeScanPlanProvider` — `getScanRangeType()` declares `JDBC_SCAN` (Route J rides the BE's
  shared `jdbc` reader); `planScan` throws rather than returning an empty scan (a silent under-return
  is banned). The throw enumerates the gating probes P1–P6.

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
| P1 | Doris vs DuckDB built-in divergence audit on the fixture corpus | how many `doris_*` parity macros (if any) |
| P2 | `JDBC_SCAN` range count/query + BE connection behavior vs the Quack 1.5.4 pool | whether the pool gate applies |
| P3 | quack-jdbc session-init / zone-explicit SQL rendering | any tz-sensitive pushdown (v1 default: gated off) |
| P4 | quack-jdbc `DatabaseMetaData` fidelity for the Doris type map | FE metadata resolution trustworthiness |
| P5 | what the SPI hands `planScan` beyond conjuncts (limit? sort? aggregates?) | LIMIT/TopN pushdown scope |
| P6 | Doris session `time_zone` visibility from the plugin | tz options 1–2 |

First concrete work item: **P4** — stand up quack-jdbc metadata resolution behind
`DuckBridgeDorisMetadata`, then **P1** (divergence audit) to seed the pushdown allowlist. The
pushdown translator is a new FE over the ported `trino-duckbridge` discipline (per-conjunct partial
pushdown, per-argument type gates, fixture-per-entry allowlist).

## Live-stack findings (smoke, 2026-07-19)

First full compose run (patched FE + patched BE + quack): plugin loads
(`jarCount=4`, type `duckbridge` registered), `CREATE CATALOG type=duckbridge`
passes (SPI whitelist gate), BE registers, teardown clean.

**FE-side resolution shields the connector stubs:** the FE answers
`Unknown database` from its own cached db map (built from our honestly-empty
`listDatabaseNames`) without ever consulting `databaseExists`/`getTableHandle`.
So the P4/P1–P6 fail-loud stubs are structurally unreachable via SQL until
listing is implemented (post-P4). They stay as throws (fail loud if any FE call
path reaches them); `smoke.sh` asserts the truthful current state instead.
