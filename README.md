# duckbridge

**Trino → DuckDB connector with remote protocol support (Quack) and real function
and predicate pushdown.**

Point Trino at a DuckDB you operate — an embedded database file, or a remote DuckDB
server speaking the [Quack](https://github.com/gizmodata) RPC protocol — and query it
with pushdown that goes far beyond the usual JDBC-connector floor: projections, domain
predicates, `LIMIT`/`ORDER BY ... LIMIT`, and a large catalog of scalar functions are
executed inside DuckDB instead of in Trino.

Pushdown correctness is held to Trino-identical semantics (NULLs, unicode, arithmetic
edge cases, date/time zone rules). Most scalar functions are emitted as plain DuckDB
SQL that DuckDB already evaluates identically to Trino (proven by per-function
cross-engine fixtures); only the handful DuckDB *cannot* match natively are backed by
the `trino_parity` DuckDB extension. When in doubt, an expression is not pushed — never
wrong results.

## Quick start

Catalog properties file (e.g. `etc/catalog/duckdb.properties`):

```properties
connector.name=duckbridge

# embedded DuckDB database file
connection-url=jdbc:duckdb:/data/analytics.db

# ... or a remote DuckDB reached over the Quack protocol
#connection-url=jdbc:quack://duckdb-host:9494
#duckbridge.quack.token=<token>          # or token-env / token-file
#duckbridge.quack.tls=true
```

```sql
SHOW TABLES FROM duckdb.main;
SELECT count(*) FROM duckdb.main.events WHERE length(user_agent) > 40;
```

For a remote server, the user runs their own DuckDB with `CALL quack_serve(...)`; the
connector connects with gizmo's pure-JVM `quack-jdbc` driver. Nothing is installed or
managed on the server by the connector.

## Pushdown

- **Projection + predicates** (`TupleDomain`): always on.
- **Scalar functions**: string, math, date/time, and more (see the full
  [pushdown reference](trino-duckbridge/README-pushdown-reference.md)). Most emit plain
  DuckDB SQL natively; a small set that DuckDB can't match is backed by the
  `trino_parity` extension. Per-conjunct: unsupported conjuncts stay in Trino,
  supported ones push.
- **LIMIT** and **`ORDER BY ... LIMIT` (TopN)**.
- `TIMESTAMP WITH TIME ZONE` functions push only when the
  `pushdown_timestamp_with_timezone` session property is on (default on); the connector
  aligns DuckDB's session `TimeZone` with Trino's.

### The `trino_parity` extension — only the functions DuckDB can't match

The [`trino_parity` DuckDB extension](https://github.com/brikk/duckdb-trino-parity-extension)
backs **only the functions whose semantics DuckDB cannot match natively**: ICU case
folding / trim / `normalize` (`lower`, `upper`, `reverse`, `trim`, `ltrim`, `rtrim`,
`normalize/1`) and the vendored-crypto hashes (`xxhash64`, `sha512`, `hmac_sha256`) —
10 functions in all. Everything else (the ~85 other pushable functions) is emitted as
plain DuckDB SQL that DuckDB already evaluates identically to Trino, verified by
per-function cross-engine fixtures.

When the planner promises pushdown the extension can't back, results could be wrong, so
for those 10 a missing extension is a hard, clearly-worded error — never a quiet
fallback.

- **Embedded** (`jdbc:duckdb:`): nothing to do. The extension binary is bundled in the
  plugin jar and loaded automatically on every connection.
- **Remote** (`jdbc:quack://`): the extension must be available to the *server* — either
  pre-loaded there, or reachable at a server-side path named by
  `duckbridge.parity-extension-path`. The connector probes for it on first use and fails
  with install instructions if it's absent.
- **Opting out**: set `duckbridge.parity.enabled=false` to run without the extension.
  This no longer disables *all* function pushdown — only the 10 extension-backed
  functions drop out; the ~85 natively-emitted functions still push, alongside
  projection, predicate (domain), and LIMIT/TopN pushdown. All queries remain correct —
  the 10 are simply evaluated by Trino above the scan.

## Lance and Vortex (experimental)

Optional table functions scan [Lance](https://lancedb.github.io/lance/) datasets and
[Vortex](https://vortex.dev/) files, and run Lance vector / full-text / hybrid search —
executed by the DuckDB `lance` / `vortex` extensions. Off by default; enable per
catalog:

```properties
duckbridge.lance.enabled=true
duckbridge.vortex.enabled=true
```

Details, function signatures, and examples:

- [README-lance.md](trino-duckbridge/README-lance.md) — `lance_scan`,
  `lance_vector_search`, `lance_fts`, `lance_hybrid_search`
- [README-vortex.md](trino-duckbridge/README-vortex.md) — `vortex_scan`

## Future development

- **Arrow columnar engine** — a columnar data plane that decodes DuckDB Arrow streams
  directly into Trino pages instead of row-by-row JDBC. Present in the codebase behind
  `duckbridge.execution-engine` for local benchmarking; not the production path yet.
- **Aggregate pushdown** (`COUNT`/`MIN`/`MAX`/`SUM`), parity-verified per aggregate.
- **Doris connector** — a `doris-duckbridge` sibling bringing the same parity-backed
  pushdown to Apache Doris (see [dev-docs/PLAN-doris-duckbridge.md](dev-docs/PLAN-doris-duckbridge.md)).

## Build & test

Requires JDK 25 (the Gradle toolchain resolves it on demand). Docker is needed for the
Quack integration tests; the lance/vortex extensions download from the network on first
use.

```sh
./gradlew :trino-duckbridge:test
./gradlew :trino-duckbridge:detekt
```

## Parity extension submodule

The plugin jar bundles the native `trino_parity.duckdb_extension`. The submodule at
`duckdb-trino-parity-extension/` pins the source; the built artifact is **not** checked
in. Build it once:

```sh
cd duckdb-trino-parity-extension && make        # host platform
# optional cross-builds: make linux-amd64 / make linux-arm64
```

The build produces `build/release/extension/trino_parity/trino_parity.duckdb_extension`,
which the module's `bundleParityExtension` task copies into the plugin jar. Missing
binaries are non-fatal at build time — the jar just ships without that platform's
variant.
