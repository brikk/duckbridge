# Probe P1 — Doris ↔ DuckDB function-divergence audit

**Date:** 2026-07-19
**Doris:** compose FE (branch-catalog-spi, patched) — FE constant-folds `SELECT f(...)`, and **FE
semantics ARE Doris semantics**, so the FE is the Doris oracle.
**DuckDB:** `v1.5.4` in the compose quack container (`duckdb` CLI), the pinned engine.
**Harness:** a throwaway three-way runner (`/tmp/opencode/audit_harness.py`, discarded) executed each
candidate on both engines across the fixture matrix and diffed. This report is the parity authority
for the Doris side; Stage B (`DuckBridgeQueryBuilder` function allowlist) admits **only** the
IDENTICAL entries below, each pinned by a fixture test (drift canary).

## Comparison rules (stated, not hand-waved)

- **String-returning functions** are compared as **`hex(UTF-8 bytes)`** (`hex(f(x))` on Doris,
  `to_hex(encode(f(x)))` on DuckDB). This is byte-exact and immune to the mysql client's display
  escaping of control chars (a `\t` in output is otherwise indistinguishable from a literal
  backslash-t). Several apparent "divergences" (trim/reverse over tab/LF) were **this artifact** and
  resolved to IDENTICAL once hex-compared.
- **NULL** normalized: both engines print `NULL` (DuckDB `-list` too); mapped to a `<NULL>` sentinel.
  `hex(NULL)`/`to_hex(encode(NULL))` = NULL on both.
- **Booleans** normalized: Doris prints `1`/`0`, DuckDB `true`/`false`; mapped to `1`/`0`.
- **Floats** compared to **15 significant digits** (`%.15g`) — the shared safe IEEE-double precision.
  A difference only beyond 15 sig-digits is engine print noise; a difference **within** 15 is a real
  divergence (and makes the function unsafe to push into an `=` predicate — see `power`).
- **Fixture construction caveat (learned):** Doris `char(769)` emits a **raw byte `0x0301`** while
  DuckDB `chr(769)` emits proper UTF-8 `0xCC81` — so `char()`/`chr()` are NOT interchangeable for
  building test strings. All decomposed/control-char fixtures embed **real UTF-8 literals** (byte-
  identical to both engines), never `char()`/`chr()`.

## Fixture matrix

Per string function: `ascii`, `empty`, SQL `NULL`, `café`(precomposed U+00E9), `café`(**decomposed**
`cafe`+U+0301), `straße`, `δοκιμή`(Greek), `İstanbul`(Turkish dotted-I), `a😀b`(astral/emoji),
`  hi  `(spaces), `\thi\t`(tabs), `\nhi\n`(LF). Numeric: pos/neg/zero/large/NULL + boundary cases
(half-way rounding, mod signs, `0^0`, negative base). Date: a datetime, a leap-day, Y2000, NULL.

---

## Statistics

**38 candidates audited: 31 IDENTICAL, 7 DIVERGENT, 0 untypable-skip.**

| Category | audited | identical | divergent |
|---|---|---|---|
| String | 18 | 12 | 6 (`length`-bytes, `lower`, `upper`, `reverse`, `substring/3`@start0, `concat`) |
| Numeric | 13 | 12 | 1 (`power` — print precision) |
| Date | 6 | 6 | 0 |
| Conditional | 3 | 3 | 0 |

---

## The interesting divergences (all REAL, byte-characterized)

### `length` — **bytes (Doris) vs code-points (DuckDB)** → DIVERGENT as-named, but MAPPABLE
Doris `length('straße')` = **7** (UTF-8 bytes); DuckDB `length('straße')` = **6** (code points).
**But the alignment exists under a rename:**
- Doris **`length`** (bytes) ≡ DuckDB **`strlen`** (bytes) — verified aligned on all fixtures.
- Doris **`character_length`** (code points; this is what Doris normalizes `char_length` to) ≡
  DuckDB **`length`** (code points) — verified aligned on all fixtures.

