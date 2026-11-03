# Doris API-26 re-vendor qualification — 2026-11-01

## Status: local correctness qualification passed

The user authorized adopting the exact build-reuse handoff at Doris
`8e10b235cf030d486b6840cfd64b6218ab515e6d`. Matching source/SDK, complete patched
Java/JNI packaging, static layout, selected upstream tests, full connector checks
and live JDBC/Quack acceptance all pass. This is now the maintained source/local
ASAN correctness baseline. **No production rollout, published release, performance
or all-JNI-backend runtime qualification is claimed.** API-24/API-13 assets remain
preserved rollback, not relabeled API-26 inputs.

The preceding Trino POWER fix is separately committed/pushed at **b0677ad**;
926 Trino tests and assembly verification passed before this re-vendor began.

Handoff read from:
`/home/jayson/DEV/brikk/doris-ducklake/dev-docs/HANDOFF-DUCKBRIDGE-DORIS-8e10b235-API26.md`.
The handoff's DuckLake/S3 acceptance is not used as DuckBridge JDBC evidence.

## Frozen inputs and completed preparation

- Connector API **26.0**, filesystem API **7.0**, JNI API **6.0**, BE execution
  maximum **19**, Thrift **0.24.0**, Maven **1.2-SNAPSHOT**.
- Runtime qualification is Linux AMD64 / ASAN with Java 17, not an additional
  native platform, stock Doris release or performance qualification.
- DuckDB/native JDBC **2.0.0/2.0.0.0**, Quack JDBC **0.12.0** and Trino **483**
  remain unchanged. No pushdown/type/streaming/write capability is enabled.
- Dedicated source worktree:
  `/home/jayson/DEV/OSS/doris-duckbridge-api26-8e10b235`.
  Its private metadata repository is
  `/home/jayson/DEV/OSS/doris-duckbridge-api26-metadata.git`; shared source-object
  stores are read-only during container builds. The shared Doris checkout's
  three staged DuckBridge files were fingerprinted and not changed.
- Pristine donor:
  `/home/jayson/DEV/OSS/doris-ducklake-api26-8e10b235`.
  All **3,231** copied FE/BE artifact hashes match its source-qualified manifest.
- SDK archive SHA-256:
  `b7b576a5f4cd61fd31b80b6bcbb3d2a672570b26491eb2179c0f9efed86cb79b`.
  The exact **29-file/eight-module** closure was verified and staged into
  project-local `doris-duckbridge/doris-m2/`, never mavenLocal or another project's
  active repository. Original coordinates were backed up before replacement.
- Native ASAN BE SHA-256:
  `881cb91a719f132e8ed0cb8fc7d8efd3e2a161f3bb2cf274b2b2eb8164ec4e28`.
  FE JAR SHA-256:
  `056bcba381ca562f76fde32140383da823ead67134f8dccc7ff82f949e2dfd59`.
- The canonical handler patch applies cleanly. Its four-file production/test
  delta is entirely Java; the upstream JNI source subtree is unchanged from the
  API-24 pin. Pristine native/FE reuse therefore avoids redundant C++ work, not
  the matching Java/JNI rebuild or our own runtime acceptance.
- The full patched Java reactor built through upstream
  `build.sh --be-java-extensions -j2` in pinned build image
  `apache/doris@sha256:4d63f788e85d35ab4f29804e3a4cdd93fe0c70c4ce954e6fbf333ac21a45ab9d`.
  Java 17.0.2, Maven 3.9.9, ASAN profile; two CPUs, 6 GiB container / 3 GiB Maven
  heap. Build compilation succeeds with upstream style/build gates intact.

## Important packaging boundary

`build.sh --be-java-extensions` compiles the complete reactor but does not deploy
the JNI/shared/plugin output while `OUTPUT_BE_BINARY=0`. The first run therefore
left a pristine output despite successful compilation; that intermediate state
was recorded instead of treating Maven success as a patched deployed BE.

The upstream `OUTPUT_BE_BINARY=1` packaging path can combine the identical-pin
native build output with the rebuilt **complete** JNI tree. Its native `be/output`
inputs have been copied from the exact donor and the executable hash rechecked.
After the user confirmed capacity, the complete upstream packaging path succeeded.
The deployed JDBC JAR now contains the handler, all nine plugins pass all six
static checks, and the native hash remains exactly the donor's. Shared
SPI/bootstrap bytes also remain identical to the handoff. Do not splice only a
scanner JAR into the old runtime, alter `build.sh` to skip phases, or fabricate a
new engine identity. Image baking now also rejects a deployed JDBC JAR that lacks
`DuckDbTypeHandler` before it can label a pristine tree as DuckBridge-ready.

## Initial capacity failure and controlled recovery

