# doris-duckbridge (WIP)

Apache Doris → DuckDB connector: the Doris sibling of `trino-duckbridge`. Doris pushes
scans/projections/predicates into a user-operated DuckDB (remote via the Quack
protocol, `jdbc:quack://…`), riding Doris's new `fe-connector` plugin SPI and the BE's
existing JDBC JNI scanner.

**Status: scaffold.** The SPI wiring compiles and the provider loads; scan planning and
metadata deliberately fail loud until the plan's open probes (P1–P6) are settled. Design
and probe list: [`../dev-docs/PLAN-doris-duckbridge.md`](../dev-docs/PLAN-doris-duckbridge.md).
Scaffold state details: [`dev-docs/NOTES-scaffold.md`](dev-docs/NOTES-scaffold.md).

## Doris baseline & patches

This connector targets pinned apache/doris `master` (the connector SPI is upstream, but not yet in
a release) and runs on a pristine FE plus one patched BE component. Everything is pinned and scripted:

- **Pin**: [`../doris-patches/BASELINE`](../doris-patches/BASELINE) — the exact commit on
  apache/doris master.
- **Patches**: [`../doris-patches/`](../doris-patches/) — only the BE `DuckDbTypeHandler`
  (LARGEINT/ARRAY/VARBINARY-faithful JDBC value coercion). Rationale, apply procedure,
  and the re-vendor log: [`../doris-patches/PATCHES.md`](../doris-patches/PATCHES.md).
- **Script**: [`../tools/doris-baseline.sh`](../tools/doris-baseline.sh)
  - `--check-only` (default) — blobless-clone master at the pin into
    `~/.cache/duckbridge/doris` (`DORIS_SRC` overrides) and verify all patches apply
    clean (`git apply --3way --check`, fail loud on drift).
  - `--install-spi-jars` — build `fe-connector-spi` / `fe-thrift`
    from the **patched pin** into the project-local repo `doris-duckbridge/doris-m2/`
    (~1 min). This is the **required bootstrap** before first build.
  - `--build-fe` / `--build-be` — full patched FE/BE builds (opt-in, long; BE is a
    multi-hour C++ build; capped at 8 parallel jobs).

## Building this module

```sh
# one-time bootstrap (JDK 17 + thrift version from pinned fe/pom.xml; both probed, fail loud)
tools/doris-baseline.sh --install-spi-jars

# then, from the repo root
./gradlew :doris-duckbridge:test :doris-duckbridge:detekt
```

The module resolves `org.apache.doris:*` **only** from `doris-m2/` (never
`mavenLocal()`), so it can't collide with other projects' Doris snapshot installs. If
you build before bootstrapping, gradle stops with the exact command to run.

Prerequisites: JDK 17 for the Doris/maven side (the gradle toolchain resolves it for
the module itself), matching `thrift` for `fe-thrift` codegen (`DORIS_THRIFT` env or
`PATH`; 0.24.0 at the current pin), Docker for the integration environment.

## Test environment

Integration/probe testing needs a running patched FE + BE plus a DuckDB/Quack server, all
containerized. **Scaffolded** in [`compose/`](compose/) (WIP — usable once the FE/BE images
are baked):

- `compose/docker-compose.yml` — `doris-fe` (patched branch FE + our plugin), `doris-be`
  (patched branch BE — `JdbcJniScanner` is absent from stock 4.1.x, so the BE must be
  branch-built too), and `duckdb-quack` (a DuckDB CLI serving the Quack RPC protocol).
- `compose/bake-images.sh` — bakes the FE/BE overlay images from the `--build-fe`/`--build-be`
  output (`~/.cache/duckbridge/doris/output`); refuses to run while the BE build is still going.
- `compose/smoke.sh` — up → health → install plugin (`:doris-duckbridge:pluginZip`) →
  `CREATE CATALOG ... type=duckbridge` → drive the fail-loud stub (asserts on the P1–P6 probe
  message — that error is the correct scaffold state) → `down -v`.

See [`compose/README.md`](compose/README.md) for the three commands and prerequisites. The
plugin zip (`plugins/connector/duckbridge/` layout) is produced by the `pluginZip` gradle
task. Real behavioral assertions replace the stub-error checks as the probes (P1–P6) land.
