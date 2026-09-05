# TODO: rectify findings from the 2026-09-02 quality/correctness evaluation

Tracker for the issues surfaced by the external evaluation of `trino-duckbridge`.
Each item is labelled `EV-nn` so it can be picked up and closed one at a time;
tick the box and add a one-line resolution note (commit, or "won't fix: reason")
when done. Order within a section is roughly severity.

**Verification method used by the evaluation.** Trino-side values were computed
through the repo's own query runner (Trino 483, `DuckBridgeQueryRunner`); DuckDB-side
values against the bundled `duckdb_jdbc 1.5.5.0`. Both engines were run, not
recalled from docs. Re-run the same pair before closing any EV-A item.

**Scope note.** Every function in section A is a `Bare`/`Rename`/`Operator`/`Inline`
emission (`DuckBridgeExpressionTranslator.kt:109-223`) — plain DuckDB SQL that never
touches the `trino_parity` extension and therefore pushes with or without it. The 10
`Emission.Alias` natives (`:98-107`) were audited in a follow-up — see section E.

Mode exposure column: "all" = pushes in every string-pushdown mode including
`NULL_ONLY`/`GUARDED` (the conjunct does not compare a string operand,
`:512`, `:612-613`); "≥BINARY" = pushes in `BINARY`/`FULL`/`PARITY` (the default).

---

## A. Wrong-result divergences (pushed SQL returns different rows than Trino)

Fix pattern per item: either **rewrite** the emission so DuckDB matches Trino, **gate**
it (new `TYPE_GATES` entry, `:230-269`, same style as `lpad`/`substring`) so it only
pushes where the engines agree, or **remove** the entry. Whichever is chosen, add the
divergent input to the fixture corpus (see EV-B1) so it stays red until fixed.

- [x] **EV-A1 `millisecond/1`** — `:218` emits `extract('millisecond' FROM x)`, which in
  DuckDB is *sub-minute* milliseconds (0..59999), not millis-of-second.
  Trino `millisecond(TS '2024-01-01 00:00:05.123')` = `123`; DuckDB = `5123`.
  The inline comment ("0..999") is wrong. Mode: all.
  Fix: `CAST(extract('millisecond' FROM x) % 1000 AS BIGINT)` (verify), fixture with seconds ≠ 0.

- [x] **EV-A2 `date_diff/3`** — `:170` `Bare`. DuckDB `date_diff` counts *partition
  boundaries crossed*; Trino counts *complete units elapsed*.
  `date_diff('month', DATE '2020-01-31', DATE '2020-02-01')`: Trino `0`, DuckDB `1`.
  `date_diff('day', TS '2020-01-01 23:00', TS '2020-01-02 01:00')`: Trino `0`, DuckDB `1`.
  `date_diff('year', DATE '2020-12-31', DATE '2021-01-01')`: Trino `0`, DuckDB `1`.
  Only `'day'` on two `DATE`s agrees (the one case the fixture covers, `SemanticFixtures.kt:152`).
  Mode: all. Fix: evaluate DuckDB `date_sub(part, start, end)` (complete-unit semantics)
  as a `Rename`; verify every unit Trino accepts incl. `week`/`quarter`/`millisecond`; else remove.

- [x] **EV-A3 `bitwise_right_shift/2`** — `:195` emits `(x >> n)`. Trino's
  `bitwise_right_shift` is a *logical* (zero-fill) shift; DuckDB `>>` is arithmetic.
  `bitwise_right_shift(BIGINT '-8', 1)`: Trino `9223372036854775804`, DuckDB `-4`.
  Mode: all. Fix: remove (a width-correct unsigned rewrite per integer type is possible
  but fiddly; Trino also has `bitwise_right_shift_arithmetic` if the arithmetic form is wanted).

- [x] **EV-A4 `to_unixtime/1` on `TIMESTAMP` (no tz)** — `:220` emits `CAST(epoch(x) AS DOUBLE)`.
  Trino interprets a naive timestamp in the *session* zone; DuckDB `epoch(TIMESTAMP)`
  treats it as UTC and ignores `SET TimeZone` (verified after `SET TimeZone='America/New_York'`).
  `to_unixtime(TS '1970-01-01 00:00:01')` in the test session: Trino `39601.0`, DuckDB `1.0`.
  Wrong for every non-UTC session. Mode: all.
  Fix: emit `CAST(epoch(timezone('<session zone>', x)) AS DOUBLE)` using the same
  normalised zone `applySessionTimeZone` sets, or restrict the `argTier(0)` gate (`:237`)
  to `TIMESTAMP WITH TIME ZONE` only. Fixture must run under a non-UTC session zone.