So both push, each with the **cross-name** rename. This is the audit's headline mapping: a naive
`length`→`length` push would be a silent byte-vs-codepoint correctness bug.

### `lower` — Turkish dotted-I → DIVERGENT (needs extension)
`lower('İstanbul')`: Doris `69 CC87 73...` (`i` + combining dot above = `i̇`, full Unicode fold) vs
DuckDB `69 73...` (`istanbul`, simple fold dropping the dot). Diverges on `İ`. ASCII + café + straße +
greek + emoji all aligned. Not pushable. A `doris_lower` ICU macro could fix it (native ICU full
fold) — **candidate for a future `duckdb-doris-parity` extension**, not worth it yet (niche input).

### `upper` — German sharp-s → DIVERGENT (needs extension)
`upper('straße')`: Doris `...53 53...` (`SS`) vs DuckDB `...E1 BA 9E...` (`ẞ` = U+1E9E capital sharp
s). Diverges on `ß`. Same shape as the Trino side. Not pushable; `doris_upper` ICU macro candidate.

### `reverse` — code-point (Doris) vs grapheme-cluster (DuckDB) → DIVERGENT (needs extension)
`reverse('cafe'+U+0301)`: Doris `CC81 65 66 61 63` (combining mark moves to front — pure code-point
reverse) vs DuckDB `65 CC81 66 61 63` (keeps `e`+combining together — grapheme-aware). Diverges on
any combining-mark / ZWJ input. Not pushable; needs a code-point-reverse macro.

### `substring/3` — start = 0 → DIVERGENT, but PUSHABLE with a constant-start≠0 guard
`substring('hello', 0, 3)`: Doris **NULL** vs DuckDB `'he'`. **Every other case aligned** — including
`start = 2` (1-based, code-point) across café/straße/greek/emoji, AND **`start = -2`** (both return
`lo`). So `substring/{2,3}` is pushable **only when the start literal is a constant ≠ 0**. (Doris
treats 0 as "no result / NULL"; DuckDB clamps 0→1.)

### `concat` — NULL handling → DIVERGENT
`concat('x', NULL)`: Doris **NULL** (NULL-propagates) vs DuckDB `'x'` (skips NULL). Same divergence
the Trino side found. Not pushable as `concat`. (The Trino connector pushes it as the `||` operator,
which propagates on both — but Doris hands us a `concat` function node, not `||`, and we have no
proof the `||` rewrite is what Doris meant; hold until a real workload needs it.)

