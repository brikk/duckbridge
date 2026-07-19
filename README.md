# duckbridge

A Trino → DuckDB connector plugin with parity-backed predicate pushdown.

`trino-duckbridge` lets Trino push scans and filters down into DuckDB. Pushdown
correctness is backed by the `trino_parity` DuckDB extension (a native
`.duckdb_extension` bundled into the plugin jar) so an expression is only pushed
when DuckDB is known to evaluate it with Trino-identical semantics.

## Transports & engine

- **T1 — embedded**: in-process DuckDB via the JDBC driver.
- **T3 — quack**: gizmo's pure-JVM `quack-jdbc` driver speaking DuckDB's Quack RPC
  (selected by a `jdbc:quack://…` connection URL).
- **T2 — Arrow engine** (gated): decodes DuckDB `arrowExportStream` batches straight
  to Trino Pages.
- **Lance / Vortex PTFs** (experimental, off by default): path-based table functions
  that scan lance datasets / vortex files and run lance vector / FTS / hybrid search
  via the DuckDB `lance` / `vortex` extensions.

## Layout

```
build-logic/                     # Gradle convention plugins (kotlin + JDK 25 toolchain)
gradle/libs.versions.toml        # version catalog
config/detekt/detekt.yml         # detekt ruleset
trino-duckbridge/                # the connector module
duckdb-trino-parity-extension/   # git submodule — the trino_parity DuckDB extension
```

The repo keeps `trino-` in the module name to leave room for a future
`doris-duckbridge` sibling; packages stay separated per engine.

## Documentation

- Module design notes & phase history: [`trino-duckbridge/dev-docs/`](trino-duckbridge/dev-docs/)
- Lance / Vortex PTF surface: [`trino-duckbridge/README-lance.md`](trino-duckbridge/README-lance.md)

## Build & test

Requires JDK 25 (the Gradle toolchain resolves it on demand via the Foojay
resolver). Docker is needed for the quack / testcontainers integration tests; the
lance / vortex extensions download from the network on first use.

```sh
./gradlew :trino-duckbridge:test
./gradlew :trino-duckbridge:detekt
```

## Parity extension submodule

The plugin jar bundles the native `trino_parity.duckdb_extension`. The submodule at
`duckdb-trino-parity-extension/` pins the source; the built artifact is **not**
checked in (build outputs are gitignored in the submodule). Build it once:

```sh
cd duckdb-trino-parity-extension && make        # host platform
# optional cross-builds: make linux-amd64 / make linux-arm64
```

The build produces `build/release/extension/trino_parity/trino_parity.duckdb_extension`,
which the module's `bundleParityExtension` task copies into the plugin jar. Missing
binaries are non-fatal at build time — the jar just ships without that platform's
variant.
