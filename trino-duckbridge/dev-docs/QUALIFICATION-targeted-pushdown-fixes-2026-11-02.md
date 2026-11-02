# Targeted floating and DATE text fixes — 2026-11-02

## Disposition and scope

After the narrow DATE write/unit fixes (`b8af0ad`), the user chose **targeted
semantic fixes first**, preserving common pushdown where possible and bringing
back any case requiring broad fallback. The user separately chose to **retain
the explicit decimal narrowing-overflow JDBC_ERROR limitation**, not withdraw
ordinary casts merely to preserve the numeric-range error category.

This change preserves ordinary floating range pushdown and explicit unbounded
DATE-to-VARCHAR pushdown. It does not add approximate math, change stored/read
types, adopt a native release, rebuild an extension or change Doris. The wider
audit remains in progress and red; this is not full-surface qualification.

## Implemented semantic repairs

### Floating domains: retain NaN exclusion

Trino 483 `SortedRangeSet.containsValue` explicitly excludes NaN from non-ALL
ranges, even those with an unbounded high endpoint. Standard base-jdbc's SQL
rendering loses that exclusion for a range like `x > 0`; DuckDB includes NaN.

The new floating domain controller keeps standard compaction/residual policy,
then intersects each non-ALL/non-NONE pushed range with `x <= +Infinity`. This
does not exclude positive infinity or any finite value, and its explicit native
upper bound excludes NaN. ALL/NOT NULL and NONE/NULL-only remain untouched;
standard read/write functions and SQL types remain unchanged. Compacted domains
retain the original residual as before. A unit test checks exact combined
pushed/residual membership, including compaction, NULL, NaN, infinities and both
zero signs for REAL and DOUBLE.

Production plans confirm ordinary REAL/DOUBLE positive ranges still remove the
Trino filter. This is not a finite-only data assumption or blanket fallback.

### Floating expressions: IEEE comparisons, SQL NULLs and SIGN

Native DuckDB comparisons use total NaN ordering/equality, unlike Trino's
primitive IEEE comparison operators. Expression emission now handles SQL NULL
first, then NaN (`<>` true, other primitive comparisons false), then the native
comparison. Explicit NULL handling matters: a simple `NOT isnan(x) AND ...`
would mishandle comparison of NULL and NaN under nesting/NOT.

Floating NULLIF uses the corrected equality, not DuckDB's NaN equality.
Floating SIGN returns its original argument for NaN and signed zero and otherwise
returns the correctly typed native sign. Integer/decimal SIGN behavior is not
broadened. Floating IS NOT DISTINCT FROM retains its distinct identity semantics;
it is not rewritten as primitive equality.

The native comparison unit matrix checks all six operators across 64 input
pairs, with NULL, NaN, both infinities and both zero signs. End-to-end comparisons,
arithmetic, negation, NULLIF, identity and nested SIGN probes retain exact values
and row sets. Finite ATAN/ATAN2/COSH differences are **not repaired** by these
changes; see the remaining-work section.

### DATE text: render the requested type conversion correctly

Explicit `CAST` / `TRY_CAST` of DATE to unbounded VARCHAR now renders Trino's
astronomical-year spelling via native date parts: optional minus sign, at least
four year digits, two-digit month/day and no `+` for expanded positive years.
SQL NULL remains NULL. No implicit source cast or stored-type stringification is
introduced; this only implements the conversion the user actually requested.

The formatter matches the actual Trino `DateTimeUtils.printDate` for eight
boundary dates and 1,024 deterministic samples across DuckDB's finite day range.
Independent Trino queries confirm BCE, year zero, year 10,000 and both endpoints.
The actual nested text predicate remains fully pushed and returns exact row IDs.
Bounded VARCHAR(n) casts are outside this formatter qualification; their existing
admission/length-error behavior was not changed or newly certified.

### Quack T2: render the infinity endpoint without losing its type

The full module run caught a real regression from adding an explicit +Infinity
domain endpoint: T2's parameter inliner previously refused infinity, breaking an
ordinary REAL range query. Its literal renderer now supports both infinities as
typed REAL/DOUBLE literals. NaN parameters still fail explicitly. Native literal
tests prove both actual SQL types and values; the unchanged ordinary REAL Quack
Arrow test passes again. A new live Quack Arrow test proves NaN exclusion, both
infinities, signed zeros, NULL and negated ranges. No route/server bypass or
source-column conversion is used.

