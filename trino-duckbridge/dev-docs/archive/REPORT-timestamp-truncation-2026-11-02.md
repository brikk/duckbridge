# Zoned sub-day timestamp truncation — 2026-11-02

## Selected scope and current state

The user selected the bounded timestamp work after the sizing discussion:
ordinary mapped zoned timestamps with known session-zone provenance, targeting
the reproduced overlap and non-hour-transition failures. General WITH_TIMEZONE
composition and DATE-only/calendar-field parity are not automatically included.
The user separately approved the small astronomical zoned YEAR correction when
the BCE boundary fixture exposed native year-of-era behavior.

The implementation uses public native SQL, not a parity-extension rebuild.
Production pins remain DuckDB/native JDBC **2.0.0/2.0.0.0**, Quack JDBC **0.12.0**,
Trino **483**, parity **0.5.1**. No extension source, metadata, version or binary
changes. Final verification is recorded below; no push is authorized by this work.

## Root cause and exact native adapter

Trino's Joda `ZonedDateTimeField.roundFloor` uses time arithmetic for fields
shorter than half a day: add the offset at the input instant, round the UTC field,
subtract **that same offset**. DuckDB 2.0's calendar truncation already preserves
offsets for millisecond/second/minute, but its hour path resets fields without
that preservation. An overlap can therefore choose the other occurrence; Lord
Howe also exposes the difference at a half-hour boundary.

For a fixed sub-day bucket width `w`, input epoch microseconds `t`, and the native
offset at that input `o`, Joda's rule is exactly:

```text
result = floor_to_bucket(t + o) - o
       = t - floorMod(t + o, w)
```

The public DuckDB 2.0 functions provide everything needed for this rule:
`epoch_us`, `date_part('timezone', value)` and `make_timestamptz`. The adapter
reduces both terms modulo `w` before adding, implements a positive floor modulo
for pre-epoch values and reconstructs a real native TIMESTAMPTZ from an integer
microsecond count. No DOUBLE epoch, text carrier or timestamp type masquerade is
used. The new native timezone layer supplies the actual offset; no second
timezone library/data set, ICU reconfiguration or private cross-extension symbol
linkage is needed.

The four units are millisecond/second/minute/hour. This is the exact time-field
algorithm, not a correction hardcoded to one New York transition. Day/week/month/
quarter/year use a different Joda branch and retain their existing admission in
this bounded change; their full parity is not certified by the sub-day fix.

## Type, origin and value guards

The existing valid-constant-unit, precision and zoned-function session gates
remain. Native sub-day translation additionally requires known zone provenance:
mapped columns (whose Trino values carry the query session zone) and supported
zone-preserving COALESCE/NULLIF/IF connector calls. New zone
constructors, including WITH_TIMEZONE, are retained locally inside this shape.
This does not repair or withdraw standalone WITH_TIMEZONE in unrelated shapes.

Truncation over truncation stays local: a rounded intermediate can be finite yet
outside Trino's packed column-storage range, so reapplying the ordinary input
guard would be an incorrect composition rule. YEAR may consume an admitted
truncation result with a finite-intermediate guard instead. Calendar truncation
itself remains on its existing path; this does not qualify its semantics.

The direct connector renderer accepts supported IF calls, but **Trino 483 SQL
IF/CASE plans stay local**. Inspection of `ConnectorExpressionTranslator.visitCase`
confirms it recognizes only the NULLIF pattern, not general CASE. The first LA
run incorrectly expected IF to push; its retained plan was correct and the row
set matched. The corrected regression asserts that existing boundary, not a
test skip or a new planner workaround. Nontrivial COALESCE/NULLIF use separate
zoned columns and require actual native scan SQL.

Trino stores a 12-bit zone key beside packed milliseconds, also for its long
zoned type. The adapter guards input microseconds to the exact finite range
`[-2251799813685248000, 2251799813685247999]`. NULL remains NULL; infinity and
unrepresentable source values throw rather than becoming NULL/filtered rows.
Negative tests must prove this fail-loud behavior, not count arbitrary errors as
successful semantic parity.

## Approved astronomical YEAR correction

DuckDB 2.0's `icu-datepart.cpp` has `ExtractYear` returning `CAL_YEAR` and
`ExtractEra` returning `CAL_ERA`. Its zoned YEAR is year-of-era; Trino/Joda uses
astronomical years (including year zero and negative years). The contextual
emission is:

```sql
CASE WHEN era(value)=0 THEN 1-year(value) ELSE year(value) END
```

It uses the same known-session-zone provenance and ordinary input bounds above.
DATE/naive YEAR remains unchanged. YEAR over an admitted truncation uses the
finite-intermediate rule, preserving useful nested predicates without exposing
an unsupported packed timestamp carrier. No broader calendar implementation is
included.

