# Doris patch contract

## Source and runtime identity

[`doris-patches/BASELINE`](../../doris-patches/BASELINE) is the single source of
truth for the exact apache/doris master SHA. The current lane uses connector API
27, filesystem API 7, JNI API 6 and Thrift 0.24.0. Do not build a moving branch
tip, relabel an older native output, mix FE/BE pins or assume an unchanged SPI
qualifies a different transitive plugin/runtime closure.

Only the BE DuckDbTypeHandler patch is carried. The FE is pristine at the matching
pin. Keep LARGEINT/ARRAY/VARBINARY, UUID and UTC-field timestamp behavior intact.
The UTF-8/JNI OVERLAY issue is separate and remains uncorrected by the handler.

## Bootstrap and apply

Run from the repository root with JDK 17 and the exact pinned Maven/Thrift tools:

```sh
tools/doris-baseline.sh --check-only
tools/doris-baseline.sh --apply
tools/doris-baseline.sh --install-spi-jars
./gradlew :doris-duckbridge:test :doris-duckbridge:detekt
```

`DORIS_SRC` may name a dedicated clean exact-pin worktree. Never point the tool
at a shared dirty/staged checkout or discard its changes to make the patch apply.
SDK artifacts are installed in the project-local `doris-duckbridge/doris-m2/`;
global Maven state and another project's SDK are not interchangeable inputs.

Explicit full builds use `--build-fe` / `--build-be`; the BE correctness fixture
requires ASAN and is a long-running C++ build. Follow the tool's environment and
parallelism checks rather than bypassing them.

## Re-vendor qualification

Before selecting a new pin, preserve the previous SDK, packaged connector/JNI
tree, image identities and volumes for rollback. Review source and transitive
runtime changes first. Rebuild the **complete** matching shared Java/JNI/plugin
tree using upstream `OUTPUT_BE_BINARY=1`; do not splice only the scanner jar.
Native reuse requires exact native-input and Java-only-delta proof, not merely
matching API numbers.

Require module tests, upstream JNI/JDBC checks, package/ServiceLoader/API guards
and actual source-qualified FE/BE JDBC/Quack runtime evidence. A fresh FE/catalog
smoke, persisted restart and plugin-scoped failure/recovery are distinct checks.
Do not weaken assertions, hide native failures, change data types or claim ASAN
correctness evidence is production performance qualification.

The [current baseline qualification](QUALIFICATION-api27-2026-11-02.md) records
the actual input/output hashes and rollback boundaries. The [compose guide](DEVELOPMENT-compose.md)
describes the developer fixture and destructive volume teardown options; do not
apply those options to unrelated or shared stacks.

Upstream exit criteria and optional seams remain in the
[Doris upstream asks](duckbridge-doris-friction.md). Historical procedures,
older pins and the detailed re-vendor log are retained in
[archive/PATCHES-history.md](archive/PATCHES-history.md), not duplicated as current work.
