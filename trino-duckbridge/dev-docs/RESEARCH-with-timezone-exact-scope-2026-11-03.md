# Exact WITH_TIMEZONE: scope and cost assessment — 2026-11-03

## Decision boundary

The user selected **size the exact fix**, not implementation of a larger native
design or acceptance of a range guard. This is an assessment. No production
translator, source type, timezone library, native source/binary or dependency pin
is changed. No upstream request has been filed and no publication is started.

The selected original slice was literal-zone attachment and nested consumers,
not general dynamic per-row zones or a new calendar subsystem. Preserve that
boundary when deciding the next implementation cut.

## Established facts

The [targeted investigation](REPORT-with-timezone-2026-11-02.md) establishes:

- A public-native preferred-original-offset candidate matches 8,896 exact scalar
  observations across 278 inputs, two transports, four query session zones and
  four literal target zones. This is bounded evidence, not proof of every IANA
  history or Joda's complete lenient resolver fallback.
- A finite native TIMESTAMP at `9223372036854775000` microseconds is a valid
  Trino `timestamp(6)` input. Attaching New York subtracts a -18,000-second offset.
  The result is outside signed microseconds, but Trino can still compute YEAR
  and HOUR directly from its long zoned intermediate, returning `[294247, 4]`.
- Native JDBC and direct Quack both overflow the candidate's INT64 subtraction.
  The focused diagnostic passes by asserting this **known divergence**, not by
  calling arbitrary matching errors parity. Its production-routing test is
  still red and has not been disabled.
- Trino `WithTimeZone` uses Joda's preferred-original-instant conversion, not a
  universal earlier/later-overlap rule. Its lenient fallback additionally compares
  next transitions. The existing SQL candidate does not prove that whole rule.

There is a separate provenance issue: bare native extraction uses the session
zone, while Trino extraction uses the attached value's zone. Range arithmetic
alone does not fix that issue.

## The necessary internal representation

Keep source columns as their actual TIMESTAMP/TIMESTAMPTZ types. Lower a zoned
expression to a typed internal description, conceptually:

```text
ZonedInstant(epochMillisSql, subMillisecondMicrosSql, knownZone, nullState)
```

For comparisons, an equivalent exact internal value is
`CAST(epochMillis AS HUGEINT) * 1000 + subMillisecondMicros`. This can represent
the attached result beyond native TIMESTAMPTZ's range. It is an **internal filter
calculation**, not a replacement source mapping, new exposed Trino type, fake
TIMESTAMPTZ alias, text carrier or SELECT-list projection feature.

The important compiler boundary is that translating a bare WITH_TIMEZONE call
must **not** return an integer SQL expression while claiming it is TIMESTAMPTZ.
A typed child-lowering helper feeds only supported parent operations; those
parents return genuine BOOLEAN/BIGINT/DOUBLE results of the expected logical
types. A raw/unhandled zoned value declines translation. The public predicate
API can continue returning SQL strings; a targeted internal helper avoids
rewriting every numeric/string translator.

Full-range here means the full finite range of the admitted source types,
particularly mapped `timestamp(6)`, including its extreme intermediates. It is
not a claim to support every Trino zoned precision, constructor, zone history
or arbitrary full-Long epoch-millisecond input.

### Exact consumer lowering

- Comparisons order/equate exact instants, ignoring zone keys as Trino does.
  Use HUGEINT or a proved millisecond/fraction comparison, never DOUBLE epochs.
  NULL and identity comparison need their own correct paths.
- YEAR/MONTH/DAY/QUARTER derive the local epoch day from the wide instant plus
  the **attached zone's** offset, then use a genuine native DATE. Over the
  admitted timestamp(6) source range these day counts fit DATE even when the
  instant cannot fit native TIMESTAMPTZ. YEAR is astronomical.
- HOUR/MINUTE/SECOND/MILLISECOND use integer floor-division and positive modulo
  of local time; negative epochs must not use truncation-toward-zero division.
- TO_UNIXTIME must reproduce Trino's operation order: convert epochMillis to
  DOUBLE, divide by 1000, then add the long representation's fractional term.
  Casting one combined HUGEINT epoch to DOUBLE is not automatically bit-exact.
- Short zoned precisions (`p<=3`) pack their result **inside** WITH_TIMEZONE;
  long precisions (`p=4..6`) do not. They cannot share a blanket range/error rule.
  A first exact implementation can admit mapped p=6 inputs and keep unproved
  short-precision/error paths local rather than add a new JDBC error limitation.
- Invalid/dynamic zones, unsupported precision, mixed-zone compositions and
  unsupported consumers stay local as complete expressions. No partially pushed
  OR or value-dropping prefilter substitutes for that rule.

A read-only arithmetic smoke test constructs the witnessed wide instant as
HUGEINT and computes `[294247,4]` using DATE and integer time arithmetic. It is
preserved in `build/with-timezone-parity/design-review/wide-arithmetic.{sql,json}`.
It proves the arithmetic/consumer direction is feasible, **not** full lowering,
timezone resolution or transport qualification.

## Two implementation cuts, with different costs

### 1. Full-range fixed-offset slice on current pins — medium, bounded

UTC and literal fixed offsets need no timezone transition lookup. Their attached
instant is exactly `wideEpochMicros(wall) - offsetMicros`, with constant known
zone provenance. The internal representation above supports exact comparisons
and listed consumers without materializing a native zoned intermediate.

