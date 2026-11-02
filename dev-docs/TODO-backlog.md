# DuckBridge backlog — choose work by category

Updated **2026-11-02**. This is the selection index for outstanding work, not
an instruction to implement every item or enlarge the DuckDB v2 release cycle.
Select a category or item ID before starting another workstream.

## Scope rule

- DuckDB v2 compatibility intake and the matched Doris API-26 qualification have
  been completed for their documented lanes. Outstanding correctness defects
  are not concealed by that statement; the latest full local run remains
  **945 tests / 942 passing / three red audit checks**, with no errors/skips.
- **Confirmed defect**, **coverage gap** and **optional feature** are different
  categories. A missing test is not evidence of a bug. An unchecked box in the
  older preview/readiness tracker is not automatically a new release gate.
- Release work has a fixed scope. Record new findings here, describe their
  evidence and impact, and ask before taking them on. Do not turn a requested
  fix into another unrestricted audit campaign.
- Category membership is not approval to defer a known wrong-result defect,
  disable a test, change semantics or declare a red gate green. Those need an
  explicit disposition. Preserve existing guards and exact counterexamples.
- User preference: timezone correctness is likely more valuable than adding
  native parity for infrequently used ATAN/ATAN2/COSH. The parity extension is
  available if a selected fix needs it. This preference is **not** an instruction
  to implement new native functions, remove pushdowns or begin another audit.

**No new implementation category is selected by this document.**

## A. Confirmed defects in currently admitted connector pushdowns

These are reproduced findings, not speculative areas to explore. They are
still enabled under their existing gates; documentation is not a fallback.
Evidence for A and B-TRINO is the
[targeted qualification](../trino-duckbridge/dev-docs/QUALIFICATION-targeted-pushdown-fixes-2026-11-02.md).

- [ ] **A-TZ-TRUNC — Zoned timestamp truncation.** Correct DST overlap/offset
  behavior in `date_trunc`; examples change actual instants and predicate row
  sets. Choose a bounded contextual/native fix or explicitly approve the
  necessary fallback. Do not generalize from one DST date to all zones/units.
- [ ] **A-TZ-ATTACH — WITH_TIMEZONE.** Correct overlap attachment and nested
  consumers of per-value zone metadata versus DuckDB's session-zone model.
  The existing zoned-function property does not gate every naive-input call.
- [ ] **A-DATE-TRUNC — Wide DATE truncation.** Resolve the native TIMESTAMP
  intermediate/result-type/range and observed error-contract differences.
  Casting the result back to DATE alone is insufficient. Ordinary DATE text
  formatting and finite DATE writes have already been fixed; do not reopen them.
- [ ] **A-MATH — ATAN, ATAN2, COSH.** Resolve exact finite-rounding differences
  affecting equality predicates. Choice: retain Trino evaluation for these
  functions, or justify the cost of genuine native parity. Local fallback is a
  recommendation to consider, **not yet an applied policy or approval**.

## B. Confirmed external defects / acceptance failures

These have concrete reproductions. They are not a mandate to edit upstream
engines or invent local bypasses. Existing shared tracker entries below refer
to the same work, not additional duplicate tasks.

- [ ] **B-TRINO-IN — Trino 483 REAL membership and signed zero.** Independently
  reproduced without DuckBridge/DuckDB: constant folding and the tested compiled
  small-constant IN path disagree for negative zero. The source's DIRECT_SWITCH
  branch compares long-carried REAL bit patterns rather than floating equality.
  A separately selected next task can produce a minimal upstream regression,
  check current upstream status and agree a disposition. No issue has been filed,
  no upstream correction qualified, and no connector workaround applied.
- [ ] **B-QUACK-LEASE — Autonomous orphan-session reaping.** Await a real server
  correction and rerun unchanged expiry assertions. Healthy heartbeat renewal
  and bounded close/cancel are already proved, not a substitute for expiry.
  [Evidence](../trino-duckbridge/dev-docs/REPORT-lifecycle-qualification-2026-10-29.md).
  Shared TODO: `e9091a57-9b2e-4abf-9f89-0c736cfd56d8`.
