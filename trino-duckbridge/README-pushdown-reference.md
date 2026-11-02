# Pushdown reference

**Source snapshot: 2026-11-02**, including fixes through `4046ce9`. Applies to
**Trino 483, DuckDB 2.0.0 / native JDBC 2.0.0.0, Quack JDBC 0.12.0 and
trino_parity 0.5.1**. An older packaged plugin may not contain these fixes.
This is the Trino connector's reference; [Doris has a separate allowlist](../doris-duckbridge/dev-docs/REPORT-doris-duckdb-function-divergence.md).

The connector has extensive predicate/function pushdown, but **being admitted by
the translator is not the same as complete semantic qualification**. The known
exceptions below are part of the current contract, not hidden in a development
tracker.

## Read the status correctly

| Status | Meaning |
|---|---|
| **Enabled** | The current implementation can emit the operation. Actual pushdown still depends on Trino's plan, operand rendering, types and settings. Tests cover specific cases, not every possible input. |
| **Conditional** | Enabled only when the stated gate accepts the expression. Otherwise that expression stays in Trino. |
| **Local** | This connector does not emit the operation through its predicate translator/domain mapping. Trino evaluates the remaining expression. |
| **Known issue — still enabled** | A demonstrated mismatch remains in an admitted operation. **Do not read this as an implemented fallback or a guarantee of correct pushdown.** |
| **Accepted error limitation** | The specific documented error-category difference is retained; successful values and NULLs are not allowed to change on that account. |

**The complete audit is not green.** The latest local full run had 945 tests,
942 passing and three failing new audit checks, with no errors/skips. The failing
checks cover the unresolved math, temporal and native-Trino membership cases
below; they are not three isolated input values. See the
[current qualification and remaining issues](dev-docs/QUALIFICATION-targeted-pushdown-fixes-2026-11-02.md).

## Known limitations that matter to queries

| Operation | Current behavior | Limitation |
|---|---|---|
| `atan`, `atan2`, `cosh` | **Known issue — still enabled** | Finite results can differ in their final floating-point bits. Equality predicates have demonstrably returned different row sets. NaN-aware comparisons do not repair the underlying math result. No local fallback for these three functions has been applied. |
| `date_trunc` with DATE input | **Known issue — still enabled**, subject to valid-unit gates | DuckDB's native overload returns TIMESTAMP where Trino returns DATE. The new unbounded DATE-text formatter repairs the sampled nested text consumers, but does not establish full result-type/range parity. Very wide DATE inputs can fail during truncation; the observed pushed and Trino error contracts differ. A cast back to DATE alone is not a full fix. |
| `date_trunc` with zoned timestamps | **Known issue — still enabled** when the zoned-function gate permits it | DST overlap/offset handling can change the instant and selected rows. For example, in New York, native hour truncation of `2024-11-03 05:30 UTC` produced `06:00 UTC`, after its input. |
| `with_timezone` | **Known issue — still enabled** for renderable naive TIMESTAMP inputs | New York overlap attachment chooses a different offset from Trino. A computed Trino value also carries a per-value zone that DuckDB's instant/session-zone model does not preserve for nested extraction. |
| `REAL IN (...)` with negative zero | **Native Trino inconsistency; no connector workaround** | In Trino 483, tested constant-folded and compiled `IN (0,1)` evaluations disagree on negative zero. Connector-free UNNEST reproduces the compiled behavior. Pushed domains and retained compiled filters can therefore disagree. The diagnostic remains open; zero signs/types are not normalized to conceal it. |
| Overflowing DECIMAL narrowing `CAST` | **Accepted error limitation; pushdown retained** | The four audited overflows to TINYINT/SMALLINT/INTEGER/BIGINT fail with pushed `JDBC_ERROR`, versus Trino's `NUMERIC_VALUE_OUT_OF_RANGE`. This does **not** permit returning a value or NULL instead of failing, or accepting arbitrary errors as equivalent. `TRY_CAST` has a separate NULL-on-failure contract. |
| Bounded `VARCHAR(n)` casts | **Existing admission retained; not fully qualified** | The translator renders the native target as VARCHAR without its length bound. The new DATE formatter applies only to **unbounded** VARCHAR; it does not certify bounded DATE spelling or general bounded-cast length/error semantics. |

These defects are **not** fixed by choosing a lower string-pushdown mode: math
and other non-string expressions have independent admission. The
`pushdown_timestamp_with_timezone` property is also **not** a blanket timezone
safety switch: it gates selected functions whose input is already zoned, not
`with_timezone` on a naive timestamp. Do not assume a listed issue already has a
configuration-based workaround.