- [x] **EV-A5 `url_encode/1`, `url_decode/1`** — `:126-127` `Bare`. Trino uses
  `application/x-www-form-urlencoded` rules (space→`+`, `*` kept, `~` encoded); DuckDB is
  RFC 3986 percent-encoding.
  `url_encode('a b*~')`: Trino `a+b*%7E`, DuckDB `a%20b%2A~`.
  `url_decode('a+b')`: Trino `a b`, DuckDB `a+b`.
  `SemanticFixtures.kt:105` currently asserts the *DuckDB* answer (`a%20b`) as if it were Trino's.
  Mode: ≥BINARY (result compared to a string). Fix: remove both; or implement as `Alias`
  natives in the extension if pushdown is wanted.

- [x] **EV-A6 `regexp_extract/2`, `/3` no-match** — `:157-158` `Bare`. Trino returns `NULL`
  on no match; DuckDB returns `''`.
  `regexp_extract('abc','x') IS NULL`: Trino `true`, DuckDB `false`.
  `IS NULL` form pushes in all modes; `= ''` form ≥BINARY.
  Fix: `CASE WHEN regexp_matches(s, p) THEN regexp_extract(s, p[, g]) END`
  (NOT `nullif(.., '')` — a genuine empty match must stay `''`). Also subject to EV-A8.

