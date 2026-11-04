# Bounded Trino release close-out — 2026-11-03

This receipt records the qualified **Quack 0.12.1 / parity 0.5.1 preparation**.
The user later authorized publishing with refreshed dependencies; final adoption,
CI gates and release assets are recorded in the
[publication receipt](REPORT-release-publication-2026-11-04.md).

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
3. Initially leave the intermittent native JDBC attach issue alone. Later user
   direction authorizes its narrow reproduction/reporting, accepts the upstream
   race as a release limitation, and approves test-only predicate-fixture lifetime
   containment. No native fix, production workaround, retry or test exclusion is
   selected. Original red builds remain red.
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

### Quack JDBC 0.12.1 intake

The user subsequently selected released `dev.brikk.duckdb:quack-jdbc:0.12.1`
for both connectors. This replaces 0.12.0 for final artifact qualification; runs
already using 0.12.0 remain preliminary, not relabelled as testing the new driver.
The server, native DuckDB/JDBC, parity inputs and Doris source/native baseline
do not change.

Maven Central JAR SHA-256:
`fcf2a7a54ef302044569504216c546088b7ab3521f599911861359c044bf72c3`.
JAR/POM/source checksums match their published SHA-256 and SHA-1; all 107 production
classes remain Java-17 classfile 61. Tag `v0.12.1` resolves to
`cca8445a66a6491c444745bf3c10387cc19b9a94`. The GitHub-release JAR has different
ZIP timestamps (SHA-256 `3624aebeef597136f70a7aeb69a07835f7a511d6258188da3013638f32403991`),
but every uncompressed entry is byte-identical to Central. Central is the normal
resolved consumer input; no alternate repository or qualification override is used.

The change finishes healthy unfinished streams through terminal FETCH before
result close/supersession, preserving manual transactions. It can wait for all
remaining work/transfer; no aggregate close-drain deadline is promised. FETCH
failures propagate, without retry/replay or implicit CANCEL. Explicit cancellation
and bounded best-effort Connection.close remain distinct. Consumer regressions
cover early result close, another statement superseding it, commit with unread
rows and exact committed trigger/audit values; Java-17 Hikari reuse checks the
same manual-transaction preservation. The Java-17 Doris build passes all 92 tests,
lint and audit isolation; its package contains exactly the verified 0.12.1 JAR.
The unchanged pinned API-27/JNI-6 FE/ASAN-BE images pass the full live JDBC/Quack
smoke: metadata/security, exact Unicode/binary/UUID/temporal values, pushed plans,
20 sequential/eight concurrent scans, persisted catalog restart and plugin-scoped
failure/recovery. Both actual FE and BE driver SHA-256s match Central; Java 17 and
JNI6 JDBC READY/zero failed plugins are verified. The owned fresh compose project
`duckbridge-release-quack0121` was stopped without deleting volumes. Peer stacks,
older runtime images/volumes and the native source/build were not changed. These
are Linux-amd64 ASAN correctness observations, not production/performance claims.
All 23 Python checks pass. Final dual-ABI Trino results are recorded below.
Evidence is under `build/release-closeout/quack-0.12.1/`. This does not establish
a fix for orphan reaping, published-port HTTP, native carriers or JNI UTF-8.

### Temporal qualification

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

The subsequent 0.12.0 LA-JVM full run (`full-484-pre0121.log` and saved XML)
completed 946 cases with one test-class initialization failure: native JDBC's
`Unique file handle conflict ... database file ... is already attached`. The
row-set class therefore did not execute its test cases. This is retained as a
red preliminary run, not a qualified build. Its cause is not reassigned to Quack,
and the user-directed no-investigation/no-retry/no-exclusion disposition remains.
The independently required final builds use the newly selected 0.12.1 driver.

The pre-containment 483/0.12.1 clean LA-JVM build passes all 977 cases, with no failures,
errors or skips; assembly has 87 JARs. Its ZIP/tar.gz and checksum/resource
validation are retained. The corresponding pre-containment 484/0.12.1 run remains red: 947 cases with one
row-set-class initialization failure from the same native attach conflict. Its
89-JAR assembly and strict cancel/partition-cancel/disconnect tasks pass, but do
not qualify the failed build.

