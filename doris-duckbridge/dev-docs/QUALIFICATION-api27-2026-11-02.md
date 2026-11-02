# Doris API-27 re-vendor qualification — 2026-11-02

## Status: local correctness qualification passed

The user authorized the exact DuckLake build-reuse handoff at
`4bc7f426828e4cedfd2b7ce9b76fe95b14b5b146`. Exact SDK staging, complete patched
Java/JNI build/packaging, layout checks, selected upstream tests, connector
check/assembly, Python checks and the full live JDBC/Quack smoke pass. Independent
runtime source/hash/Java17 identity and plugin-isolation/recovery checks pass.
This is the maintained local ASAN correctness baseline, not a production rollout,
performance result or qualification of every JNI backend.

This task only advances the Doris baseline. DuckDB/native JDBC
**2.0.0/2.0.0.0**, Quack JDBC **0.12.0**, Trino **483**, parity binaries, connector
types/functions and default execution paths are unchanged. No new write,
streaming, native-file or write-default capability is enabled. Pending Trino
audits and unrelated working changes are outside this task.

## Frozen source and build inputs

- Previous source: `8e10b235cf030d486b6840cfd64b6218ab515e6d`; new pin is 37
  commits later. See the [bounded source review](REVIEW-doris-api27-2026-11-02.md).
- Connector API **27.0**, filesystem API **7.0**, JNI API **6.0**, BE execution
  maximum **19**, Thrift **0.24.0**, Maven revision `1.2-SNAPSHOT`.
- User handoff:
  `/home/jayson/DEV/brikk/doris-ducklake/dev-docs/HANDOFF-FOR-DUCKBRIDGE-DORIS-4bc7f426-API27.md`.
  Its DuckLake/S3 results are not relabeled as our JDBC/Quack acceptance.
- Dedicated patched source:
  `/home/jayson/DEV/OSS/doris-duckbridge-api27-4bc7f426`.
  Private metadata: `/home/jayson/DEV/OSS/doris-duckbridge-api27-metadata.git`.
  Donor: `/home/jayson/DEV/OSS/doris-ducklake-api27-4bc7f426`.
- Immutable build image:
  `apache/doris@sha256:922fc3d4d44e7fdd47a611b68b0097a888e0da5a101151917b1052c4c8abb17d`.
  Java 17.0.2 / Maven 3.9.9; matched source-qualified native third-party closure.
  Handoff's Brotli/Arrow and other native inputs are retained with their manifest;
  no old-image/native assumption substitutes for the new-pin proof.
- All **29 files/eight modules** of the rebuilt SDK were hash-verified before
  staging only into project-local `doris-duckbridge/doris-m2/`. All five compile
  JARs byte-match the pristine FE host copies. Neither global Maven repositories
  nor the producer's active SDK/output were modified.
- SDK archive SHA256:
  `c95e6561a1ee3b586a52092c5b9be0590ae4e2fe4ca8cecf0bcaca6553c8d3ed`.
  Connector SPI JAR:
  `9b8896271296be1c70551e83deefe1d4553db12a6b85dd27576e08659f3a0368`.
  Thrift JAR:
  `991be99cf279dead8c1b20be5901f8cf9e6bc30a20674736fb8574dbd67a084d`.

## Exact-pin reuse and complete patched packaging

All **3,231** copied pristine FE/native/JNI/plugin artifact hashes match the
producer's source-qualified manifest. The canonical handler still applies to
four Java-only production/test files. The patch's production body is unchanged;
only its header now records the new source/API. Current patch SHA256:
`a512411db5d236e864df56871a72c14da3bf343511375181b1390a3e1e181df4`.

Native and FE reuse are from **this new exact pin**, not relabeled old artifacts.
The full patched reactor and deployment ran through upstream
`OUTPUT_BE_BINARY=1 bash build.sh --be-java-extensions -j2`. The build image,
source/build script and native output identity are verified; borrowed metadata
object stores are read-only in the container. ASAN and build/style/layout gates
remain enabled. Build uses two CPUs and a six-GiB container/three-GiB Maven heap;
test phase uses eight/four GiB. No additional swap, peer shutdown, test skip or
source workaround was introduced.

