# Doris API-24 adoption review — 2026-10-28

## Review outcome

Adopt frozen Apache Doris master `082736138b7f0e767f966cbb1434bc9ba85f29ec`
(`[improvement](fe) Defer external catalog resource cleanup from replay (#69665)`).
This was upstream master when inspected, one commit after the supplied DuckLake
handoff pin `62e3d81a42b2b74ee2a5a577969df62edee06b78`, and 411 commits after our
qualified `dd456ab7a894f54c6553415223c6d5c9c7d4e035` baseline.

Source review is complete; build/live qualification is tracked below separately.
The review initially held DuckDB 1.5.6 / Quack JDBC 0.8.0 separate. The user then
explicitly requested DuckDB 2.0.0 / Quack JDBC 0.10.0. The first lane's failed live
probe is retained; the final paired qualification and fixes are recorded in
[QUALIFICATION-api24-duckdb2-2026-10-28.md](QUALIFICATION-api24-duckdb2-2026-10-28.md).

## Findings and required changes

1. **Load gate:** connector API 13 → 24; JNI API 5 → 6. Update the connector
   manifest and compile closure together. Rebuild the complete matching shared
   Java/JNI plugin distribution through upstream `build.sh`, not an old scanner
   JAR overlay. Thrift remains 0.24.0; Maven coordinates remain `1.2-SNAPSHOT`.
2. **Security declaration:** the default provider surface is empty. DuckBridge
   accepts `driver_url` and must declare it for the engine's ALTER operator gate.
   CREATE also needs the upstream mandatory URL grammar and validation-context
   operator check; declarations alone do not implement CREATE validation.
   Validation must remain statement-time, not in replay/reconstruction. The
   bundled FE driver and BE driver URL are distinct deployment concerns; no new
   checksum/wire contract is implied. As with upstream JDBC, this declaration
   does not establish SSRF protection for arbitrary JDBC URLs. API 24's endpoint
   parser expects a non-JDBC URI and its public surface factories cannot combine
   endpoint and driver declarations; no claim of complete network isolation is made.
3. **BE handler:** still absent upstream. Canonical `git apply --3way --check`
   succeeds at the new pin. The factory now has a sensitive-values overload;
   both overloads must select `DuckDbTypeHandler`. Scanner diagnostics now use
   `JdbcIOException` / `DiagnosticException` with redaction. Keep that path intact.
   Native UUID, LARGEINT, ARRAY, BLOB and UTC-field TIMESTAMPTZ coercions remain
   required; no type downgrade or new handler masquerade is acceptable.
4. **Scan/capability compatibility:** our `planScan(session, request)`, range
   properties and JDBC format selector are unchanged. `streamSplits` now takes a
   request, but DuckBridge never opts into streaming or batching. New aggregate,
   backend-capability, SQL-cache, task-scheduler, source-nullability and partition
   hooks are additive defaults. Keep empty capabilities, no writes, synchronous
   one-range planning, and no native-partition or cache opt-in.
5. **Wire identity:** `#68837` repurposed external-report field 34 as Paimon commit
   messages and moved the Paimon original-file-path field to 20. API equality is
   not FE/BE compatibility. Require the exact source version for both engines.
6. **Latest-vs-handoff:** `#69665` changes FE catalog retirement, replay cleanup
   and cache ownership only. SPI reactor inputs, JNI/native code, IDL and build
   inputs are unchanged. The handoff SDK is reusable after per-file hash and
   source-diff verification. Its FE is not the latest FE, and its native binary
   embeds the previous revision: build a new honest exact-pin FE/BE pair rather
   than relabeling it. Test DROP/recreate, ALTER and persisted catalog restart.
7. **Bootstrap safety:** existing `tools/doris-baseline.sh` only recognizes a
   `.git` directory (not a worktree file) and unconditionally resets/cleans an
   existing checkout. Correct this before recommending it for a dedicated
   worktree; never reset the shared staged Doris checkout.

## Isolation, rollback and evidence

- DuckBridge repository started clean on `main`, commit `b3eaa14`.
- Dedicated Doris worktree:
  `/home/jayson/DEV/OSS/doris-duckbridge-api24-08273613`.
