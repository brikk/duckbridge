# ANSWER — "why register 95 functions when most do nothing?"

**To:** the DuckDB / `trino_parity` extension maintainers
**From:** the trino-duckbridge connector
**Re:** [trino-parity-passthrough-candidates.md](trino-parity-passthrough-candidates.md)

You asked why the connector registers/depends on 95 `trino_<name>` functions when
most of them are pure passthroughs that add nothing but a prefix. You were right.
As of this change the connector **emits natively for everything DuckDB already
matches, and routes only the 10 genuine divergence-fixers through the extension.**

## The decision

"Alias only what diverges." The connector's expression translator
(`DuckBridgeExpressionTranslator`) now carries an **emission strategy per pushable
`(name, arity)`** (`EMISSION_STRATEGIES`). Each entry falls into one of five
classes; only the last touches the extension.

## The five emission classes

| Class | Count | What it emits | Needs extension? |
|---|---|---|---|
| **BARE** | 57 | the same bare DuckDB built-in — `length(s)`, `abs(x)`, `year(x)` | no |
| **RENAME** | 11 | a different bare built-in name — `to_hex→hex` | no |
| **OPERATOR** | 5 | a parenthesized operator — `bitwise_and→(a & b)` | no |
| **INLINE** | 12 | a fixed SQL transform — `md5→unhex(md5(x))` | no |
| **ALIAS** | 10 | the extension's `trino_<name>(...)` | **yes** |

Total: **95** (== `trino_meta()`).

### BARE (57) — bare passthrough built-ins
String: `length`, `substring/{2,3}`, `replace`, `strpos`, `starts_with`, `lpad`,
`rpad`, `concat_ws/{2..5}`, `translate`, `chr`, `bit_length`, `url_encode`,
`url_decode`, `to_base64`, `from_base64`.
Numeric: `abs`, `ceil`, `floor`, `mod`, `power`, `sqrt`, `exp`, `ln`, `log2`,
`log10`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`, `sinh`, `cosh`,
`tanh`, `degrees`, `radians`, `cbrt`, `sign`, `pi/0`.
Regex: `regexp_extract/{2,3}`.
Date: `year`, `month`, `day`, `quarter`, `date_trunc/2`, `date_diff/3`, `week`,
`hour`, `minute`, `second`.
Conditional: `if/3`.

### RENAME (11) — a different bare built-in name
`to_hex→hex`, `from_hex→unhex`, `levenshtein_distance→levenshtein`,
`hamming_distance→hamming`, `truncate→trunc`, `bitwise_xor→xor`,
`regexp_like→regexp_matches`, `day_of_year→dayofyear`,
`last_day_of_month→last_day`, `week_of_year→week`, `from_unixtime→to_timestamp`.

### OPERATOR (5) — parenthesized operators
`bitwise_and→(a & b)`, `bitwise_or→(a | b)`, `bitwise_not→(~a)`,
`bitwise_left_shift→(a << b)`, `bitwise_right_shift→(a >> b)`.

### INLINE (12) — SQL transforms (verified against `macro_definitions.cpp`)
- `regexp_replace/2 → regexp_replace(s, p, '', 'g')`
- `regexp_replace/3 → regexp_replace(s, p, r, 'g')`
- `md5 → unhex(md5(x))`, `sha1 → unhex(sha1(x))`, `sha256 → unhex(sha256(x))`
- `if/2 → if(c, t, NULL)`
- `day_of_week → isodow(x)`
- `year_of_week → CAST(extract('isoyear' FROM x) AS BIGINT)`,
  `yow → CAST(extract('isoyear' FROM x) AS BIGINT)`
- `millisecond → CAST(extract('millisecond' FROM x) AS BIGINT)`
- `to_unixtime → CAST(epoch(x) AS DOUBLE)`
- `with_timezone(ts, zone) → timezone(zone, ts)` (arg-order flip)

Each INLINE body was copied from the extension's own macro body, not guessed —
every one was cross-checked against `macro_definitions.cpp` in this repo. None had
to stay ALIAS for lack of a verifiable body.

## The keep-list — the 10 the extension MUST keep

These are the only entries the connector still routes through `trino_<name>(...)`,
because DuckDB's built-in cannot match Trino natively:

- **ICU-backed (7):** `lower`, `upper`, `reverse`, `trim`, `ltrim`, `rtrim`,
  `normalize/1` — root-locale full case folding, code-point reverse,
  `Character.isWhitespace`-aligned trim, NFC.
- **Vendored crypto (3):** `xxhash64` (big-endian to match Trino), `sha512`,
  `hmac_sha256/2` (raw-VARBINARY key/message).

## The removable list — the other 85

Everything in BARE / RENAME / OPERATOR / INLINE above (85 entries). The connector
no longer needs the extension to ship them. You may drop them at your leisure.

## Coordination protocol

1. **Step 1 (connector, done):** the connector emits BARE/RENAME/OPERATOR/INLINE
   natively and routes only the 10 through the extension, tolerating the extension
   still shipping all 95 via a subset drift check.
2. **Step 2 (extension, done at `0d531cc`):** dropped the 85 non-ALIAS macros;
   `trino_meta()` narrowed to catalog exactly the 10 natives.
3. **Step 3 (connector, done):** the drift check is re-pinned to strict equality —
   `trino_meta() == ALIAS_FUNCTIONS` (`TestTrinoFunctionAliases.testAliasSetEqualsMeta`),
   so an extra or missing entry on either side is a hard failure again. The submodule
   pin was bumped to `0d531cc` and the bundled binary rebuilt from it.

Because the correctness of the 85 native emissions is proved by per-entry semantic
fixtures against embedded DuckDB (`TestTrinoFunctionAliases.nonAliasSemanticFixtures`,
one fixture per entry, re-run on every DuckDB pin bump), the connector does not need
the extension to vouch for them.

## Two pre-existing caveat holes, now gated

While doing this we enforced two obligations the passthrough-candidates doc flagged
that were **not actually gated** before:

- **`lpad`/`rpad` empty pad:** Trino raises on an empty pad string; DuckDB does not.
  The connector now pushes `lpad`/`rpad` **only when the pad argument is a constant,
  non-empty string** — a non-constant (could be empty at runtime) or empty literal
  stays above the scan for Trino to evaluate (and raise).
- **`substring` start:** DuckDB treats `start = 0` as `1`; Trino differs. The
  connector now pushes `substring/{2,3}` **only when the start argument is a constant
  integer ≥ 1**. Non-constant or `< 1` starts stay above the scan. (Conservative; can
  relax if a fixture later proves broader alignment.)