- [ ] **B-HOST-HTTP — Published-port streaming stalls.** The host forwarding
  path also reproduces the stall with raw HTTP. Find/qualify the actual host
  correction, then rerun the original acceptance route; do not replace it with
  successful direct-bridge diagnostics or looser timeouts.
  [Evidence](../trino-duckbridge/dev-docs/REPORT-http-response-truncation-2026-10-30.md).
  Shared TODO: `2ca59bf8-ab2d-4e0c-8539-a1edc0550995`.
- [ ] **B-NATIVE-CARRIERS — Getter crash and lossy carriers.** Native TUPLE-list
  `getString` still crashes; specific native nanos array/zoned text carriers and
  Arrow TIMETZ lose information. Keep the affected refusals until proper fixes
  and typed proof exist. The raw JDBC DATE getter is also lossy at BCE/extremes;
  our correct DATE reader must not be replaced with it. The requested post-round
  TUPLE revisit is **already done**, not another pending investigation.
  [Boundary evidence](../trino-duckbridge/dev-docs/REPORT-boundary-type-policy-2026-10-30.md),
  [carrier-specific temporal coverage](../trino-duckbridge/dev-docs/TODO-datetime-timezones.md).
  Shared TODO: `5bdd9ba4-d371-48a9-952a-504d45590c73`.
- [ ] **B-DORIS-UTF8 — JNI SQL-literal corruption.** Await the actual UTF-8 /
  modified-UTF-8 correction before promoting the test-only OVERLAY prototype.
  API-26 revendor did not change this native path. Do not reopen the completed
  revendor or splice scanner artifacts to bypass it.
  [Evidence](../doris-duckbridge/dev-docs/REPORT-overlay-qualification-2026-10-29.md).

## C. Coverage gaps / further qualification — not discovered bugs

This is what the earlier summary called **“Broader qualification backlog.”**
These are proposed extensions of evidence, largely inherited from the preview
tracker. They are **not findings that all these things are broken**, and are
not work automatically required before finishing the current release cycle.
If a selected exercise finds a defect, record it separately with its actual
reproduction instead of relabeling this whole category as failing.

- [ ] **C-SEMANTICS — Broader value/type coverage.** Decimal precisions/scales
  beyond the sampled DECIMAL(38,18) corpus; bounded VARCHAR casts, whose native
  rendering currently omits the bound; remaining date/timestamp/coercion limits,
  timezone provenance, explicit-instant domain and nanos-expression qualification.
  An admission/coverage concern is not an assertion that every overload fails.
- [ ] **C-PROTOCOL — Complete v3 and security/resource qualification.** Batch
  indexes/ACKs/terminal counts/late errors, TLS, mixed-version behavior,
  reconnect/result retention limits, backpressure and HTTP retry/resource policy.
  Actual v3 negotiation and specified cancellation/heartbeat/teardown subgates
  are complete; do not redo them as though absent.
- [ ] **C-SQL-SOURCES — Parser, optimizer and storage-source matrix.** Dedicated
  generated/adversarial SQL differential tests; pruning/row-boundary/async/spill
  checks across native files, Parquet, Lance/Vortex and object storage. Current
  connector tests already exercise generated SQL; this is a wider campaign.
- [ ] **C-STORAGE-WRITES — Additional fault and write lifecycle cases.** WAL,
  crash/corruption recovery, codec-specific and cross-process access, arbitrary
  trigger/temporary-table/rename write combinations. The existing storage
  upgrade/rollback runbook and supported trigger fixtures are already qualified.
- [ ] **C-LIFECYCLE — Additional combined lifecycle races.** Pool/reset,
  initialization, prepared-statement/resource destruction, concurrent close,
  partitions and server restarts beyond existing proved subgates. This is not
  evidence that the already-tested recovery/cancellation paths are broken.
- [ ] **C-PLATFORMS — Oldest CPU/libc and broader artifact support.** Verify
  declared minimum hardware/library contracts. Existing signed four-platform
  extension qualification is complete; a modern-host pass is not oldest-CPU
  proof, and no failing older host has been claimed merely from this gap.
- [ ] **C-OPERATIONS — Provenance and observability follow-up.** ICU/security
  maintenance policy, recorded timezone data, artifact/version/resource metrics
  and remaining preview-tracker reconciliation. No specific new security
  vulnerability is asserted by listing maintenance work.

## D. Optional features / architectural choices

These expand capability or performance; they are not correctness fixes for
the already-qualified v2 intake. Each requires a concrete selection/use case.