The reports document bounded observations, not an exhaustive proof that every
other admitted overload/input is equivalent. This page describes implementation
and established evidence; it does not turn untested behavior into a guarantee.

## How pushdown is chosen

There are two separate filter paths:

1. **Column domains** — Trino's planner extracts ranges, equality/IN and NULL
   constraints. Base-jdbc uses each column mapping's pushdown controller and
   prepared bindings. This can happen even when a function expression remains
   local.
2. **Expression predicates** — the connector translates supported calls into
   DuckDB `WHERE` SQL. Top-level AND conjuncts can push independently. An
   unsupported child prevents its enclosing expression from translating; the
   connector does not push only one side of an OR.

Only expressions the planner actually hands to these paths are candidates. SQL
aliases, constant folding and other planner rewrites can change their shape.
Listing a function below does **not** mean every SQL occurrence is pushed.
Function calls here describe **filter expressions**, not arbitrary SELECT-list
projection or aggregate pushdown. Column projection is separate. Explicit native
SQL submitted through the passthrough query table function follows DuckDB's
semantics; this catalog is not a Trino-parity promise for arbitrary passthrough SQL.

## Column domains, projection, LIMIT and TopN

| Surface | Current admission / behavior |
|---|---|
| Column projection | Requested columns can be read without fetching every table column. This is not general computed-expression projection pushdown. |
| Standard scalar domains | Boolean, integer, decimal, DATE and ordinary naive timestamp mappings use base-jdbc domain pushdown. Large domains may be compacted with an exact filter retained in Trino. This is not a claim that all supported types push domains. |
| REAL / DOUBLE domains | Use a NaN-aware controller. Non-ALL/non-NONE pushed value ranges receive an explicit `<= +Infinity` bound to preserve Trino's NaN exclusion without dropping positive infinity. Ordinary finite ranges remain pushable; standard compaction/residual behavior is retained. See the REAL IN inconsistency above. |
| VARCHAR domains | Controlled by the string mode below. |
| UUID, TIME, TIME WITH TIME ZONE, TIMESTAMP WITH TIME ZONE, mapped nanos temporal types and ARRAY domains | **Local**: these mappings disable domain pushdown. Supporting reads/writes of a type does not imply remote filtering. Expression translation is a separate path. |
| `LIMIT` | Supported; a pushed limit is declared guaranteed, so Trino need not re-enforce it. This says nothing about deterministic row choice without ordering. The planner still controls whether it is legal to push below any remaining operations. |
| TopN (`ORDER BY ... LIMIT`) | The implementation admits non-string sort keys without a further per-type ordering gate. VARCHAR/CHAR keys require `BINARY`, `FULL` or `PARITY`; every sort key must pass. SQL includes explicit NULL ordering. The connector declares TopN guaranteed only in those three modes; below them, Trino retains its TopN. This admission rule is not exhaustive ordering qualification of every non-string type/exceptional value. |
| Aggregates (`COUNT`, `MIN`, `MAX`, `SUM`, etc.) | **Not implemented as connector aggregate pushdown.** |

## String-pushdown modes

Catalog property: `duckbridge.string-pushdown.mode`. Per-query override:
`string_pushdown_mode`. Default: `PARITY`.

| Mode | VARCHAR value domains | Exact domain filter retained? | String-comparing expressions / LIKE / string TopN | Extension-backed functions |
|---|---|---|---|---|
| `NULL_ONLY` | NULL-ness only | Value predicates stay local | Local | Local |
| `GUARDED` | Domain prefilter; domains with NUL-bearing values/bounds are withheld | Yes for value-domain prefilters | Local; an independently extracted LIKE-prefix domain can still prefilter | Local |
| `BINARY` | Full domain pushdown, subject to ordinary compaction | Only when required by compaction | Eligible | Local |
| `FULL` | Same rendering as BINARY, **caller-asserted**, no comparison probe | Only when required by compaction | Eligible | Local |
| `PARITY` | Same comparison trust as BINARY | Only when required by compaction | Eligible | Eligible; extension required |

- `BINARY` and `PARITY` verify binary collation/comparison canaries at connection
  initialization. `FULL` does **not** run that probe and must not be called
  probe-verified. A successful canary is not a proof of every function/input.
