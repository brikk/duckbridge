# Pushdown Reference

The complete set of predicates and functions the connector pushes down into
DuckDB.

For open items and deferred functions see
[TODO-pushdown-duckdb.md](dev-docs/TODO-pushdown-duckdb.md). This doc is the
*current surface*; that one is the *tracker*.

## Discipline (non-negotiable)

- **Lossless only.** Anything we can't translate with confidence stays in Trino.
- **Curated, not "anything that looks similar."** Every entry is explicit, with recorded NULL / Unicode / edge semantics.
- **Cross-engine semantic test per entry.** The pushed result must match Trino's own evaluation byte-for-byte (`TestTrinoFunctionAliases`, `TestDuckBridgeExpressionTranslator`, `TestDuckBridgeExpressionEmission`, `TestDuckBridgeArithmeticPushdownParity`).

## How it fires

Trino's `applyFilter` hands the connector each conjunct; the expression
translator emits a DuckDB SQL fragment for the pushable ones, and unsupported
conjuncts stay in Trino (per-conjunct partial pushdown). The fragments are
rendered into the remote query's `WHERE` clause.

**"Alias only what diverges."** Each pushable `(name, arity)` has an *emission
class* (`DuckBridgeExpressionTranslator.EMISSION_STRATEGIES`):

| Class | Emits | Extension-backed? |
|---|---|---|
| **BARE** (57) | the same bare DuckDB built-in — `length(s)`, `abs(x)`, `year(x)` | no |
| **RENAME** (11) | a different bare built-in — `to_hex→hex`, `regexp_like→regexp_matches` | no |
| **OPERATOR** (5) | a parenthesized operator — `bitwise_and→(a & b)`, `bitwise_not→(~a)` | no |
| **INLINE** (12) | a fixed SQL transform — `md5→unhex(md5(x))`, `if/2→if(c,t,NULL)` | no |
| **ALIAS** (10) | the extension's `trino_<name>(...)` | **yes** |

Only the **10 ALIAS** entries resolve to `trino_<name>(...)` macros / native
scalar functions provided by the
[`trino_parity` DuckDB extension](../../duckdb-trino-parity-extension), loaded on
connection. The other 85 emit plain DuckDB SQL that evaluates with
Trino-identical semantics natively (proven by per-entry semantic fixtures against
embedded DuckDB), so they **stay pushable even when parity is disabled** — only
the 10 ALIAS entries drop out. See
[ANSWER-parity-passthrough-candidates.md](dev-docs/ANSWER-parity-passthrough-candidates.md).

---

## 0. String-pushdown mode

String comparison/ordering pushdown is dialed by `duckbridge.string-pushdown.mode`
(catalog default) / `string_pushdown_mode` (per-query session override), default
`PARITY`. It gates the string-touching rows of §1–§3 below on two trust axes
(comparison-byte-alignment and extension-backed functions). **Non-string** predicates
(`length(s)=5`, `id > 3`, `year(d)=2000`) are byte-exact cross-engine and push in every
mode. Grounded in a live probe: `dev-docs/REPORT-string-comparison-probe-duckdb-1.5.4.md`
(design + probe methodology borrowed from the sibling trino-doris connector).

| mode | VARCHAR domains (`=`/range/IN) | retained filter | string `LIKE` | string TopN | ALIAS fns | extension |
|---|---|---|---|---|---|---|
| `NULL_ONLY` | `IS [NOT] NULL` only | — | no | no | no | no |
| `GUARDED` | superset pre-filter, **kept locally**; `0x00`-bearing domains skipped | yes | no† | no | no | **no** |
| `BINARY` | full (probe-verified byte semantics) | no | yes | yes | no | no |
| `FULL` | full (caller-asserted, no probe) | no | yes | yes | no | no |
| `PARITY` *(default)* | full (probe-verified) | no | yes | yes | **yes** | required |

† `LIKE 'foo%'` still pre-filters in `GUARDED`: Trino's `DomainTranslator` folds the
wildcard prefix into a range domain (`'foo' <= name AND name < 'fop'`) that rides the
domain path in §1 for free; only the residual `$like` stays retained. No custom
LIKE-to-range converter exists — this is stock engine behavior, verified in the suite.

- **Init probe (fail loud).** `BINARY`/`PARITY` verify per connection that DuckDB's
  `default_collation` is binary and a comparison/ordering canary (case pairs, trailing
  space, NFC≠NFD, astral order, zero-width, NUL, `ORDER BY` incl. NULLS) matches Trino.
  On divergence they throw with instructions to drop to `GUARDED`. `PARITY` additionally
  LOADs + probes the extension. `GUARDED`/`NULL_ONLY`/`FULL` skip the probe.
