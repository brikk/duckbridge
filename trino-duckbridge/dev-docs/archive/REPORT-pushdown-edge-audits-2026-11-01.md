# Pushdown correctness audits — 2026-11-01

## Current disposition — release close-out 2026-11-03

This report preserves the **original audit** and its exact red observations.
Subsequent selected work repaired floating domain/IEEE/SIGN handling, DATE writes,
unit validation and unbounded DATE text; ATAN/ATAN2/COSH now stay local. Qualified
sub-day truncation/astronomical YEAR and fixed-offset WITH_TIMEZONE consumers are
implemented, with named-zone attachment local. The user has now approved local
DATE-input truncation for all units, accepted the native Trino REAL membership /
signed-zero release limitation, and requested no attach-conflict investigation.

The retained audit tests use precise value/type/error/plan checks. The REAL
membership diagnostic explicitly preserves folded-versus-compiled IN/NOT IN
differences and checks the actual physical path against independent equality
sets; no source sign is normalized and no membership test is skipped. The
specific four decimal narrowing-error limitations remain the only accepted
decimal error-category differences. Owned audit files/trackers are included in
the close-out, not abandoned to make CI green. Current verification is in the
[release preparation receipt](../REPORT-release-closeout-2026-11-03.md).

## Original audit outcome and subsequent narrow fixes

**Update after user disposition:** uncommon-case restrictions and DATE write fixes
were approved; common-case restrictions must be raised separately. The
[narrow DATE/unit fixes](QUALIFICATION-narrow-date-unit-fixes-2026-11-01.md) are now
implemented and pass targeted regressions. The original audit findings/counts
below are preserved. After those fixes the temporal matrix has 17/120 differences
(previously 33), DATE writes pass, and math/decimal/LIKE counts are unchanged.
Broader pushdown policy is unchanged pending the common-case decision.

**November-2 follow-up:** the user chose targeted semantic fixes first and accepted
only the explicit decimal-overflow JDBC_ERROR limitation. The
[targeted qualification](QUALIFICATION-targeted-pushdown-fixes-2026-11-02.md)
records preserved common floating ranges, IEEE expression/SIGN repairs and
unbounded DATE text formatting. Math now has 7/209 predicate differences, temporal
9/120 plus a wide-DATE boundary failure; LIKE is unchanged. The full module runs
945 tests with three still-red new audits and no skips; assembly succeeds. A
new native Trino REAL IN compiler inconsistency is independently reproduced and
left red, not hidden. The original evidence/counts below remain historical.

All four requested audit areas have been exercised. The audit finds wrong-result
floating-point and temporal pushdowns, a DATE write-format failure, and a separate
decimal overflow error-contract difference. The sampled LIKE cases match.

**At the original audit boundary no production source, driver, extension, Doris
baseline, pushdown policy or type mapping was changed.** The new tests deliberately remain red on the actual
divergences. No test was skipped, tolerance added, error changed to NULL or query
replayed. Fix disposition is separate from this audit authorization.

| Area | Bounded observations | Findings |
|---|---|---|
| Math / floating point | 16 function entries x 94 rows = 1,504 scalar observations; 212 predicates including DOUBLE/REAL column/operator controls | 40 native scalar differences; 104 production predicate differences |
| Decimal / casts | 22 DECIMAL(38,18) values; 8 CAST/TRY_CAST target shapes; 40 predicates and prepared/connector CTAS round trips | Values/NULLs and binding match in this corpus; 4 overflow predicates change the Trino error category |
| LIKE | 32 source strings; 50 predicates | 0 production or baseline differences; 8 plans remove the filter, 42 retain it |
| Temporal | 14 microsecond rows; 30 predicates x 4 session zones = 120 cases, plus exact scalar probes | 33 production predicate differences; same findings in UTC and Los Angeles JVM profiles |
| Finite DATE boundaries | 8 rows including NULL, BCE/year zero, year 10,000 and near the finite 32-bit day endpoints; 3 predicates | Production reads and actual stored day counts match; DATE write fails; 2 boundary predicates differ |

