# Open work and accepted limits

Select an item before starting another workstream. A coverage gap is not a
confirmed bug, and an optional feature is not a release gate. Do not skip tests,
weaken types, normalize values or claim arbitrary matching failures as parity.

## Confirmed defects / producer limitations

- [ ] **B-QUACK-LEASE:** autonomous orphan-session expiry is not qualified.
  Await a real producer correction and rerun unchanged lease assertions. Healthy
  heartbeats and normal close are not expiry. [Evidence](../trino-duckbridge/dev-docs/REPORT-lifecycle-qualification-2026-10-29.md).
- [ ] **B-HOST-HTTP:** affected Docker published-port routes can stall/truncate
  streams. The actual host/network correction is unassigned. Direct-bridge
  controls are not replacement acceptance. [Evidence](../trino-duckbridge/dev-docs/REPORT-http-response-truncation-2026-10-30.md).
- [ ] **B-NATIVE-CARRIERS:** retain native TUPLE/VARIANT/untyped-NULL and lossy
  carrier refusals until a real typed fix is proved. The raw TUPLE-list getter
  crash was reproduced; this is not a pending request to repeat that audit.
  [Guard policy](../trino-duckbridge/dev-docs/REPORT-boundary-type-policy-2026-10-30.md).
- [ ] **B-QUACK-YEAR-BIND:** direct Quack OffsetDateTime batch binding of expanded
  positive years produced rejected `+69388...` timestamp spelling. This fixture
  finding does not alone establish the production writer's whole boundary.
  A write/transport fix needs separate selection. [Witness](../trino-duckbridge/dev-docs/archive/REPORT-timestamp-truncation-2026-11-02.md).

## Retained engine/tooling knowledge

- **B-DORIS-UTF8:** corrected upstream and verified on API30, including the exact
  pushed supplementary-Unicode SQL-literal reproduction over JDBC/Quack. No text
  workaround or native handler splice was used. OVERLAY pushdown remains disabled;
  its candidate mapping needs separate qualification.
  [Current evidence](../doris-duckbridge/dev-docs/REVIEW-doris-api30-2026-11-10.md).
- **B-TRINO-IN:** 483's compiled REAL membership path distinguishes negative-zero
  bits from its equality/domain paths. The specific IN/NOT IN issue is fixed in
  stock 484; maintain version-specific assertions, not a skipped test or value
  normalization. The 483 limitation is accepted for this release.
- **B-TRINO-TS-LITERAL:** the bottom microsecond endpoint can overflow the 483
  literal parser's partial product. Keep exact typed construction and engine
  numeric measurement in boundary fixtures; no parser fix is selected.
- **B-TRINO-TEST-CARRIER:** ambiguous wall-time-plus-zone result reconstruction
  loses late-overlap occurrence information. Measure integer instants inside the
  engine or export UTC after calculation, not by changing the query session zone.
- **B-DB-ATTACH:** native JDBC concurrent open/close reproduces the startup attach
  conflict without the connector, on 2.0.0.0 and 2.0.0.1. The user authorized
  reporting, not an upstream fix. A Java reproducer was added to existing
  [upstream #22277](https://github.com/duckdb/duckdb/issues/22277#issuecomment-6602525930).
  The user accepts it as a release limitation while awaiting an upstream fix.
  No production retry or mitigation is selected. Predicate-parity tests may own
  a stable native database lifetime; their complete cases/assertions remain.
  [Evidence](../trino-duckbridge/dev-docs/REPORT-native-jdbc-attach-conflict-2026-11-03.md).

## Coverage gaps — not demonstrated defects

- [ ] **C-SEMANTICS:** broader decimal precision/scale/literal/rounding cases,
  bounded VARCHAR casts, remaining temporal/coercion limits and additional
  timezone provenance/nanos-expression proofs.
- [ ] **C-PROTOCOL:** full wire conformance, TLS, reconnect/retention caps,
  backpressure, mixed-version and network/resource matrices beyond the specific
  proved gates. [Active protocol contract](../trino-duckbridge/dev-docs/TODO-quack-protocol-v3.md).
- [ ] **C-SQL-SOURCES:** broader parser/optimizer/pruning/async/spill and storage
  source matrices, including object storage, Lance and Vortex.
- [ ] **C-STORAGE-WRITES:** further WAL/crash/corruption and arbitrary trigger,
  temporary-table and cross-process lifecycle cases.
- [ ] **C-LIFECYCLE:** additional combined pool/reset/initialization/destruction,
  close/cancel/partition and restart races. Existing proved recovery is not absent.
- [ ] **C-PLATFORMS:** oldest supported CPU/libc proof, not a repeat of the
  existing signed four-platform extension qualification.
- [ ] **C-OPERATIONS:** ongoing native-library/security/data provenance and
  observability policy; this listing is not a discovered vulnerability.

## Optional capability / design work

- [ ] **D-TYPES:** faithful additional logical/nested types; preserve current refusals.
- [ ] **D-NAMESPACES:** full nested-schema addressability without conflating dots and ancestry.
- [ ] **D-AGGREGATES:** exact aggregate pushdown; Doris COUNT needs a scanner seam.
- [ ] **D-READERS:** production Arrow/chunk adoption only when measured and fully qualified.
- [ ] **D-CONNECT:** experimental Quack/Arrow CONNECT adoption needs its own decision.
- [ ] **D-ABI-DISTRIBUTION:** stable extension ABI, alternate distribution/trust or extra platforms.
- [ ] **D-JAVA-SOURCES:** Java table functions only for a concrete reverse-data-flow use case.
- [ ] **D-DORIS-APIS:** stock-Doris TypeHandler registration, per-scan session init
  and a possible ADBC migration. [Current asks](../doris-duckbridge/dev-docs/duckbridge-doris-friction.md).
- [ ] **D-FUNCTIONS:** native named-zone WITH_TIMEZONE, broader calendar/JSON
  functions and other explicitly selected useful workloads. Current local DATE
  truncation, local named-zone attachment and local math policies are intentional.

The four specific DECIMAL narrowing-overflow JDBC_ERROR versus
NUMERIC_VALUE_OUT_OF_RANGE differences are accepted; other successful/error/NULL
behavior still requires exact proof. External producer regex work is not an
automatic watch or intake task. Completed selections and detailed earlier
coverage notes are retained in [the archive](archive/README.md), not duplicated
as open tasks here.
