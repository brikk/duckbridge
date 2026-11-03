# Bounded Trino release close-out — 2026-11-03

## User decisions and boundary

The user approved normal release preparation and committing the outstanding
owned work, with these explicit dispositions:

1. **DATE-input DATE_TRUNC stays local**, including ordinary/common values. The
   performance cost is accepted rather than widening the native calendar project.
2. **Release with the native Trino REAL membership / signed-zero limitation.**
   REAL is SQL's 32-bit floating-point type; IN is set membership, for example
   `WHERE x IN (0,1)`. The pinned engine's compiled IN path can distinguish -0.0
   from +0.0 while its equality/domain/constant-folding paths treat them as equal.
   Retain the independent reproducer and verify each actual connector execution
   path honestly; do not skip membership, normalize signs or invent a production
   workaround. An upstream engine fix/upgrade is not selected.
3. **Ignore the intermittent native JDBC attach issue for this close-out.** Its
   cause stays unassigned and no fix is claimed. No investigation, retry or test
   exclusion is introduced. A red build is still a red build.
4. Reconcile the owned audit files/trackers and prepare a clean, verifiable Trino
   artifact. This is not authorization to push, tag, publish or trigger the
   release-branch workflow. No Doris native/runtime/API/feature intake is included.

Subsequent user direction adds **both Trino 483 and 484 as required release
targets**, with separate `483-0.3.0` and `484-0.3.0` artifacts. Commit/push is now
explicitly authorized after validation and documentation cleanup; publishing is
still not requested. READMEs describe only current human-facing setup/limits.
Developer-only material lives in dev-docs and completed history is archived.

## DATE truncation implementation

The existing DATE_TRUNC gate now refuses DATE inputs for every unit, independently
of extension/string trust and the zoned-function switch. Native/naive timestamp
and qualified zoned truncation paths are unchanged. Existing DATE scans, finite
writes, explicit unbounded DATE-text formatting and DATE_DIFF admission remain.

The four old positive native DATE_TRUNC fixtures become explicit local fixtures;
day identity is also covered. This is an approved production fallback, not a
test skip. `TestDateTruncationLocal` checks source values/types, actual local
filter plans, ordinary results and precise wide-DATE Trino error contracts on
native JDBC and direct Quack. Planner elimination of a day identity is distinct
from forwarding an unsafe native function.

## REAL membership qualification

The remaining floating audit failure compared native compiled-UNNEST REAL IN
against a physical scan whose IN had become a pushed equality domain. That is
exactly the already reproduced Trino 483 inconsistency, not a new value tolerance.

For this one positive `REAL x IN (0,1)` audit case, the accepted reference is
independent Trino equality membership. A plain OR of equalities is itself
normalized back into IN by Trino 483, so the reference uses separate equality
branches combined with UNION (and INTERSECT for the negative diagnostic), with
an actual plan assertion that no IN was reintroduced. This is a test reference,
not a production query rewrite or optimizer-disable switch. The actual production query remains IN;
the original input values and both zero signs are untouched. Its oracle SQL is
written alongside the plan and observations. Other floating predicates keep
their original runtime oracle.

The dedicated diagnostic runs both IN and NOT IN, comparing folded VALUES,
compiled UNNEST, independent equality and both physical catalogs. It requires
the documented native discrepancy to remain visible. The positive physical IN
domain must match equality/folded membership. The negative physical NOT IN has
a retained Trino filter and must match that compiled engine path. This does
**not** claim all Trino plans agree or that the upstream bug has been fixed.

No production REAL domain/comparison code changes are made. The release notes
and pushdown reference retain the 483 limitation in plain language. The specific
signed-zero counterexample is fixed in stock 484, verified with preserved input
bits and folded/compiled/projected IN/NOT IN. See the
[483/484 probe](REPORT-real-membership-483-484-2026-11-03.md).

## Verification and repository close-out

The initial full 484 run completed 973 cases with five failures and no errors/skips.
One stale DATE-unit expectation was corrected. Four exposed actual 484 changes:
LongTimestampWithTimeZone now checks packed bounds at construction, and TO_UNIXTIME
uses compensated/FMA rounding (different DOUBLE bits even at -1 microsecond).
The user approved ABI-specific local evaluation: WITH_TIMEZONE and TO_UNIXTIME
are local on 484; zoned DATE_TRUNC admits only milliseconds. Qualified 483 rules
remain. The first focused fallback verification passed 701/702 cases; its remaining
failure exposed the ordinary TIMESTAMP reader's missing infinity guard, not a
reason to weaken the endpoint/error cases.

The native JDBC probe `naive-timestamp-reader-probe.txt` shows the driver exposes
infinity as +/-Long.MAX_VALUE microsecond LocalDateTime sentinels. The standard
Trino mapping returned them as finite longs. The owned reader now rejects reserved
or out-of-range carriers while keeping actual TIMESTAMP(6), NULL handling and every
valid finite microsecond (including Long.MIN_VALUE). Conversion checks complete
LocalDateTime bounds before arithmetic; it never clamps or wraps invalid input.
Focused unit and native/direct-Quack engine read-back tests retain both endpoints.

Targeted UTC/LA checks, final clean full build, plugin assembly verification and
archive validation are pending. Original red full-run XML/results remain in
the earlier math/timestamp/WITH_TIMEZONE evidence directories; they are not
overwritten or retroactively called green.

The seven wider audit/helper files and their original report were pre-existing
owned work. They are reviewed and committed with current dispositions, retaining
original observations as history. The two tracked readiness/temporal documents
are reconciled rather than dropped. No broad unchecked preview item becomes a
new release gate, and unsafe type/carrier guards remain in force.

Current targets: Trino 483 and 484, DuckDB/native JDBC 2.0.0/2.0.0.0, Quack JDBC 0.12.0,
parity 0.5.1. The catalogs have 75 (483) / 74 (484) entries, both with ten extension aliases. No extension
source/metadata/binary or production transport default changes.

The user selected 0.3.0 and both ABI-labelled artifacts, and subsequently authorized
cleanup, commits and push. Each local gate selects `-PtrinoVersion=483|484` and its
matching `-Pversion=<ABI>-0.3.0`. Publication remains separate. Dependency management
comes from the actual matching trino-root POM; 484 uses the Airlift Guice runtime
instead of mixing it with 483's Google Guice classes. The strict one-Guice guard
is preserved with the correct artifact form per ABI.
The release workflow still requires its complete build/test gate and verified
plugin packaging; it now prepends the checked-in user-facing notes to generated
release notes. Both notes and the pushdown reference are included in the plugin
archive and required by the assembly guard.
