# Doris connector development

## Build/bootstrap

The source pin is [`doris-patches/BASELINE`](../../doris-patches/BASELINE):
API 30 / filesystem 7 / JNI 6, at `9fb3e3a2a13f972a62cb999d5599fc8e8f17f306`.
The SDK, connector and locally qualified JDBC/JNI runtime match this pin.
The canonical BE patch,
apply procedure and retained re-vendor history are in
[the patch contract](PATCHES.md).

```sh
# Repository root; requires the pinned Maven/JDK 17/Thrift toolchain.
tools/doris-baseline.sh --check-only
tools/doris-baseline.sh --install-spi-jars
./gradlew :doris-duckbridge:test :doris-duckbridge:detekt
./gradlew :doris-duckbridge:pluginZip
```

The project resolves Doris SDK artifacts only from its own `doris-m2/`, never
`mavenLocal()`. Keep all exact-pin SDK modules, FE/BE identities and the complete
patched Java/JNI/plugin tree aligned. Never splice a scanner jar into another
runtime or reset/discard a shared staged source tree. The bootstrap tool fails
on unclean existing work rather than removing it.

Full `--build-fe` / `--build-be` runs are explicit long-running operations;
the BE correctness fixture requires ASAN. The [API30 adoption](REVIEW-doris-api30-2026-11-10.md)
records SDK intake, the complete JNI build and scoped live checks. The [API27 qualification](QUALIFICATION-api27-2026-11-02.md)
applies only to the previous pin, not to API30.

The current Quack driver is 0.14.0; 0.13.0's Java-17 consumer qualification is recorded
in the [publication receipt](../../trino-duckbridge/dev-docs/REPORT-release-publication-2026-11-04.md).
The earlier 0.12.1 module/package and API-27/JNI-6 live checks remain in the
[prior receipt](../../trino-duckbridge/dev-docs/archive/REPORT-release-closeout-2026-11-03.md#quack-jdbc-0121-intake).
The API30 intake keeps the existing JDBC/Quack driver and scan path unchanged.

## Contracts and open work

- [JDBC scan/deployment contract](DESIGN-jdbc-scan-contract.md)
- [Live compose build/smoke guide](DEVELOPMENT-compose.md)
- [Current upstream seams](duckbridge-doris-friction.md)
- [OVERLAY qualification history](REPORT-overlay-qualification-2026-10-29.md)
- [Boundary/pool constraints](REPORT-boundaries-lifecycle-2026-10-29.md)
- [Shared open backlog](../../dev-docs/TODO-backlog.md)

User-facing current behavior is in the [module README](../README.md) and
[Doris pushdown reference](../README-pushdown-reference.md). Completed API reviews,
old baseline qualifications and initial probes belong in [archive/](archive/README.md).
