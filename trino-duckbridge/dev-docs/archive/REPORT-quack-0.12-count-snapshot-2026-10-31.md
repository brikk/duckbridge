# Exact Quack snapshot: triggered-DML counts — 2026-10-31

## Artifact identity and scope

User-authorized test of published timestamped snapshot
`dev.brikk.duckdb:quack-jdbc:0.12.0-20261031.051948-3` (snapshot base coordinate
`0.12.0-SNAPSHOT`). The requested immutable timestamp/build number is downloaded
directly from Sonatype, not resolved as whichever moving snapshot is newest.

- JAR SHA-256:
  `9ea4e7988388f267b29741b190dd7d389703fb40cb0a89180cfbf45ed6c543b5`.
- Published SHA-1 verified:
  `7dcee954ad9e3882ebdff083ab71bc7a5989fad7`.
- Embedded Maven and runtime driver identities are **0.12.0-SNAPSHOT**.
- All 107 base classes are Java-17 classfile 61; no new runtime dependencies.
- The exact timestamped source JAR returned 404. No different source build or
  timestamp was substituted, and no source-commit identity is inferred.

The downloaded bytes/POM are copied into an exclusive local qualification
repository. The init script selects only that copy and checks the actual test /
JavaExec classpath driver hash before execution. Both committed production
Quack pins remain **0.11.1**. No driver publication/adoption or peer build occurs.

Evidence root:
`build/quack-snapshot-review/0.12.0-20261031.051948-3/`.

## Count-fix acceptance

```bash
./gradlew --init-script build/quack-snapshot-review/0.12.0-20261031.051948-3/qualification.init.gradle \
  :trino-duckbridge:test --tests '*TestQuackTriggeredCountContracts' \
  --tests '*TestTriggeredWrites' --tests '*TestStorageCompatibility' \
  :trino-duckbridge:detekt
```

The unchanged strict trigger count/row/audit assertions now pass. No consumer
UPDATE/DELETE count adapter, count re-query, inferred count, SQL replay or driver
patch was added in DuckBridge.

- Triggered INSERT `execute()` is classified as DML (false), with update count 2,
  rather than an ordinary result set and an unknown/zero count.
- Raw cursor and independent native Quack wrapper still return the server's real
  numeric `count_star()` result, value 2. Final target/audit rows prove no replay.
- Prepared connector UPDATE/DELETE report the exact affected source counts.
- Row/statement trigger effects, BEFORE/AFTER timing, trigger creation order,
  autocommit trigger-failure atomicity and explicit rollback/invalidation pass.
- Ordinary SELECT count and numeric aliases `Count`, `rows_affected`, and
  `"count_star()"` remain result sets in Statement and PreparedStatement paths.
- CTE SELECT is still a query; CTE INSERT reports its real count. Leading comments
  and string/bound values containing `RETURNING` do not change classification.
- DML RETURNING stays a result set even when its numeric returned column has one
  of those count-like aliases, for both ordinary and prepared statements.
- Statement and PreparedStatement batch counts are exactly `[1,1]`, with the
  expected source/audit cardinality. Trigger DDL/inspection remains native SQL;
  no new host trigger-management API is claimed.
- Storage compatibility child-JVM checks remain independent of the candidate.

The original 0.11.1 failure/observation evidence is retained. The strengthened
count contract tests would fail that baseline; no assertion is relaxed to make
the candidate pass. Focused run: `count-and-trigger-checks.log`.

## Broader regression and remaining limits

Completed broader verification against the same checked candidate bytes:

- **915 Trino + 89 Doris tests**, zero failures/errors/skips; both full module
  checks, lint and assembly/audit isolation verification pass
  (`full-consumer-checks.log`). Candidate XML is saved under `test-results/`, with
  exact totals in `test-summary.json`, before normal baseline runs overwrite the
  module output directories.
- CANCEL-under-partition, healthy cancellation/SQLState 57014 and independently
  bounded DISCONNECT gates all pass. The known published-port streaming problem
  and deferred lease-reaping task were not rerun or counted as passing here.
- Both candidate packages contain exactly one real `quack-jdbc-0.12.0-SNAPSHOT.jar`
  matching the requested timestamped bytes; the Doris candidate ZIP is preserved
  under `packages/`.
- Live exact-pin **Java-17 Doris FE/BE smoke passes**: security, metadata,
  values/EXPLAIN, temporal edges, sequential/concurrent queries, catalog lifecycle
  and persisted restart, JNI lazy loading, plugin-scoped failure and recovery
  (`doris-live-smoke.log`). No native engine rebuild occurred.
- The Java-17 boundary audit independently records `0.12.0-SNAPSHOT` and the exact
  candidate SHA-256 (`java17-boundary-types.json`, `java17-boundary-audit.log`).
- **22 Python compose/audit/bootstrap checks** and smoke shell syntax pass. The
  smoke's historical `0.11` lane label now prints the actual staged driver name;
  qualification still checks the candidate filename/hash, not that cosmetic label.

Ordinary checks are not treated as proof that every deferred acceptance task passes.

In particular, this count fix does not resolve the diagnosed host published-port
TCP stall, server autonomous orphan reaping, native JDBC TUPLE-list string-cast
crash, or Doris JNI UTF-8 issue. The temporary type guard and its user-directed
revisit TODO remain intact. No network workaround or silent source CAST is used.

Final-artifact adoption and completing/pushing the Round 3 tests remain separate
from this user-authorized candidate test; do not publish a red baseline suite by
pretending 0.11.1 contains the count fix.

## Consumer verdict and restoration

**The exact candidate is green for the triggered-DML count fix from DuckBridge's
consumer side**, including result classification controls and broad regressions.
An actual final release still needs its own version/artifact identity verification
and user-approved adoption; no source commit was inferred from the unavailable
timestamped source JAR.

The run-owned stack was stopped **without deleting volumes**. Commands without
the qualification init script restored both normal package outputs and the staged
BE driver to published **0.11.1**, SHA-256
`8cff98a94eb793569f3c6de0bc840edfc7ba9bb1118e5fb0f30ac0429d9fe3b3`.
The version catalog is unchanged. Existing baseline network and pooled lifecycle
tests pass with no qualification properties (`restore-baseline.log`); the new
trigger-count assertions remain correctly incompatible with 0.11.1.
No driver publication, production dependency switch or remote push occurred.
