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
SQL that DuckDB evaluates identically to Trino — proven by differential fixtures that
run the same expression on Trino and on DuckDB and require identical outcomes; only the
handful DuckDB *cannot* match natively are backed by the `trino_parity` DuckDB extension.
When in doubt, an expression is not pushed — never wrong results.

## Install

The connector is a standard Trino plugin (a directory of jars under `$TRINO_HOME/plugin/`).

**From a release (recommended).** Download an archive from the
[Releases](https://github.com/brikk/duckbridge/releases) page and extract it into the Trino
plugin directory on the coordinator **and every worker**, then restart:

```sh
tar -xzf trino-duckbridge-v483-0.1.0.tar.gz -C "$TRINO_HOME/plugin/"
#  -> $TRINO_HOME/plugin/trino-duckbridge-v483-0.1.0/
```

Both `.tar.gz` and `.zip` are published, each with a `.sha256` sidecar. The version scheme is
`<trino-major>-<semver>` — e.g. `483-0.1.0` targets **Trino 483**.

**From source.** Requires JDK 25 (the Gradle toolchain resolves it on demand):

```sh
./gradlew :trino-duckbridge:pluginAssemble
cp -r trino-duckbridge/build/trino-plugin/trino-duckbridge-* "$TRINO_HOME/plugin/"
```

**Compatibility.** Trino **483**; embedded DuckDB engine **1.5.5** (bundled JDBC driver). A remote
Quack server should run the **same** DuckDB version (the `trino_parity` extension is version-pinned —
see below).

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
connector connects with the pure-JVM `quack-jdbc` driver. Nothing is installed or
managed on the server by the connector.

## Configuration

`connector.name` and `connection-url` are the only required properties. The transport is chosen
from the URL scheme: `jdbc:duckdb:<path>` (embedded) or `jdbc:quack://host:port` (remote Quack).

| Property | Default | Description |
|---|---|---|
| `connector.name` | — | Must be `duckbridge`. |
| `connection-url` | — | `jdbc:duckdb:/path/to.db` (embedded) or `jdbc:quack://host:port` (remote). |
| `duckbridge.string-pushdown.mode` | `PARITY` | `NULL_ONLY` \| `GUARDED` \| `BINARY` \| `FULL` \| `PARITY` (see dial below). |
| `duckbridge.allow-unsigned-extensions` | `false` | Open the embedded DuckDB with `allow_unsigned_extensions`, disabling extension signature checks. Only needed to `LOAD` a locally built `trino_parity`; the bundled community binary is signed and verified at `LOAD`. |
| `duckbridge.parity-extension-path` | — | Explicit path to `trino_parity.duckdb_extension`, overriding the bundled binary (embedded) or naming a server-side path (Quack). |
| `duckbridge.quack.token` / `.token-env` / `.token-file` | — | Quack auth; prefer `-env`/`-file` for secrets. |
| `duckbridge.quack.tls` | `false` | Use `https` for the Quack transport. |
| `duckbridge.lance.enabled` | `false` | Enable the Lance scan/search table functions ([README-lance.md](trino-duckbridge/README-lance.md)). |
| `duckbridge.vortex.enabled` | `false` | Enable the Vortex scan table function ([README-vortex.md](trino-duckbridge/README-vortex.md)). |
| `duckbridge.execution-engine` | `JDBC` | `JDBC` (production) or `DUCKDB_LOCAL`/`QUACK` (experimental Arrow data plane). |
| `duckbridge.duckdb.memory-limit` / `.threads` | — | DuckDB tuning for the experimental Arrow engines. |

Per-query **session properties** (override the catalog default): `string_pushdown_mode` and
`pushdown_timestamp_with_timezone`.

```sql
SET SESSION duckdb.string_pushdown_mode = 'GUARDED';
```

### Dynamic catalogs

With Trino's dynamic catalog management enabled (`catalog.management=dynamic` in `config.properties`),
create a catalog at runtime — same properties as the file, quoted keys:

```sql
CREATE CATALOG analytics USING duckbridge
WITH (
  "connection-url" = 'jdbc:duckdb:/data/analytics.db',
  "duckbridge.string-pushdown.mode" = 'PARITY'
);

-- remote Quack, secret from the worker environment rather than the SQL text:
CREATE CATALOG remote USING duckbridge
WITH (
  "connection-url" = 'jdbc:quack://duckdb-host:9494',
  "duckbridge.quack.token-env" = 'QUACK_TOKEN',
  "duckbridge.quack.tls" = 'true'
);

DROP CATALOG analytics;
```

The plugin must already be installed on every node; `CREATE CATALOG` only supplies properties.

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

### String pushdown mode dial

String comparison and ordering carry a collation trust question that numeric/date
predicates don't. `duckbridge.string-pushdown.mode` (per-query override:
`string_pushdown_mode`) dials how much string pushdown you trust; the default is
`PARITY`. Non-string predicates (`length(s)=5`, `id>3`, `year(d)=2000`) push in every
mode.

| mode | string `=`/range/IN/TopN | retained filter | string LIKE | ALIAS fns (ICU/hash) | extension |
|---|---|---|---|---|---|
| `NULL_ONLY` | not pushed (only `IS [NOT] NULL`) | — | no | no | not needed |
| `GUARDED` | pushed remotely **and kept** locally (exact pre-filter; `LIKE 'foo%'` still gets its prefix range for free) | yes | no | no | **not needed** |
| `BINARY` | fully pushed (probe-verified byte semantics) | no | yes | no | not needed |
| `FULL` | fully pushed (caller-asserted; no probe) | no | yes | no | not needed |
| `PARITY` *(default)* | fully pushed (probe-verified) | no | yes | **yes** | required |

