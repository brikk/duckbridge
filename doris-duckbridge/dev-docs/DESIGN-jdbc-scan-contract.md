# Doris JDBC scan contract

This is the current internal design reference for `doris-duckbridge`. Historical scaffold plans,
probe notes and re-vendor handoffs live in [`archive/`](archive/).

## Runtime boundary

- The FE plugin targets the connector API recorded in [`doris-patches/BASELINE`](../../doris-patches/BASELINE).
- The FE resolves DuckDB metadata through Quack JDBC and emits one JDBC scan range per query.
- The matching BE lazily loads the isolated `jdbc` JNI plugin. `table_type=DUCKDB` selects the
  patched `DuckDbTypeHandler`; the complete matching JNI SPI/bootstrap and plugin tree must ship
  together.
- FE and BE source versions must match the exact baseline before smoke fixtures are allocated.

## Planning and correctness

`DuckBridgeScanPlanProvider.planScan(session, request)` receives projected columns, a typed
connector expression, limit/count flags and partition metadata. DuckDB JDBC scans are not
partitionable, so the provider emits exactly one range carrying one `query_sql`.

The connector deliberately does **not** implement `applyFilter`. Doris therefore retains every
predicate and evaluates it again above the scan. This protects predicates that are not pushed, but
cannot recover a row that an incorrect remote predicate removed. Consequently:

- a predicate is pushed only when its DuckDB rendering is proven identical or a safe superset;
- unsupported/unknown forms are omitted from remote SQL and left to Doris;
- `LIMIT` is pushed only when every filter was rendered, avoiding truncation before residual
  evaluation;
- empty projection uses `SELECT 1`, not `SELECT *`, for row-count scans;
- ranges remain retryable: they execute a statement on read and are not consumable ADBC tickets, so
  `isSingleUse()` stays false.

Function pushdown uses the small audited Doris allowlist documented in
[`REPORT-doris-duckdb-function-divergence.md`](REPORT-doris-duckdb-function-divergence.md).
Temporal rendering follows [`REPORT-doris-timezone-probe.md`](REPORT-doris-timezone-probe.md):
naive timestamps use naive literals; TIMESTAMPTZ uses explicit UTC literals.

## Type transport

Metadata trusts DuckDB `TYPE_NAME`, not lossy JDBC type codes. Current native paths include
LARGEINT, ARRAY, VARBINARY, UUID and opt-in TIMESTAMPTZ. Unsupported types fail loudly rather than
silently changing semantics. The mapping evidence is in
[`REPORT-quack-jdbc-metadata-probe.md`](REPORT-quack-jdbc-metadata-probe.md).

The handler preserves arbitrary binary bytes and converts zoned timestamps to the JNI UTC-field
carrier, independent of DuckDB session and embedded-JVM default zones.

## Deployment and security

- `driver_url` may be an absolute `file://` URL or a filename under Doris's JDBC driver directory.
- The Quack token travels through the standard JDBC password field. Connector `toString()` methods
  redact it; FE catalog rendering provides its own password masking.
- The plugin ZIP excludes FE-provided connector SPI and Thrift jars.
- A failed JDBC plugin must remain plugin-scoped and must not terminate the BE.

## Qualification

The canonical build/runtime evidence, exact hashes and re-vendor history are in
[`doris-patches/PATCHES.md`](../../doris-patches/PATCHES.md). The Compose smoke additionally proves
persisted-FE restart identity, lazy JVM/plugin loading, exact values, pushdowns and bounded
concurrency.