Counts are observations, not distinct root causes. Repeating a predicate in four
zones is not four independent implementation defects. This does not qualify every
DOUBLE pair, date/precision/unit/zone, LIKE pattern, driver path or platform.

## Method and trustworthy oracles

- Exact Trino **483**, DuckDB/native JDBC **2.0.0/2.0.0.0**, JDK **25.0.3+9-LTS**,
  Linux AMD64. The actual loaded Trino/JDBC JAR names and native JAR SHA-256 are
  checked; native source `d54cbbe3df` and `ieee_floating_point_ops=true` are checked
  with healthy control queries before and after each audit. Native JAR SHA-256:
  `5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186`.
- Newly owned database, typed JDBC source binds and exact input read-back. The
  production and expression-disabled catalogs read the **same** file. Independent
  native-Trino VALUES/date-add inputs are also evaluated without a connector scan.
- **Expression-disabled does not mean all pushdown is disabled:** base-jdbc can
  still push TupleDomains. The independent VALUES oracle is essential: it exposed
  plain floating ranges that were wrong in both physical catalogs.
- Plans, types, exact values, complete row-ID sets and real query error codes,
  failure types and messages are retained. QueryManager supplies the actual Trino
  error category. Both engines throwing arbitrary exceptions is not parity.
- Finite floating bits and signed zero compare exactly. NaN payload/sign is not
  treated as a distinct SQL value. Math's declared DOUBLE outputs are read as
  DOUBLE values, with actual JDBC metadata recorded separately (notably SIGN's
  integral native result). No claim of full native return-type equivalence follows.
- Temporal source TIMESTAMPTZ values in the independent Trino oracle explicitly
  carry the query session zone, matching the connector's documented mapping of
  DuckDB instants. We do not mistake a UTC-literal zone key for that contract.
- Normal/UTC and `America/Los_Angeles` JVM runs are separate evidence. Session
  zones are UTC, New York, Kathmandu and Lord Howe. No global JVM timezone is
  mutated inside the test process.

## 1. Floating-point wrong rows extend beyond POWER

DuckDB orders NaN above ordinary values; Trino 483's primitive ordered operators
return false for comparisons involving NaN. SQL NULL remains distinct.

Consequently a NaN source row can be incorrectly included by `x > 0`, or excluded
by `NOT (x > 0)`. The audit reproduces this in:

- Standard DOUBLE/REAL column ranges, even with our function rewriter disabled.
- DOUBLE arithmetic such as `x+x > 0` and `x*x >= 0`.
- Pushed math calls and nested expressions. Merely withdrawing POWER does not
  repair these other paths.

The current `DuckBridgeClient` uses standard real/double column mappings with
full domain pushdown. `DuckBridgeExpressionTranslator` also emits unguarded
floating comparisons/operators. Plans prove the relevant filters disappear.

The 16 function entries examined are ABS, CEIL, FLOOR, TRUNCATE, EXP, SIN, COS,
TAN, ATAN, ATAN2, SINH, COSH, TANH, DEGREES, RADIANS and SIGN. In this profile
CEIL's actual planner shape remains local; this is recorded, not called an active
wrong-result CEIL pushdown or silently counted as remote proof.

Forty raw scalar differences occur in ATAN, ATAN2, COSH and SIGN. Examples:

- `atan(pi())`: Trino `1.2626272556789115`, DuckDB `1.2626272556789118`.
- `cosh(1)`: Trino `1.543080634815244`, DuckDB `1.5430806348152437`.
- SIGN loses negative zero and returns zero for NaN where Trino's DOUBLE result
  preserves negative zero / NaN. Nesting SIGN inside ATAN2 makes the zero sign
  affect actual row selection, not just display.

Finite-value equality predicates derived from actual Trino results demonstrate
under-return; this is not an epsilon-only disagreement dismissed as harmless.
Maven Trino sources and the exact DuckDB source show Java Math/StrictMath versus
native math and different NaN comparators. Stock CLI controls independently
reproduce selected results without JDBC or the connector.

