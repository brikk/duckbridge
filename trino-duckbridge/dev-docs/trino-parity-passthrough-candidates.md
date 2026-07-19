# trino_parity passthrough candidates — can the connector use the bare name?

> **ANSWERED** (see [ANSWER-parity-passthrough-candidates.md](ANSWER-parity-passthrough-candidates.md)):
> Yes. The connector now emits natively for the 85 non-divergent entries
> (57 BARE + 11 RENAME + 5 OPERATOR + 12 INLINE) and routes only the 10 native
> C++ divergence-fixers through the extension. The `lpad`/`rpad` empty-pad and
> `substring` start caveats below are now enforced by the translator's gates.

## Question for the trino-duckbridge plugin

The `trino_parity` DuckDB extension registers 95 `trino_<name>(...)` functions.
**57 of them are pure passthroughs**: the macro body is literally
`name(args)` calling the *same-named* DuckDB built-in, with semantics already
aligned to Trino. They add nothing but the `trino_` prefix.

**Decision to make:** for these 57, can the connector push the **bare DuckDB /
Trino built-in name** (e.g. `abs(x)`, `year(x)`, `length(s)`) instead of the
`trino_` alias — i.e. drop them from the pushable set that requires the
extension? If yes, `trino_parity` only needs to ship the ~38 functions that
actually change behaviour, and the connector's dependency on the extension
shrinks to just those.

Per-function caveats are listed below — most are `none`, but a few
(`mod`, `substring`, `lpad`/`rpad`, `date_trunc`, the TIMESTAMPTZ extractors)
carry semantic notes the connector must still honour whether it calls the bare
name or the alias. Dropping the alias does **not** remove those obligations;
it just moves the call to the built-in name.

> NOTE: These 57 are passthroughs **only because the argument types stay in the
> aligned range.** The connector's existing type/arg gating (e.g. float `mod`,
> TIMESTAMP-WITH-ZONE extractors) must remain regardless of which name is emitted.

---

## The 57 pure passthroughs

Legend: `trino_*` name · arity · identical DuckDB built-in · caveat to preserve.

### String (19)

| trino_ function | arity | bare DuckDB built-in | caveat |
|---|---|---|---|
| `trino_length` | 1 | `length(s)` | none (code-point count, aligned) |
| `trino_substring` | 2 | `substring(s, start)` | verify `start = 0` handling before pushing zero (Trino undefined-ish; DuckDB treats as 1) |
| `trino_substring` | 3 | `substring(s, start, length)` | positive args aligned; same `start=0` note |
| `trino_replace` | 3 | `replace(s, search, replacement)` | none |
| `trino_strpos` | 2 | `strpos(s, sub)` | none (3-arg instance form is Trino-only, not offered) |
| `trino_starts_with` | 2 | `starts_with(s, prefix)` | none |
| `trino_lpad` | 3 | `lpad(s, size, padstring)` | empty pad-string behaviour differs (Trino raises; DuckDB NULL-ish) — avoid empty pad |
| `trino_rpad` | 3 | `rpad(s, size, padstring)` | same empty-pad note as `lpad` |
| `trino_concat_ws` | 2 | `concat_ws(sep, s1)` | NULL sep → NULL, NULL elements skipped — aligned |
| `trino_concat_ws` | 3 | `concat_ws(sep, s1, s2)` | same |
| `trino_concat_ws` | 4 | `concat_ws(sep, s1, s2, s3)` | same |
| `trino_concat_ws` | 5 | `concat_ws(sep, s1, s2, s3, s4)` | same |
| `trino_translate` | 3 | `translate(source, from, to)` | none |
| `trino_chr` | 1 | `chr(n)` | none (valid code points) |
| `trino_bit_length` | 1 | `bit_length(s)` | none |
| `trino_url_encode` | 1 | `url_encode(s)` | none (RFC 3986) |
| `trino_url_decode` | 1 | `url_decode(s)` | none |
| `trino_to_base64` | 1 | `to_base64(b)` | none (standard alphabet) |
| `trino_from_base64` | 1 | `from_base64(s)` | none |

### Numeric / math (25)

