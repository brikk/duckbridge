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

This connector targets Doris's `branch-catalog-spi` line (not in any release) and runs
only on a patched FE + BE. Everything about that is pinned and scripted:

- **Pin**: [`../doris-patches/BASELINE`](../doris-patches/BASELINE) — the exact Doris
  commit, mirrored as an immutable branch on the `brikk/doris` fork (upstream rebases
  GC their SHAs; the fork branch never moves).
- **Patches**: [`../doris-patches/`](../doris-patches/) — FE `SPI_READY_TYPES`
  whitelist for catalog type `duckbridge`, and the BE `DuckDbTypeHandler`
  (LARGEINT/ARRAY/VARBINARY-faithful JDBC value coercion). Rationale, apply procedure,
  and the re-vendor log: [`../doris-patches/PATCHES.md`](../doris-patches/PATCHES.md).
- **Script**: [`../tools/doris-baseline.sh`](../tools/doris-baseline.sh)
  - `--check-only` (default) — blobless-clone the fork at the pin into
    `~/.cache/duckbridge/doris` (`DORIS_SRC` overrides) and verify both patches apply
    clean (`git apply --3way --check`, fail loud on drift).
  - `--install-spi-jars` — build `fe-connector-api` / `fe-connector-spi` / `fe-thrift`
    from the **patched pin** into the project-local repo `doris-duckbridge/doris-m2/`
    (~1 min). This is the **required bootstrap** before first build.
  - `--build-fe` / `--build-be` — full patched FE/BE builds (opt-in, long; BE is a
    multi-hour C++ build; capped at 8 parallel jobs).

## Building this module

```sh
# one-time bootstrap (JDK 17 + thrift 0.16.0 required; both probed, fail loud)
tools/doris-baseline.sh --install-spi-jars

# then, from the repo root
./gradlew :doris-duckbridge:test :doris-duckbridge:detekt
```

The module resolves `org.apache.doris:*` **only** from `doris-m2/` (never
`mavenLocal()`), so it can't collide with other projects' Doris snapshot installs. If
you build before bootstrapping, gradle stops with the exact command to run.

Prerequisites: JDK 17 for the Doris/maven side (the gradle toolchain resolves it for
the module itself), `thrift` 0.16.0 for `fe-thrift` codegen (`DORIS_THRIFT` env or
`PATH`), Docker for the (future) integration environment.

## Test environment (planned)

Integration/probe testing needs a running patched FE + BE plus a DuckDB/Quack server —
all containerized: a compose setup (FE, BE, duck+quack) mirroring the trino module's
testcontainers Quack fixture and doris-ducklake's compose smoke layout. Lands with the
probe work (P1–P6); the FE/BE images come from `--build-fe`/`--build-be` output per
`PATCHES.md`.
