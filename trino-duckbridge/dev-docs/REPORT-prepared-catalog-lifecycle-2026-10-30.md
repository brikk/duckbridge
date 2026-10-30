# Prepared-schema and catalog-conflict lifecycle — 2026-10-30

## Outcome

Independent of the deferred Quack CANCEL-deadline and orphan-reaper changes,
the new prepared-schema/retry checks pass against the current pinned artifacts:
DuckDB/native JDBC 2.0.0/2.0.0.0, Quack JDBC 0.11.0 and signed core f96420ed46.
No dependency or engine policy was changed to make them pass.

These are tests of real drivers and the production retry helper, not injected
SQLException strings. Explicitly unsupported peer acceptance tasks remain red
and separate; a passing default module suite does not qualify those contracts.

## Prepared statements under external DDL

`TestPreparedSchemaLifecycle` uses two actual connections for each transport
(embedded JDBC and Quack JDBC), with DDL performed on the other connection:

1. Reuse an explicit-column SELECT after ADD COLUMN and a committed INSERT:
   existing projected types remain correct and the new committed row is visible.
2. Reuse SELECT * after ADD COLUMN: result metadata gains the new column and
   the returned default value agrees with the new schema.
3. Drop and recreate the same table with INTEGER→BIGINT: the prepared query must
   not return old rows/types or truncate the new 9,000,000,000 identifier.
4. Rebind integer, typed SQL NULL and conversion-failing parameters, then prove
   recovery without leaving a poisoned connection or aborted implicit transaction.

The last case found a driver contract distinction, not a connector defect:
native JDBC's `DuckDBPreparedStatement.executeDirect()` deliberately calls close
in its `queryFailed` finally block. A failed native statement is therefore
asserted closed, reuse must fail explicitly, and a **fresh** prepared statement
must succeed on the same autocommit connection. Quack 0.11 keeps that statement
open; the same object is rebound successfully there. Neither path accepts wrong
rows or turns an unrelated error into a passing value comparison.

## Actual catalog conflicts and retries

`TestDuckDbCatalogWriteRetry` runs each case on three paths:

- Embedded JDBC to a shared database file.
- Direct Quack JDBC sessions sharing a real server.
- A native DuckDB connection using `quack_query_by_name`, the same wrapper and
  local autocommit path used by the executor's production server-LOAD retry.

One connection creates a schema inside an uncommitted transaction. The target's
CREATE SCHEMA IF NOT EXISTS encounters an **actual engine catalog write-write
conflict**. The competing connection can then commit or continue holding the write.

Four cases pass on all three transports:

1. Commit the competing write after the first real conflict. The production
   `retryConflicts` converges on exactly two attempts, with the schema present once.
2. Hold the write through all attempts. The retry limit is enforced and the
   exact last SQLException is propagated; the target autocommit connection
   remains usable after the competing transaction is rolled back.
3. Parser, binder and execution errors each run **once**, never as catalog retries.
   A fresh statement on the same connection must successfully return its control.
4. Interrupt backoff after a real conflict. Only one attempt runs, the original
   conflict is retained and the worker's interrupt flag is preserved.

The target retry connection remains in autocommit. This does **not** authorize
retrying arbitrary writes or continuing an aborted explicit transaction. The
production helper remains limited to idempotent-converging catalog operations;
no generic SQL retry, rollback substitution or transaction-policy relaxation
was introduced.

## Reproduce / evidence

```bash
./gradlew :trino-duckbridge:test \
  --tests '*TestPreparedSchemaLifecycle' \
  --tests '*TestDuckDbCatalogWriteRetry' :trino-duckbridge:detekt
```

**Eight targeted tests pass**, with both/two or three transport paths exercised
inside each test. Evidence: `build/prepared-catalog-lifecycle.log`.
Final Trino `check` and `verifyPluginAssembly` pass: **701 tests, zero
failures/errors/skips** (`build/lifecycle-final-checks.log`). The separate
CANCEL-partition and orphan-reaping acceptance tasks remain deferred/red as
directed by the user; they are not counted as passed by that module result.

Remaining work is explicitly tracked rather than hidden: deferred peer fixes
and their exact gates are described in
[`REPORT-lifecycle-qualification-2026-10-29.md`](REPORT-lifecycle-qualification-2026-10-29.md).
General schema-cache races inside a long-running Trino plan, every possible DDL
shape, cross-process metadata caches and all transactional write conflicts are
not claimed solved by these prepared-driver fixtures.