## Exact oracle and fixture transport

`TestingTrinoClient` 483 formats/parses zoned results as wall time plus `VV` zone
ID, with no offset. Its `ZonedDateTime::from` parser chooses the earlier
occurrence of ambiguous local time. This is not an engine rounding/folding bug:
the retained late-overlap diagnostic measures input/output milliseconds inside
Trino, and exports the output in UTC after calculation. Both preserve
`2024-11-03T06:30:00Z`; only the ambiguous NY result carrier selects 05:30Z.
Checksum-verified Maven source is preserved in
`build/timestamp-truncation-parity/source-review/TestingTrinoClient.java`.

All scalar truncation expectations therefore use engine-side integer epochs:

```sql
date_diff('millisecond', TIMESTAMP '1970-01-01 00:00:00.000000 UTC', result)
    * BIGINT '1000'
```

Every selected result is millisecond-aligned, making this exact microsecond
measurement, not a rounded proxy. Source/type checks export UTC **after** the
calculation; query/session zones and inner value zones are unchanged. Production
filter row IDs are compared directly, independently of timestamp display text.

The fixture has **282 distinct rows**: NULL, signed/pre-epoch microseconds,
BCE/year zero, Gregorian cutover dates, broad finite packed bounds, and samples
at -1/0/+1/+999999 microseconds around quarter-hour points in transition windows.
Transitions include NY spring/fall, Lord Howe half-hour changes and Kathmandu's
1985 offset change. Native and direct-Quack instances use the same real typed
TIMESTAMPTZ/TIMESTAMP columns. Inputs in Trino VALUES are independent SQL values.

Initial remote seeding with `setObject(OffsetDateTime)` exposed an expanded-year
binding failure: Quack produced a positive year with a leading `+` that native
SQL rejected. The original XML is retained as
`build/timestamp-truncation-parity/seed-transport-failure.xml`, and the separate
finding is `B-QUACK-YEAR-BIND`. The full corpus is now seeded by prepared BIGINT
microseconds through native `make_timestamptz`/`make_timestamp`; no row was removed,
source type relabeled or production writer changed. This fixes fixture transport,
not the unselected production write issue.

## Verification receipt

The initial LA-JVM run reports **684 cases, 683 passing, one failure**: only the
incorrect general-IF pushdown expectation above. Exact four-unit scalars, nested
minute/YEAR predicates, astronomical YEAR and fail-loud guards passed. The raw
native function differs at **88 hour observations** across the two profiles;
the adapter matches all **9,024** scalar observations. These diagnostics do not
certify every zone/data version. Evidence is preserved under
`build/timestamp-truncation-parity/qualified-la-results/` and `qualified-la.log`.

The corrected UTC run selects those six classes plus the existing temporal audit:
**685 cases, 684 passing, one failure**, no errors/skips; lint passes. All selected
timestamp checks pass, including nontrivial COALESCE/NULLIF scan predicates and
the existing SQL IF/nested-truncation local boundary. The remaining temporal
audit lists **eight observations**: one wide-DATE truncation error-contract
difference and seven existing WITH_TIMEZONE attachment/composition row-set
differences. It no longer lists selected sub-day truncation predicate failures.
The DATE case is specifically `GENERIC_INTERNAL_ERROR/ArithmeticException:
integer overflow` in Trino versus pushed `JDBC_ERROR: Date out of range:
-5877641-6-1`; this is not the accepted decimal limitation, nor equivalent errors.
The audit stays red; no DATE/WITH_TIMEZONE fix or skip is included.

Evidence is preserved in `qualified-utc-temporal-results/`,
`qualified-utc-temporal-evidence/`, `qualified-utc-evidence/` and
`qualified-utc-temporal.log` below the outer evidence directory. A final added
regression checks YEAR over hour truncation at both **exact** packed endpoints,
including a finite rounded instant below the minimum packed timestamp.

The final corrected LA-JVM run passes **685 cases, zero failures/errors/skips**,
including that exact-endpoint regression; lint passes. It selects all six
classes in the command below. Evidence: `final-la.log`, `final-la-summary.json`,
`final-la-results/` and `final-la-evidence/` in the outer directory. Module-wide
UTC results follow; a full green result is not inferred from this subset.

The final full-module UTC invocation reports **942 cases: 939 pass, three
failures, zero errors/skips**. All eight `TestTimestampTruncationParity` cases,
three rendering cases and the selected existing regression classes pass. Lint
and assembly are successful/up-to-date. This is **not a green full suite**:

1. The wider temporal audit retains the eight DATE/WITH_TIMEZONE observations
   above, unchanged in disposition.