- `PARITY` additionally verifies the extension metadata. It issues idempotent
  LOAD when the connector owns loading; a preloaded remote extension need not be
  loaded by the connector. The probe is one consolidated SELECT per connection,
  separate from any LOAD. Missing required parity support fails explicitly.
- Non-string-comparing expressions do not require string-comparison trust, but
  still need their function/type/constant gates and renderable children. An
  extension-backed child is available **only in PARITY**, even if its parent
  compares a numeric result (for example, `length(upper(s)) = 3`).
- A retained domain filter can remove excess rows from a prefilter; it cannot
  recover rows incorrectly removed remotely. GUARDED is not a generic repair
  for the known function defects.

## Operators, casts and literal restrictions

| Expression | Current behavior |
|---|---|
| Comparisons | `=`, `<>`, `<`, `<=`, `>`, `>=`, `IS NULL`, `IS NOT DISTINCT FROM` can translate; NOT provides corresponding negated forms. String operands require string-comparison trust. Floating primitive comparisons explicitly preserve SQL NULL and IEEE NaN behavior; identity comparison retains its distinct semantics. |
| Logical operators | AND, OR and NOT translate when their children translate. Top-level AND permits partial pushdown. |
| Arithmetic | Binary `+`, `-`, `*` and unary `-` are emitted. Binary `/` and `%` remain **local**. Renderable types/children are still required; admission is not full qualification of every numeric overload or overflow/error category. |
| NULL handling | Variadic `COALESCE` and two-argument `NULLIF`. Floating NULLIF uses the corrected IEEE equality rather than native NaN equality. |
| `BETWEEN` | Can be handled after Trino decomposes it into comparisons. There is no separate BETWEEN emission. |
| `concat(a,b,...)` | VARCHAR-returning calls with at least two arguments become the NULL-propagating `a \|\| b \|\| ...`, not DuckDB's NULL-skipping `concat`. ARRAY-returning overloads do not use this rewrite. |
| `CAST` / `TRY_CAST` targets | The expression translator renders BOOLEAN, TINYINT, SMALLINT, INTEGER, BIGINT, DOUBLE, VARCHAR and DATE targets. REAL, DECIMAL, temporal timestamp/time and composite **targets** are not rendered by this cast rule. Source types can still include REAL/DECIMAL columns. |
| String-source casts | VARCHAR/CHAR to a non-string target remains **local**: DuckDB's parsers accept inputs Trino rejects. A VARCHAR target can be admitted; bounded lengths have the limitation above. |
| Casts to VARCHAR | Admitted from integer-family values, BOOLEAN, DATE and string sources. DOUBLE/REAL, DECIMAL, timestamp/time and other non-string sources are not admitted to VARCHAR by this rule. |
| DATE to unbounded VARCHAR | Uses an explicit Trino-style year/month/day formatter, including BCE/year zero and expanded years. Native BC display text is not used. This is an explicit user-requested cast, not a conversion of stored DATE columns to text. |
| `LIKE` / `NOT LIKE` | Conditional on string mode and a constant non-NULL pattern readable from Trino's compiled LikePattern. Optional ESCAPE is supported when the translator can extract its single-character representation. Dynamic/NULL/unreadable patterns stay local unless the planner simplifies them. The audit's matching retained-filter cases are not evidence that those cases ran remotely. |

The **expression translator's** literal renderer accepts SQL NULL, booleans,
integer-family values, finite DOUBLE values, VARCHAR strings without U+0000,
and DATE constants with years **1 through 9999**. Other constants decline
translation. In particular, REAL/DECIMAL/timestamp/UUID/binary constants are not
general expression literals here; neither are non-finite DOUBLE literals.

These are not restrictions on all **domain bindings** or data reads/writes.
For example, column domains can bind REAL/DECIMAL/ordinary timestamps, and the
finite DATE writer supports a wider range than the expression DATE-literal
renderer. The experimental Quack/Arrow parameter inliner is yet another path:
it now preserves typed REAL/DOUBLE infinity literals, but still refuses NaN
parameters and other unsupported literal types explicitly.

High-precision temporal variables (precision greater than 6) are not rendered by
the expression translator. Their qualified nanos read/write support is **not**
permission to push nanos predicates/functions. Unknown calls and non-renderable
subexpressions stay local; some unsupported column types are refused at metadata
or read time instead of being readable with a local filter. See the
[type/carrier coverage](dev-docs/TODO-datetime-timezones.md) and
[boundary policy](dev-docs/REPORT-boundary-type-policy-2026-10-30.md).

## Function catalog

