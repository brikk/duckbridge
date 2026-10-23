# DuckDB 2.0.0 / Quack JDBC 0.9.0 pre-community qualification

**Date:** 2026-10-21

**Verdict:** Trino Duckbridge is green and releaseable on Linux amd64 with the temporary unsigned
parity artifact and explicit unsigned-extension opt-in. Doris remains on its independent
DuckDB-1.5.6 / Quack-0.8 lane.

## Exact inputs

- DuckDB JDBC `org.duckdb:duckdb_jdbc:2.0.0.0`, SHA-256
  `5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186`.
- Quack JDBC `dev.brikk.duckdb:quack-jdbc:0.9.0`, SHA-256
  `043ca851212af77052b3977c6bd6922b69c685efcf8056534e8932fc860215d7`.
- Parity source `b216dc13a9beb26c4b5a3c62e0aa6763bb42715f`.
- Qualified parity binary from CI run `39636617710`, Linux amd64, SHA-256
  `9f7e26aec1f7fcc1b24cd5195d98980e46dceaaa03a853c331cf8a8fb12874d3`.
  The footer is unsigned; the formal community build is still pending.

The source submodule pins DuckDB `v2.0.0` and the matching `v2.0-cyanoptera` CI tools.

## Results

- Full Trino connector suite: **642 passed, 0 failed, 0 skipped**, including explicit embedded and
  remote `v2.0.0` version canaries.
- Detekt, build, clean plugin assembly and assembly verification passed.
- Assembly: 87 jars, no parent-provided SPI jars, ServiceLoader registration present. Connector JAR
  SHA-256: `c4f88aab796279a8230a618eb6323fcdf090546731faf36de302e275221fd530`.
- The connector jar contains only the qualified Linux-amd64 parity binary; stale 1.5.6 ARM64 output
  is deleted by the fetch script and cannot enter a 2.0 package.
- Direct DuckDB-2.0 CLI load with `-unsigned` passed: extension version `b216dc13a9`,
  `trino_meta()` returned 10 rows and `trino_upper('straße')` returned `STRAßE`.
- Embedded JDBC, direct Quack, Quack/Arrow, parameter binding, arrays, scalar type mapping,
  string/predicate semantic fixtures, parity functions, Lance and Vortex passed.
- The live string comparison probe passed on both embedded and Quack transports; current evidence is
  in [`REPORT-string-comparison-probe-duckdb-2.0.0.md`](../REPORT-string-comparison-probe-duckdb-2.0.0.md).
- The served Lance build changed to `86118537da`; signature, write/read and vector-search canaries
  passed before the pin was advanced.

## Integration changes found by qualification

1. DuckDB 2 release CLI assets changed from underscore-named ZIPs to hyphen-named tarballs.
2. `quack_serve` now defaults to TLS; the plaintext test server must pass `disable_ssl := true`.
3. Quack JDBC 0.9 still selects protocol v1 (`LATEST_QUACK_VERSION=3`), and DuckDB 2.0 retains v1
   compatibility. Protocol-v3 lifecycle features remain follow-up work, not a 2.0 release blocker.
4. Parity CI currently qualifies Linux amd64 only. The package intentionally omits ARM64 rather than
   bundling an old, incompatible binary.

## Temporary release requirement

Until the signed community artifact is published, embedded PARITY mode on Linux amd64 requires:

```properties
duckbridge.allow-unsigned-extensions=true
```

Remote Quack servers must start DuckDB with `-unsigned` and load the same binary by a server-visible
absolute path. Operators unwilling to load unsigned native code can use a non-PARITY mode such as
`GUARDED` until the signed artifact is available.