2. `testTargetedFloatingComparisons` retains the native Trino REAL `IN (0,1)`
   signed-zero compiled/constant-folded inconsistency (`B-TRINO-IN`).
3. `TestDuckBridgeStringPushdownModes` fails during setup's INSERT with the
   native JDBC unique-file-handle attach conflict (`B-DB-ATTACH`):

   ```text
   Resource In Use Error: Unique file handle conflict: Cannot attach
   "duckbridge-14133269590121190052" - the database file
   ".../build/timestamp-truncation-parity/tmp/duckbridge-14133269590121190052.db"
   is already attached by database "duckbridge-14133269590121190052"
   ```

The setup failure prevents that class's ordinary cases from running; reported
zero skips is not proof that they executed. The prior math-stage full invocation
had this same startup symptom in row-set setup; that class's 31 cases pass in
this invocation. Neither observation assigns a root cause or establishes a fix.
No retry, connection/lifecycle change, fixture removal or test skip is applied.
Full results are preserved before any further test invocation in `full-utc.log`,
`full-utc-summary.json`, `full-utc-results/`, `full-utc-evidence/` and
`full-utc-edge-evidence/` under `build/timestamp-truncation-parity/`.

The matrix covers 2 profiles × 4 session zones × 4 units. Each has source/type
proof, exact scalar epoch proof and five actual production predicate plans:
`trunc<=z`, equality, negation, nested MINUTE and nested YEAR. That is **160**
row-set/scan-plan checks, each also compared with the expression-disabled physical
catalog (not used as the independent oracle). YEAR adds **2,256** exact scalar
observations and **24** pushed BCE/year-zero/ordinary predicate checks.

Native/direct-Quack guards require the explicit finite/representable-input
message for positive/negative infinity and one microsecond outside each packed
bound, with valid and NULL controls. Unknown zone origins, disabled zoned-property
and nested-truncation fallback require retained local filters and matching row
sets. Rendering tests also cover all extension/string-trust combinations,
precision/unit gates and unchanged naive/calendar emissions.

The exact-endpoint YEAR regression compares eight production row sets/plans
against independent Trino, and explicitly confirms that the minimum input's UTC
hour-rounded intermediate is below packed storage bounds but remains finite.

Generated evidence lives in `trino-duckbridge/build/timestamp-truncation-parity/`:
`scalars.tsv`, per-case exact scalar TSVs, native scan plans, composition plans,
`late-overlap-result-carrier.txt` and JVM/Joda profile. Outer Gradle logs and
preserved XML are in `build/timestamp-truncation-parity/`. The broader temporal
audit's measurement correction remains in the pre-existing uncommitted audit
bundle; it is not a dependency of the self-contained timestamp regression.

Both Trino source and the loaded Joda implementation are inspected. The existing
four-zone counterexamples define the first regression matrix; further calendar
or zone projects remain explicit backlog selections.

The current catalog remains **76 entries / ten extension aliases**: 36 BARE,
nine RENAME, three OPERATOR, 18 INLINE/CONTEXTUAL and ten ALIAS. Only YEAR and
DATE_TRUNC changed emission class in this task. Existing unsupported-type/carrier
guards and the accepted decimal narrowing-error limitation remain unchanged.

## Reproduction commands

Run with JDK 25. `JAVA_TOOL_OPTIONS` carries the selected JVM zone into Gradle's
test process. The query matrix still explicitly uses all four session zones.

```bash
TMPDIR="$PWD/build/timestamp-truncation-parity/tmp" \
JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/build/timestamp-truncation-parity/tmp -Duser.timezone=America/Los_Angeles" \
JAVA_HOME=/path/to/jdk25 ./gradlew --no-daemon --max-workers=1 \
  :trino-duckbridge:detekt :trino-duckbridge:test \
  --tests '*TestTimestampTruncationParity' \
  --tests '*TestTimestampTruncationRendering' \
  --tests '*TestDuckBridgeExpressionTranslator' \
  --tests '*TestPushdownSemanticFixtures' \
  --tests '*TestPushdownRowSetParity' --tests '*TestPowerEdgeAudit'
```

The wider audit is separately selected by
`--tests '*TestPushdownEdgeAudits.testTemporalZonesBoundariesAndLocalGuards'` in
the uncommitted workspace bundle. Full module verification uses the same JDK and
temporary directory with `-Duser.timezone=UTC`:

```bash
./gradlew --no-daemon --max-workers=1 --continue \
  :trino-duckbridge:detekt :trino-duckbridge:test :trino-duckbridge:assemble
```

`--continue` lets assembly finish after a red test task; it does not make failures
successful or skip the remaining assertions. Do not combine passing subsets of
different invocations to claim a single full green suite.
