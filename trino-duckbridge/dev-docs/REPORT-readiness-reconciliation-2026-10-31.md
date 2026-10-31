# Readiness tracker reconciliation and mod regression — 2026-10-31

## Outcome and scope

The current readiness backlog now distinguishes completed consumer qualification,
specific external/environment blockers and optional capabilities. No production
source, artifact pin, transport default, type contract or pushdown rule changed.
The producer handoff is external work, not a local dependency or release watch.

Reconciled trackers:

- [DuckDB 2.0 readiness](../../dev-docs/TODO-duckdb-2.0.md).
- [Quack protocol v3](TODO-quack-protocol-v3.md).
- [Temporal types and timezones](TODO-datetime-timezones.md).
- [Historical evaluation findings](TODO-rectify-from-eval.md).

## Corrections grounded in existing evidence

- Quack JDBC **0.12.0** and actual negotiated v3 replace the stale 0.9.0/v1-only
  description; Doris's active API-24/JNI-6 master lane is distinct from rollback.
- Real cancellation, bounded partition-CANCEL/DISCONNECT and healthy heartbeat
  renewal are completed subgates, not proof of autonomous orphan-session expiry.
- The shipped signed parity **0.5.1** matrix is complete for four connector native
  platforms. Stable-ABI migration, Wasm and oldest-CPU proof are not inferred.
- Unicode-data parity is proved for the qualified Trino 483 / JDK 25 profile,
  rather than still waiting on the old ICU-66.1/Unicode-13 gap.
- Typed nanos support supersedes blanket driver-truncation/refusal claims.
  Native timestamp-list and historical zoned-text, Arrow TIMETZ and Doris
  precision limits remain explicit, separately named refusals.
- Flat namespace routing, current-catalog isolation, storage migration/rollback,
  supported triggered-write contracts and bounded scalar benchmarks are complete
  for their documented scopes. Full nested addressability, storage-fault recovery,
  arbitrary trigger lifecycle and a production chunk reader are not implied.
- The requested post-benchmark boundary-guard revisit already occurred; the
  isolated native TUPLE-list crash still requires that guard.
- The old `mod/2` zero-divisor pushdown hole was already fixed by a nonzero integer
  constant-divisor gate. No global engine setting change is needed to retain it.

The trackers link the dated reports behind each completed scope. Historical
reports retain their original artifact identities; they are not silently relabeled
as fresh tests or qualification of newer platforms/versions.

## Focused local regression added

`TestDuckBridgeArithmeticPushdownParity` now also exercises the actual `mod()`
function, not just the `%` operator or an isolated translator fixture:

```sql
SELECT id FROM mod_function_zero WHERE mod(id, 0) = 0;
SELECT id FROM mod_function_zero WHERE mod(id, divisor) = 0;
```

The real source table includes a zero column divisor. Distributed EXPLAIN must
retain Trino's `filterPredicate` for each expression, then actual execution must
raise division-by-zero. A successful empty/partial result or unrelated error is
not accepted. The existing positive nonzero-constant row-set tests are unchanged.
No remote SQL replay, error wrapper or pushdown widening was introduced.

## Verification

```bash
./gradlew :trino-duckbridge:test --tests '*TestDuckBridgeArithmeticPushdownParity' \
  :trino-duckbridge:detekt
./gradlew :trino-duckbridge:check :trino-duckbridge:verifyPluginAssembly \
  :doris-duckbridge:check
```

Targeted arithmetic class and lint pass: **7 tests**, zero failures/errors/skips.
Full Trino `check` passes **920 tests**, zero failures/errors/skips, and plugin
assembly verification passes with 87 JARs and no provided/parent-first SPI leak.
Doris `check` and audit-runtime isolation also pass; its unchanged 89-test task
was up-to-date, not a fresh native/JNI qualification run.

Logs and saved evidence are under `build/readiness-reconciliation/`:
`arithmetic-targeted.log`, `full-checks.log`, `test-summary.json` and copied
`test-results/`. Relative Markdown links, code fences and `git diff --check`
also pass. No test is disabled or weakened by this reconciliation.

Published-port streaming, server orphan reaping, the native TUPLE getter and
Doris JNI UTF-8 correction remain open. The full Trino suite reruns the isolated
native TUPLE-list negative control and still observes SIGSEGV/StringCast; its
log is retained as `native-tuple-negative-control.log` in the evidence directory.
The separate streaming/lease-expiry/JNI acceptance gates were not rerun or
declared fixed by the tracker cleanup or arithmetic regression.