## 2. Decimal values match; overflow codes do not

The sampled exact DECIMAL(38,18) values include fractional ties, tiny values,
signed integer boundaries and positive/negative maximum magnitudes. Typed JDBC
source binds and connector CTAS/read preserve values, SQL NULL and the declared
decimal type. The sampled TRY_CAST predicates match, including overflow-to-NULL.

For the selected overflowing plain casts to TINYINT, SMALLINT, INTEGER and
BIGINT, **both executions fail**; no silent successful or NULL result is claimed.
But native Trino reports `NUMERIC_VALUE_OUT_OF_RANGE`, while the actual pushed
query reports `JDBC_ERROR` with DuckDB's conversion diagnostic. The exact bad row
is isolated by ID so this is not merely an arbitrary first-error ordering change.

This is a policy decision about preserving the Trino error contract, distinct
from the silent wrong-row defects. A fix can leave overflow-capable plain casts
local while retaining separately qualified TRY_CAST behavior. Do not wrap remote
failures as NULL or claim every matching error is equivalent.

## 3. LIKE matches the sampled surface

The corpus covers empty/NULL source, empty pattern, `%`/`_`, literal quotes and
backslashes, explicit escapes, embedded NUL data/patterns, combining characters,
supplementary Unicode, noncharacters and the maximum Unicode scalar prefix.
LIKE, NOT LIKE, nested OR and prefix/domain paths are compared with native Trino.

All 50 production row sets match. Plans distinguish the eight fully pushed cases
from the 42 retained-filter cases. This does not authorize a wider grammar or
claim the retained cases were evaluated remotely. No LIKE policy change is
recommended from this bounded audit.

## 4. Temporal type, unit and zone contracts are not generally equivalent

### DATE truncation returns the wrong native type

Trino `date_trunc(..., DATE)` returns DATE. DuckDB's native overload returns
TIMESTAMP. Our Bare emission ignores the declared host result type. Therefore
`length(CAST(date_trunc('month',d) AS VARCHAR)) = 10` and exact DATE-text equality
under-return, although a simple direct date comparison can happen to match.

A CAST back to DATE addresses the ordinary return-type mismatch, but is **not a
complete full-DATE-range fix**: native truncation's intermediate TIMESTAMP cannot
represent the very wide finite DATE values exercised here. The CLI independently
raises `Date and time not in timestamp range` for such an input. Keep this shape
local unless a DATE-preserving implementation and its complete range are proved.

### Unit validation depends on the argument type

Trino DATE fields are day/week/month/quarter/year; timestamp fields additionally
include millisecond/second/minute/hour. The inspected Trino source enforces this.
DuckDB accepts additional combinations. Actual counterexamples include:

- `date_trunc('hour',d) IS NOT NULL`.
- `date_trunc('century',ts) IS NOT NULL`.
- `date_diff('hour',d,DATE '1970-01-01') >= 0` and the millisecond variant.

Trino rejects them with `INVALID_FUNCTION_ARGUMENT`; current pushdown returns
rows. DATE_DIFF's existing constant-unit whitelist is not sufficient because it
does not narrow units for DATE arguments. Invalid/dynamic units should stay local
under argument-type-aware admission, preserving strict NULL behavior as well.

### Zoned truncation and attaching a zone are context-sensitive

The NY overlap exposes `date_trunc('hour',z) <= z` dropping a row. For input
`2024-11-03 05:30 UTC`, the native CLI truncates to **06:00 UTC**, after its input.
The Trino oracle truncates with its own zone/overlap semantics. Lord Howe probes
also expose differing nested minute extraction. Raw scalar differences are kept
separate from actual predicate differences.

`with_timezone(ts,'America/New_York') = z` selects the wrong overlap instant:
Trino chooses the earlier offset, while native `timezone` chooses the later one.
For `2024-11-03 01:30`, the native CLI produces 06:30 UTC instead of Trino's
05:30 UTC. In addition, `hour(with_timezone(ts,'UTC')) = hour(ts)` differs in the
three non-UTC sessions: Trino's computed value carries its own zone key, whereas
DuckDB stores an instant interpreted through the session zone.

