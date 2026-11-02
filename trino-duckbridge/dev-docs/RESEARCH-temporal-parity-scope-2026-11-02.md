# Temporal parity: size and integration assessment — 2026-11-02

## Scope

Code-inspection assessment requested by the user, **not implementation approval**.
No temporal emitter, native extension source, dependency, bundled data or test
policy is changed by this assessment. Existing confirmed counterexamples define
the immediate work; this is not another general calendar/timezone audit campaign.

## Relative size

### DATE-only truncation: small native implementation; boundary policy still matters

A DATE-preserving native `trino_date_trunc` overload can calculate the existing
day/week/month/quarter/year fields directly on epoch days/Gregorian date parts,
without DuckDB's microsecond TIMESTAMP intermediate. This is a contained function
plus registration/metadata, connector routing and focused regressions, not a
replacement calendar subsystem. It does not require timezone data.

Full claimed equivalence needs the existing wide-DATE counterexamples too:
truncation can leave the representable result range, and a remote exception
normally becomes JDBC_ERROR rather than Trino's local error category. Native
arithmetic alone does not settle that error-contract/admission decision. Do not
silently return infinity/NULL or claim a CAST back to DATE fixes the whole range.

### Zoned truncation on mapped columns: medium, plausible bounded parity work

Trino `timestamptz.DateTrunc` rounds using the value's Joda chronology and preserves
its zone key. DuckBridge's ordinary mapped zoned columns carry the query session
zone, giving a known zone for a first slice. A native implementation can target
these inputs, retaining unsupported zone origins/compositions locally rather than
claiming a generic replacement for every zoned expression.

The work is in reproducing offset-preserving rounding through overlaps and
non-hour transitions, not forwarding the same unit/zone to native DATE_TRUNC.
The existing New York/Lord Howe counterexamples must pass in actual nested
predicates as well as scalar values.

There is useful infrastructure in the pinned DuckDB source:
`extension/icu/datetime/include/timezone.hpp` exposes offset lookup and
`GetOffsetFromLocal` with separate FORMER/LATTER options for gaps and overlaps.
This makes a shared resolver approach plausible, but it is **internal extension
infrastructure**, not an already-qualified public/linkable API for our binary.
Availability/linkage/data-version alignment must be checked before choosing it.
No second timezone database or ICU reconfiguration is assumed necessary or free.

### General WITH_TIMEZONE and nested consumers: larger, not extension-only

Trino's `timestamp.WithTimeZone` returns both an instant and the requested zone
key, using Joda's keep-local conversion. DuckDB's TIMESTAMPTZ is an instant whose
native extraction uses the session zone. A native function returning the right
instant therefore does not alone fix `hour(with_timezone(ts,'UTC'))` in a non-UTC
session.

The connector must preserve/understand zone provenance when rewriting consumers,
or withhold expressions it cannot represent. Literal/known zones can define a
bounded first scope; mixed/dynamic per-row zones and arbitrary compositions are
a separate design choice. No fake timestamp type, string carrier, implicit
normalization or new exposed STRUCT representation is proposed here.

## Existing extension and release cost

The parity extension currently registers only ten string/hash natives. Its ICU
configuration explicitly disables formatting/calendar/timezone services and ships
no timezone resource bundle. Having vendored ICU source is not the same as having
a ready calendar implementation/data set.

Any selected native change also needs its actual `trino_meta()` contract and
connector extension-availability/type/context gates updated together, followed
by native tests, focused Trino row-set/error checks, the supported signed native
platform builds/publication and matching consumer/server intake. That integration
and qualification can cost at least as much as writing the scalar function.

## Recommended bounded first scope, if selected

Prioritize **zoned truncation of ordinary mapped columns/known zones**; add the
small DATE-only implementation if its boundary/error disposition is agreed.
Treat full WITH_TIMEZONE composition as a separate choice, not an unnoticed
extension of the first task. No implementation is started by this memo.

Existing selections remain in [the categorized backlog](../../dev-docs/TODO-backlog.md):
`A-TZ-TRUNC`, `A-TZ-ATTACH`, `A-DATE-TRUNC`. ATAN/ATAN2/COSH were separately
approved to remain local and do not require a native parity release.