The user then authorized a narrow investigation, followed by reporting only:
**do not fix upstream JDBC**. A standalone Java reproducer confirms the conflict
on published native JDBC 2.0.0.0 and 2.0.0.1, on Java 17 and 25; one-thread
controls pass. An Opus-5.5-edited follow-up was posted to existing upstream
[#22277](https://github.com/duckdb/duckdb/issues/22277#issuecomment-6602525930),
not a duplicate issue. [Reproducer, evidence and scope](REPORT-native-jdbc-attach-conflict-2026-11-03.md).
No native pin, upstream source, test exclusion or connector workaround changed.
Dual-version release qualification remained blocked at that point.

### Accepted native race and predicate fixture scope

The user subsequently directed release preparation to continue with the native
JDBC race as an accepted limitation, awaiting a future upstream correction. The
user explicitly approved holding one native connection for the predicate-parity
fixture's lifetime. `closeAfterClass` owns it before the query runner is created,
so runner teardown precedes releasing that connection. The fixture still creates
and inserts its original data through the connector and runs every predicate,
plan, source-type and row-set assertion. There is no retry, exclusion, alternate
engine/format or production connection change.

This test-only containment separates semantic qualification from the already
reproduced native database-lifetime race. The standalone Java reproducer and all
red runs are retained; a passing contained suite is not a native-race fix or a
general concurrency guarantee. README and packaged release notes state the
remaining connection-availability limitation. New clean full builds, matching
archives and stock-engine packaged-plugin smokes pass for both ABIs below.

Earlier package-smoke probe assumptions are retained separately: Trino rewrote
a constant DATE predicate into a domain, and a zoned timestamp constant was not
an admitted remote comparison child. The corrected probes use dynamic DATE and
mapped-zoned-column comparisons already covered by the semantic suite. The
infinity reader's actual public page-source error is JDBC_ERROR wrapping its
NOT_SUPPORTED cause; the probe now checks that exact reader message/category.
These are probe corrections, not production gate widening or error relaxation.

### Final local release gates — passed

Both targets use JDK 25 with the JVM in America/Los_Angeles, matching selected
Trino dependencies, Quack JDBC 0.12.1 and the unchanged signed native inputs.
The targeted 484 predicate-parity class passes all 31 cases and lint before the
full builds; no predicate or assertion was removed.

| Gate | Trino 483 / 483-0.3.0 | Trino 484 / 484-0.3.0 |
|---|---|---|
| Clean module build and lint | Pass | Pass |
| JUnit cases | 977; zero failures/errors/skips | 977; zero failures/errors/skips |
| Plugin assembly | 87 JARs; Google Guice 7.0.0 classes | 89 JARs; Airlift Guice 10 main |
| ZIP and tar.gz, with SHA-256 sidecars | Verified | Verified |
| Actual packaged-plugin stock-engine smoke | 19 query/result/plan checks pass | 20 query/result/plan checks pass |

The final 484 invocation also passes strict healthy CANCEL/SQLState 57014,
partition cancellation/fail-closed budget and bounded DISCONNECT tasks. Those
results do not certify the separately documented published-port streaming route
or autonomous orphan reaping.

Each archive is checked against its assembled directory file-for-file, with the
matching Trino base-JDBC/toolkit, one correct Guice runtime, exactly one native
JDBC driver and one checksum-verified Central Quack 0.12.1 JAR. Provided SPI,
Slice/Jackson-annotation/OpenTelemetry API classes and test-only benchmarks are
not packaged. ServiceLoader metadata and the bundled human-facing notes are
verified. All four parity binary/`.info` pairs—eight resources—match the retained
signed-input manifest byte-for-byte. This is not a new native build or fresh
execution on all four platforms.

Stock-engine smokes load the ZIP-extracted plugin through real Trino discovery,
not programmatic `installPlugin`. They check exact values, preserved REAL zero
signs and engine-specific membership, signed parity pushdown, local DATE
truncation, complete finite TIMESTAMP endpoints, infinity refusal and per-ABI
attachment/epoch/truncation plans. The 484 constructor-bound failure is checked
as its actual GENERIC_INTERNAL_ERROR/`Millis overflow:` contract. Owned containers
are removed after each smoke; unrelated running services are untouched.

Official server images:

- 483: `trinodb/trino@sha256:db58cc93e593a2706553745f276bb119c9810e69918be56ecde088ba7ccb0534`
- 484: `trinodb/trino@sha256:2704eb53935af5e582d63d6033e9bf99d20c325e9b0e30b1016ef59a4773979f`

Prepared archives are in `build/release-closeout/artifacts/`:

| Archive | SHA-256 |
|---|---|
| `trino-duckbridge-v483-0.3.0.zip` | `03f0ab8573f3d897a36a46937b34b4ddc8cde9bb86a3c2f71becefbd09557e8e` |
| `trino-duckbridge-v483-0.3.0.tar.gz` | `1b124845ed092f38080d156ebe90ff91a8f342c028601cf2b1172cfbb3b5f8d7` |
| `trino-duckbridge-v484-0.3.0.zip` | `acb67d224a3336d88e3890ef5eeabab49a438e5d4146d7657b5d46b33b907521` |
| `trino-duckbridge-v484-0.3.0.tar.gz` | `052bdb059037bd5f7e85e3195cfffaff0d2e6aae1be7cbdc46222481beca46de` |

Detailed evidence: `full-<ABI>-final.log`, `full-<ABI>-final-results/`,
`full-<ABI>-final-summary.json`, `package-<ABI>-verified.log`, and
`smoke-<ABI>-qualified/` beneath `build/release-closeout/`. Original red full-run
XML/results and pre-containment summaries remain separately retained; they are not
overwritten or retroactively called green. The approved native race and other
unresolved producer limitations remain visible in the active backlog, README
and release notes. Completed release-closeout tasks are removed from active TODOs.

The seven wider audit/helper files and their original report were pre-existing
owned work. They are reviewed and committed with current dispositions, retaining
original observations as history. The two tracked readiness/temporal documents
are reconciled rather than dropped. No broad unchecked preview item becomes a
new release gate, and unsafe type/carrier guards remain in force.

Qualified preparation inputs: Trino 483 and 484, DuckDB/native JDBC 2.0.0/2.0.0.0, Quack JDBC 0.12.1,
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
