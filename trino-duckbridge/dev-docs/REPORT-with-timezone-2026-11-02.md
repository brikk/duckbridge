# Bounded WITH_TIMEZONE parity — 2026-11-02

## Selected scope and state

After pushing the sub-day truncation/YEAR work, the user selected the next
recommended item, `A-TZ-ATTACH`: literal-zone attachment and nested consumers.
This is not permission for general dynamic/per-row zone support, calendar
truncation, wide DATE fixes, a JDBC lifecycle workaround or another broad audit.

Qualification is in progress. No production correction, completed parity result
or native-extension publication is claimed at this draft boundary. Existing
uncommitted wider audit files and tracker edits remain separate.

## Two distinct root causes

Trino 483 `timestamp.WithTimeZone` calls:

```text
UTC.getMillisKeepLocal(targetZone, floorDiv(epochMicros, 1000))
```

The cached Joda 2.14.2 bytecode confirms that `getMillisKeepLocal` invokes the
three-argument `convertLocalToUTC(localMillis, false, originalInstant)` method.
That method first looks up the **target offset at the original UTC-carried wall
time**, then tests whether subtracting that offset yields an instant with the
same offset. If so, it keeps that candidate; otherwise it invokes its lenient
local-time resolver. Thus a universal "earlier overlap occurrence" policy is not
the rule. New York and Lord Howe need different overlap selections.

DuckDB 2.0's native calendar uses FORMER for nonexistent local times and LATTER
for duplicated local times (`datetime/calendar.cpp`,
`datetime/timezone.cpp`). A plain `timezone(zone, wall)` therefore does not
preserve Joda's original-offset preference.

Separately, a Trino zoned value carries its target zone key. A DuckDB TIMESTAMPTZ
is an instant; bare native `hour(value)` uses the query session zone. Even exact
attachment does not make `hour(with_timezone(wall,'UTC'))` correct in a New York
session. Supported consumers need explicit known-zone provenance and native
conversion in that zone; unknown/mixed origins cannot be silently treated as
session-zone values. No fake timestamp type, string carrier or exposed STRUCT is
proposed.

## Public-native candidate being qualified

For naive integer microseconds `t`, look up target offsets natively at `t` and
`t-offset(t)`. Keep the preferred candidate when both offsets agree; otherwise
use native lenient local resolution. This is a candidate, not a qualification
claim for arbitrary timezone histories. The fallback and finite boundary
behavior must be verified before production routing is changed.

Offset lookup uses the public functions `make_timestamptz`,
`timezone(zone, instant)` and `epoch_us`, preserving integer microseconds.
Native DuckDB 2.0's replacement timezone/calendar implementation supplies offsets;
no second timezone library/data set or former-ICU assumption is introduced.

## Initial targeted qualification

`WithTimezoneCases` has independent Trino VALUES and native-typed constructors
for the same wall times, including NULL, pre-epoch fractions, BCE/year zero,
finite broad bounds and local transition windows in New York, Lord Howe and
Kathmandu. Native JDBC and direct Quack profiles each use four query session
zones and four literal target zones. The query/session zone is not changed to
hide a target-zone discrepancy.

Exact expected microseconds are measured inside Trino as integer millisecond
epoch differences plus the independently supplied source sub-millisecond
remainder. Zone offsets are whole seconds, so attachment preserves that
remainder, including before the epoch. No ambiguous wall-plus-zone result carrier
or DOUBLE epoch is the scalar oracle. Production row IDs and actual scan plans
are separate checks; raw native differences remain diagnostics.

Logs and preserved results belong in `build/with-timezone-parity/`; generated
scalar/plan evidence is in `trino-duckbridge/build/with-timezone-parity/`.
No unrelated failure will be skipped or relabeled as environment variance.

The first UTC run passes the public-native candidate for **278 rows × 2 profiles
× 4 session zones × 4 target zones = 8,896 exact scalar observations**. Raw native
outcomes are retained alongside it. Source values/types match independent Trino.
The production test remains red: the existing translator retains zoned YEAR
over WITH_TIMEZONE, while the proposed test requires actual native consumer SQL.
This is not evidence that production now uses the candidate. Production files
have not been changed. Baseline XML and scalar/plan evidence are preserved under
`build/with-timezone-parity/before-routing.xml` and `before-routing-evidence/`.

## Finite boundary decision — 2026-11-03

A connector-free runtime Trino query succeeds:

```sql
SELECT year(with_timezone(wall,'America/New_York')),
       hour(with_timezone(wall,'America/New_York'))
FROM UNNEST(ARRAY[TIMESTAMP '294247-01-10 04:00:54.775000']) input(wall)
-- [294247, 4], both BIGINT
```

The naive input is a valid native `make_timestamp(9223372036854775000)` value.
Native offset lookup returns -18,000 seconds. Subtracting that offset to form an
attached instant overflows signed microseconds:

```text
Overflow in subtraction of INT64 (9223372036854775000 - -18000000000)!
```

Trino's `LongTimestampWithTimeZone` carries epoch **milliseconds** plus a fraction
and zone key, and does not pack that intermediate in its constructor. Nested
YEAR/HOUR can consume it without materializing it in packed column storage.
DuckDB's native TIMESTAMPTZ cannot hold the same instant. This is not an ambiguous
test-client result carrier, a source type mismatch or an environment failure.
Witnesses: `finite-native-boundary.txt`, `native-finite-native-boundary.txt` and
`remote-finite-native-boundary.txt` under the module evidence directory. The
corrected focused diagnostic passes on native JDBC and direct Quack, with lint:
`build/with-timezone-parity/finite-boundary-final.log` and
`finite-boundary-final.xml`. It asserts that exact Trino result and the specific
native overflow **as a known divergence**, not successful error parity. The
production-routing regression remains red and is not disabled by this diagnostic.

Consequences for the selected scope:

- A native scalar returning TIMESTAMPTZ alone does not solve the range mismatch.
- A finite/range guard is **not** a full semantic fix: it adds an error on some
  valid Trino queries. It requires explicit approval as a narrower contract.
- Keeping the complete unsupported call/consumer local preserves exact Trino
  semantics but gives up its native pushdown.
- A full-range fix needs a larger context-aware design for comparisons and
  consumers, with suitably wide instant arithmetic/native offset access, without
  relabeling source columns or exposing a fake timestamp carrier. That work must
  be sized/agreed before implementation, not slipped into this bounded fix.

Production routing is unchanged while this decision is raised with the user.
No guard, new timezone library, native rebuild, test skip or type degradation
has been introduced.

The user chose **size the exact fix**, not a guard. The subsequent
[full-range scope/cost assessment](RESEARCH-with-timezone-exact-scope-2026-11-03.md)
separates a feasible current-pin fixed-offset typed-lowering slice from the
larger named-zone path with a native producer API/release dependency. It is not
implementation approval and does not withdraw any production emission yet.
