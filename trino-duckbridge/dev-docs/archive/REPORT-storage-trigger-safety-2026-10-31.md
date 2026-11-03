# Round 3: storage migration and triggered writes — 2026-10-31

## Status

The user authorized released **Quack JDBC 0.12.0** to resolve the count issue
found by this round. It preserves the tested
[snapshot implementation](REPORT-quack-0.12-count-snapshot-2026-10-31.md), with
only generated version metadata changed. Storage and strict triggered-DML tests
pass against the final artifact without a DuckBridge count workaround.

## Storage oracle and verified cases

`TestStorageCompatibility` launches independent child JVMs with **exactly one**
native JDBC release per process: 1.5.6.0 / engine v1.5.6, or production JDBC
2.0.0.0 / engine v2.0.0. The legacy driver is a separate test-only Gradle
configuration, never part of the plugin or parent test runtime classpath.
Every child verifies `SELECT version()` before acting.

All files are newly owned disposable fixtures. They include 12,000 seeded rows,
committed deletes, a primary key and explicit ART index, decimals, Unicode text,
arbitrary binary, dates/pre-epoch timestamps, typed NULLs, and a separate 65-column
wide table. Full ordered-value SHA-256 digests, schema width and explicit index
presence are compared across engines; no partial row sample substitutes for
correctness. Compression codec observations are retained in child logs.

1. **Old file read-only in 2.0:** data agrees with the old engine, and the entire
   file hash is unchanged by the read-only open.
2. **Old file written/checkpointed in 2.0:** update/delete changes persist and are
   read identically by 1.5.6. The existing storage-version tag is preserved.
   A newer writer does not automatically imply an old file has been upgraded.
3. **New 2.0 default-format file:** tag is `v2.0.0+`; a 2.0 reopen preserves its
   digest. The old reader fails explicitly with SQLException and storage-version
   69/newer-version diagnostics, not a native crash or empty result.
4. **New 2.0 file explicitly configured for v1.5.0 storage compatibility:** both
   engines read the same data before and after a 2.0 write/checkpoint.
   This is a fixture's real storage-format setting, not a renamed file or a new
   connector default.
5. **Explicit copy upgrade:** attach the original read-only and copy into a new
   latest-format file. Data, wide schema and index survive; the original and its
   backup remain byte-identical and readable by the old engine. The upgraded
   file is intentionally unreadable by that old reader.

Storage versions are read from `duckdb_databases().tags['storage_version']`, not
guessed from engine version or the raw file header: new-format files hold the
sentinel 999 after `DUCK`, which is not itself the logical format number 69.

## Operator upgrade / rollback runbook

1. Inventory **every** process and engine version that can touch each database
   file. Stop writers and close/checkpoint cleanly before copying it; copying
   only an active main file while ignoring its WAL is not a verified backup.
2. Keep a verified, immutable backup and hash outside the working copy. Test the
   old engine's read/digest on that backup before changing the deployment.
3. Qualify 2.0 on a disposable copy with an explicit read-only connection, then
   shadow the actual workload. Existing-format reads/writes passed these fixtures,
   but novel types, extensions and workload-specific storage features still need
   their own proof.
4. If old-reader compatibility is required for **new** files, choose an explicit
   supported storage compatibility version when creating them. Do not rely on
   2.0's default new-file format, and do not change a global connector default
   silently on the operator's behalf.
5. To adopt the new format, create a **different** file through explicit
   copy/export/import and validate data, constraints/indexes and application
   behavior before switching paths. Keep the original/backup untouched.
6. **Rollback boundary:** old processes cannot reopen a default 2.0-format file.
   Roll back to the preserved compatible file/backup, or perform and validate an
   explicit conversion with the new engine. Never treat a binary downgrade alone
   as a storage downgrade.

No production file, shared volume or operator-owned engine was opened by these
tests. Corruption recovery, WAL/crash fault injection, every compression/index
shape and concurrent cross-process writers are not claimed qualified.

## Trigger and write qualification

Trigger DDL is native DuckDB setup, not a new Trino trigger-management API.
`TestTriggeredWrites` runs actual Trino INSERT/UPDATE/DELETE on embedded and
direct Quack catalogs, checking affected source counts and exact target/audit rows:

- AFTER ROW INSERT/DELETE fire once per affected row, including Trino's staged
  write/final insertion path.
- BEFORE/AFTER STATEMENT timing, old/new transition tables and multiple-trigger
  creation order are checked independently of result row ordering.
- A trigger CHECK failure rolls back both partial target and audit modifications;
  the next autocommit write succeeds.
- Explicit JDBC transactions roll back target plus trigger effects after error,
  preserve the default aborted-transaction policy, and recover only after rollback.
- Native statement-trigger RETURNING returns its actual rows. Unsupported
  BEFORE ROW, row-level UPDATE and ROW-trigger RETURNING fail with the pinned
  engine's specific diagnostics and no target/audit mutation; these limitations
  are tested, not hidden with skips. Triggers do not appear as tables.

## Reproduce / evidence

```bash
./gradlew :trino-duckbridge:test --tests '*TestStorageCompatibility' \
  --tests '*TestTriggeredWrites' :trino-duckbridge:detekt
```

The original Quack 0.11.1 run returned the correct server `count_star()` value
through raw cursor/native wrapper, while JDBC reported zero. Strict count tests
are unchanged; the released 0.12.0 driver fixes classification itself. Candidate
evidence is retained in the [snapshot report](REPORT-quack-0.12-count-snapshot-2026-10-31.md);
final artifact checks are in the [release report](REPORT-quack-0.12-release-2026-10-31.md).

Storage/initial-trigger evidence: `build/round3-storage-writes/`; child-JVM logs
and disposable fixtures: `trino-duckbridge/build/storage-compatibility/`. No host
network tuning, source CAST rewrite, count inference or SQL replay is applied.

Full release verification passes: **916 Trino + 89 Doris tests**, zero
failures/errors/skips, three strict cancellation/teardown gates, package/isolation
checks, 22 Python checks and live default-version Java-17 FE/BE smoke. The final
artifact's independent hash audit also passes. Evidence and exact reproduction
commands are in the [0.12.0 release report](REPORT-quack-0.12-release-2026-10-31.md).
The user-directed temporary type-guard revisit remains after the benchmark round.