The first Gradle connector contract invocation did **not** produce a semantic
failure or complete module tests. At **2026-11-01 00:04:59 UTC**, the kernel
reported a global OOM kill of its exact daemon PID **1529379**, with about
2.6 GiB anonymous RSS. Earlier kills in the same global OOM burst are also
retained. The source of this failed attempt is not an unexplained "variance" skip.

At diagnosis the host had approximately 26 GiB RAM, 25 GiB used, only 1.3 GiB
available, and its 8 GiB swap was full. Other projects' Doris/FE test stacks were
active. No peer container/process was stopped, no swap/host setting changed and
no test/timeout was weakened. Heavy work was paused rather than competing for
the remaining capacity.

**User-directed pause:** the user selected **Wait for capacity**. Peer jobs and
stacks were left untouched. No polling loop, automatic retry or scheduled build
was started, and no qualification was claimed from the incomplete attempt.

**Resume:** the user subsequently reported 21 GiB free/available, independently
confirmed before restarting. Heavy packaging, JVM checks and runtime qualification
then ran sequentially; the original failed attempt/evidence is preserved.

Evidence: `build/doris-api26-review/contract-targeted.log` and
`host-oom-evidence.log`. This attempt is **not passing qualification**.

Lightweight checks pass: **20 Compose/orchestration + 3 bootstrap Python tests**,
including the new guard against baking an unpatched JDBC tree. These do not
substitute for JVM or runtime gates.

The resumed orchestration run exposed `/tmp`'s user quota: standalone extraction
returned **EDQUOT (errno 122)** while the mount still had filesystem free space
and was confirmed mounted with `usrquota`. Smoke driver extraction now uses an
owned disk-backed `build/smoke-stage/` directory, consistent with image staging.
Python verification and JVM temporary files use
`build/doris-api26-review/tmp/`. This changes generated-file placement only, not
test assertions, engine formats, timeouts or semantic handling. The original
failed run is retained in `python-compose-resumed.log`; the unchanged assertions
pass in `python-compose-disk-stage.log`.

## Upstream and consumer verification

The upstream `run-fe-ut.sh --run` exact selection reports all **28 selected
classes**. Results: JNI SPI **26**, bootstrap **50**, toolkit **55**, JDBC **51**
passed; JDBC also reports **18 unchanged external-database integration skips**.
There are no failures/errors. No new skip or test-runner shim was introduced.
Report: owned `output/duckbridge-api26-jni-test-summary.json`; raw log:
`duckbridge-api26-tests.log`.

The Java-17 API-26 contract class, lint and connector assembly pass against the
actually staged SDK (`build/doris-api26-review/contract-resumed.log`). Tests pin
the real API resource, preserved wire fields, synchronous/read-only opt-outs,
negative streaming estimate and shared cancellation-context propagation. This
does not enable asynchronous planning or arbitrary JDBC cancellation support.

Full forced `:doris-duckbridge:check :doris-duckbridge:assemble --rerun-tasks`
passes **90 tests**, zero failures/errors/skips, on the Java-17 module toolchain.
Lint, API-26 JAR manifest and audit-runtime isolation pass. The ZIP contains exactly
the released Quack 0.12.0 driver bytes (SHA-256
`0fa0cc634d35cb2a77ff88232fe1f6a3fd1570ba54729c9cc50005c7b575f1cf`) and no
host SPI/Thrift/foundation/filesystem/extension-SPI JARs. The five actual compile
SDK JARs match the pristine FE host copies byte-for-byte.

Assembled JAR SHA-256:
`87b9ab0a6a192a8e311e11b9064befe4f28cd7551a5f994c2d6c3ea5c4987235`.
Plugin ZIP SHA-256:
`f999ea157e27a61b4fdc32a14e2449a5346339e55e7dabcdc2480991b1a156c5`.

## Complete tree and image identities

The final source-qualified manifest retains **3,231** artifact paths, with none
missing or added relative to the pristine handoff. Ten JAR hashes differ:
the patched JDBC JAR; eight embedded-POM-only differences from the SDK flatten
profile versus the Java reactor; and Java-UDF's `Version.class` build-time string.
ZIP entry comparisons and disassembly confirm the latter's only changed value is
the build timestamp, not source identity or logic. No other classes differ in
those dependency JARs. These differences are inspected, not dismissed from labels.

Qualified image names/IDs:

- `doris-fe:duckbridge-8e10b235cf0-api26`:
  `sha256:5b1d0dab268385a40f1de5a8ae97c8c90573ce06b7c80b4e5c92aa6d8e4a610f`.
- `doris-be:duckbridge-8e10b235cf0-api26-asan`:
  `sha256:daabdbf6de0dc3815b0851c60f51f51e16087573c9a76b841832b5208c383cbf`.