- **TopN guarantee.** `isTopNGuaranteed` is true only at `BINARY`/`FULL`/`PARITY` (byte
  ordering probe-verified); string sort keys are only pushed at those modes. Non-string
  sort keys push the bound in every mode but Trino re-applies TopN below `BINARY`.
- **CHAR.** DuckDB has no CHAR padding (CHAR ≡ VARCHAR) and the read mappings never
  produce `CharType`, so there is no CHAR trailing-space read hazard (unlike doris).

## 1. Predicate / value pushdown

These don't need the expression translator.

| Surface | Notes |
|---|---|
| **TupleDomain on `WHERE`** | Range/equality/`IN`/`IS NULL` constraints on all supported column types. String columns are gated by the string-pushdown mode (§0); non-string columns always push. |
| **LIMIT** | Pushed and final — Trino drops its own limit. |
| **TopN (`ORDER BY ... LIMIT`)** | Pushed into the remote query (with explicit `NULLS FIRST/LAST`). Guaranteed (Trino drops its own TopN) only at string-pushdown mode ≥ `BINARY`; string sort keys require ≥ `BINARY` (§0). |

## 2. Operators & transforms

Translator-level rewrites — emitted directly as SQL, not via the macro catalog.

| Group | Pushed |
|---|---|
| Comparison | `=`, `<>`, `<`, `<=`, `>`, `>=`, `IS NULL`, `IS NOT DISTINCT FROM` |
| Logical | `AND`, `OR`, `NOT` |
| Arithmetic | `+`, `-`, `*`, `/`, `%` (infix), unary `-` (negate) |
| Null-handling | `COALESCE` (variadic), `NULLIF` |
| Cast | `CAST` / `TRY_CAST` for primitive types (BOOLEAN, TINYINT, SMALLINT, INTEGER, BIGINT, DOUBLE, VARCHAR, DATE) |
| Pattern | `LIKE` / `NOT LIKE` with optional `ESCAPE` (constant patterns only; dynamic or NULL patterns stay unpushed) |
| `BETWEEN` | Pushed implicitly — Trino's planner decomposes it to `>= AND <=` before `applyFilter`, so the comparison + `AND` translators handle it. |
| `concat(a, b, …)` → `(a \|\| b \|\| …)` | Translator rewrite for VARCHAR returns, **not** a macro: DuckDB's `concat` skips NULL while Trino's NULL-propagates; the `\|\|` operator propagates in both, matching Trino. |

## 3. Functions

**~95 pushable entries** (the translator's `PUSHABLE_FUNCTIONS` set /
`EMISSION_STRATEGIES` map). Only **10 route through the extension** (the ALIAS
class — native C++ in the extension); the other **85 emit plain DuckDB SQL
natively** (BARE / RENAME / OPERATOR / INLINE) and push regardless of whether the
extension is loaded. The **Ext?** column below marks which entries are
extension-backed. `trino_meta()` still lists all ~95 for now (a follow-up shrinks
it to the 10 — see [ANSWER-parity-passthrough-candidates.md](dev-docs/ANSWER-parity-passthrough-candidates.md)).
Counts: string 22, numeric 32, regex 5, encoding 6, distance 2, hash 6, date 20,
conditional 2.

