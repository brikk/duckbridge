# Quack 0.11.1 snapshot consumer qualification — 2026-10-30

## Exact candidate, not a release adoption

User-authorized test of the Quack agent's local candidate:

- Coordinate: `dev.brikk.duckdb:quack-jdbc:0.11.1-SNAPSHOT`.
- Clean peer branch `fix/cancel-ack-deadline`, commit
  `4d3c966f8ebaf4666247e90f316b06e2ea5bdf66`.
- Original artifact:
  `/home/jayson/DEV/brikk/fork-quack-jdbc/target/quack-jdbc-0.11.1-SNAPSHOT.jar`.
- Tested copy SHA-256:
  `db80a005300ea98d005f8b02a3624467283a0c4ef55df31fa33b03e5103e7067`.
- All 105 base classes are classfile 61 / Java 17. Embedded Maven properties
  identify the same SNAPSHOT version.

This candidate was **not published** at the time of qualification. Sonatype's
0.11.1-SNAPSHOT path returned 404. Its older published 0.12.0 snapshot is a
different artifact and was not substituted. The peer checkout was inspected
read-only; no build, source change, install or publication occurred there.

The JAR and actual POM were copied into an isolated local test repository under
`build/quack-snapshot-review/4d3c966/`. The explicit Gradle init script selects
that coordinate and rejects a resolved driver whose filename/hash differs.
Version assertions remain exact; only the expected candidate identity is
provided to test/audit JVMs. No runtime guard is disabled.

**Committed production pins remain 0.11.0.** Candidate packages are test outputs,
not published releases or an approval to adopt a different artifact.

## Consumer acceptance

```bash
./gradlew --init-script build/quack-snapshot-review/4d3c966/qualification.init.gradle \
  :trino-duckbridge:qualifyQuackCancelPartition \
  :trino-duckbridge:qualifyQuackCancel \
  :trino-duckbridge:qualifyQuackDisconnect \
  :trino-duckbridge:qualifyQuackStreaming
```

All four pass on the copied candidate. The init-script verifier prints the
resolved path and SHA-256 before running the gate.

- Previously failing prolonged response-loss gate: with a 30-second ordinary
  request timeout, CANCEL plus explicit fail-closed teardown completes within
  the unchanged five-second allowance, returns SQLException, and leaves the
  connection closed. No global query-timeout reduction or SQL replay is used.
- Healthy cancellation still interrupts the observed target query, returns
  SQLState **57014**, and permits its next healthy autocommit control.
- DISCONNECT remains independently bounded while its response is blackholed.
- A genuine multi-batch result still fails explicitly after a network cut,
  rather than returning successful partial EOF; a fresh session recovers.

Evidence: `build/quack-snapshot-review/4d3c966/acceptance.log` and
`manifest.json`. These tests exercise the real signed DuckDB-2/Quack-v3 server,
not an invented cancel action or simulated result rows.

## Regression and Java-17 scanner checks

The candidate is resolved for both modules, including their runtime/package
closure, solely for commands carrying the explicit init script. Results:

- **701 Trino + 85 Doris tests**, zero failures/errors/skips; both full module
  checks and assembly/isolation verification pass (`full-checks.log`). Candidate
  JUnit XML is preserved under this review's `test-results/`, separately from
  subsequent baseline runs.
- The live exact-pin **Java-17 FE/BE scanner smoke passes** with the same hashed
  SNAPSHOT JAR: security, metadata, exact values, EXPLAIN, temporal edges,
  concurrency, catalog restart and JNI plugin-isolation/recovery
  (`doris-live-smoke.log`). No native engine rebuild was performed.
- The Java-17 boundary audit independently records driver version
  `0.11.1-SNAPSHOT` and SHA-256 `db80...7067` (`java17-boundary-types.json`).
- Both candidate packages contain exactly one driver: the real SNAPSHOT JAR,
  matching the tested bytes. The candidate Doris plugin ZIP is preserved under
  `packages/doris-snapshot-plugin.zip`.
- Cluster-free compose/intake/bootstrap checks: **22 passed**.

Logs above are under `build/quack-snapshot-review/4d3c966/`. The standalone
Kotlin/Java production code is not changed to invoke candidate-private APIs.

For the live scanner test, the smoke's explicit qualification version requires
the exact SHA-256 as well as the real candidate JAR name. The file is not renamed
to pretend to be released 0.11.0. Default invocations retain their original pin.

## Release scope / exclusions

This verifies the corrected **caller deadline** and normal consumer recovery.
It does not claim arbitrary custom transport I/O or an already-blocked query
reader can always be forcibly terminated. The peer agent's stronger stalled-body
and custom-I/O oracle results are their reported evidence, not silently counted
as tests run by DuckBridge.

Autonomous abandoned-session reaping belongs to the Quack server. Its deferred
`qualifyQuackLeaseReaping` gate against core f96420ed46 is not fixed by this driver
patch and is not represented as passing. Existing JNI/OVERLAY and unsupported
native-JDBC/Arrow type paths also remain unchanged.

## Release recommendation / cleanup

**Green from the DuckBridge consumer side to cut final 0.11.1 from tested commit
4d3c966**, subject to the explicit scope/limitations above. Verify the resulting
final artifact's identity/version before its separate adoption; the final JAR's
hash will naturally differ when generated version metadata changes.

The run-owned stack was stopped without deleting volumes. Normal builds were
then run **without** the qualification init script: both package outputs and the
staged BE driver are verified back at released 0.11.0, with its published SHA-256.
Default-version smoke/network and pooled-driver tests pass without qualification
properties (`restore-baseline.log`). The committed version catalog is unchanged.
No driver publication, production dependency upgrade, release or push occurred.