| trino_ function | arity | bare DuckDB built-in | caveat |
|---|---|---|---|
| `trino_abs` | 1 | `abs(x)` | none |
| `trino_ceil` | 1 | `ceil(x)` | none |
| `trino_floor` | 1 | `floor(x)` | none |
| `trino_mod` | 2 | `mod(n, m)` | **integer only** — float `mod` diverges (Trino IEEE-remainder vs DuckDB fmod); keep gating float out |
| `trino_power` | 2 | `power(x, y)` | none |
| `trino_sqrt` | 1 | `sqrt(x)` | none |
| `trino_exp` | 1 | `exp(x)` | none |
| `trino_ln` | 1 | `ln(x)` | none |
| `trino_log2` | 1 | `log2(x)` | none |
| `trino_log10` | 1 | `log10(x)` | use `log10` explicitly, NOT bare `log` (DuckDB `log` = log10 but Trino `log(b,x)` is base-b) |
| `trino_sin` | 1 | `sin(x)` | none |
| `trino_cos` | 1 | `cos(x)` | none |
| `trino_tan` | 1 | `tan(x)` | none |
| `trino_asin` | 1 | `asin(x)` | none |
| `trino_acos` | 1 | `acos(x)` | none |
| `trino_atan` | 1 | `atan(x)` | none |
| `trino_atan2` | 2 | `atan2(y, x)` | none |
| `trino_sinh` | 1 | `sinh(x)` | none |
| `trino_cosh` | 1 | `cosh(x)` | none |
| `trino_tanh` | 1 | `tanh(x)` | none |
| `trino_degrees` | 1 | `degrees(x)` | none |
| `trino_radians` | 1 | `radians(x)` | none |
| `trino_cbrt` | 1 | `cbrt(x)` | none |
| `trino_sign` | 1 | `sign(x)` | none (NaN→NaN aligned) |
| `trino_pi` | 0 | `pi()` | none |

### Regex (2)

| trino_ function | arity | bare DuckDB built-in | caveat |
|---|---|---|---|
| `trino_regexp_extract` | 2 | `regexp_extract(s, pattern)` | none (RE2 both sides; group 0 = whole match in both) |
| `trino_regexp_extract` | 3 | `regexp_extract(s, pattern, group)` | none — DuckDB reserves `group` keyword; positional call is fine |

### Date / time (10)

| trino_ function | arity | bare DuckDB built-in | caveat |
|---|---|---|---|
| `trino_year` | 1 | `year(x)` | TIMESTAMPTZ input is session-`TimeZone` dependent (same as alias) |
| `trino_month` | 1 | `month(x)` | same TZ note |
| `trino_day` | 1 | `day(x)` | same TZ note |
| `trino_quarter` | 1 | `quarter(x)` | same TZ note |
| `trino_date_trunc` | 2 | `date_trunc(unit, x)` | DuckDB returns TIMESTAMP even for DATE input (Trino preserves DATE); auto-cast keeps numeric comparisons aligned |
| `trino_date_diff` | 3 | `date_diff(unit, t1, t2)` | boundary-count semantics aligned; unit set = second..year intersection |
| `trino_week` | 1 | `week(d)` | none (DuckDB bare `week()` is already ISO-aligned) |
| `trino_hour` | 1 | `hour(t)` | TIMESTAMPTZ input session-TZ dependent |
| `trino_minute` | 1 | `minute(t)` | same TZ note |
| `trino_second` | 1 | `second(t)` | same TZ note |

### Conditional (1)

| trino_ function | arity | bare DuckDB built-in | caveat |
|---|---|---|---|
| `trino_if` | 3 | `if(cond, t, f)` | none (2-arg `trino_if` is NOT a passthrough — it injects `NULL`, keep the alias) |

---

## For contrast: the ~38 that are NOT passthroughs (keep the `trino_` alias)

These carry real parity value and must keep routing through the extension:

- **Native C++ (10):** `lower`, `upper`, `reverse`, `trim`, `ltrim`, `rtrim`,
  `normalize` (ICU); `xxhash64`, `sha512`, `hmac_sha256` (vendored libs).
- **Semantic transforms (12):** `regexp_replace/2`, `regexp_replace/3` (`'g'`
  flag), `md5`, `sha1`, `sha256` (`unhex()` wrap), `if/2` (NULL fill),
  `day_of_week` (`isodow`), `year_of_week`, `yow` (`isoyear`), `millisecond`,
  `to_unixtime` (casts), `with_timezone` (arg-order flip).
- **Pure renames (11):** `to_hex→hex`, `from_hex→unhex`,
  `levenshtein_distance→levenshtein`, `hamming_distance→hamming`,
  `truncate→trunc`, `bitwise_xor→xor`, `regexp_like→regexp_matches`,
  `day_of_year→dayofyear`, `last_day_of_month→last_day`,
  `week_of_year→week`, `from_unixtime→to_timestamp`. (Bare name differs, so the
  connector would still need a rename map — but not the extension.)
- **Operator bridges (5):** `bitwise_and/or/not/left_shift/right_shift` →
  `&`, `|`, `~`, `<<`, `>>`.