All **nine plugins pass all six upstream layout checks**. This is complete
shared/plugin packaging, not a scanner-only splice. Native SHA256 remains:
`21084b84cec99d4a79a7c65c95fade2770ae464e2b41fca1983531f257a03778`.
Independent ELF version inspection finds maximum undefined GLIBC **2.10**,
within the enforced 2.17 boundary. FE main JAR SHA256 remains:
`31cce4a13b6668072ab29ac7c83973a2cc007fff7a383fadafff6594feec1359`.

Ten rebuilt JAR hashes differ from the pristine donor and were inspected at ZIP
entry level: the canonical JDBC handler/factory classes, eight embedded-POM-only
changes, and Java-UDF's `Version.class`. Full disassembly proves the latter
differs only in `DORIS_BUILD_TIME`, not source identity or behavior. Shared JNI
SPI/bootstrap hashes match the handoff exactly. The rebuilt JDBC JAR contains
`DuckDbTypeHandler` and is independently checked before image baking.

## Upstream and module checks

- Upstream `run-fe-ut.sh --run` executes the exact 28-class JNI/JDBC selection;
  all selected classes report. JNI SPI **26**, bootstrap **50**, toolkit **55**
  and JDBC **51** pass, with **18 unchanged external-database JDBC skips**.
  No failures/errors or new skip policy. Unchanged Java/JNI source and identical
  canonical patch bodies support the stated skip provenance; raw XML/reasons
  are retained.
- Forced `:doris-duckbridge:check :doris-duckbridge:assemble --rerun-tasks`:
  **91 tests pass**, no failures/errors/skips, on the module's Java-17 toolchain.
  Lint and function-audit runtime/package isolation pass.
- `TestDorisApi27Contract` checks the actual API resource, availability but
  non-advertisement of `SUPPORTS_WRITE_ONLY_DEFAULTS`, report fields 18/19,
  retained scanner option ID 1002/default true, preserved consumed wire fields,
  and existing security/read-only/synchronous/cancellation-context contracts.
  These do not promise mixed-version compatibility or new async/JDBC deadlines.
- **23 Python checks pass**: 20 Compose/orchestration and three bootstrap checks.
- Connector manifest reports **27.0**. The ZIP bundles the exact released
  Quack driver SHA256
  `0fa0cc634d35cb2a77ff88232fe1f6a3fd1570ba54729c9cc50005c7b575f1cf`,
  and no FE-provided SDK/Thrift/foundation/filesystem/extension-SPI or audit JARs.

Connector JAR SHA256:
`ec3fc8b3915a9052cccb021e131f9dc35bf66d87977c70a1c4302e87d9e8ab62`.
Plugin ZIP SHA256:
`5be78a0c306b221c7747bebfc3ff9507ec17da9b6fc441a6d229d1e5a09ee21b`.
Rebuilt JDBC plugin SHA256:
`aee0b12ddf03a051e5453c89d6b62dae102d5a20bf857b476b77b0049cbe212f`.

## Qualified images and live JDBC/Quack acceptance

Complete image pair baked successfully from the verified output:

- FE `doris-fe:duckbridge-4bc7f426828-api27`:
  `sha256:5d3e850758bbdf5c6e8389ee7b96eb84b21fa0f03ae4f00af88ac9050efa5d59`.
- BE `doris-be:duckbridge-4bc7f426828-api27-asan`:
  `sha256:1090657eba6490f4a7342deeb508f6a9600cccc7b590255b1f90e134a5ff938a`.

Immutable overlay bases match the handoff: FE
`sha256:3cbfc9b6293057a0b7f10c2e597f20f4a6bafed0729dfa9795db21eae2e136bb`, BE
`sha256:08c139f3d0ec45090a5214fd76f19f84b871d05f1a2db83b5e35a85c193617b2`.
Labels identify the actual source, API27/JNI6 and canonical patch; they do not
substitute for source/artifact checks or runtime hashes.

