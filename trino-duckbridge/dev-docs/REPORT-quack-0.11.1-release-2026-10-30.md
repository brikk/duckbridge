# Quack JDBC 0.11.1 release adoption — 2026-10-30

## Identity and scope

User-authorized adoption of `dev.brikk.duckdb:quack-jdbc:0.11.1` from Maven
Central for both the Trino and Doris connectors. No snapshot init script,
qualification properties or local repository substitution is used in the release
verification commands.

- Published JAR SHA-256:
  `8cff98a94eb793569f3c6de0bc840edfc7ba9bb1118e5fb0f30ac0429d9fe3b3`.
- JAR, POM and source JAR match their published SHA-256 checksums. Embedded Maven
  identity is `0.11.1`; all 105 base classes are classfile 61 / Java 17.
- Release tag `v0.11.1` resolves to `58da468121737b1d12e3a4ebd099107ab9b0d603`.
  Its single commit after the tested snapshot changes only the POM version and
  release documentation; no Java implementation changed.
- All 51 tracked Java files in the published sources match the independently
  tested snapshot's source commit `4d3c966` exactly. Generated `DriverVersion`
  identifies the final version; the POM differs only in its version. No runtime
  dependency or semantic change is introduced by the release transition.
- DuckDB **2.0.0**, native JDBC **2.0.0.0**, Trino **483** and Doris
  **082736138b7**, connector API **24** / JNI API **6**, remain unchanged.

The version catalog, exact fixture defaults, Java-17 boundary audit identity/hash,
and live scanner smoke default now expect released 0.11.1. Historical snapshot
and 0.11.0 reports describe their original runs and are not rewritten as final
release evidence.

Artifact/source manifests and run evidence live under
`build/quack-0111-release-review/`. See the prior
[snapshot qualification](REPORT-quack-0.11.1-snapshot-qualification-2026-10-30.md).

## Verification

```bash
./gradlew :trino-duckbridge:qualifyQuackCancelPartition \
  :trino-duckbridge:qualifyQuackCancel \
  :trino-duckbridge:qualifyQuackDisconnect \
  :trino-duckbridge:qualifyQuackStreaming \
  :trino-duckbridge:check :trino-duckbridge:verifyPluginAssembly \
  :doris-duckbridge:check :doris-duckbridge:assemble

# No candidate version/hash environment variables; use the released default.
bash doris-duckbridge/compose/smoke.sh --no-build --keep
./gradlew :doris-duckbridge:auditBoundaryTypes
```

Final release verification passes with no snapshot overrides:

- All four explicit acceptance gates pass: CANCEL under prolonged response loss
  completes within the unchanged five-second fail-closed allowance while the
  ordinary request timeout remains 30 seconds; healthy cancellation returns
  SQLState 57014 and permits reuse; DISCONNECT is independently bounded; partial
  streaming fails explicitly after the deliberate network cut, never as EOF.
- **701 Trino + 85 Doris tests**, zero failures/errors/skips. Both module checks,
  lint and assembly/audit isolation verification pass. Doris's 85 tests ran with
  the released dependency in the initial command and were up-to-date in the
  final command. Saved XML and `test-summary.json` preserve the full counts.
- Both packages contain exactly one `quack-jdbc-0.11.1.jar` matching the published
  SHA-256, with no snapshot or older Quack driver alongside it.
- Default-version live Java-17 FE/BE smoke passes: security policy, exact
  metadata/values, predicate/function EXPLAIN, timezone/temporal edges, 20
  sequential and eight concurrent scans, catalog ALTER/drop/recreate and
  persisted restart, lazy JNI loading and plugin-scoped failure/recovery.
- The default Java-17 boundary audit independently records driver version
  `0.11.1` and the exact published JAR hash. The staged BE driver has that hash.
- **22 Python checks** pass (19 compose/audit, three baseline/bootstrap).

Evidence: `full-release-checks.log`, `doris-live-smoke.log`,
`java17-boundary-audit.log`, `java17-boundary-types.json`, the two Python logs,
saved `test-results/` and `packages/`. Tests retain their original deadline and
semantic assertions; a passing default suite alone is not acceptance evidence.

The run-owned stack was stopped without deleting volumes. Normal package outputs
and the staged BE driver remain on adopted **0.11.1**; no restoration to 0.11.0
is needed after this user-authorized production-pin update. Other running
services and peer checkouts were not changed.

### Initial streaming-response failures retained

The initial combined run passed the three CANCEL/DISCONNECT gates, but streaming
failed **before the intentional network cut** while opening its cursor. The
declared 33,570,767-byte initial body ended after 13,569,055 bytes. A standalone
reproduction similarly ended after 12,530,839 bytes. Neither is a successful
streaming-fault assertion; the initial run stopped before the full Trino suite.
Evidence: `consumer-checks.log`, `streaming-reproduction.log`.

Diagnostic control runs then passed with both the previously tested snapshot and
the released JAR, with the same eight-million-row SQL and unchanged assertions.
Captured proxy logs show the complete initial response forwarded in each passing
control before the test itself disables the proxy. The released sources match
the snapshot; normalized disassembly also shows no change in `readResponseBody`.
Docker event history shows fixture teardown, not an earlier recorded OOM kill.
These observations do **not** establish the cause of the earlier truncations.
No error was accepted as a substitute for reaching the deliberate cut, no query
was retried in production, and no timeout, row count or budget was relaxed.
The initial failures remain an unresolved intermittent qualification observation,
not a diagnosed engine, driver or environment defect. A subsequent complete
release command passed all four gates and module checks unchanged. The shared
follow-up TODO retains the truncation investigation alongside server reaping;
the passing rerun is not presented as proof of a truncation fix.

## Remaining limits

The driver patch bounds the CANCEL caller and fails closed on an unconfirmed
acknowledgement without lowering ordinary query deadlines or replaying SQL.
It does not guarantee forcible termination of arbitrary custom I/O or an already
blocked query reader.

Autonomous orphan-session reaping is a separate **server** contract:
`qualifyQuackLeaseReaping` remains deferred/red against signed core f96420ed46.
This adoption does not fix it or claim that gate passed. Existing native-JDBC
nanos-list/zoned-text and Arrow TIMETZ guards, Doris JNI UTF-8/OVERLAY deferral,
and local JSON mutation evaluation remain unchanged. No native rebuild, peer
source change, publication, deployment or remote push is part of this adoption.
