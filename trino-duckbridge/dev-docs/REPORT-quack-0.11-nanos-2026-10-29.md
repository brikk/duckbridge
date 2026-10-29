# Quack JDBC 0.11.0 and nanos qualification — 2026-10-29

## Inputs

- Both connectors select `dev.brikk.duckdb:quack-jdbc:0.11.0` from Maven Central.
- Published JAR SHA-256:
  `7c5889640b38e494a39c0bf818f1e0e35fb68152a00e0dcc9f273a5f29935ff3`.
  All 105 base classes are classfile 61 (Java 17); no new runtime dependencies.
- DuckDB remains **2.0.0**, native JDBC **2.0.0.0**, Trino **483**.
- The signed core Quack extension reports source **f96420ed46**, expanded to
  `f96420ed46aee9c42064bdb99ddfd1230d6c0086`. Protocol v3 is asserted in both fixtures.
- No native Doris patch or engine rebuild is part of this qualification.

Downloaded release/source identities: `build/quack-011-review/manifest.json`.

## Driver acceptance gates

Published 0.11 includes CANCEL_REQUEST (12), statement-owned query tracking before
PREPARE, a one-row inline target rounded to a whole batch, bounded DISCONNECT, and zoned-nanos
type decoding. These are inspected source facts; actual consumer gates separately
prove the following on a real server and Toxiproxy network:

```bash
./gradlew :trino-duckbridge:qualifyQuackStreaming \
  :trino-duckbridge:qualifyQuackCancel \
  :trino-duckbridge:qualifyQuackDisconnect
```

- **Streaming interruption:** the cursor's initial materialized row count must be
  below the eight-million-row result. Cut TCP after consuming a first row. After
  the buffered prefix, FETCH must raise SQLException, never successful partial
  EOF. Restore the network and prove a fresh session works. No decode budget was raised.
- **Actual cancellation:** first observe the marked query in server active-query
  diagnostics. Its server execution fallback is 10 seconds, HTTP timeout 30
  seconds. `Statement.cancel()` must cause failure within two seconds, with an
  interrupt diagnostic and SQLState **57014**—not a timeout mistaken for cancel.
- **Independent DISCONNECT deadline:** blackhole the response with a general
  30-second request timeout. Closing must complete within the three-second test
  allowance for a two-second teardown request budget.

All three gates pass. Source: `TestQuackNetworkLifecycle`; logs:
`build/quack-011-cancel-disconnect.log` and `build/quack-011-qualification.log`.
Ordinary JUnit tests also cover TCP cut/restoration, configured response timeout,
and server restart: an old session must fail while a fresh one recovers.
All faults target isolated test containers, not shared/user services.

### Correction to the initial streaming diagnosis

`inline_rows` is a **soft row target**, not a batch count: this server increments
the count by `entry.rows`, but drains whole batches. A compressed range sequence
can fit millions of rows in one native batch and expand beyond the Java decoded
allocation budget. Consequently the earlier 20-million-row sequence probe fails
before reaching its intended FETCH fault, including on 0.11. That is not evidence
that 0.11's streaming fix failed.

The strict probe now uses flat hash-derived BIGINT values, which force multiple
default-sized batches. It retains the initial-prefix assertion, actual TCP fault,
SQLException requirement and fresh-session control. The original budget errors
remain in `build/quack-011-acceptance.log` / `build/quack-partial-stream.log`.
No server batching override, larger allocation limit or skipped assertion was used.

## Qualified Trino nanos paths

TIMESTAMP_NS and TIME_NS map to timestamp(9)/time(9). Reads use typed Java
LocalDateTime/LocalTime, writes bind full-precision text through an explicit target
cast; no microsecond TIMESTAMP literal is used as an intermediate. Requests at
precision 7/8 use the native nine-digit storage type, with Trino's coercion before
binding; precision >9 is refused. This is value-preserving storage widening, not
a claim that DuckDB stores a separate precision-7/8 schema type.

| Native type | Native JDBC | Native Arrow | Quack JDBC 0.11 | Quack Arrow |
|---|---|---|---|---|
| TIMESTAMP_NS scalar | exact | exact | exact | exact |
| TIME_NS scalar/list | exact | exact | exact | exact |
| TIMESTAMP_NS list | refused | exact | exact | exact |
| TIMESTAMPTZ_NS scalar | refused | exact | exact | exact |

