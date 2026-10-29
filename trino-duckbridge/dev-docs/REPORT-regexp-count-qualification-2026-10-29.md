# Guarded REGEXP_COUNT qualification — 2026-10-29

## Decision

Admit **only** `regexp_count(VARCHAR, constant VARCHAR)` in the grammar below.
Emit `length(regexp_extract_all(source, pattern))`, returning BIGINT/SQL NULL.
This is not a generic alias and does not consume generated SQL from brikk-sql.
It works without the parity extension because this bounded native rendering is
independently compared with Trino 483 and DuckDB 2.0.0.

Accepted patterns are a nonempty concatenation of these consuming atoms, each
optionally followed by one greedy `+`:

- ASCII letters/digits and the literal characters space, `_ - / : , =`.
- Exactly `[0-9]`, `[a-z]`, `[A-Z]`, `[A-Za-z]`, `[a-zA-Z]`,
  `[A-Za-z0-9]`, `[a-zA-Z0-9]`, `[a-z0-9]`, `[A-Z0-9]`.

Examples: `aba`, `a+`, `[0-9]+`, `[A-Z]+-[0-9]+`.

Every admitted atom consumes at least one ASCII character. Neither engine can
produce a zero-width match in this grammar; literal/class matching, greedy
concatenation and leftmost non-overlapping iteration coincide. The positive
ASCII classes are intentional: do not widen this to arbitrary Unicode classes,
negated classes or the existing broader `Re2Safety` gate without a fresh proof.
Unicode is fully allowed in **source data**; it simply cannot satisfy an ASCII
atom. BIGINT counts and SQL NULL are preserved, including mixed Unicode/NUL input.

Empty patterns, `*`, `?`, dot, anchors, alternation, groups, lookarounds, escapes,
flags, braces, negated/unknown classes, malformed patterns and dynamic/NULL
pattern arguments stay in Trino. Source CHAR/non-VARCHAR overloads are not admitted.
This leaves the published intake's valid Trino lookaround counterexamples local.

## Proof

- `TestRegexpCountQualification`: **26 patterns × 28 inputs = 728** exact native
  Trino / emitted DuckDB observations, with actual BIGINT type and SQL NULL checks.
  Covers no/one/multiple matches, overlap opportunities, greedy concatenation,
  empty/NULL source, 4,096-character source, CR/LF/tab, BMP, supplementary Unicode,
  combining marks and embedded NUL. All admitted probes must succeed; no exception
  is accepted as parity. The production translator supplies the DuckDB expression.
- Negative tests assert that unqualified patterns and dynamic/NULL arguments do
  not emit SQL. The shared semantic fixture catalog includes positive and rejected
  cases so entry-coverage gates continue to enforce this mapping.
- `TestPushdownRowSetParity`: adds positive classes, zero matches/NULL and nested
  OR. Two catalogs use the same file; native-local predicates retain a Trino filter,
  production plans must remove it, and exact row IDs must match.
- `TestDuckBridgeQuackTransport`: Quack JDBC **0.10.0**, protocol **v3**, server
  DuckDB **2.0.0**. Qualified patterns must remove `filterPredicate` and return exact
  IDs, including supplementary Unicode/NULL/empty rows. Valid Trino lookarounds
  and dynamic patterns must retain the filter and return exact native results.
- The published-generator corpus and its six typed matches/two lookaround errors
  are retained unchanged. Those observations do not authorize broader production
  regex grammar.

```bash
./gradlew :trino-duckbridge:test \
  --tests '*TestRegexpCountQualification' \
  --tests '*TestPushdownRowSetParity' \
  --tests '*TestPushdownSemanticFixtures' \
  --tests '*TestPublishedSqlIntake' \
  --tests '*TestDuckBridgeQuackTransport' \
  :trino-duckbridge:detekt
```

Targeted checks passed. Raw log: `build/regexp-count-targeted.log`. Compact
matrix evidence: `trino-duckbridge/build/function-audit/regexp-count-qualified.tsv`.
Full Trino `check` and `verifyPluginAssembly` passed: **660 tests, zero failures,
errors or skips** (`build/regexp-count-full-check.log`). No extension binary,
engine version, driver version or string-pushdown trust setting was changed.
