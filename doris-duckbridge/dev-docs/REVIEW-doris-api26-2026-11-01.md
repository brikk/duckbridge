# Doris API-26 compatibility review — 2026-11-01

## Scope and exact inputs

User-authorized re-vendor from qualified master
`082736138b7f0e767f966cbb1434bc9ba85f29ec` to the handoff's exact
`8e10b235cf030d486b6840cfd64b6218ab515e6d`, **58 commits** later. This is a
targeted connector/source/build-input review, not an exhaustive review of all
upstream changes or a substitute for runtime qualification.

The donor is `/home/jayson/DEV/OSS/doris-ducklake-api26-8e10b235`; its pristine
SDK/FE/native outputs are reused only after independent artifact verification.
The patched build lives in a separate
`/home/jayson/DEV/OSS/doris-duckbridge-api26-8e10b235` worktree. The shared staged
Doris checkout and donor files are not modified.

## Contract changes and conservative handling

- Connector API **24→26** and filesystem API **6→7** require a real matched SDK
  and provider manifest. The same `1.2-SNAPSHOT` coordinates do not identify the
  source. All 29 files in the eight-module exported closure are hash-verified;
  the five compile JARs match the exact FE host copies. API-24 rollback coordinates
  and packages are preserved before project-local staging.
- `ConnectorScanPlanProvider.partitionBatchSize` is an additive default of 1024.
  DuckBridge still inherits `supportsBatchScan=false` and a negative streaming
  estimate, and still emits one synchronous JDBC range. An upstream producer/queue
  default change does not opt this connector into batch/streaming production.
- `ConnectorScanContext` adds shared cancellation/progress; requests and
  `withRequiredPartitions` retain the same context. New contract tests cover the
  actual API propagation and cancellation signal. They do not claim that this
  synchronous string-building provider or arbitrary running JDBC I/O gains a
  new cancellation/deadline contract merely by recompilation.
- `ConnectorTaskScheduler.remove` is additive, with a default no-op. DuckBridge
  does not start using the asynchronous scheduler during this re-vendor.
- The statement-scope contract now explicitly rejects loads after close. The
  underlying engine lifecycle correction is useful, but does not prove arbitrary
  catalog/DDL cache coherence or remote-server orphan-session cleanup.
- Writes, CDC/events, native partition planning, SQL-cache versions and sampling
  remain opted out. No type mapping, function pushdown or timestamp precision is
  expanded by the new API number.

## Wire and native boundaries

The inspected IDL delta adds optional row-TTL fields, an AI structured-output
option and optional Parquet `Statistics.nan_count` field 9. No renumbering of
our pinned report/Paimon fields is present in this delta; contract tests retain
`TReportExecStatusParams.PAIMON_COMMIT_MESSAGES=34` and
`TPaimonFileDesc.ORIGINAL_FILE_PATH=20`. This source check does not claim an
exhaustive cross-release wire audit. Matched FE/BE runtime identity and actual
JDBC scans remain required.

JNI API **6**, BE execution maximum **19**, Thrift **0.24.0** and the complete
upstream Java/JNI source subtree are unchanged. `build.sh` is also unchanged.
The canonical four-file handler delta is Java-only and still applies cleanly.
The identical-pin native ASAN executable and pinned build image can therefore
be reused, while rebuilding and packaging the **complete** shared/plugin Java
tree. Reusing only a scanner JAR or a different-pin native executable is not
permitted. Static plugin layout success does not establish reflective driver
load or JDBC correctness.

The native `be/src/util/jni-util.cpp` path is unchanged. This re-vendor does not
fix the previously reported ordinary-UTF-8/modified-UTF-8 SQL-literal corruption;
OVERLAY remains test-only/unpushed. Quack orphan reaping and the diagnosed host
published-port stall are likewise separate issues, not closed by this upgrade.

## Evidence and qualification

Exact source comparison evidence is retained in the owned output's
`api26-source-review/`: `facts.json`, `commits.txt`, `changed-contract-paths.txt`,
`spi.diff` and `idl.diff`. Source/SDK/native artifact identities and live test
results are tracked in [the qualification report](QUALIFICATION-api26-2026-11-01.md).
No claim of qualification follows from patch applicability or matching ABI
labels alone.