| Category | Functions | Ext? | Notes |
|---|---|---|---|
| **String — native (ICU)** | `lower`, `upper`, `reverse`, `trim`, `ltrim`, `rtrim`, `normalize/1` | **yes** (ALIAS) | Native C++ (`string_functions.cpp`) for full Trino parity: root-locale **full** case folding (`lower('İ')`→`'i'+U+0307`, `upper('ß')`→`'SS'`), **code-point** reverse, `Character.isWhitespace`-aligned trim, NFC via `icu::Normalizer2`. `normalize/2` (NFD/NFKC/NFKD selector) is **not** pushed — the vendored ICU snapshot ships only NFC data. |
| **String — native emission** | `length`, `substring/{2,3}`, `replace`, `strpos`, `starts_with`, `lpad`, `rpad`, `concat_ws/{2..5}`, `translate`, `chr`, `bit_length` | no (BARE) | Code-point (not byte / grapheme) semantics; pinned in fixtures against unicode, NULL, and edge inputs. **`lpad`/`rpad` push only with a constant, non-empty pad** (Trino raises on empty pad; DuckDB doesn't). **`substring` pushes only with a constant start ≥ 1** (DuckDB treats 0 as 1; Trino differs). |
| **Numeric** | `abs`, `ceil`, `floor`, `mod`, `power`, `sqrt`, `exp`, `ln`, `log2`, `log10`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`, `sinh`, `cosh`, `tanh`, `degrees`, `radians`, `cbrt`, `truncate`, `sign`, `pi/0`, `bitwise_and`, `bitwise_or`, `bitwise_not`, `bitwise_xor`, `bitwise_left_shift`, `bitwise_right_shift` | no | BARE, except RENAME `truncate→trunc`, `bitwise_xor→xor`; OPERATOR `bitwise_and/or/not/left_shift/right_shift`. Float `mod` gated out; `log10` emitted explicitly (never bare `log`); `bitwise_right_shift` on negatives can differ (signed/unsigned) — safe for typical positive-integer use. |
| **Regex** | `regexp_like/2`, `regexp_extract/{2,3}`, `regexp_replace/{2,3}` | no | RE2 on both sides. RENAME `regexp_like→regexp_matches`; BARE `regexp_extract`; INLINE `regexp_replace` forces the `'g'` flag to match Trino's global default (2-arg uses `''`). |
| **Encoding** | `url_encode`, `url_decode`, `to_hex`, `from_hex`, `to_base64`, `from_base64` | no | RFC-3986 / hex / base64; output bytes identical. RENAME `to_hex→hex`, `from_hex→unhex`; the rest BARE. |
| **Distance** | `levenshtein_distance`, `hamming_distance` | no | RENAME `→levenshtein` / `→hamming`. Code-point edit distance. |
| **Hash** | `md5`, `sha1`, `sha256`, `sha512`, `xxhash64`, `hmac_sha256/2` | `sha512`, `xxhash64`, `hmac_sha256` (ALIAS) | `md5`/`sha1`/`sha256` are INLINE `unhex(<hash>(x))` (bare DuckDB hash + unhex, no extension). `sha512`, `xxhash64`, `hmac_sha256` are **native C++** (`hash_functions.cpp`) over vendored xxHash (BSD-2) + WjCryptLib SHA (public domain). `xxhash64` big-endian to match Trino; `hmac_sha256(data, key)` over raw VARBINARY bytes. |
| **Date / time** | `year`, `month`, `day`, `quarter`, `hour`, `minute`, `second`, `millisecond`, `day_of_week` (ISO), `day_of_year`, `last_day_of_month`, `week` / `week_of_year` (ISO), `year_of_week` / `yow`, `date_trunc/2`, `date_diff/3`, `to_unixtime`, `from_unixtime`, `with_timezone/2` | no | BARE (`year`…`second`, `date_trunc`, `date_diff`, `week`), RENAME (`day_of_year→dayofyear`, `last_day_of_month→last_day`, `week_of_year→week`, `from_unixtime→to_timestamp`), INLINE (`day_of_week→isodow`, `year_of_week`/`yow→isoyear`, `millisecond`, `to_unixtime`, `with_timezone` arg-flip). Type-gated. Over `TIMESTAMP WITH TIME ZONE` they push only when `pushdown_timestamp_with_timezone` is on (**default on**). `date_trunc` on DATE input: DuckDB returns TIMESTAMP but auto-casts in comparisons, so pushed **results** stay DATE-aligned (fixture-pinned). |
| **Conditional** | `if/{2,3}` | no | INLINE `if/2→if(c, t, NULL)`; BARE `if/3`. |

## Not pushable (by design)

- `at_timezone(WTZ, varchar)` — DuckDB's TIMESTAMPTZ has no per-value zone metadata, so "rezone display" isn't expressible.
- `hmac_md5` / `hmac_sha1` / `hmac_sha512` — only `hmac_sha256` is ported natively; add the WjCryptLib primitives if a workload needs the others.
- `murmur3` — reconstructable from `murmurhash3_x64_128` but deferred (niche; needs a live Trino byte-layout confirmation). See TODO.
- `url_extract_*` (netquack) — rejected: DuckDB returns empty strings where Trino returns NULL, plus a `BIGINT`-vs-`VARCHAR` port mismatch.
- `normalize/2`, `position` (operator-form), `lower`/`upper` over collations.

## Adding an entry

Add a `(name, arity) → Emission` row to `EMISSION_STRATEGIES` in
`DuckBridgeExpressionTranslator`, plus a per-entry fixture:

- **BARE / RENAME / OPERATOR / INLINE** (DuckDB matches Trino natively): choose the
  class, and add a semantic fixture to `SemanticFixtures` (evaluated against
  embedded DuckDB by `TestTrinoFunctionAliases.nonAliasSemanticFixtures`). No
  extension change needed — `testEveryNonAliasEntryHasAFixture` enforces coverage.
- **ALIAS** (DuckDB diverges, needs native C++): add the macro/function +
  `trino_meta()` row in the `trino_parity` extension repo, mark it `Emission.Alias`
  here, and it's covered by the `trino_meta() == ALIAS_FUNCTIONS` lockstep in
  `TestTrinoFunctionAliases.testAliasSetEqualsMeta` (strict equality since the
  extension shrank to exactly the 10 natives).
