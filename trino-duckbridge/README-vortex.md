# Vortex Scan

A path-based table function that scans [Vortex](https://vortex.dev/) files, executed by
the DuckDB `vortex` extension. **EXPERIMENTAL** — the surface may evolve with the
upstream extension.

The function takes the file **path** directly: name a path, the connector scans it.

## Enabling

Off by default. Enable on the catalog:

```properties
duckbridge.vortex.enabled=true
```

When enabled, the connector `INSTALL`s + `LOAD`s the `vortex` extension on every DuckDB
connection. If the extension can't be installed or loaded, using the function fails
with install instructions — never a silent empty result.

On a **remote (Quack) server** the extension is a server-side concern: the connector
issues INSTALL/LOAD over the connection, but the server needs extension-repository
access (or the extension pre-installed).

## Scanning

```sql
SELECT * FROM TABLE(duckdb.system.vortex_scan(path => '/data/events.vortex'));
```

| Function | Arguments | Returns |
|---|---|---|
| `vortex_scan` | `path` (`.vortex` file) | the file's columns as-is |

Scalars, arrays, and unicode content round-trip.

## Filtering

A `WHERE` / `ORDER BY ... LIMIT` over the function is applied by Trino above the
function, not pushed into the generated vortex SQL. Results are correct either way.

## Extension versioning

The `vortex` extension comes from DuckDB's extension repository (latest build per
DuckDB version + platform; a hard version pin isn't possible). The connector's test
suite includes a canary that verifies the call shape against the currently served
build.

## Limitations

- **Read only.** No writes. Write vortex files with DuckDB or any vortex writer out of
  band, then scan them by path.
- **`s3://` paths** pass through to DuckDB as-is; the extension reads process-global
  `AWS_*` environment credentials, which the operator must provide.