`TestNanosEndToEnd` uses an independent native-Trino oracle and **3,000 rows per
path**, exercising multiple Arrow batches, NULL scalars/lists/elements, -1/-999/
-1001 nanos, DST-gap and overlap wall clocks/instants, and both finite nanos range
boundaries. It also checks CTAS/bind/read round trips, precision 7/8 coercion,
non-UTC session rendering, local predicate plans and explicit infinity/24:00 refusal.

Nanos domain/function predicates stay local until their semantics are separately
qualified. Reading/writing nanos does not authorize date arithmetic or extraction
pushdown at that precision. Arrow conversion rejects precision loss, reserved
timestamp infinity/invalid sentinels and unrepresentable 24:00 instead of silently
rounding or wrapping. `TestArrowNanosPrecision` pins the exact units and guards.

### Remaining driver/producer limits—not silently bypassed

- Native JDBC's legacy Timestamp getter normalizes JVM DST-gap wall clocks. The
  scalar mapping uses JDBC 4.2 `getObject(..., LocalDateTime.class)` instead.
  DuckDBArray exposes legacy Timestamp elements and its result-set typed getter
  is unimplemented, so that native-JDBC list mapping is refused. Native Arrow and
  Quack's LocalDateTime list carrier are exact.
- Native JDBC's TIMESTAMPTZ_NS text omits historical offset seconds. At New York
  in 1677, parsing it shifts the instant by two seconds. That reader remains
  refused; no timezone-rule reconstruction is attempted. Arrow epoch nanos and
  Quack 0.11's typed OffsetDateTime carrier avoid the lossy text representation.
- DuckDB Arrow TIME WITH TIME ZONE exports only time-of-day, losing its offset.
  Both Arrow modes refuse this type; normal JDBC's existing packed/typed decoder
  remains separate. Doris's packed-TIMETZ rejection remains necessary too.
- Doris native temporal precision remains at six digits; this does not label a
  degraded DATETIMEV2 value as nanos or enable TIMESTAMPTZ_NS/TUPLE there.

Raw evidence: `trino-duckbridge/build/function-audit/nanos-transport-probe.txt`.
UTC and non-UTC JVM runs are distinct. Prior non-UTC proof log:
`build/nanos-non-utc-jvm.log`; the 0.11 rerun is recorded separately.

## Arrow lifecycle and zone propagation

The pending Arrow work now serializes read/teardown, interrupts before waiting
on an in-flight read lock, closes on EOF/query/conversion failure, and makes close
idempotent/terminal. This does not claim that every concurrent-close or network
race is qualified merely because normal scans pass; those remain explicit gates.

Zoned-nanos tests exposed a real Quack Arrow propagation bug: only the remote
server's zone was set, so the local DuckDB exported an Arrow schema marked UTC.
Set the requested zone on **both** sessions and fail if either setting fails.
The New York test checks exact native-Trino rendering including the historical
offset boundary; it is not weakened to an instant-only comparison.

## Final verification

- Full module checks and package verification: **677 Trino + 85 Doris tests**,
  zero failures/errors/skips (`build/quack-011-final-checks.log`).
- All three explicit network/driver acceptance tasks pass. These are additional
  to the JUnit count; they are not declared passing from a source-code inspection.
- Non-UTC JVM nanos/vector rerun: **11 tests passed** with
  `JAVA_TOOL_OPTIONS=-Duser.timezone=America/Los_Angeles`
  (`build/quack-011-nanos-non-utc.log`).
- Live exact-pin Java-17 Doris FE/BE/Quack smoke passed with the 0.11 driver:
  security, metadata, exact values/EXPLAIN, temporal edges, pool concurrency,
  catalog restart and plugin-isolation/recovery (`build/quack-011-doris-smoke.log`).
- Java-17 boundary audit verifies the 0.11 artifact hash and records its typed
  zoned-nanos carrier (`doris-duckbridge/build/function-audit/boundary-types.json`).
- Cluster-free orchestration/intake/bootstrap checks: **22 passed**.
- Trino assembly, Doris plugin ZIP and staged BE driver contain the exact
  published 0.11 JAR bytes. No old Quack driver is bundled alongside it.

The run-owned stack was stopped without deleting volumes. No native Doris patch,
baseline change, push, release publication or production deployment was made.
