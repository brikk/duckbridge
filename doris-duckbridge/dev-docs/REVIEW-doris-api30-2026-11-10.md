# Doris API30 adoption

## Scope

Upgrade the existing JDBC/Quack connector in this checkout to the exact upstream
`9fb3e3a2a13f972a62cb999d5599fc8e8f17f306`. Connector API is **30.0**, filesystem
API **7.0**, JNI API **6.0**, BE execution maximum **19**, Thrift **0.24.0**,
Maven modules **1.2-SNAPSHOT**, and the Doris runtime remains Java **17**.

The driver remains Quack JDBC **0.14.0**. No production Kotlin, pushdown decisions,
SQL rendering, type mapping or BE patch was changed. Partition pruning, storage
pruning, batching and cooperating split sources remain unadvertised.

## SDK and source

Read-only donor: `/home/jayson/DEV/OSS/doris-ducklake-api30-9fb3e3a2`.
The SDK export's 29 files across eight Maven modules were checked against
`output/api30-m2-manifest.json` before importing them into this project's
`doris-duckbridge/doris-m2/`. All five compile JARs also match the actual FE-host
JAR bytes in `output/api30-sdk-host-identity.json`.

Archive SHA-256: `7317d2665f140d28336ee15e9b264e77c24d23e20d37458c535f26a8273e5c80`.
Local import receipt: `build/reports/api30-sdk-import.json` under this module.
Neither the donor nor global Maven state was modified.

The canonical `doris-patches/be/0001-duckdb-type-handler.patch` passes read-only
`git apply --check` against that source. Applicability is separate from the
build and runtime checks recorded below.

`BASELINE` records the full source SHA and the donor's compiled SQL version,
`doris-0.0.0-9fb3e3a2`. The smoke guard compares that explicit version instead of
assuming the git abbreviation has eleven characters. Compose and bake guards
target the same API30 source and runtime.

## API contract

The plugin manifest targets **30.0**. The renamed API contract test checks the
SDK resource, retained query/report fields, corrected schema-table/routine-load
wire IDs, disabled optional scan features and JDBC one-range behavior.

API29's typed table-absence exception does not justify reclassifying JDBC
transport/authentication errors or an empty column listing as confirmed absence.
Existing metadata error behavior is unchanged.

Upstream includes the standard JNI UTF-8 conversion fix and dictionary-encoded
Parquet BOOLEAN fix. Neither establishes JDBC/Quack Unicode qualification;
DuckLake's native-S3 results are not Duckbridge runtime results. OVERLAY pushdown
remains disabled.

## Verification and remaining work

Passed:

- SDK archive, all 29 imported files and five FE-host JAR identities; Java17
  classfile floor checked independently across 7,115 classes.
- `:doris-duckbridge:compileKotlin`, `compileTestKotlin`, `detekt` and `pluginZip`.
  Detekt metric advisories remain visible and non-blocking.
- **56 focused headless tests**, including API30/wire contracts, provider loading,
  config/security/redaction, query SQL and type mapping. No skipped tests.
- **5 cluster-free orchestration tests**: smoke version acceptance/refusals,
  election-network staging and rejection of an unpatched BE before image baking.
- Shell syntax and diff whitespace checks.
- Plugin ZIP manifest **30.0**, bundled Quack JDBC **0.14.0**, and absence of
  FE-provided Doris JARs from the package.

The SDK resource check initially looked for the filesystem kernel's version
resource inside `fe-filesystem-api`, where upstream does not ship it. That checker
assumption was corrected; no artifact or production code was changed to satisfy it.

## JDBC/JNI runtime build

The requester authorized the runtime follow-up after the SDK checks. Build source
is an ordinary local clone at
`/home/jayson/DEV/OSS/doris-duckbridge-api30-9fb3e3a2`, not a Duckbridge worktree.
Its source delta is exactly the existing four-file Java handler patch.

The native BE and pristine FE were copied from the exact matching API30 outputs
and independently checked against their recorded SHA-256s. No C++ was rebuilt.
The complete Java/JNI tree was rebuilt using unmodified upstream
`OUTPUT_BE_BINARY=1 bash build.sh --be-java-extensions -j2`, with three CPUs,
an 8-GiB container cap, 3-GiB Maven heap and two Maven workers. All nine JNI plugins
passed all six upstream static layout checks. The unchanged exact-pin CDC client,
outside the JNI-only build, was retained; the complete BE file inventory matches
the donor rather than silently dropping it.

