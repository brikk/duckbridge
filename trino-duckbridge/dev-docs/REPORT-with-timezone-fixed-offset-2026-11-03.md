# Exact fixed-offset WITH_TIMEZONE consumers — 2026-11-03

## Selected disposition

After the boundary investigation and exact-fix sizing, the user selected the
recommended current-pin exact cut: UTC/fixed-offset consumers use typed wide
instant lowering; named-zone WITH_TIMEZONE calls remain entirely in Trino.
This is an explicit local performance disposition, not a claim of native
named-zone parity. No range narrowing, new native runtime, timezone library,
private bind-data access or calendar-cycle folding is used.

Production pins remain Trino 483, DuckDB/native JDBC 2.0.0/2.0.0.0, Quack JDBC
0.12.0 and parity 0.5.1. Source columns and logical host types are unchanged.
The parity extension's ten aliases/metadata/binaries are unchanged.

The combined LA-JVM qualification passes; full-module UTC verification retains
two separately documented audit failures. The receipt below is separate from
the earlier experimental candidate's bounded named-zone scalar success.

## Admission and typed boundary

`DuckBridgeWithTimezone` holds an internal instant expression as exact HUGEINT
microseconds, optional known fixed offset, source-validity checks and NULL
provenance. A naive source is admitted only as a mapped variable or typed NULL,
precision 4..6, with a constant zone that Trino's own TimeZoneKey resolves to
UTC or an explicit fixed offset. UTC aliases and supported offset spellings use
that canonical host validation, not an invented second timezone resolver.

Short (`p<=3`) constructors pack inside WITH_TIMEZONE and have a different
range/error contract; they stay local. Nanos, named/dynamic/invalid zones and
other naive source expressions stay local. The zoned pushdown session property
must be enabled and present. This now covers these WITH_TIMEZONE consumers too,
not only functions whose original input column was already zoned.

The context-free WITH_TIMEZONE emission is removed from the function catalog.
A bare constructor never returns an integer string pretending to be TIMESTAMPTZ.
Only a supported parent consumes the internal form and emits its real result
type: BOOLEAN comparisons/NULL tests, BIGINT calendar/time fields or DOUBLE
TO_UNIXTIME. Computed SELECT-list projection remains local as before.

The catalog consequently has **75 entries**: 36 BARE, nine RENAME, three
OPERATOR, 17 INLINE/CONTEXTUAL and ten ALIAS. The typed constructor rule is not
a new context-free catalog function, just as other translator-specific rewrites
are distinct from that table. No alias was removed from the extension.

## Exact calculation and scope

Attachment computes `CAST(epoch_us(wall) AS HUGEINT) - fixedOffsetMicros`.
This represents even a valid attached intermediate beyond native signed
microseconds. It is not constructed as a native TIMESTAMPTZ, materialized as an
unsupported host column or sent to the client as a fake timestamp type.

- Comparisons, including identity, compare exact instants and ignore zone keys.
- YEAR/MONTH/DAY/QUARTER recover a local epoch day with integer floor division
  and use an actual native DATE. These epoch days fit DATE across the full
  admitted finite source range; YEAR is astronomical without year-of-era loss.
- HOUR/MINUTE/SECOND/MILLISECOND use integer positive floor modulo. Negative
  epochs and sub-millisecond fractions are retained, not truncated toward zero.
- TO_UNIXTIME follows Trino's long representation's operation order: DOUBLE
  epochMillis / 1000 plus DOUBLE picosOfMilli / 1e12. HUGEINT is not prematurely
  converted to DOUBLE; exact-bit qualification is required, not a tolerance.
- COALESCE and NULLIF carry typed instant/NULL provenance. Comparison can consume
  mixed known zones because only the instant matters. Calendar/time extraction
  requires a single known fixed offset; NULLIF inherits the first argument's
  zone. Mixed-zone or session-zone-unknown consumers stay local.
- Truncation, DATE_DIFF, casts and other unsupported consumers of an attachment
  remain complete local expressions. The previous mapped-column sub-day
  truncation/YEAR adapter and unrelated temporal paths are not broadened.