The live acceptance route passes the unchanged full `smoke.sh --no-build --keep`
against fresh `doris-duckbridge-api27-4bc7f426` volumes. Before starting, candidate
identities, free host ports, absent owned container names and absent new project
volumes are checked; existing peer containers and old volumes are left alone.

Actual runtime checks pass:

- Matching FE/BE `doris-0.0.0-4bc7f426828` and DuckDB v2.0.0 before allocation.
- Provider load, real metadata/types and CREATE/ALTER driver-URL security rules.
- Exact Unicode, LARGEINT, ARRAY, binary invalid/NUL/trailing-zero bytes and native UUID.
- Exact row IDs/counts and EXPLAIN proving the allowed function/predicate SQL.
- Asia/Tokyo Quack, America/Los_Angeles BE JVM and UTC FE display preserve naive
  walls and UTC instants: pre-epoch microseconds, distinct overlap instants and
  temporal NULLs retain their exact values.
- Twenty sequential and eight concurrent scans without failures.
- ALTER, DROP/recreate and persisted FE/catalog restart retain replacement
  metadata, FE identity and exact values.
- The initial registry is lazy/uninitialized. First use loads only JDBC. An
  incompatible run-owned JDBC manifest fails that plugin alone while native
  health survives. Complete-image recreation restores the actual scans.

After recovery the running JNI API is **6.0**, JDBC is **READY**, loaded count is
one and failed count is zero. Both FE and BE JVMs report Java17. The running
native/FE engine, all five FE SDK JARs, deployed connector, JNI SPI/bootstrap/JDBC
and driver hashes match their verified build inputs. Static correctness of the
other eight deployed plugins is not claimed as their runtime qualification.

Nineteen captured container/FE/BE log files contain no matching ASAN/UBSAN/native
crash diagnostic. Only this run's Compose stack is stopped, using `down` **without
`-v`**; all five new API27 volumes and the old baseline's assets are preserved.
Peer containers and unrelated services are not restarted or stopped.

## Rollback, preservation and reproducibility

Owned `output/rollback-api26/` preserves the original complete project-local
SDK, displaced Doris coordinate directory, previous plugin JAR/distributions,
baseline/Gradle/Compose/scripts, canonical patch and image identities. Previous
API26 complete output/images/volumes and earlier rollback assets are not altered.
The donor build/output, global SDKs and unrelated shared staged source are not
adoption targets we overwrite. Preservation checks confirm the shared source
HEAD/status/index/work diff and 186 existing Trino/root files are unchanged,
including the pending audit files, dependency pins and earlier README cleanup.

Evidence lives in owned `output/duckbridge-api27-*.json` / build logs and
project `build/doris-api27-review/`: frozen source diffs, preflight/preservation
snapshots, SDK/host identity, complete artifact/entry comparisons, disassembly,
ELF checks, exact upstream/module XML, package/driver identities, image-bake log
and image identity. Runtime hashes/status/Java/logs and volume-preserving cleanup
are captured under that evidence directory. `consumer-manifest.json` binds the
completed results and artifact identities; historical reports retain their own pins.

Heavy builds use the owned `output/duckbridge-api27-build.sh`, which refuses to
overwrite existing phase logs. Gradle/Python temporary files use project disk
because the host's `/tmp` user quota is constrained:

```bash
TMPDIR="$PWD/build/doris-api27-review/tmp" \
JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/build/doris-api27-review/tmp" \
JAVA_HOME=/path/to/jdk25 ./gradlew --no-daemon --max-workers=1 \
  :doris-duckbridge:check :doris-duckbridge:assemble --rerun-tasks
```

JDK25 runs Gradle/build logic; the Doris module/runtime target remains Java17.
This re-vendor does not close native JNI UTF-8, host HTTP streaming, autonomous
Quack reaping, typed boundary or pending Trino audit issues. It does not claim
production/performance or all-JNI-plugin runtime qualification.