`GUARDED` is the extension-free exact mode for plain OLAP filters: it pre-filters
remotely but re-checks every row in Trino, so results are identical to `NULL_ONLY` even
if DuckDB's collation were ever non-binary. `BINARY`/`PARITY` verify DuckDB's byte
semantics with a live comparison probe on first connection and fail loud on divergence
(e.g. a Quack server with a `nocase` collation) — telling you to drop to `GUARDED`.

### The `trino_parity` extension — only the functions DuckDB can't match

The [`trino_parity` DuckDB extension](https://github.com/brikk/duckdb-trino-parity-extension)
backs **only the functions whose semantics DuckDB cannot match natively**: ICU case
folding / trim / `normalize` (`lower`, `upper`, `reverse`, `trim`, `ltrim`, `rtrim`,
`normalize/1`) and the vendored-crypto hashes (`xxhash64`, `sha512`, `hmac_sha256`) —
10 functions in all. Everything else (the ~69 other pushable functions) is emitted as
plain DuckDB SQL that DuckDB evaluates identically to Trino, verified by differential
fixtures (Trino result vs DuckDB result for the same expression, incl. NULL / unicode /
domain-edge / zone inputs).

The extension is required only in the default `PARITY` string-pushdown mode (above), and
only for those 10 functions. When `PARITY` promises pushdown the extension can't back,
results could be wrong, so a missing extension is a hard, clearly-worded error — never a
quiet fallback.

The extension is published on the DuckDB **community-extensions** repository, so any DuckDB of the
matching version can obtain it — no manual binary management:

```sql
INSTALL trino_parity FROM community;
LOAD trino_parity;
```

`INSTALL` only downloads the binary (once per machine, to `~/.duckdb/extensions/…`); `LOAD` is what
registers the functions — and it is scoped to the **database instance**, not the connection. A
single successful `LOAD` therefore covers the instance; issuing it again is idempotent. The
connector deliberately issues that idempotent `LOAD` while opening each embedded connection (and
each remote connection when a server-side path is configured), because JDBC provides no stable
database-instance identity with which to detect a restarted/replaced instance safely.

How the connector gets it, per transport:

- **Embedded** (`jdbc:duckdb:`): on **Linux amd64/arm64** the signed binary is bundled in the
  release plugin jar and `LOAD`ed automatically on every connection — nothing to do. On other
  worker platforms (macOS / Windows) the release ships no binary; either install it once from
  community (`INSTALL trino_parity FROM community` in a DuckDB CLI of the **same** version) and set
  `duckbridge.parity-extension-path` to the resulting
  `~/.duckdb/extensions/<duckdb-version>/<platform>/trino_parity.duckdb_extension`, or run in
  `GUARDED` mode. The binary is extracted to a process-private temp directory and `LOAD`ed with
  DuckDB's signature verification **on** (`duckbridge.allow-unsigned-extensions` defaults to
  `false`); an unsigned local build is refused with a message naming the flag to set.
- **Remote** (`jdbc:quack://`): the extension is a **server-side** concern — the connector never
  installs on the server. Because `quack_serve` serves one shared DuckDB instance and `LOAD` is
  instance-scoped, load it **once at server startup** and every client connection is covered — run
  the `LOAD` in the same session that starts the server:

  ```sql
  INSTALL trino_parity FROM community;   -- once per machine
  LOAD   trino_parity;                   -- once per instance; covers all connections
  CALL   quack_serve(...);
  ```

  Don't rely on autoloading here: DuckDB autoloads *core* extensions on first use, but a community
  extension's functions aren't in that map, so `INSTALL` without an explicit `LOAD` leaves
  `trino_meta()` unresolved. Alternatively, set `duckbridge.parity-extension-path` to a path the
  *server* can read — then the connector issues the instance-wide, idempotent `LOAD` while opening
  each connection. Either way every BINARY/PARITY connection runs one consolidated validation
  `SELECT`: `default_collation` + all comparison/ordering canaries, plus `trino_meta()` in PARITY.
  That fresh one-round-trip check detects a restarted server, a missing extension, or changed
  collation before a pushed predicate can return wrong rows.
- **Running without the extension**: set `duckbridge.string-pushdown.mode=GUARDED` (or any
  non-`PARITY` mode). Only the 10 extension-backed functions drop out; the ~69 natively-emitted
  functions still push, alongside projection, predicate (domain), and LIMIT/TopN pushdown. All
  queries remain correct — the 10 are simply evaluated by Trino above the scan. (This replaces the
  former `duckbridge.parity.enabled=false`.)

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

## Parity extension

The `trino_parity` extension is published on the DuckDB
[community-extensions](https://duckdb.org/community_extensions/) repository, which is the primary
distribution — operators normally never touch a raw binary (see the transport table above). The
release build and CI fetch the signed community binary for the pinned DuckDB version and bundle the
Linux variants into the plugin jar (`.github/scripts/fetch-parity-extension.sh`).

For **extension development**, the source is pinned as a submodule at
`duckdb-trino-parity-extension/`; build it locally with:

```sh
cd duckdb-trino-parity-extension && make        # host platform
# optional cross-builds: make linux-amd64 / make linux-arm64
```

The build produces `build/release/extension/trino_parity/trino_parity.duckdb_extension`, which the
module's `bundleParityExtension` task copies into the plugin jar. Missing binaries are non-fatal at
build time — the jar just ships without that platform's variant.
