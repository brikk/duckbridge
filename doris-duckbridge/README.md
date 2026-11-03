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
- Function pushdown uses a [curated allowlist](README-pushdown-reference.md),
  not the Trino connector's catalog. JSON mutation and OVERLAY functions are not enabled.
- Untyped NULL requires a declared source type. Packed TIMETZ is rejected rather than
  exposed as misleading time text; native temporal precision is limited to six digits.
- Autonomous server orphan-session expiry and published-port streaming have open
  acceptance issues. Large results on affected Docker published-port routes may
  stall/truncate; abandoned server sessions may persist beyond their lease.

## Runtime requirements

Use the exact source pin in [`doris-patches/BASELINE`](../doris-patches/BASELINE):
a pristine matching FE, the patched matching BE TypeHandler and the complete
matching Java/JNI/plugin runtime. Do not mix FE/BE pins or scanner jars. This is
not a plugin for an arbitrary stock Doris release. It is separate from the Trino
483/484 distribution.

Install the prepared plugin under `plugins/connector/duckbridge/` on the FE. The
Quack driver must also be available at the allowed local `driver_url` path on
every relevant BE. The server is operated separately and must run DuckDB 2.0.0.

## Create a catalog

```sql
CREATE CATALOG analytics PROPERTIES (
    'type' = 'duckbridge',
    'jdbc_url' = 'jdbc:quack://duckdb-host:9494',
    'driver_class' = 'com.gizmodata.quack.jdbc.sql.QuackDriver',
    'driver_url' = 'file:///opt/duckbridge-drivers/quack-jdbc.jar',
    'password' = '<quack-token>'
);
```

The driver path must satisfy the configured local-path policy; remote driver URLs
are not accepted. TIMESTAMPTZ mapping exposes UTC fields in DATETIMEV2(6), not an
arbitrary per-value-zone type. Use the documented explicit-UTC predicate rules.

Build/bootstrap, patch maintenance and live-cluster fixtures are described in
[the developer guide](dev-docs/README.md).
