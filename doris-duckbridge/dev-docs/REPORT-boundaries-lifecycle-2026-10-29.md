# DuckDB-2 boundary and pool follow-up — 2026-10-29

## Scope

This closes specific JDBC/Quack observation and pool-reuse gaps, not all of
DB20-15/16/19. No native Doris patch, type masquerading, legacy NULL setting or
transaction-policy relaxation was applied. Native timestamp-nanos getters are
not sufficient proof of a host read/write/bind/predicate contract.

## Reproducible boundary capture

```bash
# Requires the run-owned development Quack server; default fixture token, or set
# FUNCTION_AUDIT_QUACK_TOKEN to the fixture's actual token without logging it.
./gradlew :doris-duckbridge:auditBoundaryTypes
```

Output: `doris-duckbridge/build/function-audit/boundary-types.json`.
The completed capture has **25 cases per transport (50 observations)**.
Both transport sessions are independent. Only a per-session TEMP table is created.
The audit captures result/table metadata, JDBC code/name/class/precision/scale,
raw object/string getters, timestamp getters where applicable, explicit
server-rendered temporal text, CTAS-from-NULL and actual settings. Errors are
retained, not relabeled unsupported or equated to successful values.

The native driver reports `DatabaseMetaData.getDriverVersion() == "1.0"` even
though the actual artifact is `duckdb_jdbc-2.0.0.0.jar`. Both driver JARs are
verified by their previously qualified SHA-256, and `SELECT version()` must report
DuckDB v2.0.0. The metadata stub is not used as version authority.

Observed contracts:

- Untyped `SELECT NULL`, `VALUES(NULL)` and CTAS remain logical **`"NULL"`**, not
  INTEGER. Native result metadata reports `"NULL"` / OTHER(1111); Quack result
  metadata reports NULL / Types.NULL(0), while Quack table metadata reports the
  source spelling `"NULL"` / OTHER. Typed NULL remains INTEGER in both drivers.
- All-NULL arrays/struct fields retain NULL child types. Anonymous rows are
  **TUPLE**: native JDBC exposes text/OTHER; Quack exposes a QuackStruct/STRUCT.
  These are not interchangeable transport contracts. No TUPLE mapping was enabled.
- **TIMESTAMP_NS raw getters preserve nine digits** in the released native JDBC
  and Quack drivers, including negative pre-epoch values. The earlier production
  comment blaming driver truncation is not true for these artifacts and is corrected.
  A source expression using a microsecond `TIMESTAMP '...'` literal before casting
  to TIMESTAMP_NS has already lost its extra digits; the audit captures that
  distinction separately from a direct string→TIMESTAMP_NS cast.
- Trino keeps TIMESTAMP_NS, TIMESTAMPTZ_NS and TIME_NS out of unqualified generic
  lower-precision JDBC mappings. The guard now says **unqualified host precision**,
  rather than falsely claiming that every driver getter truncates nanos.
- Native JDBC exposes TIMESTAMPTZ_NS as text/OTHER. Quack's current path errors
  with `Variable-width physical type INVALID is not supported`. No zoned-nanos
  support was enabled. Zoned fixtures use explicit UTC input, not an accidental
  comparison of zone-dependent naive inputs.
- **Doris TIMETZ correction:** Quack 0.10 returns its packed uint64 carrier through
  `getObject` **and `getString`** (e.g. `759942847184016295`), not time text
  `12:34:56.123456+05:30`. The current BE handler's STRING arm calls getString
  unchanged. The old Doris TIMETZ→STRING declaration therefore advertised false
  time text. It now fails loudly in metadata before scanning, with the column and
  specific packed-carrier reason. Unit and real Quack metadata tests cover this
  refusal, including array elements. No wire-format reinterpretation was added.
  Trino's existing explicit packed-TIMETZ decoder is separate and remains enabled.
- TIMESTAMP_S/MS round the selected negative fractional boundary to epoch;
  TIMESTAMP micros retains `1969-12-31 23:59:59.999999`. These are source cast
  observations, not a general host rounding policy.
- `preserve_identifier_case` is VARCHAR `preserve_case`;
  `legacy_disable_null_type=false`; transaction invalidation remains
  `ALL_ERRORS_INVALIDATE_TRANSACTION`. No global compatibility mode was set.

Doris's NULL mapper now names the actual NULL/`"NULL"`/SQLNULL spelling and
column, asks for an explicit supported source type, and fails before scanning.
The same refusal propagates through an all-NULL array element. Typed NULL still
uses its declared type. Existing documented Doris TIMESTAMP_NS→DATETIMEV2(6)
degradation was not silently widened or advertised as lossless.

## Pool-reuse proof

`TestQuackPooledLifecycle` uses the exact pinned BE's **HikariCP 6.0.0** with
`SELECT 1` validation, Quack JDBC 0.10.0, negotiated protocol v3 and DuckDB 2.0.0.
It configures exactly one physical pool slot to make reuse real, not a sequence
of unrelated fresh connections. Hikari is test-only, never packaged in the FE plugin.

Three tests pass:

1. Parser, binder and execution errors in autocommit mode, followed by a fresh
   borrower that must have autocommit=true and successfully return 42.
2. An explicit transaction with an uncommitted INSERT and conversion error is
   returned **without manual cleanup**. The next borrower must see zero inserted
   rows and a healthy session, proving the pool's rollback/reset path.
3. A prepared statement is reused with parameters, a failed conversion and a
   subsequent valid binding. A 100,000-row result is closed after its first row;
   the next borrower must execute successfully.

```bash
./gradlew :doris-duckbridge:test --tests '*TestQuackPooledLifecycle'
```

Evidence: `build/quack-pooled-lifecycle.log`. This tests the real pool/driver
combination, not BE JNI classloading. The live smoke separately covers actual
BE sequential/concurrent scans and catalog lifecycle. Heartbeat expiry, network
partitions, process restart, concurrent cancel/close, prepared schema changes,
Arrow-vector boundaries and retry-after-catalog-conflict remain open.

## Final verification

Full module checks and package verification passed: **85 Doris tests and 662
Trino tests, zero failures/errors/skips**. Cluster-free compose/intake/bootstrap
checks: **22 passed**. Java-17 audit compile/execution and audit-runtime isolation
passed. The assembled Doris production JAR contains no OVERLAY candidate class.
The published/live metadata contract and reviewed Trino generator corpus still
match. Main log: `build/function-followup-final-checks.log`.

The run-owned Doris/Quack stack was stopped without deleting volumes; no native
Doris patch, baseline change, release, push or production deployment was made.