- [x] **EV-A7 `regexp_replace/2`, `/3` replacement syntax** — `:203-204`. Trino replacement
  uses `$1`/`${name}` group references; DuckDB (RE2) uses `\1`.
  `regexp_replace('abc','(b)','[$1]')`: Trino `a[b]c`, DuckDB `a[$1]c`.
  Mode: ≥BINARY. Fix: gate on a `Constant` replacement containing neither `$` nor `\`
  (translate `$n`→`\n` only if you are prepared to prove the full escaping grammar).
  Also subject to EV-A8.

- [x] **EV-A8 regex dialect (Joni/Java vs RE2)** — affects `regexp_like` (`:182`),
  `regexp_extract`, `regexp_replace`. Silent divergence found: `$` matches before a
  trailing `\n` in Java, not in RE2 — `regexp_like('abc\n', 'c$')`: Trino `true`,
  DuckDB `false`. (`\w` on `é` was checked and agrees: both `false`.) Loud divergence:
  lookaround/backreferences/possessive quantifiers error in RE2 where Trino succeeds.
  `regexp_like` is a boolean call, so it pushes in all modes.
  Fix: gate on a `Constant` pattern that passes an RE2-safe allowlist (no `$` unless
  `(?m)`-explicit or trailing-`\n` handled, no `(?=`/`(?!`/`(?<`, no `\1`..`\9`, no `*+`/`++`/`?+`,
  no `\Z`/`\z`/`\G`, no `\p{}` names RE2 lacks), else leave in Trino.

- [x] **EV-A9 `TRY_CAST(varchar AS int/bigint/…/date/boolean)`** — `:551`, `:716-727`.
  DuckDB's string parsers are more lenient, so `TRY_CAST` yields a value where Trino yields `NULL`:
  `TRY_CAST('1.0' AS INTEGER)`: Trino `NULL`, DuckDB `1`;
  `TRY_CAST('2020/01/01' AS DATE)`: Trino `NULL`, DuckDB a date;
  `TRY_CAST('yes' AS BOOLEAN)`: Trino `NULL`, DuckDB `true`.
  (`' 1 '`→`1` agrees.) Mode: all (`IS NULL` / numeric compare).
  Plain `CAST` from varchar is error-vs-result (Trino throws, DuckDB returns) — see EV-A12.
  Fix: in `translateCast`, refuse `TRY_CAST` when the operand type is `VarcharType`/`CharType`.

- [x] **EV-A10 `CAST(double AS VARCHAR)`** — `:549`, `:723`. Java `Double.toString` vs DuckDB
  formatting: `CAST(DOUBLE '1e7' AS VARCHAR)`: Trino `1.0E7`, DuckDB `10000000.0`;
  `1e20` → `1.0E20` vs `1e+20`. Agrees only for ~`1e-3 ≤ |x| < 1e7`. Mode: ≥BINARY.
  Fix: in `translateCast`, refuse `VARCHAR` target when the operand is `DoubleType`/`RealType`
  (check `REAL`, `DECIMAL`, `TIMESTAMP` → `VARCHAR` too before allowing any of them).

- [x] **EV-A11 `concat_ws/2..5` with an ARRAY argument** — `:119-122` `Bare`, no type gate.
  Trino's `concat_ws(sep, ARRAY[...])` overload joins elements; DuckDB stringifies the list.
  `concat_ws(',', ARRAY['a','b'])`: Trino `a,b`, DuckDB `[a, b]`. Arrays are mapped
  (`DuckBridgeArrayColumnMapping`) so this is reachable. Mode: ≥BINARY.
  Fix: `TYPE_GATES` entry requiring every argument to be `VarcharType`.

- [x] **EV-A12 error-vs-result divergences** — Trino fails the query where DuckDB silently
  returns rows (dropping or keeping them), or vice versa:
  - `mod/2` (`:134`): `mod(5, 0)` Trino throws `/ by zero`, DuckDB `NULL` → row silently dropped.
    Contradicts the stated reason `$modulo` is NOT pushed (`:747-751`). Mode: all.
    Fix: gate divisor to a non-zero `Constant` (same shape as `constIntArgAtLeast`), or remove.
  - `ln/1`, `log2/1`, `log10/1`, `sqrt/1` (`:136-140`): Trino returns `-Infinity`/`NaN`,
    DuckDB throws `Out of Range` → pushdown makes a previously-working query fail. Mode: all.
    Decide: accept as fail-loud and document, or gate/remove.
  - `bitwise_left_shift/2` (`:194`): `bitwise_left_shift(BIGINT '1', 64)` Trino `0`, DuckDB throws.
  - `CAST(varchar AS int/date/boolean)`: Trino throws on `'1.0'`/`'2020/01/01'`/`'yes'`, DuckDB returns.
  - RE2-unsupported syntax (see EV-A8): DuckDB throws, Trino succeeds.

**Resolution of EV-A1..A12 (2026-09-02, commits `2639822` Tier 1 + Tier 2).** Rewrites:
`millisecond` (`% 1000`), `date_diff→date_sub` (unit-gated), `to_unixtime` (`epoch(timezone(<zone>,
ts))`, fixed-offset zones only — see EV-A13), `regexp_extract` (`CASE WHEN regexp_matches`),
`sqrt`/`ln`/`log2`/`log10` were initially rewritten with `CASE` → NaN / -Infinity, then **removed by
EV-B2** when end-to-end WHERE tests proved scalar equality was insufficient. Gates: regex pattern must be a constant
passing `Re2Safety`; `regexp_replace/3` replacement constant without `$`/`\`; `mod` non-zero
constant divisor; `concat_ws` all-VARCHAR; `CAST`/`TRY_CAST` refuse string→non-string and
→VARCHAR from anything but integer/BOOLEAN/DATE. Removed: `url_encode`, `url_decode`,
`bitwise_left_shift`, `bitwise_right_shift`. Catalog split into `DuckBridgeEmissionCatalog`;
`README-pushdown-reference.md` corrected. See EV-B2 for the final NaN-domain disposition.

- [x] **EV-A13 divergences surfaced by the Trino-computed fixtures (EV-B1)** — the new harness
  found these on its first run; all fixed in the Tier 2 commit. Trino vs DuckDB:
  - `substring('hello', 2, -1)`: `''` vs `'h'` → `substring/3` gate: constant length ≥ 0.
  - `replace('abc', '', 'x')`: `'xaxbxcx'` vs `'abc'` → gate: constant non-empty search string.
  - `lpad('abc', -1, 'x')`: error vs `''` → `lpad`/`rpad` gate: constant size ≥ 0 (plus existing pad gate).
  - `levenshtein_distance('äö','ab')`: `2` vs `4`; `hamming_distance` same inputs: `2` vs error —
    DuckDB's are byte-based → both **removed**.
  - `asin(2.0)` / `acos(-2.0)`: `NaN` vs error → initially a `CASE` rewrite; **removed by EV-B2**
    because DuckDB and Trino's runtime filter path order the resulting NaN differently.
  - `cbrt(-27.0)`: `-3.0` vs `-3.0000000000000004` → **removed** (equality predicates would miss).
  - `to_unixtime(TIMESTAMP '2024-11-03 01:30:00')` in `America/New_York`: `1730611800` (Trino: earlier
    offset) vs `1730615400` (ICU: later offset) → the EV-A4 rewrite is now emitted **only for
    fixed-offset session zones** (UTC, `+HH:MM`); DST zones stay in Trino. The spring-gap hour agreed.
  - VARCHAR constant containing U+0000: Trino fine, DuckDB "unterminated quoted string" (the translator
    wrote the raw NUL into the literal) → `translateConstant` declines NUL-bearing constants.
  - `from_hex('abc')`: error vs `0ABC` (DuckDB pads odd length); `from_base64('YWI')`: decodes vs error
    (DuckDB requires padding) → both **removed** (VARBINARY-shaped, unreachable anyway per EV-C5).
  - `bit_length` is not a Trino function ("Function 'bit_length' not registered") → dead entry removed.
  Net after EV-A13 was 85 entries; EV-B2 removed 6 NaN-domain functions, leaving **79**
  (43 Bare, 9 Rename, 3 Operator, 14 Inline/Contextual, 10 Alias), down from 95.

## B. Test methodology

- [x] **EV-B1 fixtures are not cross-engine** — **done (Tier 2 commit).** `SemanticFixtures` +
  `TestPushdownSemanticFixtures`: every fixture is a Trino `Call` that is (1) rendered to Trino SQL
  and evaluated on the Trino query runner — the authoritative expected value, never hand-written —
  and (2) run through the production translator and executed on DuckDB; outcomes must be identical
  (value, or both engines error). `NotPushed` fixtures pin every gate. Both engines run in a non-UTC
  session zone (`America/New_York`). 396 current cases: string/numeric/regex/date-time/encoding/cast edge
  corpus for all 75 native entries + the section-E ALIAS corpus (case mapping, whitespace classes,
  NFC, hashes incl. >64-byte HMAC keys). `testEveryNonAliasEntryHasAFixture` still enforces
  coverage. First run found EV-A13 (10 new divergences) — the methodology works.
  (Historical note: `SemanticFixtures.kt:105` had asserted the DuckDB answer for `url_encode` as
  Trino's; `:152/:185/:212/:213` tested only the coinciding inputs.)

- [x] **EV-B2 no pushed-vs-unpushed result comparison in integration tests** — **done.**
  `TestPushdownRowSetParity` installs two catalogs over the SAME DuckDB file: the production
  connector and a test-only instance whose expression rewriter has no rules. For each of 18
  risk-focused predicates (every emission class and every EV-A rewrite/gate reachable through table
  columns, plus nested AND/OR and rows containing NULL), it proves via distributed `EXPLAIN` that
  production removed the Trino `filterPredicate`, baseline retained it, then compares sorted row IDs.
  This exercises planner conversion, per-conjunct splitting, SQL emission, connection init, remote
  WHERE evaluation, JDBC decoding and SQL three-valued logic end to end.

  **It found one more real bug on its first run:** the `CASE` rewrites for `sqrt`, `ln`, `log2`,
  `log10`, `asin`, `acos` correctly reproduced the scalar NaN/-Infinity values, but DuckDB and
  Trino's runtime filter path order NaN differently. Pushed `sqrt(x) >= 0`, `ln(x) > 0`, and
  `asin(x) > 0` selected negative/out-of-domain rows the local Trino filter rejected; the initial
  rewrite also needed explicit NULL propagation. There is no context-free scalar rewrite that
  preserves every comparison, so all six functions are now **not pushed**. This is exactly the gap
  EV-B2 was intended to catch: scalar-in/scalar-out parity (EV-B1) is necessary but not sufficient.

- [x] **EV-B3 correct README/doc claims once B1 lands** — done: README.md now describes the
  differential fixtures accurately and says ~69; `README-pushdown-reference.md` counts and per-row
  notes updated (incl. removing the false "RE2 on both sides").

## C. Design / operational

- [x] **EV-C1 per-connection probe cost** — **done.** Was: every PARITY `openConnection` ran
  `LOAD` + separate `trino_meta()` + `duckdb_settings()` + 12 individual canary queries: ~15
  statements / HTTP round trips over Quack. Now `DuckBridgeStringComparisonProbe.probe()` emits
  one `SELECT` containing `default_collation`, optional `trino_meta()` count, and all 12 canaries as
  columns; `ProbeResult` is validated without further SQL. Cost: **BINARY = 1 statement; PARITY = 1
  validation statement plus an idempotent LOAD only when this connector owns loading** (embedded or
  configured server-side path); pre-loaded Quack PARITY = 1. A counting JDBC proxy test proves the
  consolidated probe executes exactly one `executeQuery`; embedded + Quack integration tests pass.

  Deliberately **not** memoised by `(connection-url, mode)`: there is no JDBC-level stable database
  instance identity. A URL cache survives a Quack/DuckDB restart, then skips the checks precisely
  when the extension may no longer be loaded or `default_collation` may have changed — a wrong-row
  risk. One fresh round trip per connection is the real fix; a stale cache would be a crutch.

- [x] **EV-C2 README says no per-connection `LOAD`; embedded does one** — **done with EV-C1.**
  README now states the actual behavior: `LOAD` registration is instance-scoped/idempotent, but the
  connector issues it on every embedded connection (and remote connection with a configured
  server-side path) because JDBC exposes no safe instance identity. A pre-loaded Quack server gets
  no connector-issued LOAD. Validation is one consolidated query per connection.

- [x] **EV-C3 Alias natives unaudited** — done 2026-09-02, results in section E
  (EV-E1..E4). Remaining action: fold the section-E corpus into EV-B1 so it runs on every
  DuckDB / extension pin bump.

- [x] **EV-C4 native extension loaded from a predictable tmp path with signatures off** — **done.**
  Was: `TrinoParityExtensionResolver.extractOrNull` wrote to
  `$java.io.tmpdir/trino-duckbridge/<platform>/trino_parity.duckdb_extension` and the connector
  `LOAD`ed it with `allow_unsigned_extensions=true` by default — on a shared-`/tmp` host another
  local user could own that directory and swap the file (TOCTOU) → arbitrary native code in the
  Trino worker. Now: (1) extraction root is `Files.createTempDirectory("trino-duckbridge-")` with
  `rwx------`, per process, unpredictable name; (2) `duckbridge.allow-unsigned-extensions` defaults
  to **false**, so the bundled community binary (signed) is signature-verified by DuckDB at `LOAD`
  — which also covers the unpinned `curl` in `fetch-parity-extension.sh`; (3) an unsigned binary
  under the default config fails loud with a message naming the flag
  (`TrinoParityExtensionResolver.isUnsigned` reads the 256-byte signature footer); (4) the
  `DUCKDB_LOCAL` Arrow executor no longer hardcodes `allow_unsigned_extensions=true` and honours the
  same flag. Test harness opts in only when the bundled binary is actually unsigned (dev builds), so
  CI/release run with verification on. Verified both ways: 433 tests green against the signed
  community 0.3.0 binary with verification on, and the same suites green with the local unsigned
  build via the opt-in; the refusal message fires for unsigned + default config.
  README config table and embedded-transport paragraph updated.

- [x] **EV-C5 type-mapping coverage vs. claims** — **done.** `DuckBridgeScalarColumnMappings`
  adds lossless JDBC mappings, verified embedded + Quack in both directions:
  - BLOB→VARBINARY; TIMESTAMPTZ→`TIMESTAMP(6) WITH TIME ZONE` carrying the query session zone;
    TIME→`TIME(6)`; TIMETZ→`TIME(6) WITH TIME ZONE`; UUID→UUID.
  - UTINYINT→SMALLINT, USMALLINT→INTEGER, UINTEGER→BIGINT; UBIGINT→DECIMAL(20,0), including
    quack-jdbc's signed-Long physical bits (`2^64-1` arrives as `-1`, decoded with
    `Long.toUnsignedString`). Quack TIMETZ's packed DuckDB `dtime_tz_t` uint64 is decoded exactly;
    embedded JDBC returns OffsetTime. Offset-second TIMETZ values fail loud (Trino stores minutes).
  - HUGEINT/UHUGEINT deliberately remain unsupported: extrema require 39 digits, Trino DECIMAL max
    is 38. The former ARRAY `HUGEINT→BIGINT` lossy mapping was removed; UBIGINT[] also remains out
    because Quack's signed-long element cannot be distinguished from DECIMAL(20,0) in that mapper.
  - Writes: VARBINARY, UUID, TIME/TIMETZ and TIMESTAMPTZ through precision 6; higher precision fails
    loud. UUID/zoned temporal domain pushdown is disabled pending ordering/bind proofs.
  - `TestDuckBridgeScalarTypes` proves native DuckDB→Trino reads, NULLs, Trino→DuckDB writes,
    session-zone extraction above and below the scan, precision refusal, and that BLOB makes
    `to_hex`/`to_base64` + MD5/SHA1/SHA256/SHA512/xxHash predicates fully pushable. Quack read/write
    coverage is in `TestDuckBridgeQuackTransport`.

  C5 exposed EV-E2 as a reachable concern, so `hmac_sha256` was TYPE_GATED OFF until extension
  0.4.0 fixed empty-key behavior. EV-E2 is now done and the gate removed; embedded + Quack
  end-to-end tests prove normal HMAC predicates push and empty-key rows fail remotely like Trino.

- [ ] **EV-C6 hourglass timestamp precision** — `Types.TIMESTAMP` → `TIMESTAMP_MICROS`
  unconditionally (`DuckBridgeClient.kt:341-343`); DuckDB `TIMESTAMP_NS` columns are read
  truncated. Low priority; document or map by type name.

## D. Build / hygiene

- [ ] **EV-D1 dead Arrow pin** — `gradle/libs.versions.toml` pins `arrow = "18.3.0"` and
  imports `arrow-bom`, but the Trino BOM forces Arrow 19.0.0 (seen in the offline-resolution
  failure). Drop the catalog pin/`arrow-bom` platform or make it authoritative; don't leave a
  pin that isn't the version shipped.

- [ ] **EV-D2 stale comments** — `gradle.properties` refers to `trino-ducklake`;
  `DuckBridgeExpressionTranslator.kt:218` comment ("0..999") is wrong (EV-A1).

- [ ] **EV-D3 `--offline` build unusable after a warm online build** — arrow 19 /
  `opentelemetry-jdbc` artifacts weren't cached because they resolve only through the Trino BOM
  at task time. Minor; note in the dev-docs build section or pre-resolve in CI.

## E. `trino_parity` extension natives (the 10 `Emission.Alias` entries)

Follow-up audit, 2026-09-02. Method: read `duckdb-trino-parity-extension/src/string_functions.cpp`
and `hash_functions.cpp` (submodule `1c82062`), then differential-tested every native:
Trino side via the query runner (`SELECT lower(U&'…')` etc., Trino 483 / JDK 25), extension side
via `duckdb_jdbc 1.5.5.0` with the bundled `linux-amd64` binary `LOAD`ed. Corpus: 63 strings
(case-mapping specials, ligatures, astral case pairs, combining sequences, ZWJ emoji, 20
Unicode whitespace classes, NUL) × 7 string natives = 441 evaluations; hashes over 8 byte
inputs × 5 HMAC keys (incl. >64-byte keys). Also ran an end-to-end check through the connector
in default `PARITY` mode (`WHERE upper(s) = <Trino's upper>` over a DuckDB table).

**Results:** `trim`/`ltrim`/`rtrim` (63/63), `reverse` (63/63), `normalize/1` (63/63),
`xxhash64`, `sha512`, `hmac_sha256` (all inputs) — **match Trino exactly**. `lower`/`upper`
— **16 mismatches**, and the end-to-end check returns wrong rows. Details:

- [x] **EV-E1 `trino_lower` / `trino_upper` implement the wrong case-mapping model** —
  **Resolved 2026-09-02** — extension commit `b181c61` on `brikk/duckdb-trino-parity-extension`
  `main` (submodule pointer bumped here); see "Resolution" after the table.
  `string_functions.cpp:19-50` uses ICU `UnicodeString::toLower/toUpper(Locale::getRoot())`,
  i.e. **full**, context-sensitive string case mapping (Java `String.toUpperCase(Locale.ROOT)`),
  on the premise that Trino does the same. Trino does not: `StringFunctions.lower/upper` go
  through airlift `SliceUtf8.toLowerCase/toUpperCase`, which apply **simple** per-code-point
  mapping (`Character.toLowerCase(int)` / `Character.toUpperCase(int)`). Observed:

  | input | Trino | extension |
  |---|---|---|
  | `upper('ß')` | `ß` (U+00DF, unchanged) | `SS` |
  | `upper('straße')` | `STRAßE` | `STRASSE` |
  | `lower('İ')` (U+0130) | `i` | `i̇` (`i` + U+0307) |
  | `lower('İstanbul')` | `istanbul` | `i̇stanbul` |
  | `lower('ΟΔΥΣΣΕΥΣ')` | `οδυσσευσ` (no final-sigma rule) | `οδυσσευς` |
  | `lower('ΣΑΣ')` | `σασ` | `σας` |
  | `upper('ﬁ')` U+FB01 | `ﬁ` | `FI` |
  | `upper('ﬀ')`, `upper('ﬃ')` | unchanged | `FF`, `FFI` |
  | `upper('ŉ')` U+0149 | unchanged | `ʼN` |
  | `upper('ΐ')` U+0390 | unchanged | `Ϊ́` (3 cps) |
  | `upper('ǰ')` U+01F0 | unchanged | `J̌` |
  | `upper('ᾈ')` U+1F88 | unchanged | `ἈΙ` (U+1F08 U+0399) |
  | `upper('ᾀ')` U+1F80 | `ᾈ` U+1F88 | `ἈΙ` |
  | `lower('İ̇')` U+0130 U+0307 | `i̇` (2 cps) | `i̇̇` (3 cps) |

  End-to-end in default `PARITY` mode: `WHERE upper(s) = U&'STRA\00DFE'` returns **no row**
  for `straße`; `WHERE lower(s) = 'i'` returns **no row** for `İ`; `WHERE upper(s) = 'ﬁ'`
  misses `ﬁ`. These are wrong results in the default configuration; the whole point of the
  Alias layer is to prevent exactly this. Mode: `PARITY` only (`aliasAvailable`), but that is
  the default.

  For comparison, DuckDB's **built-in** `lower`/`upper` (utf8proc) were also run against
  Trino on a 37-string corpus: they match on everything (İ, dotless ı, final sigma, ligatures,
  Deseret, Kelvin/Ångström signs, Georgian Mtavruli, U+A7C5, U+2C2F, …) except `upper('ß')`
  = `ẞ` U+1E9E (Trino: `ß`). So the built-in is *closer* to Trino than the extension is, but
  still not exact — `Bare` is not an option either.

  Fix (extension repo): replace the `toLower/toUpper(Locale::getRoot())` calls with a
  U8_NEXT loop applying `u_tolower(c)` / `u_toupper(c)` per code point (ICU's *simple* case
  mapping — the same UnicodeData.txt field Java's `Character.toLowerCase(int)` reads). Update
  the header comments (`:19-24`, `:36-41`), which state the wrong premise, and the extension's
  `test/sql/trino_parity.test`. Then bump the submodule + bundled binaries and add the table
  above to the EV-B1 corpus. Re-verify against DuckDB `upper('ß')` too: the simple mapping
  must yield `ß`, not `ẞ`.

  **Resolution.** `string_functions.cpp`: `TrinoLowerFun`/`TrinoUpperFun` now run a `U8_NEXT`
  loop applying `u_tolower`/`u_toupper` per code point (`MapCodePoints<>`); header comment
  rewritten with the verified Trino model. `test/sql/trino_parity.test` lower/upper sections
  rewritten (ß unchanged, İ→i, no final sigma, ligatures/ŉ/ΐ/ǰ unchanged, ᾀ→ᾈ 1:1, ǅ, ı/ſ,
  Kelvin/Ångström, Deseret, `upper(lower('İ'))`='I') — 77/77 assertions green on the rebuilt
  host binary. Extension README + `docs/REPORT-string-unicode-audit.md` (erratum) +
  `docs/RESEARCH-trino-duckdb-function-mapping.md` corrected. Connector side:
  `TestTrinoFunctionAliases.testRepresentativeAliasSemantics` now asserts the Trino-verified
  values; `TestDuckBridgeArrowEngine`, `TestDuckBridgeInProcessExecutor`,
  `TestDuckBridgeQuackArrowEngine`, `TestDuckBridgeQuackTransport` changed from
  `upper(name) = 'STRASSE'` (which Trino itself would never match) to `= 'STRAßE'`;
  `README-pushdown-reference.md`, `TODO-pushdown-duckdb.md`, `P3-NOTES.md` corrected.
  Re-ran the differential corpus against the rebuilt binary: **1 mismatch over 518
  evaluations** (down from 16/441), the survivor being the EV-E3 Unicode-version case
  (`lower(U+2C2F)`); end-to-end `WHERE upper(s)=…`/`lower(s)=…` in `PARITY` mode now returns
  the right row for all 7 probe strings.

  **Deployment: complete (2026-09-03).** (1) extension fix `b181c61` pushed, MainDistributionPipeline
  green; (2) duckdb/community-extensions #2594 **merged 2026-09-03 09:51 UTC** (`ref b181c61`,
  `version 0.3.0`); CDN rebuilt 10:29 UTC — the served v1.5.5 `linux_amd64` binary verified:
  `trino_upper('straße')` = `STRAßE`, `trino_lower(chr(304))` = `i`, `trino_meta()` = 10 rows;
  (3) submodule pointer + connector changes pushed (`2cf5ed5`); (4) `brikk/duckbridge` CI rerun
  against the new community binary (run 33618985820) — the 18 alias-related failures were the
  only red and self-heal with (2).
  Stale pre-fix `build/{linux-*,darwin-*,windows-*}` binaries under the submodule were deleted
  locally (they are untracked; anyone else with an old checkout should do the same, or
  `bundleParityExtension` picks them up on a non-linux-amd64 host).

- [x] **EV-E2 `trino_hmac_sha256` accepts an empty key** — **fixed in extension 0.4.0 commit
  `3d6b049`; final release ref `e93c6b7`; community-extensions PR #2614.**
  `TrinoHmacSha256Fun` checks `key.GetSize()==0`
  and throws `InvalidInputException("Empty key")` before RFC 2104 computation, matching Trino's
  `SecretKeySpec` API contract. Empty data remains valid. Extension sqllogic pins normal vector,
  empty data, empty-key error, binary-NUL key/message, >64-byte key hashing, and NULL propagation
  (82/82; full Linux/macOS/Windows/Wasm + Format/Tidy matrix green, run 33933840257).
  Connector submodule bumped; temporary TYPE_GATE removed. `TestPushdownSemanticFixtures` compares
  Trino vs extension empty-key errors; `TestDuckBridgeScalarTypes` and Quack transport tests prove
  column-key HMAC fully pushes and an empty-key row fails remotely with the same message.
  The community PR's DuckDB-latest job exposed a retained sentinel-only scalar `DefaultMacro` array
  and no-op loader from before the 0.2 native-function shrink. Final ref `e93c6b7` removes that dead
  API surface (no version shim); `trino_meta()` remains on its separate table-macro path.

- [ ] **EV-E3 Unicode-version skew** — the vendored ICU is 66.1 (Unicode 13,
  `duckdb/extension/icu/third_party/icu/common/unicode/uvernum.h:142`); Trino runs on the
  worker JDK (JDK 25 → Unicode 16). Code points whose case mapping / whitespace property /
  NFC data were added or changed between Unicode 13 and 16 will diverge even after EV-E1.
  **Confirmed after EV-E1:** `lower(U+2C2F)` (Glagolitic CAPITAL LETTER CAUDATE CHRIVI, case
  pair added in Unicode 14.0) → Trino `U+2C5F`, extension `U+2C2F` unchanged — the one
  remaining mismatch in the 518-evaluation corpus. DuckDB's built-in (utf8proc, newer tables)
  gets it right. Same class will hit Vithkuqi (U+10570.., 14.0), Latin Extended-D additions
  (U+A7C0/A7C1/A7D0.., 14.0), and 15.x/16.0 additions. Fix: bump the vendored ICU snapshot in
  `duckdb-trino-parity-extension/third_party/icu` to one carrying Unicode ≥ 16 data (ICU ≥ 76),
  or document the floor in the extension README and add the U+2C2F canary to
  `test/sql/trino_parity.test` as an expected-failure marker until then.

- [x] **EV-E4 extension test coverage** — done with EV-E1: `test/sql/trino_parity.test` now
  pins the EV-E1 table (ß, İ, final sigma, ligatures, ŉ/ΐ/ǰ, ᾀ, ǅ, ı/ſ, Kelvin/Ångström, Deseret,
  round-trip). Remaining: the trim/whitespace-class corpus and hash key-length cases are only in
  the connector-side ad-hoc run; add them when EV-B1 lands.

**Verified aligned (no action):** `trim`/`ltrim`/`rtrim` strip exactly Java
`Character.isWhitespace` — ASCII controls U+001C..U+001F, U+0085 *not* stripped, U+00A0 /
U+2007 / U+202F (non-breaking) *not* stripped, U+2028/U+2029/U+3000/U+1680/U+205F/U+2000..
U+200A stripped, U+200B / U+180E / U+FEFF *not* stripped, NUL preserved — all matched Trino.
`reverse` reverses code points (combining marks and ZWJ sequences split, as Trino does).
`normalize/1` = NFC. `xxhash64` big-endian 8 bytes, `sha512`, and `hmac_sha256` (incl.
>64-byte keys, key-hashing path) byte-identical to Trino's `to_hex(...)`.

---

## Not issues (checked, agree across engines)

Recorded so nobody re-investigates: `regexp_like('é','\w')` (both `false`); `strpos(s,'')`
(both `1`); `replace(s,'','x')` (both no-op); `week`/`isodow`/`date_trunc('week')` on DATE;
`NaN = NaN` and `NaN > 1e308` (both `true`); `TRY_CAST(' 1 ' AS INTEGER)` (both `1`);
`TINYINT` overflow on `+` (both throw); `hamming_distance` unequal lengths (both throw);
TopN `NULLS FIRST/LAST` emission; identifier/literal escaping in translator and table functions.