The current catalog has **79 `(name, arity)` entries**, not 79 distinct SQL
function names or a guarantee that all 79 push in every query.

| Emission class | Entries | What is emitted |
|---|---:|---|
| BARE | 41 | Same native function name |
| RENAME | 9 | A different native name |
| OPERATOR | 3 | SQL bitwise operator |
| INLINE / CONTEXTUAL | 16 | SQL transformation, sometimes using types/session state |
| ALIAS | 10 | Extension `trino_<name>` function |

Only the ten ALIAS entries require the parity extension. The other **69** do
not reference it, but still need all applicable gates. Extension independence
is **not** a claim that DuckDB matches Trino over every input: the known enabled
math/temporal defects above are in that extension-free surface.

The table lists every catalog entry; `name/arity` identifies the internal call
shape. Trino may normalize user-visible aliases before calling the connector.

| Category | Catalog entries | Conditions / implementation |
|---|---|---|
| Case, reverse, trim, normalization | `lower/1`, `upper/1`, `reverse/1`, `trim/1`, `ltrim/1`, `rtrim/1`, `normalize/1` | **PARITY only**, extension-backed. Simple per-code-point case mapping (`upper('ß')` stays `ß`), code-point reversal, Java-whitespace-aligned trim, NFC normalization. The validated Unicode profile is Trino 483 / JDK 25 / parity 0.5.1, not every future JDK. |
| String inspection / translation | `length/1`, `strpos/2`, `starts_with/2`, `translate/3`, `chr/1` | Native emission, subject to renderable arguments and string-comparison trust where the enclosing expression compares strings. |
| Substring | `substring/2`, `substring/3` | Start must be an integer-family constant ≥ 1; the three-argument form also needs an integer-family constant length ≥ 0. Other cases stay local. |
| Replace | `replace/3` | Search argument must be a constant, non-empty VARCHAR. |
| Padding | `lpad/3`, `rpad/3` | Constant integer-family size ≥ 0 and constant non-empty VARCHAR pad. |
| Separator concatenation | `concat_ws/2`, `concat_ws/3`, `concat_ws/4`, `concat_ws/5` | Every argument must be VARCHAR. The ARRAY overload is not admitted. |
| Numeric native calls | `abs/1`, `ceil/1`, `floor/1`, `exp/1`, `sin/1`, `cos/1`, `tan/1`, `sinh/1`, `tanh/1`, `degrees/1`, `radians/1`, `truncate/1`, `pi/0` | Native emission; `truncate` is renamed to `trunc`. These catalog entries have no additional argument-type gate; that is not full-overload qualification. CEIL stayed local in the recent audit's actual planner shape despite having a catalog entry. |
| Floating sign | `sign/1` | DOUBLE/REAL emission preserves NaN and signed zero and otherwise casts native sign to the floating type. Other argument types keep native `sign` emission. |
| Modulo function | `mod/2` | Divisor must be a nonzero integer-family constant. Zero, NULL and dynamic divisors stay local. This does not enable the `%` operator. |
| Known divergent native math | `atan/1`, `atan2/2`, `cosh/1` | **Still enabled; known finite-rounding/wrong-row issues.** No full-domain fix or fallback has been applied. |
| Bitwise | `bitwise_and/2`, `bitwise_or/2`, `bitwise_not/1`, `bitwise_xor/2` | AND/OR/NOT use operators; XOR becomes native `xor`. Shift functions remain local. |
| Regex matching / extraction / replacement | `regexp_like/2`, `regexp_extract/2`, `regexp_extract/3`, `regexp_replace/2`, `regexp_replace/3` | Constant pattern must pass Re2Safety. `regexp_like` becomes `regexp_matches`; extraction is match-guarded to return NULL on no match; replacement forces global mode. The three-argument replacement also requires a constant replacement with neither `$` nor backslash. See the regex restrictions below. |
| Regex count | `regexp_count/2` | VARCHAR source and constant nonempty pattern in the smaller consuming-ASCII grammar below. Other patterns stay local. |
| Encoding | `to_hex/1`, `to_base64/1` | Native `hex` rename and `to_base64` respectively. Binary column mappings allow these in actual filter expressions; literal rendering still limits which calls translate. |
| Native hashes | `md5/1`, `sha1/1`, `sha256/1` | Native hex result converted with `unhex` to the binary shape expected by Trino. |
| Extension hashes | `sha512/1`, `xxhash64/1`, `hmac_sha256/2` | **PARITY only**, extension-backed. Empty-key HMAC throws like Trino; empty data is distinct from an empty key. |
| General temporal extraction | `year/1`, `month/1`, `day/1`, `quarter/1`, `hour/1`, `minute/1`, `second/1`, `millisecond/1` | Gate admits DATE/naive TIMESTAMP, or zoned TIMESTAMP when the zoned-function property is on. That shared gate is not a promise that each function has every listed overload in Trino. Millisecond emission reduces DuckDB's sub-minute part modulo 1000. High-precision variables still decline translation. |
| DATE-only calendar calls | `day_of_week/1`, `day_of_year/1`, `last_day_of_month/1`, `week/1`, `week_of_year/1`, `year_of_week/1`, `yow/1` | DATE arguments only. ISO weekday/week/week-year semantics; native renames/extractions as needed. Timestamp overloads are not admitted by these gates. |
| Truncation | `date_trunc/2` | Constant valid unit, temporal-type/session gates. DATE and zoned cases retain the **known issues** above. |
| Difference | `date_diff/3` | Emits native `date_sub` for complete-unit differences, not native `date_diff` boundary counting. Constant valid unit; both temporal inputs must pass their type/session gates. |
| To epoch | `to_unixtime/1` | Naive TIMESTAMP is anchored with the **fixed-offset session zone**; a missing/non-fixed/unrenderable session zone declines emission. Zoned TIMESTAMP uses native epoch with a DOUBLE result and requires the zoned-function property. |
| From epoch | `from_unixtime/1` | Renamed to `to_timestamp`. No additional per-function type/session gate; generic rendering still applies. It is not a proof of all overload, precision or per-value-zone behavior. |
| Attach timezone | `with_timezone/2` | Naive TIMESTAMP input gate; emits `timezone(zone, timestamp)` with arguments reversed. **Still enabled with known DST/per-value-zone issues**; the zoned-function switch does not gate this naive input. |
| Conditional | `if/2`, `if/3` | Two-argument form inserts a NULL false branch; three-argument form uses native `if`. Children must translate. |