### `power` — print precision → DIVERGENT (conservative)
`power(2, 0.5)`: Doris prints `1.414213562373095` (16 digits), DuckDB `1.4142135623730951` (17). They
differ at the 15th significant digit under `%.15g` (`1.41421356237309` vs `1.4142135623731`). Whether
the underlying IEEE double is identical can't be proven from the text, and a `WHERE power(x,y) =
<literal>` predicate is print-precision-fragile. **Conservatively DIVERGENT / not pushed.** Integer-
exact cases (`2^10`, `(-2)^3`, `0^0`) align. (This is why the allowlist excludes ALL float-**returning**
functions from predicate pushdown — see Stage B rationale.)

---

## Full IDENTICAL set (31) — the pushable candidates

**String (12):** `char_length`→`length`, `length`→`strlen`, `trim`, `ltrim`, `rtrim`, `substring/2`,
`substring/3` (start≠0), `replace`, `strpos`/`locate`, `instr`, `starts_with`, `lpad`, `rpad`.
**Numeric (12):** `abs`, `ceil`, `floor`, `sign`, `sqrt`, `exp`, `ln`, `log10`, `round/1`, `round/2`,
`mod`, (and integer-exact `power` cases — but `power` held, see above).
**Date (6):** `year`, `month`, `day`, `hour`, `minute`, `second`.
**Conditional (3):** `coalesce/2`, `nullif`, `if/3`.

### Doris→DuckDB name / arg mappings (part of the audit output)
The FE hands the connector these exact `getFunctionName()` values (confirmed via `EXPLAIN VERBOSE`
`PREDICATES:`), which differ from DuckDB's spelling:

| Doris (FE emits) | Return | DuckDB rendering | Note |
|---|---|---|---|
| `character_length` (← `char_length`) | INT | `length(x)` | code-point count |
| `length` | INT | `strlen(x)` | **byte count** — NOT DuckDB `length` |
| `locate(needle, hay)` | INT | `strpos(hay, needle)` | **arg order swapped** |
| `instr(hay, needle)` | INT | `instr(hay, needle)` | same order |
| `substring(s, start[, len])` | VARCHAR | `substring(s, start[, len])` | **only if start literal ≠ 0** |
| `year`/`month`/`day`/`hour`/`minute`/`second` | INT/SMALLINT | same | over DATE/DATETIME |
| `abs`/`ceil`/`floor`/`sign`/`round`/`mod` | numeric | same | integer-exact for INT inputs |
| `upper`/`lower`/`trim`/`ltrim`/`rtrim`/`replace`/`starts_with`/`lpad`/`rpad` | VARCHAR/BOOL | same | (upper/lower are DIVERGENT — excluded) |

---

## Stage-B allowlist decision (what actually gets wired)

The audit's IDENTICAL set is larger than what v1 wires. The wiring rule is **conservative beyond
"identical"**:

1. **Exclude float-RETURNING functions from predicate pushdown** (`sqrt`, `exp`, `ln`, `log10`,
   `power`, non-integer `round`). Even when identical to 15 sig-digits, a float result inside a
   `WHERE f(x) = <literal>` is print-precision-fragile across the wire — a mismatch loses rows
   irrecoverably (FE retention protects over-return, not under-return). These functions are rarely
   used inside filters anyway. **Held, not divergent — a deliberate pushdown-safety exclusion.**
2. **Admit integer/string/boolean-returning IDENTICAL functions** — their results compare exactly.
3. **Honor the guards the audit found:** `substring` only with a constant start literal ≠ 0;
   `character_length`→`length`, `length`→`strlen`, `locate`→`strpos`(arg-swap) renames baked in.

**v1 allowlist (10 entries):**

| Doris name / arity | DuckDB rendering | verdict source |
|---|---|---|
| `character_length/1` | `length(x)` | IDENTICAL (code-point count) |
| `length/1` | `strlen(x)` | IDENTICAL (byte count) |
| `substring/2` | `substring(x, start)` (start≠0) | IDENTICAL (start≠0) |
| `substring/3` | `substring(x, start, len)` (start≠0) | IDENTICAL (start≠0) |
| `locate/2` | `strpos(hay, needle)` (arg swap) | IDENTICAL |
| `instr/2` | `instr(hay, needle)` | IDENTICAL |
| `starts_with/2` | `starts_with(x, prefix)` | IDENTICAL |
| `abs/1` | `abs(x)` | IDENTICAL |
| `year/1` `month/1` `day/1` `hour/1` `minute/1` `second/1` | same | IDENTICAL |

`trim/ltrim/rtrim/replace/lpad/rpad` are IDENTICAL and could be added, but v1 holds them one more
beat: `replace`/`lpad`/`rpad` want a couple more edge fixtures (empty pad, NULL args) before wiring,
and `trim` family wants confirmation of the full Doris whitespace set beyond tab/LF/space. They're on
the **grow-next** list, not a divergence — the allowlist grows entry-by-entry.

## Extension candidates (documented, NOT built)

If a workload needs case-insensitive or reverse pushdown, a thin `duckdb-doris-parity` extension
(sibling to `trino_parity`, per the plan) could add:
- `doris_lower` / `doris_upper` — ICU full case fold matching Doris (`İ`→`i̇`, `ß`→`SS`).
- `doris_reverse` — code-point reverse (ICU `u_strReverse`) matching Doris.
Each is a ~single native scalar. **Not worth building for v1** (niche inputs; the domain floor +
the 10 wired scalars cover the common filters). Zero macros remains the v1 outcome — exactly the
plan's "alias only what diverges, and only if worth it."