Named-zone WITH_TIMEZONE expressions would remain entirely in Trino in this
cut. This fixes their correctness by an explicit local disposition, not by
pretending native named-zone parity was implemented. The performance cost is
loss of their existing unsafe pushdown. Fixed-offset/UTC pushdown remains useful
and does not depend on the parity extension or a new native release.

Expected footprint: a targeted typed-zoned helper and translator interception,
catalog/type/session admission changes, focused fixtures/rendering tests and
native/direct-Quack row-set/error qualification. The mapped-column truncation
adapter, source mappings, ordinary calendar paths and other pushdowns need not
be broadly refactored. Existing semantic fixtures that request a bare native
WITH_TIMEZONE value must become truthful local/value-consumer tests, not receive
an integer masquerading as that value.

This is **medium work**, larger than the previous 69-line truncation adapter but
bounded to connector translation and tests. The assessment has not implemented
it, proved every DOUBLE/error edge or authorized the named-zone withdrawal.

### 2. Clean full-range named-zone path — larger; native API/release gate

The recommended native design needs offset/resolution operations that consume integer epoch
**milliseconds** without first constructing a microsecond TIMESTAMPTZ. Proposed
capabilities (names/signatures provisional):

```text
offset_at_millis(zone, epochMillis) -> integer offsetMillis
resolve_local_millis(zone, localMillis, preferredOriginalMillis) -> epochMillis
```

The resolver must implement/qualify the complete Joda preferred-original and
lenient fallback contract. An alternative is offset plus next-transition
primitives, with the exact Joda algorithm in the parity adapter. A generic
native LATTER-overlap resolver is not a proven substitute. The returned
millisecond value and original fraction feed the internal typed lowering;
they are not exposed as a fake TIMESTAMPTZ result.

**The current native boundary is not ready for this extension-only change:**

- `extension/icu/datetime/include/timezone.hpp` has millisecond-based GetOffset
  and GetOffsetFromLocal, but no public next-transition method and no DUCKDB_API
  export on the timezone factories/classes.
- `nm -D --defined-only -C` on the actual cached Linux-amd64 JDBC 2.0.0.0 shared
  library finds no exports for TimeZone::TryCreate, its timezone API, the calendar
  factory or ICUDateFunc's needed entry points. This is a concrete Linux check,
  not a claim to have tested every platform's dynamic symbols.
- Current public timezone SQL overloads accept timestamp carriers, not a wide
  epoch-millisecond primitive. `make_timestamp_ms` returns ordinary TIMESTAMP;
  changing the constructor's name does not widen the downstream timezone path.
- The existing parity extension builds string/hash functions and its separately
  configured Unicode ICU. It does not already own a usable timezone service.

Evidence: `design-review/native-symbols.json` and `public-signatures.json`.
The native TimeZone/Calendar code is DuckDB 2.0's replacement implementation
despite the directory name. No additional timezone library is needed in the
design; a supported producer-side API/SQL seam must be exposed instead.

The clean route identified here is a native producer API change using the engine's existing
timezone data, then matched native JDBC/Quack runtime qualification. A public
SQL millisecond resolver could avoid a parity adapter binary altogether; a C++
service seam would additionally need an adapter, capability metadata and matching
signed parity builds. Exact packaging depends on the seam chosen; neither path
is established on today's shipped pins. This is a concrete integration gap, not
a proof that no specialized SQL algorithm could ever implement the operation.
Unproved calendar-cycle folding or representative-instant offset probes are not
counted as an exact alternative or assumed as an approved workaround.

Reaching through another built-in's private bind-data layout/vtable, copying
DuckDB's timezone data/code into the parity binary, silently re-enabling its old
ICU calendar, or inventing a custom engine fork are **not** approved shortcuts.
None has been implemented or assumed as the release plan.

## Relative effort and release cost

| Work package | Size | Main cost / uncertainty |
|---|---|---|
| Fixed-offset typed lowering and admission | Medium | New targeted value representation; exact consumers/NULL/error rules, not a scalar rename |
| Native wide offset/Joda resolver seam | Medium-to-large | Producer API design, complete gap/overlap/next-transition behavior, no existing supported export |
| Named-zone lowering on that seam | Medium | Reuse typed instant IR; attach provenance and native capability/mode gates |
| Qualification | Medium | Both transports/JVM zones, negative epochs, fractions, BCE, native and packed endpoints, exact row IDs/plans/errors |
| Named-zone release/intake | Large / external gate | Matching native JDBC/Quack producer artifacts; signed parity platforms only if an adapter is needed; current pins do not supply the seam |

These are code-inspection estimates, not measured engineering hours or promised
delivery dates. Fixed-offset work is a contained connector task; named-zone
completion includes a producer/release dependency whose lead time is unknown.

New helper capability/version checks must not masquerade as the existing ten
logical string/hash aliases. Preserve string-comparison trust separately from
native temporal capability. With a missing/unqualified helper, retain the whole
affected expression locally; do not emit a missing function or return NULL.

## Recommendation for the next decision

If the release must remain on current pins, select **the exact fixed-offset/UTC
slice plus explicit named-zone local evaluation**. It preserves valid results
without narrowing the timestamp domain, retains useful common pushdown and
avoids another native release cycle. It needs explicit approval of that local
performance disposition; it is not silently applied by this assessment.

If native named-zone pushdown is required now, select the producer wide-timezone
API design first. Do not start with another TIMESTAMPTZ-returning scalar and
rediscover its range limit later. Native data/version parity still needs bounded
qualification; a new API is not a universal equivalence guarantee.

No guard, production change, new native binary, source type degradation, test
skip, unrelated calendar fix or new push has been made while sizing this work.
