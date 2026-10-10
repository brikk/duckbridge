# Quack JDBC 0.8 / DuckDB 1.5.6 downstream validation

**Date:** 2026-10-10
**Verdict:** PASS

## Exact inputs

- DuckDB JDBC: `org.duckdb:duckdb_jdbc:1.5.6.0`.
- Remote DuckDB CLI: `v1.5.6` in Trino tests, Doris tests and Doris Compose.
- Quack JDBC: released `dev.brikk.duckdb:quack-jdbc:0.8.0` from Maven Central.
- Quack JAR SHA-256: `320ccd96e978e1b30ff7ba77791dd0062bc6e72bd6d160e389b9e2c52269ac9d`.
- Driver constants: `QUACK_VERSION=1`, `LATEST_QUACK_VERSION=3`; this build prepares for but does
  not yet claim the DuckDB-2.x v3 wire protocol.
- Doris: `dd456ab7a89`, connector API 13, JNI plugin API 5, matching ASAN FE/BE.

## Results

- Trino connector: **641 passed, 0 failed, 0 skipped**. Embedded JDBC, real Quack transport,
  pushed/local semantic fixtures, parity extension, Arrow, Lance and Vortex paths passed.
- Doris connector: **62 passed, 0 failed, 0 skipped** on its Java-17 executor; Detekt and
  assembly passed.
- Doris live smoke: API-13 plugin and exact FE/BE guard passed; metadata, LARGEINT, ARRAY,
  native UUID, arbitrary BLOB bytes, naive TIMESTAMP, native TIMESTAMPTZ, predicates,
  functions, 20 sequential scans and 8 concurrent scans passed through Quack 0.8 / DuckDB 1.5.6.
- Quack server version is checked before fixture allocation, and the staged driver filename is
  pinned exactly so a cached older image or ZIP fails loud.
- The first Trino run found a newly served Lance extension build (`2913169` vs `2f167ea`). Its
  signature, write/read and vector-search canaries passed before the pin check; the pin was advanced
  and the complete 641-test suite reran green.
- Signed `trino_parity` binaries were fetched from community-extensions for `v1.5.6`. The source
  submodule remains at `0059004`: its current upstream main still builds against v1.5.5, so moving
  that pointer would not create a source-buildable 1.5.6 pin.

## DuckLake catalog scope

`dev.brikk.ducklake:ducklake-catalog` is not a Duckbridge dependency, so no DuckLake catalog
dependency was added here. It was validated separately in the sibling `doris-ducklake` checkout:
exact catalog build `0.11.0-20261010.161734-2` plus DuckDB JDBC `1.5.6.0` passed **252 tests,
0 failures, 1 skip** on Java 25 and **228 tests, 0 failures, 1 skip** in the isolated Java-17
plugin suite; Detekt and assembly also passed with refreshed dependencies.

This validates the maintained DuckDB 1.5.6 lane. It is preparation for, not qualification of,
DuckDB 2.x or its later Quack protocol.