### Temporal unit and session gates

- DATE units: `day`, `week`, `month`, `quarter`, `year`.
- Timestamp units: those DATE units plus `millisecond`, `second`, `minute`, `hour`.
- DATE_TRUNC checks its value argument's type. DATE_DIFF uses the DATE unit set
  if either input is DATE, and checks both temporal arguments separately.
- Units must be constant VARCHAR strings; case is normalized. Dynamic, NULL,
  plural, unknown or type-invalid units stay in Trino. For example,
  `date_trunc('hour', date_col)` and `date_trunc('century', ts_col)` are not
  emitted; Trino preserves its own validation/NULL behavior.
- `pushdown_timestamp_with_timezone` defaults to **true**. Turning it off keeps
  the gated extraction/truncation/difference/to-epoch calls over zoned inputs
  local. It neither enables zoned **domains** nor gates every timezone-related
  call. It does not fix the known admitted cases.
- Connections align DuckDB's session zone with Trino's. If setting it fails
  while zoned-function pushdown is enabled, connection setup fails explicitly.
  With that property off, the failure is warned about rather than presented as
  a successful zone change. Named zones and integer-hour offsets have native
  spellings; not every fractional bare offset does.

### Regex restrictions

Trino uses Joni/Java-style patterns; DuckDB uses RE2. Availability in both engines
is not dialect equivalence.

- `regexp_like`, `regexp_extract` and `regexp_replace` use the constant-pattern
  [Re2Safety allowlist](src/dev/brikk/duckbridge/trino/plugin/Re2Safety.kt).
  Its shared subset includes ordinary literals/classes/groups, permitted
  quantifiers and selected escapes. `$`, lookaround, backreferences, inline
  flags, possessive quantifiers, nested/POSIX/intersection classes and many
  Java-specific escapes are withheld. This is an allowlist, not all RE2 syntax;
  passing it does not validate every other argument or malformed pattern.
- `regexp_count` uses the stricter
  [RegexpCountSafety grammar](src/dev/brikk/duckbridge/trino/plugin/RegexpCountSafety.kt):
  one or more ASCII atoms, each optionally followed by `+`. Literal atoms are
  ASCII letters/digits, space and `_ - / : , =`. Class atoms are exactly
  `[0-9]`, `[a-z]`, `[A-Z]`, `[A-Za-z]`, `[a-zA-Z]`, `[A-Za-z0-9]`,
  `[a-zA-Z0-9]`, `[a-z0-9]`, `[A-Z0-9]`. No empty pattern, zero-width match,
  anchors, escapes, groups, alternation or other quantifiers. Source strings
  may contain Unicode; the pattern restriction is not an ASCII-only data rule.

