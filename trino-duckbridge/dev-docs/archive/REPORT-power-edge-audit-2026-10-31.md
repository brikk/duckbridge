# Trino POWER edge-case audit — 2026-10-31

## Verdict and approved fix

The original `power/2` emission was an unconditional native DuckDB call. The audit
finds **8 scalar differences across 713 input pairs** and **8 differing row sets
across 14 predicate shapes**. The original production plans removed the Trino filter, and their
row IDs agreed with direct native DuckDB, not the independently evaluated Trino
baseline. This is an actual wrong-result connector surface, not a benchmark or
an unimplemented optional capability.

The initial audit correctly failed against that emission. After reviewing the
options, the user explicitly approved **keeping power local**. Bare `power/2`
is now removed; both `power` and its SQL alias `pow` evaluate in Trino. All 713
production scalar results and all 14 predicate row sets must match the native
Trino baseline exactly. No test is skipped, tolerance added, error converted to
NULL, SQL replayed or engine rebuilt. Only this optimization is withdrawn;
the host function, argument/result types and query semantics are preserved.

## Exact profile and method

- Trino **483**, JDK **25.0.3+9-LTS**, Linux AMD64.
- Native JDBC **2.0.0.0**, published JAR SHA-256
  `5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186`.
- DuckDB **2.0.0**, independently reproduced with stock CLI source `d54cbbe3df`.
  Default `ieee_floating_point_ops=true`; disabling the optimizer in a separate
  owned CLI diagnostic does not remove the selected divergences. No connector
  setting or acceptance route was changed.
- Doris, its in-progress revendor, Quack, dependency pins and parity binaries are
  untouched. No network server or shared service is needed for this audit.

`PowerEdgeCases` crosses 23 bases with 31 exponents. Inputs include SQL NULL,
signed zero, subnormals/minimum normal, values adjacent to one, positive/negative
finite values, maximum finite, infinities and NaN. Exponents include fractions,
negative values, underflow/overflow boundaries and large odd/even integers around
the DOUBLE exact-integer boundary.

The fixture uses a newly owned database file and typed JDBC DOUBLE binds. Both
raw JDBC and Trino read back the exact input bits before evaluation. Two catalogs
read that same file: the production connector and a test-only expression-rewriter
disabled baseline. Trino evaluates the latter's `power` function locally. The
initial native scalar oracle used the actual production translator's emitted
expression. After removal, raw native `power` is explicitly diagnostic SQL, not
a production emission or a claim of qualified parity.
Both engines return DOUBLE metadata; every scalar call must succeed. Arbitrary
or matching errors never count as scalar parity.

Finite results, infinities and signed zeros compare by raw bits. NaN payload/sign
is recorded but not treated as a distinct SQL value. The report does not claim
all finite DOUBLE pairs, other JDKs, platforms or engine settings are qualified.

## Concrete counterexamples

| Base | Exponent | Native Trino result | Native DuckDB result |
|---|---|---|---|
| `-1` | positive/negative infinity | NaN | `1` |
| `1` | positive/negative infinity or NaN | NaN | `1` |
| `0.9999999999999999` | `-1` | `1.0000000000000002` | `1` |
| `-0.9999999999999999` | `-1` | `-1.0000000000000002` | `-1` |
| `0.9999999999999999` | `0.5` | `0.9999999999999999` | `1` |

These finite differences affect predicates, not merely display formatting. On
the original fixture, `power(b,e) = 1` returned **94 Trino rows / 101 pushed rows**;
`power(b,e) > 1` under-returned one Trino row and added 83 others.

There is a second, independent problem even where scalar results agree:
negative-base fractional powers produce NaN in both engines, but their runtime
comparison semantics differ. `power(b,e) > 0` originally returned **347 Trino rows /
435 pushed rows**. A NOT predicate turned those 88 extra rows into missing rows.
NULL controls, `< 0`, `<= 0`, `= 0` and `<> 0` happen to match in this corpus;
that does not qualify unrestricted `power/2` emission.

## Actual code paths

