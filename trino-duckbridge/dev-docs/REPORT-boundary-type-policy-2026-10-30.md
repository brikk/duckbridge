# Round 2: boundary-type policy investigation — 2026-10-30

## Status: user-authorized temporary guard, full support deferred

Round 1 is committed/pushed at `e5075bd` (897 Trino + 88 Doris tests passed).
The user authorized a temporary guard and requested revisiting the underlying
type/getter problem **after** the current storage/write-safety and benchmark
rounds. Shared project TODO **5bdd9ba4-d371-48a9-952a-504d45590c73** records that
order and the removal/qualification criteria. No driver dependency change,
automatic SQL CAST, peer patch or native rebuild is applied.

Trino rejects VARIANT, positional TUPLE and untyped SQLNULL (including their
list/array shapes) before an unsafe getter can run. Unqualified composite
auto-/forced-VARCHAR conversion is also refused: an Arrow list/struct is not a
VARCHAR vector, and an unverified native display getter is not typed support.
Already-qualified scalar arrays remain typed even with generic text fallback
enabled. Existing temporal-carrier guards remain unchanged.

Doris retains its documented JSON/STRUCT/MAP/ENUM text mappings and supported
arrays, while existing VARIANT/TUPLE refusals now name the temporary guard,
explicit source CAST option and revisit TODO. Its untyped-NULL refusal remains.
No new semantic/type degradation is silently introduced.

The raw reproducer has **not** been skipped: native observations run in child
JVMs using the exact resolved test classpath. The known TUPLE-list case must
terminate with its SIGSEGV/string-cast signature; controls must exit normally.
This is negative-control evidence of an outstanding driver bug, not successful
value mapping or a claim the crash is fixed. A changed driver outcome forces
reassessment of the temporary guard instead of silently retaining an obsolete
workaround. Crash dumps are contained outside the Gradle/JUnit worker.

## Isolated native getter crash

Pinned `org.duckdb:duckdb_jdbc:2.0.0.0`, JDK 25.0.3, Linux AMD64:

```java
statement.execute("CREATE TABLE boundary_raw AS SELECT [row(1,'x'),NULL] v");
ResultSet rows = statement.executeQuery("SELECT v FROM boundary_raw");
rows.next();
rows.getObject(1); // returns the positional list representation
rows.getString(1); // SIGSEGV inside native vector string casting
```

The native frame is `VectorCastHelpers::StringCast<int,...>` beneath
`DuckDBNative.duckdb_jdbc_cast_result_to_strings`. A fresh child JVM per expression
isolates this to the list containing TUPLE values; scalar TUPLE and the selected
NULL/JSON/STRUCT/MAP/list controls do not crash. The crash is not an error that can
be safely caught in the connector JVM.

The stock DuckDB 2.0 CLI successfully executes
`SELECT CAST([row(1,'x'),NULL] AS VARCHAR)` and returns `[(1, x), NULL]`.
This distinguishes the failing JDBC getter path from the explicit SQL cast.
Native VARIANT's generic getter also returns `{a=1}` where explicit SQL VARCHAR
casting returns `{'a': 1}`; generic getter display text is not automatically an
engine textual-value contract.

Evidence: `build/round2-type-policy/initial-getter-crash.log`,
`native-getter-isolation.json`, per-case crash/log files and
`native-cli-tuple-list-cast.json`. The original failed Gradle probe is retained.

## Public issue search

Searches of open/closed issues and PRs in `duckdb/duckdb-java` and `duckdb/duckdb`
did **not find an exact report** of this list-of-TUPLE JDBC getter crash.

- [duckdb-java PR #728](https://github.com/duckdb/duckdb-java/pull/728), merged
  July 1, 2026, temporarily excludes TUPLE from `test_all_types`. Its patch only
  changes that test exclusion; there is no description or getter-crash fix.
  This establishes known incomplete tuple coverage, not that our crash is fixed
  or reported by that PR.
- [DuckDB PR #26944](https://github.com/duckdb/duckdb/pull/26944) addresses fixed
  ARRAY children inside TUPLE row-layout materialization (GROUP BY/DISTINCT/etc.).
  It is related type territory but a different demonstrated code path; it was
  closed without merging, and is not claimed to fix this crash.

Search responses and query URLs are saved as `github-issue-search*.json` under
the review directory. No new public issue/comment has been posted.

## Host policy verification

`TestBoundaryTypePolicy` exercises native JDBC, Quack JDBC, native Arrow and
Quack Arrow. Generic text fallback cannot bypass the temporary guards. Explicit
source views that CAST to VARCHAR carry the chosen DuckDB text result on all
four paths, including NULL and the formerly crashing tuple-list expression.
That is an intentionally different, visible result type—not lossless typed
TUPLE/VARIANT support and not a promise that DuckDB display text is JSON.

The existing JSON text opt-in preserves duplicate keys, a number wider than
IEEE double's exact integer range, JSON `null` versus SQL NULL, and keeps its
comparison predicate local. A 3,000-row qualified integer-array case remains
`array(integer)` with NULL elements on every path, despite text fallback being
enabled. Doris unit checks pin unchanged documented text mappings and actionable
VARIANT/TUPLE/list rejection.

```bash
./gradlew :trino-duckbridge:test --tests '*TestBoundaryCarrierContracts' \
  --tests '*TestBoundaryTypePolicy' \
  :doris-duckbridge:test --tests '*TestDuckDbToDorisTypeMapper' \
  :trino-duckbridge:detekt :doris-duckbridge:detekt
```

Targeted checks and lint pass (`build/round2-type-policy/guard-targeted.log`).
Child-process evidence is retained under
`trino-duckbridge/build/boundary-type-probes/`; original crash/search evidence
remains under `build/round2-type-policy/`. Full module checks, lint and package
verification pass: **903 Trino + 89 Doris tests**, zero failures/errors/skips
(`full-checks.log`, `test-summary.json`, saved `test-results/`). The isolated
native crash is a negative control, not counted as qualified type support.
The revisit TODO stays open; no upstream issue is automatically filed.
