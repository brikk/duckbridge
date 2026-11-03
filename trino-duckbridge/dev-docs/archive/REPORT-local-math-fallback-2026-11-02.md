# ATAN / ATAN2 / COSH local fallback — 2026-11-02

## Approved outcome

The user explicitly chose to keep all three functions in Trino rather than
implement native math parity. Their `(name, arity)` entries are removed from
the production emission catalog. Ordinary and nested calls remain local,
independent of parity availability or string comparison mode. Values/types,
NaN, signed zero and SQL NULL are not approximated, cast away or normalized.

No date/time behavior, native extension source, dependency pin or Doris code
changes. The temporal extension work was only sized in the
[separate assessment](RESEARCH-temporal-parity-scope-2026-11-02.md); it has not
been selected for implementation.

The catalog now has **76 entries**: 38 BARE, nine RENAME, three OPERATOR,
16 INLINE/CONTEXTUAL and ten extension ALIAS entries. This does not change the
ten-function native extension contract or require a new native binary.

## Focused verification

`TestPowerEdgeAudit` now also checks the three withdrawn functions:

- No emission for direct or nested calls under either extension availability
  or string-comparison trust setting.
- **713 typed input rows per function**, including NULL, NaN, infinities, signed
  zeros, subnormals, ordinary inputs and finite extremes. Independent Trino
  VALUES inputs provide exact expected values, not a merely expression-disabled
  physical catalog. All **2,139 scalar observations** preserve actual DOUBLE
  types and exact finite/zero bits; only NaN payload/sign is ignored.
- **12 predicate row sets** include ordinary and negated ordered comparisons,
  equality to zero and the finite rounding smoking guns. Production plans retain
  the Trino filter; row IDs match independent native-Trino VALUES evaluation.
- Raw native outcomes remain in TSV diagnostics, including **127 differences**
  on this pinned Linux run (31 ATAN, 34 ATAN2, 62 COSH). These are not called
  production parity and no platform-specific rounding is prescribed as a fix.

The four original coinciding semantic fixtures are retained as explicit
NotPushed fixtures rather than deleted. The existing broader uncommitted math
audit is updated to distinguish the three approved native diagnostics from exact
production behavior. Its **209 predicate probes** now match both production and
expression-disabled catalogs against independent Trino, with explicit fallback
plan checks and exact production scalar-contract checks.

Targeted module run: **592 tests pass**, no failures/errors/skips; lint passes.
Evidence: `build/math-local-2026-11-02/targeted-final.log` and
`targeted-evidence/`. Wider temporal/Trino-IN audits remain separate; no full
green result is inferred from this targeted run.

## Wider verification and independently recorded startup failure

The full workspace invocation (`:trino-duckbridge:test :trino-duckbridge:assemble`
with `--continue`) reports **916 test cases: 913 pass, three failures, no errors
or skips**. Assembly succeeds. The three failures are:

1. Existing temporal audit counterexamples, unchanged by this task.
2. Existing Trino REAL IN signed-zero runtime/constant-folding inconsistency.
3. `TestPushdownRowSetParity` fails during its first INSERT in setup, before
   running that class's ordinary test cases. Native JDBC startup raises:

   ```text
   Resource In Use Error: Unique file handle conflict: Cannot attach
   "duckbridge-13162556355720043700" - the database file
   ".../build/math-local-2026-11-02/tmp/duckbridge-13162556355720043700.db"
   is already attached by database "duckbridge-13162556355720043700"
   ```

The stack reaches `DuckDBNative.duckdb_jdbc_startup` from the JDBC page sink's
connection creation. The underlying lifecycle/root cause is **not assigned**;
this is not dismissed as harness/environment variance or declared unrelated
merely to obtain a green bar. An unchanged focused rerun of that class passes
**31 tests**, but does not erase the full-run failure or establish a correction.
It is recorded as `B-DB-ATTACH` in the categorized backlog, not automatically
expanded into a lifecycle implementation task. No retries, connection changes,
test skips, fixture replacements or timeout changes are applied.

The withdrawn-math tests and updated math audit pass in the full invocation.
Full-run XML is preserved before the focused rerun. Evidence:
`build/math-local-2026-11-02/full.log`, `full-summary.json`, `full-evidence/`
and `rowset-startup-check.log`. Do not combine separate runs arithmetically to
claim a complete green suite. The known temporal/IN tests and all wider audit
helpers remain uncommitted work in progress; they are not disabled by this fix.

```bash
TMPDIR="$PWD/build/math-local-2026-11-02/tmp" \
JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/build/math-local-2026-11-02/tmp -Duser.timezone=UTC" \
JAVA_HOME=/path/to/jdk25 ./gradlew --no-daemon --max-workers=1 \
  :trino-duckbridge:detekt :trino-duckbridge:test \
  --tests '*TestPowerEdgeAudit' --tests '*TestPushdownSemanticFixtures'
```

The self-contained regression uses committed PowerEdgeCases and does not depend
on the still-uncommitted wider audit helpers. The targeted workspace run also
selected `TestPushdownEdgeAudits.testRemainingMathEdges` to verify that existing
audit; its helper files remain work in progress rather than dependencies of the
committed regression. No test is disabled, error changed to NULL or unsafe source
conversion introduced.
