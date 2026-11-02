# Doris API-27 intake review — 2026-11-02

## Scope and frozen source

User-authorized re-vendor from `8e10b235cf030d486b6840cfd64b6218ab515e6d`
to handoff pin `4bc7f426828e4cedfd2b7ce9b76fe95b14b5b146` (37 commits).
Handoff: sibling `doris-ducklake/dev-docs/HANDOFF-FOR-DUCKBRIDGE-DORIS-4bc7f426-API27.md`.
This review is bounded to the consumer/runtime boundary; it is not a new general
engine audit or an authorization to expand types, pushdowns or write support.

## Source-backed compatibility facts

- Connector API **26→27**; filesystem **7**, JNI **6**, BE execution maximum **19**,
  Thrift **0.24.0** and Maven revision `1.2-SNAPSHOT` remain the same.
- The SPI production delta adds `SUPPORTS_WRITE_ONLY_DEFAULTS`, an Iceberg-style
  writer-schema default contract. DuckBridge remains read-only and does **not**
  declare it. Compile/runtime contract tests require the new enum to exist while
  asserting that no capability or write provider was enabled.
- The complete scan SPI, filesystem API and Java/JNI source subtrees have
  identical Git tree identities across the pins. The canonical handler delta
  still applies cleanly and changes four Java-only production/test files.
- Wire delta: optional tablet-report fields 18/19; deprecated scanner option
  field 1002 now defaults true. Consumed Paimon/report field IDs are unchanged.
  Contract tests pin these facts; this is not mixed-version wire qualification.
- File scans now always use native V2; the old option is not a rollback switch.
  DuckBridge still emits JDBC ranges, not native file scan plans. Native Parquet,
  Iceberg/default and reader changes are not new DuckBridge capabilities.
- `be/src/util/jni-util.cpp` is unchanged. This intake does not claim to fix the
  existing UTF-8/modified-UTF-8 limitation or enable OVERLAY.

## Reuse and preservation

Independent source: `/home/jayson/DEV/OSS/doris-duckbridge-api27-4bc7f426`;
pristine donor: `/home/jayson/DEV/OSS/doris-ducklake-api27-4bc7f426`.
Own metadata repository is separate; borrowed object stores are read-only in
build containers. Shared staged source and pending Trino audit changes remain
outside this task.

Before staging anything, all 29 files of the exact eight-module API27 SDK and
all 3,231 FE/native/JNI/plugin artifact hashes were independently verified.
The SDK archive hash is
`c95e6561a1ee3b586a52092c5b9be0590ae4e2fe4ca8cecf0bcaca6553c8d3ed`.
Five compile JARs must match the FE host, not merely mutable SNAPSHOT labels.

The current project-local SDK, connector JAR/distributions, baseline/configuration
and image identities are preserved under the owned `output/rollback-api26/`.
The previous complete API26 output/images/volumes and donor assets are retained.

Native/FE reuse is limited to this exact-pin handoff. Immutable build image:
`apache/doris@sha256:922fc3d4d44e7fdd47a611b68b0097a888e0da5a101151917b1052c4c8abb17d`.
The handoff records the updated Brotli/Arrow/native third-party closure; no old
native executable is relabeled API27. Exact native BE SHA256:
`21084b84cec99d4a79a7c65c95fade2770ae464e2b41fca1983531f257a03778`.

The complete patched Java/JNI reactor and packaging must still be rebuilt through
upstream `build.sh`, with all layout gates and the independent JDBC/Quack live
smoke. Unchanged SPI/bootstrap/source bytes do not qualify transitive plugins.
No scanner-only splice, test skip, type downgrade or producer/native bypass is
permitted. The completed [qualification](QUALIFICATION-api27-2026-11-02.md)
records module/upstream checks, actual JDBC/Quack runtime identity, full live
acceptance and volume-preserving teardown. No broader surface is enabled.

Source diffs, identities and preflight snapshots:
`build/doris-api27-review/source-review/` and owned
`output/duckbridge-api27-reuse-proof.json`.
