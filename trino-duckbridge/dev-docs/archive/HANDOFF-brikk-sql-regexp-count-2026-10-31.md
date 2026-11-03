# brikk-sql handoff: safe Trino REGEXP_COUNT -> DuckDB translation

## Task and ownership

Work in the **brikk-sql repository**. Add a conservative semantic guard to the
cross-engine translation of Trino `regexp_count(VARCHAR, VARCHAR)` to DuckDB.
Use the existing generation/capability/unsupported conventions; do not introduce
a new compiler architecture for this task. Do not change DuckBridge or rebuild
engines. Publishing a release is a separate user decision.

brikk-sql owns the reusable translation contract. DuckBridge owns the final
pushdown decision and fallback to Trino. DuckBridge already has an independent
guard and is not blocked on this work. This handoff closes the current
DuckBridge-side discussion; it does not request automatic downstream adoption.

## Observed problem

DuckBridge audited published `dev.brikk.house:brikk-sql-jvm:0.21.0` and
`brikk-sql-metadata-jvm:0.21.0` against **Trino 483** and **DuckDB 2.0.0**, using
native JDBC **2.0.0.0**. The generator emits the equivalent of:

```sql
CASE WHEN source IS NULL OR pattern IS NULL THEN NULL
     ELSE length(regexp_extract_all(source, pattern)) END
```

That is not generally equivalent to Trino regex semantics. These valid Trino
queries succeed, while the generated DuckDB calls error because RE2 does not
support lookaround:

```sql
SELECT regexp_count('ab', '(?<=a)b'); -- Trino: 1
SELECT regexp_count('aaa', '(?=a)');  -- Trino: 3
```

Eight audited generator cases produced six value/type matches and those two
errors. Six matches are not proof of arbitrary regex compatibility. The bundled
Trino catalog was 484, not our exact 483 oracle; catalog overlap is not semantic
qualification.

## Conservative initial contract

Admit only VARCHAR source and a **non-NULL constant VARCHAR pattern** matching
this grammar:

- A nonempty concatenation of the atoms below.
- Each atom may have one optional greedy `+` suffix.
- Literal atoms: ASCII `a-z`, `A-Z`, `0-9`, space, `_`, `-`, `/`, `:`, `,`, `=`.
- Class atoms, matched exactly:
  `[0-9]`, `[a-z]`, `[A-Z]`, `[A-Za-z]`, `[a-zA-Z]`, `[A-Za-z0-9]`,
  `[a-zA-Z0-9]`, `[a-z0-9]`, `[A-Z0-9]`.

Accepted examples: `aba`, `a+`, `a+a`, `[0-9]+`, `[A-Z]+-[0-9]+`.
Every atom consumes at least one ASCII character; there are no zero-width
matches or dialect-specific operators in this contract. **Source data may
contain arbitrary Unicode**, including supplementary characters and embedded NUL.

Decline translation for everything else initially: empty patterns, dynamic or
NULL patterns, CHAR/non-VARCHAR sources, `.`, `*`, `?`, anchors, alternation,
groups, lookarounds, escapes, flags, braces, negated/unknown classes, Unicode
pattern literals and malformed patterns. Some excluded patterns may agree;
they are **unqualified**, not necessarily known divergences.

For admitted inputs, the target expression is:

```sql
length(regexp_extract_all(source, pattern))
```

Preserve BIGINT result type, SQL NULL source -> SQL NULL, zero for no matches,
and leftmost non-overlapping counts. Do not coalesce NULL to zero, rewrite
unsupported patterns approximately, catch errors as NULL, stringify inputs, or
silently substitute a different regex engine.

## API behavior

- Check the source/target dialect, argument types and constant pattern before
  claiming a semantically supported translation.
- Return the existing explicit unsupported/capability result with a useful
  reason when the guard cannot prove eligibility. Missing type/constant
  information must not be guessed from SQL text.
- If syntax-only generation is an intentional existing API, distinguish it
  explicitly from the safe semantic path; unsafe SQL must not be advertised as
  qualified translation.
- Document the qualified engine versions. Do not silently infer qualification
  from nearest-version catalogs.
- Make the qualification rule and rejection reason reusable by consumers,
  using existing public API patterns. Do not claim this qualifies other regex
  functions, reverse translation, or every RE2-compatible expression.

## Verification and acceptance

1. Unit-test the grammar boundaries, types, constant detection and unsupported
   outcomes. Test the public generation path, not just an isolated validator.
2. Execute admitted generated SQL against real, version-recorded Trino and
   DuckDB. Compare actual BIGINT metadata, exact counts and SQL NULL. Every
   admitted case must succeed; matching exceptions do not establish parity.
3. Include empty/NULL source, no/one/multiple matches, overlap (`ababa` / `aba`
   -> 1), greedy concatenation, long input, CR/LF/tab, BMP/supplementary Unicode,
   combining marks and embedded NUL. Example: `😀12é3` / `[0-9]+` -> 2.
4. Keep both lookaround counterexamples as explicit unsupported regressions.
   Confirm dynamic, malformed and unqualified patterns cannot escape the guard.
5. Preserve tests and mappings for unrelated dialects/functions. No regex
   dependency or runtime-engine substitution is required.
6. Preserve Java-17-compatible published JVM artifacts. DuckBridge consumes the
   pair only in an isolated audit runtime; this task should not require a
   connector Kotlin/JVM upgrade.
7. Deliver the implementation, test evidence with exact engine versions,
   documented contract/limitations and API usage examples. Clearly identify
   any oracle coverage not actually run. Do not publish automatically.

## Downstream status

DuckBridge already implemented this bounded grammar independently and verified
**26 patterns x 28 inputs = 728 typed native comparisons**, plus embedded/Quack
predicate-plan and exact row-set checks. It retains unqualified expressions in
Trino and does not consume the generic brikk-sql regex rendering in production.

DuckBridge's latest completed checks were **919 Trino + 89 Doris tests**, zero
failures/errors/skips. Its runtime behavior needs no change for this handoff.
A future brikk-sql intake would be a separate, explicitly requested task with
independent consumer validation; do not remove the local guard by implication.