All referenced source variables receive validity checks outside the lowered
result, including unchosen COALESCE branches. Naive sources require finite values
or NULL; they do **not** receive a packed-zoned-range or smaller finite-range
guard. Ordinary mapped zoned columns retain the existing packed-input validity
boundary. Invalid sources throw a specific error instead of silently becoming
NULL or disappearing from a filter. NULL remains NULL, including identity and
IS NULL semantics. Unsupported shapes decline translation, not runtime errors.

## Verification receipt

The final combined LA-JVM run passes **702 cases**, with zero failures/errors/
skips; lint passes. It selects the two new WITH_TIMEZONE classes, both timestamp
truncation classes, expression translator, semantic fixtures, physical row-set,
POWER/local-math and extension alias/metadata regressions. No full green result
is inferred from this targeted run. Evidence is preserved as
`exact-fixed-qualified-la.log`, `exact-fixed-qualified-la-summary.json`,
`exact-fixed-qualified-la-results/` and `exact-fixed-qualified-la-evidence/` in
the outer evidence directory.

The fixed-offset matrix has two actual profiles (native JDBC/direct Quack),
four query session zones (UTC/New York/Kathmandu/Lord Howe), five literal target
zones (UTC/+05:45/-05:00/+14:00/-14:00), and 278 distinct ordinary typed rows.
All **100,080 field observations** match independent Trino exactly: eight BIGINT
fields and one exact-bit DOUBLE TO_UNIXTIME per row. Native result metadata
requires real BIGINT/DOUBLE types, not an internal HUGEINT carrier on the wire.
Source rows/types are independently checked; UTC export is measurement-only.

Each matrix cell also verifies ten actual production row-set/scan-plan probes:
**400** total, including equality/order/negation, identity, NULL, calendar/time
and epoch consumers. Absence of a retained filter is required **with actual
HUGEINT scan SQL**, not merely a query the planner eliminated.

Five full finite endpoint/near-endpoint rows add **450 exact field observations**
and ten production row-set/plan checks. These include Long.MIN_VALUE,
Long.MIN_VALUE+2, near-millisecond bounds and Long.MAX_VALUE-1. Both signs of
fixed offsets can take the intermediate outside signed native microseconds.
Engine-side decimal integer epoch measurements prove the independent Trino
source values before consumer comparisons. The corrected focused UTC endpoint
run also passes separately (`exact-fixed-wide-measured.log`/XML/evidence).

Additional checks cover 32 named-zone/property-disabled local row sets/plans,
32 COALESCE/NULLIF/mixed-origin production-versus-local-consumer probes, eight
specific fail-loud invalid-source queries with native plans and six finite/NULL
controls. Rendering tests exercise all extension/string-trust combinations,
fixed-offset aliases, bare-value refusal, invalid/dynamic/named zones, precision
and session gates. Unsupported source shape/consumer tests assert local behavior,
not skipped execution or degraded values.

The final full-module UTC invocation reports **969 cases: 967 pass, two failures,
zero errors/skips**. All selected WITH_TIMEZONE and previous timestamp/math/
alias/row-set regressions pass. Lint and assembly are successful/up-to-date.
This is **not a green full suite**. The two failures are:

1. `TestPushdownEdgeAudits.testTemporalZonesBoundariesAndLocalGuards`: the
   **single** remaining temporal finding is wide-DATE
   `length(CAST(date_trunc('month',d) AS VARCHAR)) = 10`, with the previously
   recorded Trino/native range/error-contract difference. The seven prior
   WITH_TIMEZONE row-set findings no longer appear. The audit remains red;
   neither its assertion nor DATE behavior was changed by this work.
2. `testTargetedFloatingComparisons`: the existing native Trino 483 REAL
   `IN (0,1)` signed-zero compiled/constant-folded inconsistency. No connector
   workaround or upstream change is part of this task.

