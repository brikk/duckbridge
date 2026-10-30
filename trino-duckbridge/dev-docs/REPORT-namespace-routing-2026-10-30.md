# Round 1: namespace discovery and exact routing — 2026-10-30

## Qualified policy

Both connectors expose **flat schemas in the connection's current DuckDB
catalog**. A dot inside a flat schema/table name remains a literal character,
not an implicit path separator. Quotes, keywords and metadata pattern characters
must identify exactly the requested object.

**True nested schemas are rejected explicitly at metadata resolution.** Standard
JDBC metadata loses their ancestry: both native JDBC 2.0.0.0 and Quack JDBC 0.11.1
report the nested child as its leaf name, indistinguishable from a same-named
root schema. `duckdb_schemas()` separately exposes `parent_schema_oid`, so the
connector detects the unsupported situation before advertising or resolving a
misleading namespace. No flattening, path encoding, view substitution or new
session `USE` convention was invented.

This is a catalog-level guard: **a current catalog containing nested schemas
cannot use normal host schema/table metadata**, including its flat siblings.
That limitation is intentional and actionable, rather than silently omitting
objects or routing a leaf name to another table. A flat schema whose literal
name contains dots does not trigger the guard. Explicit native DuckDB SQL is a
separate escape-free way to address native paths; it is not claimed as host
namespace support. Full nested addressability needs an explicit path-aware
namespace/metadata design before implementation.

## Reproduced bugs and fixes

1. **Lost ancestry:** native SQL creates `finance.reports`, its child `q3`, and
   flat `"finance.reports"` as distinct schemas. With a root `reports`, both
   drivers return two `reports.t` entries from an exact metadata request. New
   guards throw NOT_SUPPORTED / DorisConnectorException naming the nested schema,
   parent and catalog. They use current engine catalog state, not a stale URL cache.
2. **Unescaped schema patterns in Trino:** `names_a` also matched `namesxa`,
   producing multiple matches for a single table. Escape schema names using the
   same JDBC metadata literal-pattern helper already used for table names.
3. **Wildcard table/column merging in Doris:** `_` and `%` in requested object
   names could match another table and merge columns, or report a nonexistent
   literal table as present. Escape both schema and table patterns and verify
   returned catalog/schema/table identities before accepting their rows.
4. **Catalog scope in Trino:** table lookup now uses the connection's current
   catalog, consistent with schema listing, rather than asking metadata for all
   attached catalogs under one flat host namespace.
   Quack JDBC's `Connection.getCatalog()` is a null stub, so passing it to JDBC
   metadata does not scope anything. Both connectors now discover the actual
   current catalog through `current_database()` rather than guessing from an
   ATTACH listing. The final competing-catalog fixture reproduced this additional
   null-catalog hole before the correction (`final-namespace-check.log`).

The original red tests are preserved in
`build/round1-namespaces/routing-before-fix.log`; native CLI/driver observations
are retained alongside them. No fixture is skipped to suppress these failures.

## Real verification

`TestNamespaceContracts` pins raw native/Quack ancestry loss and metadata spelling.
`TestNamespaceRouting` exercises native JDBC, direct Quack JDBC, native Arrow and
Quack Arrow for literal dotted schema names, keywords, quotes, schema/table
wildcards, and exact row values. Create, bind/write, table rename and drop keep
each literal component intact. A competing attached catalog cannot inject its
same-named table into the current host namespace. Nested schemas fail before
their leaves can resolve a different physical object.

Doris's live Quack metadata suite checks literal dotted names, escaped wildcard
table/column lookup, rejection of nonexistent literal names, nested-schema
refusal and actual production query-builder SQL returning the intended row.
Its Java-17/native JNI boundary and existing type policy are unchanged.

```bash
./gradlew :trino-duckbridge:test --tests '*TestNamespaceContracts' \
  --tests '*TestNamespaceRouting' \
  :doris-duckbridge:test --tests '*TestDuckBridgeDorisMetadataOverQuack' \
  :trino-duckbridge:detekt :doris-duckbridge:detekt
```

Full Trino/Doris checks, lint and package/isolation verification pass:
**897 Trino + 88 Doris tests**, zero failures/errors/skips. Evidence:
`build/round1-namespaces/final-full-checks.log`, `final-test-summary.json` and
saved `final-test-results/`. Neither upstream reaping/JNI fixes nor the separately
diagnosed published-port HTTP stall is represented as resolved by this round.
