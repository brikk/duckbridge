# TODO — date/time/timestamp & timezone coverage

The DuckLake connector accumulated a lot of hard-won date/time/timezone correctness
work for its Trino→DuckDB read path. Status of what carried over here, and what still
needs doing.

**Reconciled 2026-10-31:** DuckDB/native JDBC 2.0.0/2.0.0.0, Quack JDBC 0.12.0,
Trino 483 and JDK 25. The earlier blanket claim that native JDBC truncates every
TIMESTAMP_NS getter is obsolete. The
[nanos qualification](REPORT-quack-0.11-nanos-2026-10-29.md) and current source
distinguish exact typed carriers from specific refused legacy/text paths. This
tracker records their existing proof, not a new runtime change or test run.

## Carried over (working, tested)

- **Session zone alignment** — `TrinoTimeZoneNormaliser` translates the Trino session
  zone for DuckDB's `SET TimeZone` (`Z`→`UTC`, integer-hour `±HH:00`→`Etc/GMT∓H` POSIX
  inversion, named IANA pass-through) and the client sets it on every session
  connection (embedded and Quack — verified over the wire).
- **Fail loud on unsettable zones** — if DuckDB rejects the zone (e.g. fractional bare
  offset `+05:30`) while `pushdown_timestamp_with_timezone` is enabled, the connection
  THROWS with an actionable message (tz-sensitive functions may already be pushed;
  evaluating them in a mismatched zone would return wrong rows). With the property off
  it's a one-shot warn (nothing tz-sensitive pushes). Tested both ways in
  `TestDuckBridgePushdown`.
- **Tier/type gating in the translator** — qualified date/time functions use
  per-function/type gates; zoned timestamp functions additionally require
  `pushdown_timestamp_with_timezone` (default on). Nanos predicates stay local;
  read/write support is not generic function-pushdown qualification.
- **20 date/time parity functions** (`year` ... `with_timezone/2`) via `trino_parity`,
  drift-tested against `trino_meta()`.
- **TIMESTAMP columns** map to DuckDB microsecond `TIMESTAMP` (`TIMESTAMP_MICROS`),
  read + write.
- **DATE** read/write (string-rendered write with `CAST(? AS DATE)`).
- **TIMESTAMPTZ columns** map losslessly to Trino `TIMESTAMP(6) WITH TIME ZONE`, carrying the
  query session zone so above-scan extraction matches DuckDB below the scan. Read + write over
  embedded and Quack; predicate domains disabled pending explicit-instant literal proof.
- **TIME / TIME WITH TIME ZONE** map at microsecond precision in both directions. TIMETZ handles
  embedded OffsetTime and Quack's packed uint64; offset-second values fail loud because Trino stores
  offset minutes. Both Arrow modes reject TIMETZ because the producer drops its offset.
- **Qualified nanos reads** — TIMESTAMP_NS scalar and TIME_NS scalar/list are
  exact on native JDBC, direct Quack JDBC, native Arrow and Quack Arrow.
  TIMESTAMP_NS lists and TIMESTAMPTZ_NS scalars are exact on Arrow/direct Quack,
  but refused on native row JDBC for the specific carrier defects below.
- **Qualified nanos writes** — Trino timestamp/time precisions 7..9 bind full
  precision through explicit native target casts, not microsecond intermediate
  literals. Precision 7/8 uses nine-digit native storage after Trino coercion;
  values remain exact, not a separate precision-7/8 native schema promise.
- **Independent value/transport proof** — `TestNanosEndToEnd` compares 3,000 rows
  per path against native Trino, including NULLs, multi-batch reads, pre-epoch
  nanos, finite nanos boundaries, DST gap/overlap cases, CTAS/bind/read round trips
  and non-UTC rendering. Both local and remote sessions receive the requested
  zone for Quack Arrow. Separate tests check UTC/non-UTC JVM behavior.

## Specific refusals that must remain

- Native JDBC TIMESTAMP_NS **lists** expose legacy `java.sql.Timestamp` elements
  that normalize DST-gap wall clocks; its typed array-element getter is not
  implemented. Scalar JDBC reads instead use exact typed LocalDateTime.
- Native JDBC TIMESTAMPTZ_NS **text** loses historical offset seconds. Quack's
  typed OffsetDateTime and Arrow epoch nanos avoid that lossy representation.
- Arrow TIMETZ loses the original offset, so neither Arrow mode may advertise it
  as lossless. Normal JDBC's typed/packed decoding is a separate qualified path.
- Precision >9, infinity/invalid sentinels and unrepresentable 24:00 values must
  fail rather than round, wrap or masquerade as finite supported values.
- Doris's native six-digit precision ceiling and packed-TIMETZ refusal are
  unchanged by Trino's nanos support.

## Remaining qualification / capability work

1. **TIMESTAMPTZ domain literals** — domains remain disabled. Before enabling,
   prove explicit UTC-instant literals and exact truth sets in multiple zones;
   never use naive timestamps merely because sessions share a zone.
2. **Nanos predicate/function semantics** — remain local until independently
   qualified. Existing scans, binds and local predicate truth-set tests do not
   authorize date arithmetic/extraction pushdown at nanos precision.
3. **DST function matrix** — extend `date_trunc`/day-boundary and other function
   cases against native Trino, not just transport round trips. The naive
   `to_unixtime(TIMESTAMP)` rewrite stays limited to fixed-offset zones because
   Trino and DuckDB disagree on ambiguous DST wall clocks.
4. **Broader extremes** — finite nanos boundaries and explicit infinity refusal
   are tested; the complete DATE/microsecond timestamp ranges, calendar behavior
   and every coercion boundary are not implied. Prove exact values or explicit
   refusal before extending those claims.
5. **Exotic-zone function cases** — complete Kathmandu and Lord Howe coverage
   and record timezone-database provenance; existing New York nanos rendering
   and historical-offset checks are not the whole function/zone matrix.
6. **Arrow temporal completeness** — retain the existing exact-unit/precision
   guards and value matrix; extend remaining type/error/zone shapes before any
   broader promotion. Do not describe the already-qualified nanos paths as absent.

## Cross-pollination from the Doris side (2026-07-19)

When TIMESTAMPTZ domain pushdown is implemented, apply the lesson from
`doris-duckbridge`'s P3/P6 probe (`REPORT-doris-timezone-probe.md` there): a
NAIVE timestamp literal compared against a DuckDB `TIMESTAMPTZ` column is
interpreted in the DuckDB session zone — zone-dependent rows. Domain literals
against TIMESTAMPTZ must be rendered as explicit-UTC instants
(`TIMESTAMPTZ '...+00'`), never naive, and pinned with a dual-server-zone
drift canary (UTC vs America/Los_Angeles, identical rows). The trino side's
SET TimeZone alignment does NOT make naive rendering safe here — it aligns
function evaluation, but explicit instants are still the robust form for
domain literals.