## Deliberately evaluated in Trino

These are absent from the function catalog or refused by the relevant rule;
they are **not** the same category as the known defects still enabled above.
Planner simplification can remove a call before the connector sees it, and a
different, supported conjunct/domain can still push alongside a local call.

| Operation / case | Why it is not emitted |
|---|---|
| `power` / SQL alias `pow` | Finite rounding and special-value counterexamples; local evaluation preserves Trino's result. |
| `sqrt`, `ln`, `log2`, `log10`, `asin`, `acos` | Existing policy withholds the functions: native domain/error behavior differs, and the earlier scalar rewrites failed end-to-end comparison tests. The new IEEE comparison renderer has **not** re-enabled or requalified them. |
| `cbrt` | Demonstrated finite difference even on perfect cubes. |
| `bitwise_left_shift`, `bitwise_right_shift` | Width/shift-count and logical-versus-arithmetic semantics differ. |
| `url_encode`, `url_decode` | Form encoding versus native URL encoding, including space/plus handling. |
| `from_hex`, `from_base64` | Odd-length/unpadded inputs have different acceptance/error behavior. |
| `levenshtein_distance`, `hamming_distance` | Audited native Unicode/byte behavior differs. |
| `at_timezone` | No emitter preserving Trino's per-value zone metadata. |
| `normalize/2` | Only the one-argument NFC call is registered. This is the connector's API scope, not a claim that ICU cannot normalize other forms. |
| `hmac_md5`, `hmac_sha1`, `hmac_sha512`, `murmur3`, `url_extract_*` | No admitted emitter in this catalog. Implementations are missing/deferred or have identified result/type differences. |
| Restricted argument shapes | Nonconstant/empty padding or replace-search strings, unsupported substring bounds, dynamic/unsafe regex, invalid/dynamic temporal units, unsupported casts and non-renderable literals follow the gates above. |
| Nanos expressions and disabled domains | Follow the precision/type rules above; do not infer pushdown from successful typed reads. |
| Any other unknown function/call shape | No generic name-based forwarding. Trino may rewrite SQL syntax to a known internal call, but unrecognized calls themselves are not emitted. |

## Verifying a particular query

Use `EXPLAIN (TYPE DISTRIBUTED)` and examine both the connector scan constraints
and any retained `filterPredicate` / ScanFilterProject. Look for the expression
in the remote scan SQL/constraints: **absence of a filter alone is not proof**
if the planner simplified or eliminated it. A retained filter can coexist with
pushed column domains. Successful results alone do not establish where
evaluation ran. LIMIT/TopN nodes have their own placement and guarantee rules.

An expression-disabled physical catalog is **not** a fully local oracle because
its domain controller can still push. The audits use independent native-Trino
inputs as well as actual production plans and exact row/value/error comparisons.
See:

- [Targeted fixes, accepted limitations and remaining counterexamples](dev-docs/QUALIFICATION-targeted-pushdown-fixes-2026-11-02.md).
- [DATE write and type-aware temporal-unit fixes](dev-docs/QUALIFICATION-narrow-date-unit-fixes-2026-11-01.md).
- [POWER fallback and original counterexamples](dev-docs/REPORT-power-edge-audit-2026-10-31.md).
- [Restricted regex-count qualification](dev-docs/REPORT-regexp-count-qualification-2026-10-29.md).
- [Current-profile Unicode qualification](dev-docs/REPORT-unicode-version-recheck-2026-10-30.md).
- [Active readiness tracker](../dev-docs/TODO-duckdb-2.0.md), for engineering follow-up rather than the user-facing support contract.

Implementation authorities are the
[function catalog and gates](src/dev/brikk/duckbridge/trino/plugin/DuckBridgeEmissionCatalog.kt),
[expression translator](src/dev/brikk/duckbridge/trino/plugin/DuckBridgeExpressionTranslator.kt),
[column mappings and LIMIT/TopN rules](src/dev/brikk/duckbridge/trino/plugin/DuckBridgeClient.kt)
and their type-specific mapping/controller helpers. Catalog/fixture coverage is
necessary regression protection, not a proof of universal parity. New admissions
require appropriate scalar **and** planned row-set/error tests; extension aliases
must also remain in metadata lockstep with the ten registered native functions.