## Verification and honest remaining failures

- Lint passes with no disabled rules.
- **676 scoped tests pass** in a Los Angeles JVM: 587 existing semantic fixtures,
  31 existing row-set checks, 52 existing translator tests, three new direct
  semantic tests and three targeted integration/diagnostic methods.
- The Quack inliner/live Arrow targeted run passes after the actual infinity
  regression was fixed, without changing the old expected row sets.
- Final UTC **full module run: 945 tests, 942 pass, 3 failures, 0 errors/skips**.
  All three failures are in the new, uncommitted audit class; no existing test
  fails. `:trino-duckbridge:assemble` succeeds (run with Gradle `--continue` to
  execute that independent task despite the honestly red audit task).
- Math: **7 production predicate differences in 209 probes**, all the known
  ATAN/ATAN2/COSH finite equalities. SIGN's 18 scalar differences are repaired.
  The original 212-probe matrix is preserved; three derived SIGN finite-equality
  probes are no longer generated because their source scalar difference is gone.
- Temporal: **9/120 zone-matrix differences**, down from 33 at the original audit
  and 17 after unit fixes. One additional wide-DATE truncation boundary/error
  mismatch remains. Direct DATE text and finite DATE write checks now match.
- Decimal: four documented overflow error-category differences remain in the
  raw 40-predicate data. The audit now tests only those exact approved failures
  as explicit limitations (expected numeric error/value/target and actual native
  conversion diagnostic), rejects every other mismatch, and still requires all
  successful value/type/NULL/TRY_CAST cases to match. It does **not** call those
  four outcomes exact parity or treat arbitrary matching errors as success.
- LIKE remains 0 differences in 50 predicates; no grammar was broadened.

Evidence is under `build/pushdown-edge-audits/`: `targeted-regressions-la/`,
`targeted-quack-infinity.log`, `full-targeted-final.log` and
`full-targeted-final/` (complete XML plus final observations). The first full
run's real Quack regression and preceding failures are retained, not overwritten
as successful evidence.

## Newly exposed native Trino REAL IN inconsistency

The expanded runtime comparison test remains red for REAL `x IN (0,1)` with
negative zero. This is not attributed to an environment or unexplained harness:

1. Native Trino VALUES constant folding includes negative zero as equal to zero.
2. Connector-free native UNNEST runtime excludes it for the same IN predicate.
3. Trino 483's inspected `InCodeGenerator.checkSwitchGenerationCase` selects
   DIRECT_SWITCH for these long-backed REAL constants; the generated switch
   distinguishes their raw bit patterns. The equality operator does not.
4. Physical IN domain pushdown agrees with folded/IEEE equality. Physical NOT IN,
   whose compiled filter remains in Trino, reproduces native runtime behavior.
   Our DATE/floating type readers and stored zero signs have not been changed.

`testRealSignedZeroInCompilationDiagnostic` records and proves the native
folded/runtime disagreement and the retained-filter outcome. The stricter general
runtime-equivalence test is **still red**, not skipped, assigned a tolerance or
changed to pass by choosing whichever oracle agrees. No connector workaround,
signed-zero normalization, fake type or upstream edit is applied. Exact native
sources and both input/result paths are saved in the evidence. Further disposition
of this pinned-engine inconsistency needs separate review.

## Remaining common-case decisions

- **ATAN, ATAN2 and COSH:** native finite results still differ in final bits.
  NaN-aware comparison emission cannot fix that. A real full-domain native fix
  requires reproducing Trino/JVM math, not a rounding tolerance or algebraic
  approximation. Local fallback for these three functions is the small policy
  option, but would affect ordinary finite arguments and is not applied here.
- **DATE truncation:** the text consumers are repaired, but native intermediate
  TIMESTAMP range and extreme truncation overflow remain. A CAST back alone is
  insufficient. Full DATE-preserving parity must also address result limits and
  exact local error delivery; otherwise DATE truncation fallback affects common
  month/day predicates.
- **Zoned truncation/WITH_TIMEZONE:** overlap selection and per-value-zone versus
  session-zone semantics remain divergent. Merely spelling the same zone or
  changing one DST example is not a full fix. Broader fallback or a separately
  qualified contextual design is still needed.
- **REAL IN compilation:** see the explicit native inconsistency above. No
  workaround or full semantic-equivalence claim is made.

All of these remain visible in reports/tests. The wider audit files are still
uncommitted. No new full-suite green claim or publication is made.
