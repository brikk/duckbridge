# doris-duckbridge

Apache Doris → DuckDB connector: the Doris sibling of `trino-duckbridge`. Doris pushes
scans/projections/predicates into a user-operated DuckDB (remote via the Quack
protocol, `jdbc:quack://…`), riding Doris's new `fe-connector` plugin SPI and the BE's
existing JDBC JNI scanner.

**Status:** qualified end-to-end against pinned Doris master `dd456ab7a89`, connector API 13 and
JNI plugin API 5. The matching ASAN runtime passed exact JDBC values, native UUID/TIMESTAMPTZ,
pushdowns, concurrency, lazy plugin loading, persisted-FE restart and plugin-scoped failure recovery.
Metadata, JDBC scans, predicate/function pushdown, temporal values, LARGEINT, ARRAY, VARBINARY,
and concurrent scans run through DuckDB/Quack. Design and probe history:
[`../dev-docs/PLAN-doris-duckbridge.md`](../dev-docs/PLAN-doris-duckbridge.md).

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

Integration testing uses the compose environment in [`compose/`](compose/):

- `compose/docker-compose.yml` — `doris-fe` (pristine pinned master FE + our plugin), `doris-be`
  (patched exact-pin BE — `JdbcJniScanner` is absent from stock 4.1.x, so the BE must be
  branch-built too), and `duckdb-quack` (a DuckDB CLI serving the Quack RPC protocol).
- `compose/bake-images.sh` — bakes the FE/BE overlay images from the `--build-fe`/`--build-be`
  output (`~/.cache/duckbridge/doris/output`); refuses to run while the BE build is still going.
- `compose/smoke.sh` — install plugin → up → persisted-FE restart → health → catalog/metadata →
  lazy-JVM transition → exact scan/pushdown/concurrency assertions → `down -v`.

See [`compose/README.md`](compose/README.md) for the three commands and prerequisites. The
plugin zip (`plugins/connector/duckbridge/` layout) is produced by the `pluginZip` Gradle task.