Immutable overlay bases are exactly the handoff's FE
`sha256:3cbfc9b6293057a0b7f10c2e597f20f4a6bafed0729dfa9795db21eae2e136bb`
and BE `sha256:08c139f3d0ec45090a5214fd76f19f84b871d05f1a2db83b5e35a85c193617b2`.
The rebuilt JDBC JAR is
`aee0b12ddf03a051e5453c89d6b62dae102d5a20bf857b476b77b0049cbe212f`;
shared JNI SPI/bootstrap hashes still match the handoff. Native hash is unchanged.

## Live JDBC/Quack acceptance

Fresh `doris-duckbridge-api26-8e10b235` volumes and the new image pair pass the
full `smoke.sh --no-build --keep` route, not a substitute transport:

- FE and BE both report `doris-0.0.0-8e10b235cf0`; DuckDB reports v2.0.0.
- Provider load, real metadata/types and CREATE/ALTER driver security assertions.
- Exact Unicode, LARGEINT, ARRAY, arbitrary binary bytes and native UUID values.
- Exact count, returned row IDs and actual EXPLAIN pushdown assertions.
- Asia/Tokyo Quack, America/Los_Angeles embedded JVM and UTC FE display preserve
  naive timestamps and UTC instants, including pre-epoch microseconds, distinct
  DST-overlap instants and temporal NULLs.
- Twenty sequential and eight concurrent scans without failure.
- ALTER, DROP/recreate and initialized-catalog persisted FE restart retain
  replacement metadata, FE identity and exact values.
- Before first scan, the JVM registry is uninitialized. First use loads only
  JDBC. Deliberately invalidating its manifest fails that plugin alone while
  native BE health survives; recreation from the complete image restores scans.

After recovery, JNI API is **6.0**, one JDBC plugin is **READY**, and failed count
is zero. Actual running native, JNI shared/JDBC, driver, FE SDK/engine and deployed
connector JAR hashes match the verified build/package inputs. Both runtime JVMs
are Java 17. Captured container output plus **18 FE/BE log files** contain no
matching sanitizer/native-crash diagnostic. This does not qualify other JNI
plugins merely because their static layout passes.

Only this run's stack was stopped, **without deleting volumes**. Peer services,
shared staged source, donor images and all inventoried rollback image IDs remain
unchanged. Source review and unchanged unsupported surfaces are documented in
[the compatibility review](REVIEW-doris-api26-2026-11-01.md).

## Reproduce the consumer gates

Use the source-qualified build wrapper and frozen inputs in the owned output for
upstream Java packaging/tests. It refuses to overwrite existing phase logs; use
a new owned build or preserve earlier logs before an explicit retry.

For the connector checks on this host, keep temporary files on project disk:

```bash
mkdir -p build/doris-api26-review/tmp
TMPDIR="$PWD/build/doris-api26-review/tmp" \
JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/build/doris-api26-review/tmp" \
JAVA_HOME=/path/to/jdk25 ./gradlew --no-daemon --max-workers=1 \
  :doris-duckbridge:check :doris-duckbridge:assemble --rerun-tasks

# After baking the matching image pair, retaining runtime evidence/volumes:
bash doris-duckbridge/compose/smoke.sh --no-build --keep
docker compose -f doris-duckbridge/compose/docker-compose.yml down
```

The module toolchain and runtime are Java 17; JDK 25 above runs Gradle/build-logic.
The report's immutable base digests and exact `DORIS_OUTPUT` identify the image
bake inputs. Do not substitute a different native pin or an old scanner closure.

## Rollback and durable evidence

Owned `output/rollback-api24/` preserves the complete project-local Maven repo,
original Doris coordinate directory, connector distributions/JARs, previous
BASELINE/Compose and old/new image identities. Old runtime images and volumes
remain untouched. Fresh API-26 test containers were stopped/removed after evidence
capture; their named volumes are preserved. No production deployment occurred.

Owned output also contains `duckbridge-api26-preflight.json`,
`duckbridge-api26-reuse-proof.json`, `duckbridge-api26-staged-sdk.json`,
`duckbridge-api26-post-java-state.json`, `duckbridge-api26-build.sh`,
`duckbridge-api26-java.log`, `duckbridge-api26-package.log`,
`duckbridge-api26-tests.log`, the exact test selection, JNI/module summaries,
`duckbridge-api26-engine-manifest.json`, JAR-entry/disassembly comparisons and SDK
host-identity proof. Project-local evidence under `build/doris-api26-review/`
includes original failed attempts, final module XML/packages, image bake/identity,
full live smoke, runtime hashes/status/logs and volume-preserving cleanup.
No source pin, ABI number or successful evidence is inferred from a filename alone.
