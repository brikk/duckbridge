# duckbridge

**Trino → DuckDB connector with remote protocol support (Quack) and real function
and predicate pushdown.**

Point Trino at a DuckDB you operate — an embedded database file, or a remote DuckDB
server speaking the [Quack](https://github.com/gizmodata) RPC protocol — and query it
with pushdown that goes far beyond the usual JDBC-connector floor: projections, domain
predicates, `LIMIT`/`ORDER BY ... LIMIT`, and a large catalog of scalar functions are
executed inside DuckDB instead of in Trino.

Pushdown uses a curated function catalog, argument/type gates and differential tests
against Trino. Some calls use native DuckDB SQL; others use the `trino_parity` extension.
See the [pushdown reference](trino-duckbridge/README-pushdown-reference.md) for supported
cases, local fallbacks and **known unresolved math/timezone limitations**.

## Install

The connector is a standard Trino plugin (a directory of jars under `$TRINO_HOME/plugin/`).

**From a release (recommended).** Download an archive from the
[Releases](https://github.com/brikk/duckbridge/releases) page and extract it into the Trino
plugin directory on the coordinator **and every worker**, then restart:

```sh
tar -xzf /path/to/downloaded-plugin.tar.gz -C "$TRINO_HOME/plugin/"
```

Both `.tar.gz` and `.zip` are published, each with a `.sha256` sidecar. The version scheme is
`<trino-major>-<semver>`; use an archive targeting your Trino major version.

**From source.** Requires JDK 25 (the Gradle toolchain resolves it on demand):

```sh
./gradlew :trino-duckbridge:pluginAssemble
cp -r trino-duckbridge/build/trino-plugin/trino-duckbridge-* "$TRINO_HOME/plugin/"
```

**Compatibility.** Trino **483**; embedded DuckDB engine **2.0.0** (bundled JDBC driver). A remote
Quack server should run the **same** DuckDB version (the `trino_parity` extension is version-pinned —
see below).

The plugin bundles its JDBC drivers. Driver versions are pinned in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml).

Schema discovery is scoped to the connection's current DuckDB catalog. Flat names containing dots
are quoted literally; catalogs containing true nested schemas fail explicitly rather than flattening
their ancestry.

Unsupported VARIANT/TUPLE/untyped-NULL carriers and unsafe composite text conversions
are rejected explicitly. A user-requested source cast is a different, explicit type
choice—not typed support for the original value.

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
connector connects with the pure-JVM `quack-jdbc` driver. The connector does not start
or manage the server; configure the required server-side extensions as described below.

## Configuration

`connector.name` and `connection-url` are the only required properties. The transport is chosen
from the URL scheme: `jdbc:duckdb:<path>` (embedded) or `jdbc:quack://host:port` (remote Quack).

| Property | Default | Description |
|---|---|---|
| `connector.name` | — | Must be `duckbridge`. |
| `connection-url` | — | `jdbc:duckdb:/path/to.db` (embedded) or `jdbc:quack://host:port` (remote). |
| `duckbridge.string-pushdown.mode` | `PARITY` | `NULL_ONLY` \| `GUARDED` \| `BINARY` \| `FULL` \| `PARITY` (see dial below). |
| `duckbridge.allow-unsigned-extensions` | `false` | Open embedded DuckDB with `allow_unsigned_extensions`, disabling extension signature checks. Only needed for locally built, unsigned development binaries; release packages bundle signed community artifacts. |
| `duckbridge.parity-extension-path` | — | Development override for a directly loadable extension path (embedded or server-side Quack). Community-signed builds must use their installed layout and are bundled automatically on supported platforms. |
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

### Type mapping

Supported scalar mappings on the production JDBC path (embedded and Quack):

| DuckDB | Trino | Notes |
|---|---|---|
| `BLOB` | `VARBINARY` | Exact bytes; enables real table-column hash pushdown. |
| `TIMESTAMPTZ` | `TIMESTAMP(6) WITH TIME ZONE` | Instant preserved at microseconds; represented in the Trino query session zone. |
| `TIME` | `TIME(6)` | Microsecond precision. |
| `TIME WITH TIME ZONE` | `TIME(6) WITH TIME ZONE` | Exact for minute-aligned offsets; DuckDB offset-second values fail loud because Trino cannot represent them. |
| `UUID` | `UUID` | Exact 128-bit UUID; domain pushdown remains disabled pending an ordering proof. |
| `UTINYINT` / `USMALLINT` / `UINTEGER` | `SMALLINT` / `INTEGER` / `BIGINT` | Lossless widening. |
| `UBIGINT` | `DECIMAL(20,0)` | Full unsigned 64-bit range, including Quack's signed-long wire representation. |
| `HUGEINT` / `UHUGEINT` | unsupported | Their extrema need 39 digits; Trino `DECIMAL` stops at 38. Never truncated or coerced by default. |

`TIMESTAMP_S` / `TIMESTAMP_MS` read as Trino `TIMESTAMP(0)` / `TIMESTAMP(3)`; ordinary DuckDB
`TIMESTAMP` reads as `TIMESTAMP(6)`. Scalar `TIMESTAMP_NS` and `TIME_NS` map to
`TIMESTAMP(9)` and `TIME(9)` on the qualified JDBC and Arrow paths. Timestamp/time
precision 7–9 writes use the corresponding native nanosecond storage types.

Transport-specific limits remain explicit:

- Native JDBC refuses `TIMESTAMP_NS` arrays and `TIMESTAMPTZ_NS` scalars because their
  available carriers lose information. Direct Quack JDBC and the Arrow paths support
  the qualified forms.
- Both Arrow paths refuse `TIME WITH TIME ZONE` because the producer drops its offset;
  the normal JDBC path supports the minute-aligned values described above.
- Temporal precision above 9 is not supported. Nanos predicates/functions stay local;
  read/write support does not imply pushdown.

Writes also support `VARBINARY`, `UUID` and the supported microsecond temporal types.
See the [type/carrier coverage](trino-duckbridge/dev-docs/TODO-datetime-timezones.md)
for the exact path matrix and remaining refusals.

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

- **Column projection and predicates** (`TupleDomain`), subject to type-specific gates.
- **Scalar functions**: string, math, date/time, and more (see the full
  [pushdown reference](trino-duckbridge/README-pushdown-reference.md)). Most emit plain
  DuckDB SQL natively; a small set that DuckDB can't match is backed by the
  `trino_parity` extension. Per-conjunct: unsupported conjuncts stay in Trino,
  supported ones push.
- **LIMIT** and **`ORDER BY ... LIMIT` (TopN)**.
- Selected functions over `TIMESTAMP WITH TIME ZONE` require
  `pushdown_timestamp_with_timezone` (default on); the connector aligns DuckDB's session
  `TimeZone` with Trino's. This is not a blanket switch for every timezone expression.

### String pushdown mode dial

`duckbridge.string-pushdown.mode` (per-query override:
`string_pushdown_mode`) dials how much string pushdown you trust; the default is
`PARITY`. Non-string comparisons are independent of this dial but still need their
own function/type gates; lowering the mode does not fix known math/timezone defects.

| mode | string `=`/range/IN/TopN | retained filter | string LIKE | ALIAS fns (ICU/hash) | extension |
|---|---|---|---|---|---|
| `NULL_ONLY` | not pushed (only `IS [NOT] NULL`) | — | no | no | not needed |
| `GUARDED` | pushed remotely **and kept** locally (exact pre-filter; `LIKE 'foo%'` still gets its prefix range for free) | yes | no | no | **not needed** |
| `BINARY` | eligible for full pushdown after comparison probe | when required by domain compaction | yes | no | not needed |
| `FULL` | eligible for full pushdown (caller-asserted; no probe) | when required by domain compaction | yes | no | not needed |
| `PARITY` *(default)* | eligible for full pushdown after comparison probe | when required by domain compaction | yes | **yes** | required |

`GUARDED` retains the exact VARCHAR value-domain filter in Trino and withholds
NUL-bearing domains. It is not a general repair for function defects.
`BINARY`/`PARITY` run a comparison probe on each connection and fail explicitly on
divergence. The reference explains the domain, expression and TopN rules separately.

### The `trino_parity` extension

The [`trino_parity` DuckDB extension](https://github.com/brikk/duckdb-trino-parity-extension)
provides the connector's ten native parity functions: ICU case
mapping / trim / `normalize` (`lower`, `upper`, `reverse`, `trim`, `ltrim`, `rtrim`,
`normalize/1`) and the vendored-crypto hashes (`xxhash64`, `sha512`, `hmac_sha256`) —
these calls require the `PARITY` mode. Other catalog entries use extension-free
DuckDB SQL, subject to the gates and known limitations in the pushdown reference.

The extension is required only in the default `PARITY` string-pushdown mode (above), and
only for those ten functions. Missing required extension support is an explicit error,
not a quiet fallback.

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

- **Embedded** (`jdbc:duckdb:`): release packages bundle the signed DuckDB-2.0 community binaries
  for **Linux amd64/arm64, macOS arm64, and Windows amd64**. They are extracted into a
  process-private directory and loaded with signature verification enabled. Platforms without a
  published community build must use a local development binary with the explicit unsigned opt-in,
  or run in `GUARDED` mode.
- **Remote** (`jdbc:quack://`): the extension is a **server-side** concern — the connector never
  installs on the server. Because `quack_serve` serves one shared DuckDB instance and `LOAD` is
  instance-scoped, load it **once at server startup** and every client connection is covered — run
  the `LOAD` in the same session that starts the server:

  ```sql
  INSTALL trino_parity FROM community;  -- once per machine
  LOAD trino_parity;                    -- once per server instance
  CALL   quack_serve(...);
  ```

  Don't rely on autoloading here: DuckDB autoloads *core* extensions on first use, but a community
  extension's functions aren't in that map, so `INSTALL` without an explicit `LOAD` leaves
  `trino_meta()` unresolved. Alternatively, set `duckbridge.parity-extension-path` to a path the
  *server* can read — then the connector issues the instance-wide, idempotent `LOAD` while opening
  each connection. Either way every BINARY/PARITY connection runs one consolidated validation
  `SELECT`: `default_collation` + all comparison/ordering canaries, plus `trino_meta()` in PARITY.
  The fresh check validates extension availability and comparison settings rather than
  relying on cached assumptions about the server.
- **Running without the extension**: set `duckbridge.string-pushdown.mode=GUARDED` (or any
  non-`PARITY` mode). Extension-backed calls stay in Trino. Other pushdowns remain
  subject to their normal gates and the selected mode's string/TopN restrictions;
  disabling the extension does not fix the known native-function limitations.

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

## Apache Doris

The [`doris-duckbridge`](doris-duckbridge/README.md) sibling connects Doris to remote DuckDB/Quack
through Doris's connector SPI and matching JNI plugin runtime.

## Build & test

Requires JDK 25 (the Gradle toolchain resolves it on demand). Docker is needed for the
Quack integration tests; the lance/vortex extensions download from the network on first
use.

```sh
./gradlew :trino-duckbridge:test
./gradlew :trino-duckbridge:detekt
```

Dependency versions provided by Trino come from the Trino BOM;
`gradle/libs.versions.toml` only pins dependencies this project owns independently. An offline build
is supported after the exact task graph has been resolved online at least once:

```sh
./gradlew :trino-duckbridge:compileTestKotlin :trino-duckbridge:detekt
./gradlew --offline :trino-duckbridge:compileTestKotlin :trino-duckbridge:detekt
```

`--offline` never downloads missing artifacts or JDK toolchains; on a fresh/partial Gradle cache it
fails and names what must first be resolved online. Network-backed Lance/Vortex tests have their own
availability assumptions and are not made hermetic by Gradle's offline flag.

Development follow-ups are listed in the [categorized project TODO](dev-docs/TODO-backlog.md).
Detailed qualification evidence and change history live in the modules' `dev-docs/` directories.

### Building the parity extension

For a local plugin package, fetch the signed community binary matching the pinned
DuckDB version and current host first:

```sh
./.github/scripts/fetch-parity-extension.sh
```

For **extension development**, the source is pinned as a submodule at
`duckdb-trino-parity-extension/`. Its vendored DuckDB must match the root pin before using a local
build in place of the fetched binary:

```sh
cd duckdb-trino-parity-extension && make        # host platform
# optional cross-builds: make linux-amd64 / make linux-arm64
```

The build produces `build/release/extension/trino_parity/trino_parity.duckdb_extension`, which the
module's `bundleParityExtension` task copies into the plugin jar. Missing binaries are non-fatal at
build time — the jar just ships without that platform's variant.
