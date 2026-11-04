# Trino 0.3.0 publication and dependency refresh — 2026-11-04

## Scope and current status

**Published as stable, non-SNAPSHOT releases on 2026-11-04:**

- [v483-0.3.0](https://github.com/brikk/duckbridge/releases/tag/v483-0.3.0)
- [v484-0.3.0](https://github.com/brikk/duckbridge/releases/tag/v484-0.3.0) — latest

Both tags resolve to `76c3701288d2101e778f4ed0c7ef276f469accaa`. Each release
contains its ZIP, tar.gz and both SHA-256 sidecars; neither is a draft or prerelease.

The user authorized publishing both `483-0.3.0` and `484-0.3.0` to GitHub Releases,
then explicitly chose parity 0.5.2 adoption rather than reusing 0.5.1 inputs.
After a resource-priority pause, the user resumed this work and requested the
latest published Quack JDBC. GitHub Releases and Maven Central both select
**Quack JDBC 0.13.0**. Both connector pins and version assertions are updated;
the underlying native JDBC remains **2.0.0.0** and DuckDB remains **2.0.0**.

The previous [local qualification](archive/REPORT-release-closeout-2026-11-03.md) used
Quack 0.12.1 and parity 0.5.1. Its test results, hashes and prepared artifacts
are historical evidence, not qualification of these new dependencies. The
preserved inputs/results are in `build/release-publication/rollback-parity-0.5.1/`.
The accepted native JDBC race and all semantic fallback/error contracts remain;
no production workaround, assertion relaxation or test exclusion is selected.

## Why the previous CI stopped

Run `41631390809` failed in the extension-fetch smoke before either Trino test
job ran. An unversioned `FORCE INSTALL trino_parity FROM community` now obtains
**v0.5.2**, but the script correctly required v0.5.1. Independent stock DuckDB
2.0.0 probing showed the version mismatch while all ten scalar checks passed.
Both versioned community URLs for `0.5.1` and `v0.5.1` returned 404. The user
selected a real dependency update, not removal or weakening of that guard.

## Selected input changes

- Parity source tag `v0.5.2`: `0d6f9a1b2ca5a93f4ff813c2fdab9ef0b5a9c4ed`.
  The DuckDB/CI-tools gitlinks are unchanged. The source diff adds function
  descriptions/examples/categories and parameter names; native algorithms,
  types, arities, NULL/error contracts and the ten-row `trino_meta()` API remain.
- Quack JDBC 0.13.0 keeps integer/bigint sequences and primitive constants compact
  during decoding. Our consumers use JDBC interfaces, not an exhaustive switch
  over its low-level `DecodedVector` variants. Java-17 bytecode and published
  checksums are verified separately before qualification.
- Bounded 6,000-row consumer regressions cover multiple FETCH batches, actual
  JDBC/Trino types, sequences, numeric extrema, signed floating zero, and NULL
  getter behavior. These are not a claim of universal large-result qualification.

The Central 0.13.0 JAR SHA-256 is
`0935b75d3aa86a320b9f1d0330fbd8dfa551c4c2505aa11cc1be612dfdf455e0`.
JAR/POM/source SHA-256 and SHA-1 checksums match Central; all 111 production
classes are classfile 61 (Java 17), with no added runtime dependency closure.
The release tag resolves to commit `aad8bdf7f207c0b6bdb40dadf23485ea7169610e`.
The signed Linux-amd64 parity 0.5.2 pair passes stock DuckDB 2.0.0 LOAD with
signature verification enabled, the exact version guard and all ten scalar checks.
Binary SHA-256: `bfa77b4acbe06e60b585ad0aadebe6c47052016dba448d7d89926e0e77f048ff`;
`.info` SHA-256: `02e2a3ec50720f058c6541dd6b916d32693c46a0659bd1607e42f27a64d9e9e5`.

The first focused run passed 607/608 cases and exposed an existing connector gap:
query-result metadata reports `FLOAT` with standard JDBC `REAL` code 7, which
our mapper omitted while supporting JDBC `FLOAT` code 6. The 0.12.1 and 0.13.0
source mappings agree; this is not attributed to a new decoder regression. Both
codes now use the existing actual Trino REAL/32-bit read mapping and floating
predicate controller. The regression keeps its source types and exact signed-zero
bits; it is not converted to DOUBLE or skipped.

The corrected focused 484 run passes all **608 tests**, zero failures/errors/skips,
plus lint. This includes the new compact-vector case, native aliases, per-entry
semantic fixtures, and all 1,112,064 Unicode scalars with nine JDK-oracle checks.
The Java-17 Doris full build, lint and audit-runtime isolation pass **93 tests**,
zero failures/errors/skips. Its plugin ZIP contains exactly the checksum-verified
`quack-jdbc-0.13.0.jar`. Full live testing reuses the pinned API-27/JNI-6 images;
no Doris native rebuild or source re-vendor is selected.

The full existing API-27/JNI-6 live Doris JDBC/Quack smoke also passes with 0.13.0:
security, metadata, actual scan types/Unicode/binary/temporal values, pushed plans,
20 sequential/eight concurrent queries, persisted FE/catalog restart and isolated
JDBC plugin failure/recovery. The loaded FE and BE driver hashes both match
Central; Java 17 and final JNI6 JDBC READY/zero failed plugins are verified.
The owned `duckbridge-release-quack013` stack was stopped without deleting volumes;
peer services and pinned native images/source remain unchanged. All 23 Python
checks pass. Logs/manifests are under `build/release-publication/doris-live/`.

Local qualification bundles only the newly verified host-platform 0.5.2 pair.
The old non-host 0.5.1 pairs were moved to the rollback directory, not mixed into
a 0.5.2 artifact. Only the release workflow's separately verified four-platform
matrix supplies final publication archives.

## Publication gates

The full existing per-module assertions and both matching Trino ABI gates pass.
All four signed native jobs (Linux amd64/arm64, macOS arm64, Windows amd64) pass
stock-CLI signature-enabled LOAD, exact v0.5.2 and ten-function smoke checks for
each release. No CI check was bypassed or weakened.

| Run | Result |
|---|---|
| [Main CI 41748485274](https://github.com/brikk/duckbridge/actions/runs/41748485274) | Both Trino build/test jobs pass |
| [483 release 41748485126](https://github.com/brikk/duckbridge/actions/runs/41748485126) | Four native jobs, both ABI compatibility jobs and the 483 release build pass |
| [484 release 41748485335](https://github.com/brikk/duckbridge/actions/runs/41748485335) | Four native jobs, both ABI compatibility jobs and the 484 release build pass |

The workflow stages drafts first. Both uploaded archive sets were downloaded
through authenticated asset IDs and checked before either became public:

- ZIP/tar.gz file contents agree; sidecars and GitHub asset digests agree with
  actual downloaded bytes. Names, paths, target commit and four assets per release
  are checked explicitly.
- 483 has 87 JARs and Google Guice 7.0.0 classes; 484 has 89 JARs and Airlift Guice
  10 main. Both contain their matching Trino base-JDBC/toolkit, one native JDBC
  2.0.0.0 and the exact Central Quack 0.13.0 JAR. Provided SPI/API JARs and test
  benchmark classes are absent; ServiceLoader metadata and bundled notes match.
- All eight parity binary/`.info` resources agree byte-for-byte with the actual
  signed artifacts uploaded by the corresponding platform jobs. The two release
  runs produced the same four native pairs; no old 0.5.1 resources were mixed in.
- The exact uploaded ZIPs were extracted and loaded by official stock Trino:
  **22 checks on 483 and 23 on 484** pass. These include default native PARITY
  operation, the actual v0.5.2 version, engine-specific REAL membership, DATE/
  temporal gates, finite endpoints and refusal/error contracts.
- A separate owned Quack server exercises the packaged 0.13.0 driver through
  each stock Trino plugin's real classloader: 6,000-row sequence/constant queries
  retain the exact source types, full distinct range, corresponding integer
  values, BIGINT maximum, signed REAL/DOUBLE zero bits and NULLs. This uses a
  private bridge route; it is not a correction or acceptance claim for the
  separately documented published-port streaming fault.

Both drafts were published only after those checks. Publication is read back
from the server, actual tags are resolved to the expected commit, and 484 is
explicitly marked latest. The release-body reference link is tag-specific rather
than a broken relative path. Publication used the existing release IDs; no new
duplicate release or tag was created during verification/retry handling.

## Published archive hashes

| Asset | SHA-256 |
|---|---|
| `trino-duckbridge-v483-0.3.0.zip` | `0e825099adea8ed5d8fd380d2f7479495664d815af7516b162162795c33152d2` |
| `trino-duckbridge-v483-0.3.0.tar.gz` | `8cfd9bb6523fe222161aca36ac1b46d77e070e8fafcc065e743b3ee67c88bb35` |
| `trino-duckbridge-v484-0.3.0.zip` | `9547afe75ce807785c1c5abe3d67291b60a04766edd5d195f192c8a08a297906` |
| `trino-duckbridge-v484-0.3.0.tar.gz` | `37dbc00ef7e1bd515ed0e6fc43ee51d35fc4e5c5ca509a5d16f44bfdca9dcd95` |

## Evidence and cleanup

Evidence is under `build/release-publication/`: `verified-drafts.json`,
`uploaded-smokes.json`, `published-releases.json`, both `published-release-*.json`,
native input artifacts, downloaded assets and the full stock-server/RPC logs.
The official CLI used to seed native data is checksum-verified DuckDB 2.0.0,
source `d54cbbe3df`. Owned stock Trino/Quack smoke containers are removed after
each check; the Doris stack was already stopped without deleting volumes.
Unrelated services, old images/volumes and failure evidence remain untouched.

The accepted native JDBC cache race and other open producer/type limitations
remain documented and unresolved. Successful release qualification is not an
upstream fix, broader transport guarantee or semantic/error waiver.
