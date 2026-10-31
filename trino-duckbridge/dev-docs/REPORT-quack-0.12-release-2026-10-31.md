# Quack JDBC 0.12.0 release adoption — 2026-10-31

## Published artifact

User-authorized adoption of `dev.brikk.duckdb:quack-jdbc:0.12.0` from Maven
Central in both connectors. Release verification uses the normal dependency
graph, not a snapshot repository or qualification version/hash overrides.

- JAR SHA-256:
  `0fa0cc634d35cb2a77ff88232fe1f6a3fd1570ba54729c9cc50005c7b575f1cf`.
- JAR, POM and source JAR match their published SHA-256 and SHA-1 checksums.
- All 107 base classes are Java-17 classfile 61; Maven metadata says `0.12.0`.
- Release tag `v0.12.0` resolves to `9ef00f3c75433e9cb71d265ccd69df88147480bd`.
- Compared with independently tested `0.12.0-20261031.051948-3`, only embedded
  POM/version properties, DriverVersion and its inlined metadata/client-version
  consumers differ. Normalized disassembly has **zero differences** after version
  strings and constant-pool indices are normalized; the POM differs only by its
  version. No implementation change is inferred from metadata labels alone.
- DuckDB 2.0.0, native JDBC 2.0.0.0, Trino 483 and Doris 082736138b7 / API24 / JNI6
  are unchanged. The 1.5.6 storage oracle is isolated test tooling, never packaged.

Artifact and binary-comparison evidence: `build/quack-012-release-review/`.

## Count and result classification

The driver—not a DuckBridge SQL rewrite/count adapter—now classifies triggered
DML's actual `count_star()` result correctly. Strict source-count/audit checks
remain unchanged. Normal SELECT count/count-like aliases and DML RETURNING remain
result sets in Statement/PreparedStatement paths; CTEs, comments, string/bound
values and batch counts are qualified by the retained consumer fixtures.

[Round 3 storage/trigger qualification](REPORT-storage-trigger-safety-2026-10-31.md)
records the file-format and write/rollback contracts.

## Final release verification

- **916 Trino + 89 Doris tests**, zero failures/errors/skips, with no snapshot init
  script or qualification properties; full module checks and lint pass.
- Strict CANCEL-under-partition, healthy cancellation/57014 and bounded DISCONNECT
  acceptance tasks pass. Known published-port streaming and autonomous reaping
  issues remain separate, not declared passing from the default suite.
- Both package outputs contain exactly one `quack-jdbc-0.12.0.jar`, matching the
  published SHA-256. Assembly and audit-runtime isolation verification pass.
- Default-version live **Java-17 Doris FE/BE smoke** passes security, metadata,
  exact values and EXPLAIN, timezone/temporal edges, 20 sequential/eight concurrent
  scans, catalog lifecycle/persisted restart and JNI plugin isolation/recovery.
- Default Java-17 boundary audit independently records driver `0.12.0` and the
  exact published hash; the staged BE driver has the same hash.
- **22 Python checks** and smoke shell syntax pass.

Evidence under `build/quack-012-release-review/`: `full-checks.log`,
`round3-targeted.log`, saved `test-results/`, `test-summary.json`, candidate package,
`doris-live-smoke.log`, `java17-boundary-types.json`, Python logs and manifests.
The run-owned stack was stopped without deleting volumes; normal packages and
staged driver remain on the user-authorized released **0.12.0**.

## Unchanged limits

This driver release does not fix native JDBC TUPLE-list getString, the temporary
boundary-type guard, Doris JNI UTF-8/OVERLAY, autonomous server orphan reaping or
the diagnosed host published-port TCP stall. Those TODOs and original acceptance
assertions remain intact; no unsupported transport/type is silently promoted.
No peer source change, native rebuild, driver publication or host tuning occurs.