The first two wrapper attempts stopped during version generation: a missing Git
common-directory mount, then a missing shallow boundary in the local shared clone.
The read-only mount and a normal depth-one fetch corrected the Git setup. The
third attempt passed without changing upstream code or supplying a fake version.
All attempt logs and resource records remain under that source's `output/`.

Focused upstream JDBC/JNI tests passed: **101 tests in 11 classes**, no failures,
errors or skipped tests, through upstream `run-fe-ut.sh --run`.
The complete packaged JNI tree contains **821 JARs**. Its JDBC/shared closure has
**2,925 runtime classes**, all compatible with Java17; the packaged handler and
factory class bytes match the actual compilation output.

## Images and live checks

Built from the audited complete output with the matching immutable stock base
images; these are Duckbridge's patched images, not relabeled pristine donor BEs:

- `doris-fe:duckbridge-9fb3e3a2a13-api30`:
  `sha256:736c41f2bc18ff6d84b29e8f66eb7aedf5d953e56126a6e1a8285fd448508c0e`.
- `doris-be:duckbridge-9fb3e3a2a13-api30-asan`:
  `sha256:8f762628b324709231e27b2fa7f8d4fb0f0a8112206d9ee3ef18482327adce86`.

Image inspection, complete artifact hashes, build/test logs and resource records
are retained under the build source's `output/duckbridge-api30-*` paths.
The live smoke and focused JDBC checks passed on run-owned Compose project
`duckbridge-api30-check-a298d4c0`; its containers, network and volumes were removed.
Prior API27 qualification is not used as API30 runtime evidence.

Passed live gates:

- Matching `doris-0.0.0-9fb3e3a2` FE/BE and DuckDB `v2.0.0` before catalog allocation.
- Metadata and exact LARGEINT, ARRAY, arbitrary binary/NUL/trailing-zero bytes,
  UUID, Unicode and NULL values through JDBC/Quack.
- Lazy JVM/plugin initialization, only JDBC loaded, and JVM metrics after first use.
- Exact predicates/counts, emitted function and temporal pushdowns, and temporal
  values across Tokyo Quack / Los Angeles BE JVM / UTC FE, including DST/pre-epoch edges.
- 20 sequential and 8 concurrent scans, ALTER and DROP/recreate replacement,
  initialized-catalog persisted restart and exact returned values.
- Incompatible JDBC-manifest refusal isolated to that plugin; native health and
  complete-image recreation/recovery remained intact.
- A separate 14-row Unicode/NULL fixture, **41 native OVERLAY cases** above actual
  JDBC scans, **3 residual predicate/LIMIT checks** and **2 NULL-bound cases**.
  No OVERLAY pushdown was enabled or assumed by these checks.
- Actual deployed FE, provider, native BE and driver hashes; all **821 deployed JNI
  JAR hashes** matched the audited complete build after the failure/recovery control.

Runtime receipt: `output/duckbridge-api30-runtime-result.json`; focused values and
emitted SQL: `output/duckbridge-api30-jdbc-result.json`. Package SHA-256 is
`8a60aab7f82d40e3bc81069a3c2ac4d352b2c50ca47e1366af1ab741ffe7091f`;
provider JAR SHA-256 is `8c8f8df2b6197613d1aa4dc9a585f950fdc53498138e3c077879a2b5845e89fd`;
unchanged Quack JDBC 0.14.0 SHA-256 is
`e6a80070c66129e54d70fcd29d3693be6dd5dd0160176136f1c42034137bf8a5`.

The exact old JNI failure was then checked on a separate fresh fixture, without
repeating the full smoke. All **3 directly pushed UTF-8 literal cases** passed:
`source='a😀bc'` returned row 2, the ZWJ-family literal returned row 12, and an
absent astral literal returned no rows. EXPLAIN confirmed the actual remote WHERE
retained each literal; raw UTF-8 query bytes are recorded in
`output/duckbridge-api30-literal-result.json`. Both engine images were identical
to the smoke-tested images, and this fixture's owned resources were also removed.
This closes the demonstrated JNI UTF-8 transport defect, not the separate
candidate OVERLAY pushdown qualification.

This is scoped local correctness qualification, not a production deployment,
performance result, whole-source acceptance or qualification of the other JNI plugins.