The native JDBC attach startup conflict is **not reproduced in this invocation**:
all 16 string-mode cases and all 31 physical row-set cases run and pass. Its root
cause remains unassigned; this is not claimed as a correction. No retries,
connection/lifecycle changes, skips, arbitrary matching-error parity or unrelated
fixes were applied. Full XML/observations are preserved before any subsequent
invocation in `exact-fixed-full-utc.log`, `exact-fixed-full-utc-summary.json`,
`exact-fixed-full-utc-results/`, `exact-fixed-full-utc-evidence/` and
`exact-fixed-full-utc-edge-evidence/` in the outer evidence directory.

`WithTimezoneCases` and `TestWithTimezoneParity` are self-contained and do not
depend on the wider uncommitted audit helpers. The original preferred-offset
named-zone candidate and its specific overflow counterexample remain test-only
diagnostics, not production native implementations. Named-zone local fallback
must retain actual Trino filters and match independent engine row IDs.

The first ordinary-range UTC qualification passes all fixed-offset field/DOUBLE
bit checks, 400 pushed row-set/plan probes, local fallback, composition and
fail-loud source tests. Its endpoint test failed while constructing the Trino
literal, before evaluating WITH_TIMEZONE; this remains a preserved red result,
not a completed endpoint qualification (`exact-fixed-utc-boundary.log`/results).

DuckDB reserves **+/-Long.MAX_VALUE** as timestamp infinities; Long.MIN_VALUE
itself is finite. The initial fixture incorrectly attempted the negative infinity
sentinel as a finite constructor argument. It is corrected to retain the actual
finite endpoints, including Long.MIN_VALUE and Long.MAX_VALUE-1; actual infinities
remain explicit negative tests. No valid endpoint is removed.

Trino 483's `DateTimes.parseShortTimestamp` multiplies floor epoch seconds by
1e6 before adding the positive fraction. At the bottom endpoint that partial
product underflows, even though the final microsecond value fits. The independent
Trino fixture constructs that same timestamp as a nearby parsable TIMESTAMP(6)
minus an exact one-second interval. Integer decimal epoch measurements inside
Trino verify each original microsecond value before consumer checks; source/native
types and all endpoint values are preserved. No Trino parser fix or relaxed
oracle is included. The parser finding is separately recorded in the backlog.

Outer logs/XML and preserved evidence: `build/with-timezone-parity/`.
Generated native scalar/plan/profile evidence:
`trino-duckbridge/build/with-timezone-parity/`.

Fixed-field TSVs retain each Trino/native value vector with exact DOUBLE bit
patterns. Endpoint field TSVs, pushed plans, local plans, composition/source-
guard plans and `fixed-scalar-profile.txt` retain the actual observations. Both
oracles assert DuckDB v2.0.0 source ID `d54cbbe3df`. No raw native named-zone
candidate is used as the production oracle.

## Reproduction

With JDK 25, a project-local temporary directory and no test skips:

```bash
TMPDIR="$PWD/build/with-timezone-parity/tmp" \
JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/build/with-timezone-parity/tmp -Duser.timezone=America/Los_Angeles" \
JAVA_HOME=/path/to/jdk25 ./gradlew --no-daemon --max-workers=1 \
  :trino-duckbridge:detekt :trino-duckbridge:test \
  --tests '*TestWithTimezoneParity' --tests '*TestWithTimezoneRendering' \
  --tests '*TestDuckBridgeExpressionTranslator' --tests '*TestPushdownSemanticFixtures' \
  --tests '*TestPushdownRowSetParity' --tests '*TestPowerEdgeAudit' \
  --tests '*TestTimestampTruncationParity' --tests '*TestTimestampTruncationRendering' \
  --tests '*TestTrinoFunctionAliases'
```

The full invocation changes the JVM zone to UTC and selects the module's complete
`:trino-duckbridge:test :trino-duckbridge:assemble` with lint and `--continue`.
That option lets assembly finish after a red test task; it does not clear errors
or turn subsets from separate runs into a single green suite.

## Separate work

Full native named-zone pushdown still needs the separately sized wide-timezone
API/design and qualification; local evaluation is the current disposition.
Dynamic per-row zones, broad calendar/DATE work, Trino REAL IN, the unassigned
native JDBC attach conflict, Quack expanded-year binding and the test client's
ambiguous zoned carrier are not silently added to this task. Prior accepted
decimal overflow error-category limitations are unchanged.
