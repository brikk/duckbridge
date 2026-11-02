# doris-duckbridge

Apache Doris → DuckDB connector: the Doris sibling of `trino-duckbridge`. Doris pushes
scans/projections/predicates into a user-operated DuckDB (remote via the Quack
protocol, `jdbc:quack://…`), using Doris's `fe-connector` plugin SPI and the BE's
existing JDBC JNI scanner.

**Status:** read-only development connector for the pinned Doris master baseline and
DuckDB **2.0.0**. The matched FE/BE runtime is locally qualified for correctness;
this is not a production rollout or performance claim. The plugin bundles its Quack
JDBC driver; dependency versions are pinned in
[`../gradle/libs.versions.toml`](../gradle/libs.versions.toml).

Metadata, JDBC scans, predicate/function pushdown, supported temporal values,
LARGEINT, ARRAY, VARBINARY and concurrent scans run through DuckDB/Quack.
Schema discovery keeps literal dotted names distinct and rejects catalogs containing
true nested schemas rather than flattening them.

## Limitations

- Read-only: no connector write support.
- Function pushdown uses a [curated allowlist](dev-docs/REPORT-doris-duckdb-function-divergence.md),
  not the Trino connector's catalog. JSON mutation and OVERLAY functions are not enabled.
- Untyped NULL requires a declared source type. Packed TIMETZ is rejected rather than
  exposed as misleading time text; native temporal precision is limited to six digits.
- Autonomous server orphan-session expiry and published-port streaming have open
  acceptance issues; see the [categorized backlog](../dev-docs/TODO-backlog.md).

## Doris baseline & patches

This connector requires the exact apache/doris source baseline below and runs on a
pristine FE plus one patched BE component. Do not substitute an unqualified Doris
release or mix FE/BE pins. Everything is pinned and scripted:

- **Pin**: [`../doris-patches/BASELINE`](../doris-patches/BASELINE) — the exact commit on
  apache/doris master.
- **Patches**: [`../doris-patches/`](../doris-patches/) — only the BE `DuckDbTypeHandler`
  (LARGEINT/ARRAY/VARBINARY-faithful JDBC value coercion). Rationale, apply procedure,
  and the re-vendor log: [`../doris-patches/PATCHES.md`](../doris-patches/PATCHES.md).
- **Script**: [`../tools/doris-baseline.sh`](../tools/doris-baseline.sh)
  - `--check-only` (default) — blobless-clone master at the pin into
    `~/.cache/duckbridge/doris` (`DORIS_SRC` overrides) and verify all patches apply
    cleanly (`git apply --3way --check`, fail loud on drift).
    An existing checkout/worktree must be clean; staged/untracked work is never reset or removed.
  - `--install-spi-jars` — build `fe-connector-spi` / `fe-thrift`
    from the **patched pin** into the project-local repo `doris-duckbridge/doris-m2/`.
    This is the **required bootstrap** before first build.
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
   (patched exact-pin BE), and `duckdb-quack` (a DuckDB CLI serving the Quack RPC protocol).
- `compose/bake-images.sh` — bakes the FE/BE overlay images from the `--build-fe`/`--build-be`
    output (`~/.cache/duckbridge/doris/output`); uses source-qualified image names, requires an
    ASAN BE, and refuses to run while the build is still going.
- `compose/smoke.sh` — install plugin → up → persisted-FE restart → health → catalog/metadata →
  lazy-JVM transition → exact scan/pushdown/concurrency assertions → `down -v`.

See [`compose/README.md`](compose/README.md) for the three commands and prerequisites. The
plugin zip (`plugins/connector/duckbridge/` layout) is produced by the `pluginZip` Gradle task.

Internal scan/runtime design: [JDBC scan contract](dev-docs/DESIGN-jdbc-scan-contract.md).
Qualification evidence and baseline history are kept in [`dev-docs/`](dev-docs/)
and [`doris-patches/PATCHES.md`](../doris-patches/PATCHES.md), rather than this setup guide.
