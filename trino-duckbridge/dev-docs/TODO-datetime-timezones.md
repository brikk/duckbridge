# Temporal contracts and remaining work

## Current invariants

- Native TIMESTAMPTZ maps to timestamp(6) with time zone carrying the query session
  zone; instants retain microseconds. Zoned domains remain disabled.
- Connections align the native session zone. Failure while zoned expression
  pushdown is enabled throws; property-off warning is not a successful zone change.
- DATE scans and finite writes retain actual DATE types and epoch days. DATE-input
  DATE_TRUNC is local for every unit. Explicit unbounded DATE text uses astronomical
  year spelling; bounded VARCHAR casts have a separate qualification boundary.
- On 483, mapped-column sub-day truncation uses the input instant's offset and integer
  microseconds. Ordinary zoned YEAR is era-aware. Unknown zone origins and nested
  truncation decline emission; finite YEAR consumers may use a rounded intermediate
  beyond packed column bounds.
- On 483, fixed-offset WITH_TIMEZONE consumers use a typed internal wide instant plus
  provenance; no integer is exposed as TIMESTAMPTZ. Admitted mapped naive sources
  have precision 4..6 and retain their full finite range. Named/dynamic/short/nanos
  constructors and unsupported consumers stay local.
- On 484, all WITH_TIMEZONE and TO_UNIXTIME calls are local. Zoned DATE_TRUNC
  admits only milliseconds. Preserve its early zoned-constructor bounds and
  compensated DOUBLE rounding; do not reuse the 483 wide-consumer contract.
- Ordinary microsecond TIMESTAMP reads reject infinity/reserved carriers before
  evaluating local expressions. Check the complete finite bounds before conversion;
  the valid Long.MIN_VALUE epoch must survive its overflowing floor-second product.
- Generic expression precision above 6 stays local even when typed nanos reads or
  writes are supported. No read/write proof implies predicate parity.

## Type/carrier boundaries to preserve

| Path | Qualified nanos forms | Specific refusals |
|---|---|---|
| Native row JDBC | TIMESTAMP_NS scalar, TIME_NS scalar/list | TIMESTAMP_NS list and TIMESTAMPTZ_NS scalar carriers lose information |
| Direct Quack JDBC | Qualified scalar/list nanos forms, including zoned nanos | Retain unsupported precision/value/type guards |
| Native and Quack Arrow | Qualified timestamp/time nanos, including timestamp lists and zoned scalars | TIME WITH TIME ZONE loses its offset in the producer |

TIMETZ on the ordinary JDBC paths requires minute-aligned offsets. Offset seconds,
unrepresentable 24:00, precision above 9, infinity and invalid sentinels fail rather
than round, wrap or masquerade as finite supported values. Doris retains its
separate six-digit ceiling and packed-TIMETZ refusal.

Use engine integer epochs for ambiguous zoned outputs, or UTC export **after** the
calculation. Do not change the query/session zone to conceal a discrepancy. The
483 bottom-end literal parser requires exact typed fixture arithmetic for some
valid microsecond values; retain independent numeric read-back. Do not replace the
DATE reader with the lossy native LocalDate getter at BCE/extreme dates.

## Incomplete work — requires selection

- [ ] Explicit-instant TIMESTAMPTZ domain literals and multi-zone truth-set proof.
- [ ] Nanos arithmetic/extraction/predicate semantics, not just carrier round trips.
- [ ] Calendar-unit timestamp and broader zone/data-history qualification beyond
  the selected UTC/New York/Kathmandu/Lord Howe sub-day and fixed-offset matrices.
- [ ] Additional temporal coercion/error boundaries and bounded VARCHAR behavior.
- [ ] Broader Arrow memory/ownership/error/zone combinations before promotion.
- [ ] Native named-zone WITH_TIMEZONE pushdown only after its wide offset/resolver
  API/design is selected; the current disposition is local evaluation.

Completed matrices and earlier tracker detail are retained in
[archive/](archive/README.md). Keep these invariants when changing mappings,
writers, prepared parameters, query-session initialization or typed lowering.