- Shared `/home/jayson/DEV/OSS/doris` staged files and all DuckLake build/runtime
  worktrees, images and volumes must remain intact.
- Back up project-local Doris coordinates and existing plugin artifacts before
  staging the verified eight-module handoff SDK. Never use `~/.m2` as our compile
  repository. Use distinct source-qualified images and fresh runtime volumes.
- Immutable build environment:
  `apache/doris@sha256:4d63f788e85d35ab4f29804e3a4cdd93fe0c70c4ce954e6fbf333ac21a45ab9d`.
  Heavy phases run sequentially with bounded CPU/memory and disk-backed staging.
- This is a master-development ASAN correctness lane, not production deployment
  or release-performance qualification. Existing API-13 artifacts remain rollback.

## Qualification status

- Source review and exact-pin patch applicability: passed.
- SDK provenance: all 34 exported files verified against the supplied manifest;
  the eight-module closure's source inputs are identical at the candidate pin.
- Exact-pin FE build: passed through upstream `build.sh --fe -j3` with Checkstyle
  and the upstream build gates enabled (8 GiB container, 4 GiB Maven heap).
- Java-17 connector tests: **69 passed, zero skipped/failures/errors**; Detekt and
  assembly passed with tasks forced to rerun. The packaged JAR declares API 24.0;
  the ZIP contains no host SPI/Thrift/foundation/extension/filesystem/libthrift JARs.
  ZIP SHA-256: `1aef001ec518c6738eb728709f74fd41f7b7d86c6965e2aa73ceaaf886810171`.
- Cluster-free bootstrap/smoke orchestration: **6 passed**, including preservation
  of staged/untracked work and matched-but-wrong-source rejection.
- All Java-extension reactor modules built successfully via upstream
  `build.sh --be-java-extensions -j2`; full BE deployment/layout checks subsequently passed.
- Patched JDBC reactor: JNI SPI **26/26**, plugin toolkit **55/55**, JDBC
  **51 passed / 18 upstream environment-gated skips**, zero failures/errors.
  The skipped upstream cases require explicit integration URLs for MySQL,
  Oracle (modern/legacy), PostgreSQL, SQL Server, Trino/Presto and ClickHouse;
  no skip was added or changed. Both DuckDB factory overloads and direct
  UUID/binary/UTC TIMESTAMPTZ tests passed.
- Exact-pin native ASAN build passed through `build.sh --be -j3`, including CDC
  packaging; 12 GiB container, 14 GiB memory+swap, 3 GiB Maven heap. The upstream
  glibc 2.17 compatibility gate passed. Native SHA-256:
  `1cb51a8159dfb8f3703fd14f9d42d2c424b946d39ff263ab7473533d03c2020f`.
- Complete matching JNI tree: **9 plugins pass all six upstream static layout
  checks**. JDBC JAR declares JNI API 6.0 and contains `DuckDbTypeHandler`;
  SHA-256 `aee0b12ddf03a051e5453c89d6b62dae102d5a20bf857b476b77b0049cbe212f`.
  SPI/bootstrap hashes match the handoff's API-6 shared layer. This is static
  packaging evidence, not runtime JDBC qualification.
- SDK/actual-FE class identity passed: 137 SPI, 6,814 Thrift, 33 foundation,
  5 extension-SPI and 35 filesystem-API classes. Rebuilt JAR archive hashes differ;
  the class bytes are identical. Evidence: `output/duckbridge-api24-sdk-host-identity.json`.
- All 3,231 FE/native/shared/plugin artifact paths and hashes are retained in
  `output/duckbridge-api24-engine-manifest.json` (includes repeated dependency
  JARs across isolated plugin directories, not just unique artifacts).
- Image packaging and final DuckDB-2/Quack-0.10 live qualification passed. The
  final report records validation-order/TLS/readiness fixes, 72 Doris tests,
  643 Trino tests, 50 loader tests and the actual runtime/provenance evidence.
  The initial 69-test / Quack-0.8 ZIP above is preliminary evidence, not the final
  qualified plugin artifact. No new pushdown or production release was enabled.