- Published Trino `MathFunctions.power` calls Java `Math.pow`.
- Trino SPI `DoubleType.lessThanOperator` / `lessThanOrEqualOperator` use primitive
  Java `<` / `<=`; an ordered comparison involving NaN is false.
- DuckDB v2.0.0 `extension/core_functions/scalar/math/numeric.cpp` binds
  `IEEEPowOperator` when `ieee_floating_point_ops` is true, and that operator calls
  `std::pow`. Its special-value and observed rounding contract is not identical
  to the Java implementation in this profile. This audit does not identify a
  deeper platform-specific native math-library implementation defect.
- DuckDB `src/common/vector_operations/comparison_operators.cpp` deliberately
  orders NaN above non-NaN values in `GreaterThanFloat` / `GreaterThanEqualsFloat`.
- DuckBridge `DuckBridgeEmissionCatalog` previously emitted `power` as Bare,
  with no guard or comparison-aware handling for either difference. The approved
  fix removes that entry rather than pretending either difference is repaired.

Maven source JARs were verified against published checksums. Stock CLI controls
repeat selected scalar and WHERE cases without JDBC, the connector or the parity
extension; these are not decode errors or a claim that engine behavior is our
fault. Our bug is advertising this native call as equivalent pushdown.

## Disposition and alternatives

1. **User-approved correctness fix:** stop emitting bare `power/2` and retain its
   evaluation in Trino, with exact local-plan/value/row-set regressions. This
   preserves query semantics and only gives up that pushdown optimization. It is
   not a test skip, type degradation or approximate arithmetic workaround.
2. A full parity design is larger work: exact supported numeric behavior **and**
   correct surrounding comparisons are needed. Returning the same NaN from a
   native function alone does not make DuckDB's WHERE semantics match Trino.
3. Do not add a handful of CASE branches or relax numeric tolerances. The finite
   rounding counterexamples and comparison-context differences remain after
   special-value patches. A newly narrowed pushdown subset needs its own proof.

The regression requires absence from the pushable catalog and no SQL emission
under either parity/string-comparison trust axis. The original ordinary scalar
fixtures now require explicit non-push, and a finite rounding case is added.
Production must retain the power filter and return the exact baseline rows,
including a separate alias check and partial-AND/whole-OR cases. Existing safe
emissions are still exercised by their ordinary positive pushdown tests.

Raw native counterexamples remain tested independently of the fixed connector.
Their platform-specific finite rounding differences are recorded, not imposed as
universal native results. Stable special-value/NaN counterexamples must remain
visible on the pinned engine, while **every** production scalar value and row set
must agree with Trino. This is fallback verification, not weakening a still-pushed
function's parity requirement.

## Reproduce and evidence

```bash
./gradlew :trino-duckbridge:test --tests '*TestPowerEdgeAudit' \
  :trino-duckbridge:detekt
```

The targeted fallback/audit, translator, semantic-fixture, row-set and alias
coverage checks pass; lint passes. Full Trino `check` passes **926 tests**, zero
failures/errors/skips; `verifyPluginAssembly` passes with 87 JARs and the existing
SPI isolation checks. The final scalar matrix has **zero production differences**
across 713 pairs; all 14 predicate row sets match, while the eight native scalar
and eight native predicate differences remain visible. Doris was neither changed
nor run for this task. The original red audit is retained, not relabeled passing.
Evidence in `build/power-edge-audit/` includes the original failing JUnit XML/log,
`initial-summary.json`, `predicate-summary.json`, `native-cli-controls.json`,
verified source artifacts and `initial-divergences/` (all raw scalar bits, complete
row ID sets, distributed plans and identities). The live test output is under
`trino-duckbridge/build/power-edge-audit/`.
Final evidence: `fallback-targeted.log`, `full-trino-check.log`,
`final-summary.json`, saved `final-test-results/` and `final-fallback/`.

The first attempted identity assertion encountered JDBC's generic metadata
`driverVersion=1.0`; it was corrected to check the actual resolved JAR and exact
published SHA-256 plus real engine version, not to assume metadata equals the
artifact version. That initial failure is retained separately as
`initial-metadata-identity.log` and is not counted as a semantic observation.