- [ ] **D-TYPES — Faithful additional logical types.** Full VARIANT/JSON/SQLNULL
  and nested-value contracts across hosts/transports. Current unsupported guards
  remain; do not silently stringify or relabel values to add apparent support.
- [ ] **D-NAMESPACES — Full nested-schema addressability.** A path-aware design
  distinguishing literal dots from ancestry. Exact flat routing and nested
  refusal are complete, not an unfinished version bump.
- [ ] **D-AGGREGATES — Aggregate pushdown.** Trino COUNT/MIN/MAX/SUM with exact
  per-aggregate semantics; Doris COUNT needs scanner support. No aggregate
  pushdown is promised by ordinary row-scan success.
- [ ] **D-READERS — Production Arrow/chunk-reader adoption.** Extend measured
  prototypes only if worthwhile, with complete types, memory accounting,
  backpressure and cancellation. Existing scalar benchmarks did not justify
  changing defaults.
- [ ] **D-CONNECT — Experimental Quack/Arrow CONNECT path.** Compare it with the
  existing wrapper and qualify parameters, session state and teardown before
  choosing a production path. Not needed for current direct Quack JDBC support.
- [ ] **D-ABI-DISTRIBUTION — Stable extension ABI / repository choices.** Assess
  a separate port and cross-version proof; consider private distribution/trust
  policy only if requested. Exact-version signed 2.0 binaries already exist.
- [ ] **D-JAVA-SOURCES — Java table functions.** Require a real reverse-data-flow
  use case before prototyping callbacks, classloaders and resource ownership.
- [ ] **D-DORIS-APIS — Stock-Doris and future scanner seams.** Upstream the
  DuckDbTypeHandler or add its registration seam to remove our remaining patch;
  choose a release containing the needed SPI/handler. Per-scan session init and
  an ADBC migration are separate optional choices, not current scan defects.
- [ ] **D-FUNCTIONS — Additional native parity / SQL functions.** Only for a
  selected useful workload or demonstrated divergence. The extension is available,
  but function availability is not a reason to implement all missing pushdowns.
  Broader JSON mutation support remains unpushed; local regex safety is complete.

## E. Bounded release close-out — after selected issue dispositions

- [ ] **E-CLOSEOUT — Finish the agreed cut, not every item above.** Agree the
  treatment of the currently demonstrated failures, finalize the outstanding
  audit files/tracker changes, run the relevant final verification and record
  the exact supported boundary. Publish/push only when asked. No test skipping,
  tolerance or inaccurate green claim substitutes for disposition. A newly
  discovered item is recorded for selection rather than automatically added to
  this close-out scope.

## Accepted limitations and completed work — not new TODOs

- The specific decimal narrowing-overflow **JDBC_ERROR versus
  NUMERIC_VALUE_OUT_OF_RANGE** difference is explicitly accepted and tested.
  It is not a request to keep revisiting whether ordinary casts must be local;
  successful values/NULL behavior still must not silently change.
- Completed: DuckDB 2.0 intake, matched Doris API-26 revendor, exact flat namespace
  routing, temporary type guards, supported storage/trigger proof, bounded reader
  benchmarks, POWER local fallback, current-profile Unicode qualification,
  narrow DATE/unit fixes, targeted floating/SIGN/DATE-text repairs and the
  current user-facing pushdown reference.
- Producer regex work proceeds externally. DuckBridge's independent guard and
  handoff are complete; no automatic release watch/intake or wider grammar task
  is selected.

## Navigation and evidence

- [User-facing pushdown reference](../trino-duckbridge/README-pushdown-reference.md)
  describes enabled, conditional, local and still-defective behavior.
- [Detailed DuckDB 2.0 readiness history](TODO-duckdb-2.0.md) preserves broader
  checklist/evidence detail; **this categorized index controls future selection**,
  not an automatic sweep of its unchecked preview-era items.
- [Temporal coverage](../trino-duckbridge/dev-docs/TODO-datetime-timezones.md),
  [protocol coverage](../trino-duckbridge/dev-docs/TODO-quack-protocol-v3.md) and
  [Doris upstream API asks](../doris-duckbridge/dev-docs/duckbridge-doris-friction.md)
  provide detail, not additional authority to expand scope.
