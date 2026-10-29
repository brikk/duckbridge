# Doris OVERLAY qualification — 2026-10-29

## Contract and decision

**Qualification is blocked; the candidate renderer is test-only and cannot ship
in the connector.** Production QueryBuilder continues declining all OVERLAY calls.
The full native matrix found that a supplementary-Unicode literal in `query_sql`
is corrupted between BE and Java. Minimal reproduction independent of OVERLAY:
`SELECT id FROM duckbridge_test.sales.overlay_inputs WHERE source='a😀bc'`.
EXPLAIN contains the correct SQL; execution fails with a corrupted, unterminated
literal. The same UTF-8 query works through direct Quack JDBC tests. Native
`Jni::Util::convert_to_java_map` calls `String<Local>::new_string`, which passes
ordinary UTF-8 to `JNIEnv::NewStringUTF` (modified UTF-8). The proper fix requires
patching/rebuilding the native BE boundary, not simply rebuilding unchanged code.
No native patch or bypass has been applied. At the user's request this is being
reported upstream instead of rebuilding our BE; the
[Slack report, standalone reproduction and proposed patch](upstream-reports/2026-10-29-jni-utf8/README.md)
are prepared separately. The patch proposal is not part of the required runtime.
The failing matrix is retained in `build/overlay-native-qualification.log`.

The test-only candidate has a **source-aware, bounded predicate rendering**, not a generic
OVERLAY alias or a computed-projection pushdown. The library generator remains
audit-only and unchanged. `LENGTH → STRLEN` remains unchanged.

Permitted calls have exactly four arguments and a Doris STRING/VARCHAR return:

- Source and replacement are direct columns whose actual DuckDB type is VARCHAR,
  or STRING/VARCHAR literals (including typed SQL NULL).
- Position and length are integral literals within signed INT32. Position is
  positive, length is nonnegative, and `position - 1 + length <= INT32_MAX`.
- Unknown/non-VARCHAR storage types, CHAR, JSON/VARIANT/nested text, nested calls,
  dynamic bounds, negative/zero positions, negative lengths, NULL bounds,
  fractional/coerced numeric literals, overflow and NUL-containing literals stay
  local. A rejected conjunct still prevents remote LIMIT, including inside OR/NOT.

The rendering preserves two differences from a direct argument swap:

```sql
CASE WHEN source IS NULL OR replacement IS NULL THEN NULL
     WHEN length(source) < position THEN source
     ELSE overlay(source PLACING replacement FROM position FOR length)
END
```

This is an explicit translation of the source function's NULL and boundary
semantics, not a filter on input rows. In particular, **no length guard discards
short strings**. Empty strings and positions beyond the end remain unchanged;
NULL replacement remains NULL even on those branches. All admitted bounds are
constants; no narrowing casts or integer-overflow behavior are reproduced.

## Native implementation review

Exact source: Doris `082736138b7f0e767f966cbb1434bc9ba85f29ec`, API 24 / JNI 6.
`FunctionOverlay` in `be/src/exprs/function/function_string_replace.h` takes
`(source, INT position, INT length, replacement)`. Both its ASCII and UTF-8 paths
return the original source when the zero-based position is outside the string;
negative length consumes the rest. The UTF-8 path counts code points, not bytes
or grapheme clusters. The FE signature is STRING/VARCHAR and `PropagateNullable`.

DuckDB 2.0.0's `src/function/scalar/string/overlay.cpp` takes
`(source, replacement, BIGINT position, BIGINT length)`. It clamps nonpositive
positions and appends past the end, including empty sources. Negative length
defaults to replacement character length. Valid positions and nonnegative
lengths use the same code-point splice. The mapping's bound prevents depending
on Doris's signed INT32 arithmetic at its extreme edges.

Direct reordered calls have concrete counterexamples; the exploratory matrix is
`build/overlay-initial-probes.json`. The qualification retains them as evidence,
not as a reason to weaken/skip source tests.

## Evidence and reproduction

```bash
./gradlew :doris-duckbridge:test --tests '*TestDorisOverlay*'
./gradlew :doris-duckbridge:pluginZip
bash doris-duckbridge/compose/smoke.sh --no-build --keep
python3 doris-duckbridge/compose/overlay_qualification.py \
  --output doris-duckbridge/build/function-audit/overlay-qualification.json
./gradlew :doris-duckbridge:auditOverlayTypes
./gradlew :doris-duckbridge:check :doris-duckbridge:assemble
docker compose -f doris-duckbridge/compose/docker-compose.yml down # never -v
```

- `TestDorisOverlayCandidate`: exact SQL, arity/return/storage types, unknown type
  refusal, integral bounds/overflow, casts/nested input refusal, escaping, and
  production OR/NOT/AND/LIMIT refusal. `DorisOverlayCandidate` is under `test/src`.
- `TestDorisOverlayOverQuack`: executes the **test-only candidate rendering** over
  Quack JDBC 0.10.0 / protocol v3 / DuckDB 2.0.0. Pins ASCII, astral characters,
  combining marks, joiners, quotes, empty strings/replacement, strict NULLs,
  inequality, zero length and INT32 bounds.
- `overlay_qualification.py`: independent expected-value model plus native BE
  projections above real JDBC scans, direct DuckDB comparisons, and exact-row
  and **deployed EXPLAIN QUERY** assertions for every distinct result. This is a
  blocked acceptance probe, not a passing current-production capability test. Verifies
  exact FE/BE source versions and the full 14-row UTF-8/NULL fixture first;
  errors fail, never count as parity. Unsupported calls must remain local and
  withhold remote LIMIT. Doris's INSERT alias is checked via its FE resolution.
- `auditOverlayTypes`: ten real JDBC metadata observations across native
  STRING/VARCHAR arguments, including empty/NULL and Unicode results. Values
  and NULL flags must agree; native string return families are asserted. Doris
  reports CHAR/text-family metadata with a protocol width (85), versus DuckDB
  VARCHAR with width 2^31-1. Those metadata are **not claimed identical**. Only
  predicate values are pushed; Doris still owns result types and projections.

Targeted Quack/unit tests, the ten native return-contract probes, and the full
Doris module check passed; the full deployed Unicode matrix is red as described
above, so those passes do not establish end-to-end qualification. This does not qualify arbitrary lengths/types, invalid
UTF-8 transport, new JSON mappings, or the published generator's generic SQL.
