# Trino connector development

## Build and verify

JDK 25 is required. Docker is required for Quack integration tests. Maven/extension
downloads must be available for a fresh cache; Gradle offline mode does not make
network-backed integration tests hermetic.

```sh
# From the repository root. Select the engine independently of the plugin version.
./.github/scripts/fetch-parity-extension.sh
./gradlew :trino-duckbridge:build -PtrinoVersion=483 -Pversion=483-0.3.0
./gradlew :trino-duckbridge:build -PtrinoVersion=484 -Pversion=484-0.3.0

# Focused iteration
./gradlew :trino-duckbridge:test --tests '*TestDateTruncationLocal' -PtrinoVersion=483
./gradlew :trino-duckbridge:detekt
```

Builds are sequential in one checkout; copy an artifact before cleaning the module
or changing targets. CI uses isolated 483/484 jobs. Each target uses its matching
`io.trino:trino-root` dependency management and ABI-labelled artifact. Google Guice
classes are used for 483; the matching Airlift Guice runtime is used for 484.
The assembly guard rejects duplicate Guice, provided SPI jars and missing drivers,
ServiceLoader registration or user-facing package notes.

```sh
./gradlew :trino-duckbridge:pluginAssemble :trino-duckbridge:verifyPluginAssembly \
  -PtrinoVersion=484 -Pversion=484-0.3.0
```

The output is `trino-duckbridge/build/trino-plugin/trino-duckbridge-<version>/`.
Release-branch workflows fetch all four signed parity binary/`.info` pairs and
require a complete versioned build before publishing. A local host-only extension
fetch is not a four-platform release package. Do not forge `.info`, disable
signature verification or publish a failed gate. Local unsigned extension work
uses the explicit opt-in and the pinned parity submodule; it is not a release input.

## Current contracts and investigation entry points

- [Temporal types, precision and provenance](TODO-datetime-timezones.md)
- [Quack protocol/lifecycle limits](TODO-quack-protocol-v3.md)
- [Boundary type/carrier guard](REPORT-boundary-type-policy-2026-10-30.md)
- [Published-port HTTP investigation](REPORT-http-response-truncation-2026-10-30.md)
- [Server expiry / lifecycle evidence](REPORT-lifecycle-qualification-2026-10-29.md)
- [Accepted native JDBC connection-lifetime race](REPORT-native-jdbc-attach-conflict-2026-11-03.md)
- [Current publication and dependency qualification](REPORT-release-publication-2026-11-04.md)
- [Earlier local release preparation](REPORT-release-closeout-2026-11-03.md)
- [REAL membership in 483/484](REPORT-real-membership-483-484-2026-11-03.md)
- [Project backlog](../../dev-docs/TODO-backlog.md)

The function catalog/gates, typed WITH_TIMEZONE and truncation helpers, domain
controllers and client mappings are the implementation authorities. The extension
contract has ten string/hash natives. The 483 catalog has 75 name/arity entries:
36 Bare, nine Rename, three Operator, 17 Inline/Contextual and ten Alias. The 484
catalog omits TO_UNIXTIME (74 entries/16 Inline-Contextual); its early zoned
constructor bounds require additional exact local paths. The
typed WITH_TIMEZONE constructor is not a context-free native catalog value.
New admission needs exact values/types,
NULL/error contracts, actual scan plans and complete row sets, not merely a scalar
coincidence. String comparison trust and extension availability are separate axes.

Completed phases, old TODOs, source-intake reports and qualification history are
in [archive/](archive/README.md). Preserve evidence there rather than carrying
completed checklists as active work. Human-facing behavior belongs in the
[pushdown reference](../README-pushdown-reference.md), not in this history.
