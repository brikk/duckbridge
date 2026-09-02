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
class* (`DuckBridgeEmissionCatalog.EMISSION_STRATEGIES`, surfaced as `DuckBridgeExpressionTranslator.EMISSION_STRATEGIES`):

| Class | Emits | Extension-backed? |
|---|---|---|
| **BARE** (43) | the same bare DuckDB built-in — `length(s)`, `abs(x)`, `year(x)` | no |
| **RENAME** (9) | a different bare built-in — `to_hex→hex`, `regexp_like→regexp_matches`, `date_diff→date_sub` | no |
| **OPERATOR** (3) | a parenthesized operator — `bitwise_and→(a & b)`, `bitwise_not→(~a)` | no |
| **INLINE / CONTEXTUAL** (20) | a fixed SQL transform — `md5→unhex(md5(x))`, `if/2→if(c,t,NULL)`, `ln→CASE … END` (IEEE edges), `to_unixtime→epoch(timezone(<session zone>, ts))` | no |
| **ALIAS** (10) | the extension's `trino_<name>(...)` | **yes** |

Counts as of the 2026-09-02 correctness evaluation
([dev-docs/TODO-rectify-from-eval.md](dev-docs/TODO-rectify-from-eval.md)), which removed
`url_encode`, `url_decode`, `bitwise_left_shift`, `bitwise_right_shift`, `from_hex`, `from_base64`,
`levenshtein_distance`, `hamming_distance`, `cbrt` (and the never-reachable `bit_length`, which is not
a Trino function) and added gates/rewrites noted per row below. Every remaining entry is pinned by
`TestPushdownSemanticFixtures`, which evaluates the same expression on **Trino** and on **DuckDB**
(via the production translator's SQL) and requires identical outcomes — expectations are never
hand-written. Authoritative list: `DuckBridgeEmissionCatalog.EMISSION_STRATEGIES`.

Only the **10 ALIAS** entries resolve to `trino_<name>(...)` macros / native
scalar functions provided by the
[`trino_parity` DuckDB extension](../../duckdb-trino-parity-extension), loaded on
connection. The other 85 emit plain DuckDB SQL that evaluates with
Trino-identical semantics natively (proven by per-entry semantic fixtures against
embedded DuckDB), so they **stay pushable even when parity is disabled** — only
the 10 ALIAS entries drop out.

---

## 0. String-pushdown mode

String comparison/ordering pushdown is dialed by `duckbridge.string-pushdown.mode`
(catalog default) / `string_pushdown_mode` (per-query session override), default
`PARITY`. It gates the string-touching rows of §1–§3 below on two trust axes
(comparison-byte-alignment and extension-backed functions). **Non-string** predicates
(`length(s)=5`, `id > 3`, `year(d)=2000`) are byte-exact cross-engine and push in every
mode. Grounded in a live probe: `dev-docs/REPORT-string-comparison-probe-duckdb-1.5.5.md`
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
| Cast | `CAST` / `TRY_CAST` for primitive targets (BOOLEAN, TINYINT, SMALLINT, INTEGER, BIGINT, DOUBLE, VARCHAR, DATE), **except**: from a string source to a non-string target (DuckDB's parsers accept `'1.0'`→INTEGER, `'2020/01/01'`→DATE, `'yes'`→BOOLEAN where Trino rejects — EV-A9), and to VARCHAR from anything other than an exact integer, BOOLEAN or DATE (`DOUBLE` renders `1.0E7` in Trino vs `10000000.0` in DuckDB — EV-A10). Those stay in Trino. |
| Pattern | `LIKE` / `NOT LIKE` with optional `ESCAPE` (constant patterns only; dynamic or NULL patterns stay unpushed) |
| `BETWEEN` | Pushed implicitly — Trino's planner decomposes it to `>= AND <=` before `applyFilter`, so the comparison + `AND` translators handle it. |
| `concat(a, b, …)` → `(a \|\| b \|\| …)` | Translator rewrite for VARCHAR returns, **not** a macro: DuckDB's `concat` skips NULL while Trino's NULL-propagates; the `\|\|` operator propagates in both, matching Trino. |

## 3. Functions

**~95 pushable entries** (the translator's `PUSHABLE_FUNCTIONS` set /
`EMISSION_STRATEGIES` map). Only **10 route through the extension** (the ALIAS
class — native C++ in the extension); the other **85 emit plain DuckDB SQL
natively** (BARE / RENAME / OPERATOR / INLINE) and push regardless of whether the
extension is loaded. The **Ext?** column below marks which entries are
extension-backed. `trino_meta()` catalogs exactly the 10 extension-backed
entries, and the drift test pins that set with strict equality.
Counts: string 22, numeric 32, regex 5, encoding 6, distance 2, hash 6, date 20,
conditional 2.

| Category | Functions | Ext? | Notes |
|---|---|---|---|
| **String — native (ICU)** | `lower`, `upper`, `reverse`, `trim`, `ltrim`, `rtrim`, `normalize/1` | **yes** (ALIAS) | Native C++ (`string_functions.cpp`) for Trino parity: **simple** per-code-point case mapping exactly as Trino's airlift `SliceUtf8` does (`upper('ß')`→`'ß'` unchanged, where DuckDB's built-in gives `'ẞ'`; `lower('İ')`→`'i'`; no final-sigma rule; ligatures unchanged), **code-point** reverse, `Character.isWhitespace`-aligned trim, NFC via `icu::Normalizer2`. `normalize/2` (NFD/NFKC/NFKD selector) is **not** pushed — the vendored ICU snapshot ships only NFC data. |
| **String — native emission** | `length`, `substring/{2,3}`, `replace`, `strpos`, `starts_with`, `lpad`, `rpad`, `concat_ws/{2..5}`, `translate`, `chr` | no (BARE) | Code-point (not byte / grapheme) semantics; pinned in fixtures against unicode, NULL, and edge inputs. **`lpad`/`rpad` push only with a constant, non-empty pad and a constant size ≥ 0** (Trino raises on empty pad / negative size; DuckDB returns `''`). **`substring` pushes only with a constant start ≥ 1, and for `/3` a constant length ≥ 0** (DuckDB treats start 0 as 1 and returns a slice for a negative length where Trino returns `''`). **`replace` pushes only with a constant, non-empty search string** (Trino's `replace('abc','','x')` = `'xaxbxcx'`; DuckDB no-op). **`concat_ws` pushes only when every argument is VARCHAR** (Trino's ARRAY overload joins elements; DuckDB would stringify the list — EV-A11). |
| **Numeric** | `abs`, `ceil`, `floor`, `mod`, `power`, `sqrt`, `exp`, `ln`, `log2`, `log10`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`, `sinh`, `cosh`, `tanh`, `degrees`, `radians`, `truncate`, `sign`, `pi/0`, `bitwise_and`, `bitwise_or`, `bitwise_not`, `bitwise_xor` | no | BARE, except RENAME `truncate→trunc`, `bitwise_xor→xor`; OPERATOR `bitwise_and/or/not`; INLINE `sqrt`/`ln`/`log2`/`log10`/`asin`/`acos` wrap in `CASE` so out-of-domain inputs yield Trino's `-Infinity` / `NaN` instead of DuckDB's error (EV-A12/A13). **`cbrt` is not pushed**: DuckDB's is inexact on perfect cubes (`cbrt(-27)` = `-3.0000000000000004`). **`mod` pushes only with a non-zero constant divisor** (Trino throws on zero, DuckDB returns NULL — EV-A12). **`bitwise_left_shift` / `bitwise_right_shift` are not pushed**: Trino's right shift is logical (zero-fill), DuckDB's `>>` is arithmetic; left shift wraps to width in Trino, throws for shift ≥ width in DuckDB (EV-A3). |
| **Regex** | `regexp_like/2`, `regexp_extract/{2,3}`, `regexp_replace/{2,3}` | no | **Not** "RE2 on both sides": Trino compiles with Joni (Java syntax), DuckDB with RE2. All three push **only with a constant pattern that passes the RE2-safe allowlist** (`Re2Safety`: no `$`, no `(?…)` other than `(?:`, no lookaround/backrefs/possessive quantifiers, no `\Z \G \v \h \R \u \0`, no nested/POSIX/`&&` classes; only general-category `\p{L}`-style properties — EV-A8). RENAME `regexp_like→regexp_matches`; INLINE `regexp_extract` guarded with `CASE WHEN regexp_matches(...)` so a non-match yields NULL as in Trino (DuckDB returns `''` — EV-A6); INLINE `regexp_replace` forces the `'g'` flag (2-arg uses `''`), and **`regexp_replace/3` also requires a constant replacement containing no `$` or `\`** (Trino group refs are `$1`, RE2's are `\1` — EV-A7). |
| **Encoding** | `to_hex`, `to_base64` | no | RENAME `to_hex→hex`; BARE `to_base64`. **`from_hex` / `from_base64` are not pushed**: DuckDB left-pads odd-length hex (`'abc'`→`0ABC`) where Trino errors, and errors on unpadded base64 (`'YWI'`) where Trino decodes. **`url_encode` / `url_decode` are not pushed**: Trino uses `application/x-www-form-urlencoded` rules (space→`+`, `*` kept, `~` encoded), DuckDB RFC 3986 (`a b*~` → `a+b*%7E` vs `a%20b%2A~`; `url_decode('a+b')` → `a b` vs `a+b` — EV-A5). |
| **Distance** | — | — | **`levenshtein_distance` / `hamming_distance` are not pushed**: DuckDB's operate on bytes (`levenshtein('äö','ab')` = 4; Trino 2). |
| **Hash** | `md5`, `sha1`, `sha256`, `sha512`, `xxhash64`, `hmac_sha256/2` | `sha512`, `xxhash64`, `hmac_sha256` (ALIAS) | `md5`/`sha1`/`sha256` are INLINE `unhex(<hash>(x))` (bare DuckDB hash + unhex, no extension). `sha512`, `xxhash64`, `hmac_sha256` are **native C++** (`hash_functions.cpp`) over vendored xxHash (BSD-2) + WjCryptLib SHA (public domain). `xxhash64` big-endian to match Trino; `hmac_sha256(data, key)` over raw VARBINARY bytes. |
| **Date / time** | `year`, `month`, `day`, `quarter`, `hour`, `minute`, `second`, `millisecond`, `day_of_week` (ISO), `day_of_year`, `last_day_of_month`, `week` / `week_of_year` (ISO), `year_of_week` / `yow`, `date_trunc/2`, `date_diff/3`, `to_unixtime`, `from_unixtime`, `with_timezone/2` | no | BARE (`year`…`second`, `date_trunc`, `week`), RENAME (`day_of_year→dayofyear`, `last_day_of_month→last_day`, `week_of_year→week`, `from_unixtime→to_timestamp`, **`date_diff→date_sub`** — Trino counts complete units, DuckDB's `date_diff` counts boundaries crossed; unit gated to `millisecond…year` — EV-A2), INLINE (`day_of_week→isodow`, `year_of_week`/`yow→isoyear`, **`millisecond→extract('millisecond') % 1000`** — DuckDB's part is sub-minute ms, EV-A1; `with_timezone` arg-flip), CONTEXTUAL **`to_unixtime`**: on a naive `TIMESTAMP` Trino interprets the value in the *session* zone while DuckDB's `epoch()` assumes UTC, so it emits `epoch(timezone('<session zone>', ts))` — **only when the session zone is a fixed offset** (UTC, `+05:00`); under a DST zone Trino and ICU resolve the ambiguous autumn hour to different offsets, so it stays in Trino (EV-A4/A13). On `TIMESTAMP WITH TIME ZONE` bare `epoch()`. Type-gated. Over `TIMESTAMP WITH TIME ZONE` they push only when `pushdown_timestamp_with_timezone` is on (**default on**). `date_trunc` on DATE input: DuckDB returns TIMESTAMP but auto-casts in comparisons, so pushed **results** stay DATE-aligned (fixture-pinned). |
| **Conditional** | `if/{2,3}` | no | INLINE `if/2→if(c, t, NULL)`; BARE `if/3`. |

## Not pushable (by design)

- `at_timezone(WTZ, varchar)` — DuckDB's TIMESTAMPTZ has no per-value zone metadata, so "rezone display" isn't expressible.
- `hmac_md5` / `hmac_sha1` / `hmac_sha512` — only `hmac_sha256` is ported natively; add the WjCryptLib primitives if a workload needs the others.
- `murmur3` — reconstructable from `murmurhash3_x64_128` but deferred (niche; needs a live Trino byte-layout confirmation). See TODO.
- `url_extract_*` (netquack) — rejected: DuckDB returns empty strings where Trino returns NULL, plus a `BIGINT`-vs-`VARCHAR` port mismatch.
- `normalize/2`, `position` (operator-form), `lower`/`upper` over collations.

## Adding an entry

Add a `(name, arity) → Emission` row to `EMISSION_STRATEGIES` in
`DuckBridgeEmissionCatalog` (and a `TYPE_GATES` row if the push must be restricted to certain
argument shapes), plus a per-entry fixture:

- **BARE / RENAME / OPERATOR / INLINE** (DuckDB matches Trino natively): choose the
  class, and add a semantic fixture to `SemanticFixtures` (evaluated against
  embedded DuckDB by `TestTrinoFunctionAliases.nonAliasSemanticFixtures`). No
  extension change needed — `testEveryNonAliasEntryHasAFixture` enforces coverage.
- **ALIAS** (DuckDB diverges, needs native C++): add the macro/function +
  `trino_meta()` row in the `trino_parity` extension repo, mark it `Emission.Alias`
  here, and it's covered by the `trino_meta() == ALIAS_FUNCTIONS` lockstep in
  `TestTrinoFunctionAliases.testAliasSetEqualsMeta` (strict equality since the
  extension shrank to exactly the 10 natives).
