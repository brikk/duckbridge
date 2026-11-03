# Narrow DATE write and temporal-unit fixes — 2026-11-01

## Approved scope and result

The user approved withholding newly unsafe pushdowns and limiting existing ones
in uncommon ways, with notification before common-case restrictions. Accordingly
this change repairs finite DATE writes and temporal unit validation only. It does
**not** withdraw ordinary floating ranges, math calls, valid DATE truncations,
DATE text casts, timezone calls or narrowing decimal casts. Their known audit
defects remain open, not accepted as correct.

- Preserve the existing correct DATE string reader unchanged. Do not use native
  JDBC's lossy `getObject(..., LocalDate.class)` getter.
- Preserve the existing typed DATE write (`CAST(? AS DATE)`) and normal transport.
  Remove ISO's leading `+` for expanded positive years, which DuckDB rejects.
  Negative years already have a valid native spelling and remain negative.
- Refuse epoch days outside `[-2147483646, 2147483646]` **before binding**, with
  `NUMERIC_VALUE_OUT_OF_RANGE`. Exact native date headers reserve the neighboring
  values for infinity and define these finite endpoints. No finite Trino date is
  silently converted to an engine sentinel or wrapped into another date.
- Require constant valid units for DATE_TRUNC and DATE_DIFF. DATE admits
  day/week/month/quarter/year; timestamps additionally admit
  millisecond/second/minute/hour. Invalid, NULL and dynamic unit expressions stay
  in Trino. Existing temporal-tier/session and precision gates remain in effect.

This does not fix DATE_TRUNC's DATE-to-TIMESTAMP return-type/range divergence or
zoned offset semantics. The misleading old blanket return-type-safety comment
was replaced with an explicit pending-fix TODO, not an assertion of parity.

## Verification

Commands use JDK 25, module-scoped Gradle tests and project-disk JVM/TMPDIR storage
because the host's `/tmp` user quota is constrained. No tests or lint rules were
disabled.

- UTC JVM: `TestDateWritesAndTemporalUnits`, existing
  `TestDuckBridgeExpressionTranslator`, and the two targeted integration methods
  `TestPushdownEdgeAudits.testFiniteDateWriteRoundTrip` /
  `testInvalidTemporalUnitsStayLocal` pass; lint passes.
- Los Angeles JVM: **623 tests pass**, zero failures/errors/skips: three new
  binding/unit tests, two targeted integration methods, 587 semantic fixtures and
  31 production row-set parity checks. Lint passes. These cover continued valid
  DATE/TIMESTAMP unit pushdown and existing function emissions.
- Native prepared-bind tests preserve actual SQL DATE and exact integer day
  counts for BCE, year zero, year 10,000, normal years and both finite endpoints.
  NULL stays NULL. Both adjacent sentinels, out-of-range values and Long extrema
  fail before binding.
- Connector CTAS from independent Trino date-add inputs, production read-back and
  independent native stored-day validation all match for all eight boundary rows.
- The full audit class was rerun **without relaxing assertions**. It remains red
  in math, decimal and temporal tests. Its temporal zone matrix improves from
  33/120 to **17/120 differences**, with the 16 invalid-unit divergences removed;
  finite DATE CTAS now passes. The two DATE-text/truncation boundary predicate
  differences remain. Math remains 104/212, decimal 4/40, LIKE 0/50.

Evidence: `build/pushdown-edge-audits/narrow-fixes.log`, `narrow-fixes-la.log`,
`narrow-fixes-la/` (saved XML and exact integration outcomes), and
`after-narrow-fixes/` (complete still-red audit observations and XML). The wider
audit helpers/report remain work in progress; a full-suite green result is not
claimed.

## Common-case decision still needed

Blanket fallbacks would affect ordinary `amount > 0` filters, DATE month
truncation, DATE text conversion and non-overflowing decimal narrowing, not just
NaN/BCE/DST/overflow inputs. ATAN/ATAN2/COSH also have finite rounding differences
and SIGN has signed-zero/NaN differences. The existing translation interfaces do
not carry a proof that a physical column lacks these values.

For floating domains, Trino's SortedRangeSet explicitly excludes NaN from a
non-ALL range; base-jdbc's current SQL rendering loses that distinction for an
unbounded high endpoint. NaN-aware **domain and expression** rendering is a
possible real semantic fix that could preserve common filtering, but needs
separate design/qualification and does not repair finite math differences.
No such rewrite or broad fallback is included here. No producer/native build,
dependency update, new type support or Doris change is included.