Changing a zone label or inserting a CASE for one date does not fix these type
and calendar contracts. Contextual native equivalents would require a separately
qualified design; otherwise keep the affected calls local.

### Finite DATE writes and text casts need their own treatment

All eight boundary source values are genuinely stored correctly (independent
integer day-count proof), and **our existing production DATE reader reads them
correctly**, including BCE and large years. Preserve that reader.

The current DATE writer formats year 10,000 as `+10000-01-01`, then binds it
through `CAST(? AS DATE)`. DuckDB rejects that spelling, so a valid DATE CTAS
fails. Repair the actual native literal/binding contract with range/sentinel
checks; do not weaken the test or stringify the final stored type.

Separately, native DATE-to-VARCHAR uses a BC display spelling (`0001-01-01 (BC)`)
where Trino renders year zero differently. The currently allowed DATE string cast
therefore drops a legitimate row. Keep unqualified DATE text casts local.

**Raw-driver diagnostic, not a production reader defect:** native JDBC's typed
LocalDate getter delegates to `getDate().toLocalDate()` and loses BCE/extreme
dates in these probes. Verified published driver source confirms that path. The
original audit oracle was corrected to validate actual stored day counts rather
than trust this getter; all typed-getter observations remain visible. Do not
replace our correct string-based reader with that lossy typed getter.

## Proposed next work (not implemented)

1. Fail closed for unqualified floating value predicates in both domain and
   expression paths, keeping their evaluation in Trino. Native NaN-aware wrappers
   alone would not repair finite math rounding and SIGN's value differences.
2. Tighten temporal admission by type/unit and leave affected DATE/zoned
   truncation and WITH_TIMEZONE calls local pending full parity. Repair valid
   finite DATE write formatting and keep DATE-to-VARCHAR local where unproved.
3. Decide whether narrowing decimal CAST overflow must preserve Trino's exact
   error code. If yes, retain those plain casts locally; do not confuse that
   decision with the wrong-row fixes. Keep proven behavior/NULLs unchanged.

Local fallback preserves query types and semantics but may transfer more rows.
Exact native math/date/zone parity is larger work. No approximate arithmetic,
format/engine masquerade, global legacy setting or error-to-NULL adapter is proposed.

## Verification, state and evidence

The four-area targeted run is intentionally red on math, decimal error codes and
temporal findings; LIKE passes. Final temporal probes in UTC and LA JVMs reproduce
the same 33/120 zone-matrix differences plus the DATE boundary findings. Lint
passes without suppressing or skipping rules. A first temporal attempt stopped
on an unsupported test-carrier representation (ZonedDateTime); that helper issue
was fixed and is not counted as an engine mismatch. A stale snapshot copied after
a lint-only failure is explicitly isolated as `stale-copy-before-lint-fix`, not
used as fresh execution evidence.

Raw data, errors, plans and identities are under
`build/pushdown-edge-audits/`: `complete-matrix/` holds the math/decimal/LIKE run;
`temporal-verified-utc/` and `temporal-verified-la-final/` hold the final temporal
runs. Earlier failed attempts are retained. `source-review/` contains verified
published/native implementation sources; CLI reproductions are separate JSONs.
Live test outputs are under `trino-duckbridge/build/pushdown-edge-audits/`.

```bash
mkdir -p build/pushdown-edge-audits/tmp
TMPDIR="$PWD/build/pushdown-edge-audits/tmp" \
JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/build/pushdown-edge-audits/tmp" \
JAVA_HOME=/path/to/jdk25 ./gradlew --no-daemon --max-workers=1 \
  :trino-duckbridge:test --tests '*TestPushdownEdgeAudits'
```

At the original audit boundary no full-suite green result was claimed and these
files remained uncommitted pending the common-case disposition. They are now
included in release close-out with that disposition and fresh verification
recorded separately above. Doris and its qualified API-26 assets were untouched;
the original audit baseline was `e870920`.
