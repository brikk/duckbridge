# DuckDB 2 function intake — 2026-10-28

## Decision

**No new pushdowns enabled.** Availability, parser support and matching names are
not parity. Existing Doris canaries pass on DuckDB 2; the new JSON candidates have
concrete semantic/availability blockers. Both connectors carry regression guards
keeping these names out of their predicate-pushdown catalogs.

## Inputs and reproducibility

- DuckDB **v2.0.0**, source **d54cbbe3df**; Doris exact master
  `082736138b7f0e767f966cbb1434bc9ba85f29ec`, API 24 / JNI 6.
- Quack JDBC **0.10.0**; the live Doris smoke passed native values, current
  predicates, non-UTC temporal edges, concurrency, catalog restart and isolation.
- Inspected brikk-sql main: `ace33b9`. Its generated catalog still identifies
  **DuckDB v1.5.6**; the sibling's 2.0 refresh is pending. Its SQLGlot catch-up is
  not evidence that a connector may push the corresponding native function.
- Script: `../compose/function_audit.py`. Run after the smoke with `--keep`:

  ```bash
  python3 doris-duckbridge/compose/function_audit.py \
    --brikk-sql-root /home/jayson/DEV/brikk/brikk-sql \
    --output /path/to/function-audit.json
  ```

Raw typed results, full SQL, error diagnostics, resolved signatures, inventory,
catalog commit/header/hash and engine identities:
`/home/jayson/DEV/OSS/doris-duckbridge-api24-08273613/output/duckbridge-api24-duckdb2-function-audit-typed.json`.
The initial untyped-value probe is retained separately, not called a semantic
divergence: DuckDB's SET/INSERT/REPLACE signatures require a JSON-typed third
argument, so the corrected comparison explicitly uses `CAST('9' AS JSON)` on both sides.

## Inventory, not release-history attribution

The actual fresh DuckDB CLI registry exposes **2,970 identifier-shaped,
non-PRAGMA overload rows**. Compared by name with the sibling's generated catalog:

- **227 additions:** 13 internal names, 154 collation wrappers, 60 other names.
- **4 removals:** `icu_collate_yue`, `icu_collate_yue_cn`, `pandas_scan`,
  `python_map_function`.

This compares an old **Python-embedded** registry with a new **CLI** registry;
embedding/extension policy differs. In particular the missing Python hooks are
not a DuckDB engine regression, and a newly listed `if` need not be new SQL syntax.
Overload/type changes need their own diff; this name inventory does not prove them.

Useful follow-up families include JSON, `overlay`, `path_join`, decimal functions,
and `variant_*`. Variant transport is not enabled. Internal, collation, environment,
sequence and external-resource/admin functions are not ordinary predicate aliases;
side effects and server-local state make many intrinsically inappropriate to push.

## Differential JSON observations

Seven inputs per expression: nested object/JSON nulls, empty object, SQL NULL,
duplicate keys, punctuation-bearing key, array/JSON nulls, malformed JSON.
Doris evaluates the host expression above a real JDBC scan of VARCHAR fixtures;
the independent DuckDB CLI uses byte-identical inputs. Results compare UTF-8 hex,
not display formatting. **56 comparisons: 22 matching fixture results, 2 different
results, 32 error/availability observations requiring review.** Errors are never
counted as parity, even when both sides fail.

| Function | Observations | Pushdown decision |
|---|---|---|
| `json_set` | 5 matching, duplicate-key difference, invalid-JSON value/error split | Hold |
| `json_replace` | 5 matching, duplicate-key difference, invalid-JSON value/error split | Hold |
| `json_insert` | 6 matching; invalid-JSON value/error split | Hold; current VARCHAR→JSON expression is unsafe |
| `json_remove` | 6 matching; invalid-JSON value/error split | Hold; current VARCHAR→JSON expression is unsafe |
| `json_deep_merge` | Doris has no function with this name | No direct host alias |
| `json_merge_patch_diff` | Doris has no function with this name | No direct host alias |
| `json_normalize` | Doris has no function with this name; DuckDB returns VARCHAR | No direct host alias |
| `json_strip_nulls` | Doris has no function with this name | No direct host alias |

Concrete duplicate-key counterexample, input `{"a":1,"a":2}`, path `$.a`, JSON value `9`:

- Doris SET/REPLACE: `{"a":9,"a":2}`.
- DuckDB SET/REPLACE: `{"a":9}`.

For malformed VARCHAR input `invalid`, Doris's explicit `CAST(... AS JSON)` yields
SQL NULL, while DuckDB throws a conversion error. That is an **expression-shape**
blocker, not a claim that every typed-JSON call to the function differs. A proposed
lowering must preserve cast/error semantics too; do not sneak in TRY_CAST or a
different input contract merely to make the comparison green.

Matching six fixtures is not full semantic qualification. Before considering
INSERT/REMOVE or any new alias: prove host/remote types and nullability, top-level
JSON null, JSONPath/Pointer, escaped/missing keys, array bounds, return type,
variadic/arity rules, errors, and actual filter EXPLAIN/results. Revisit the exact
new/changed overload set when brikk-sql's catalog refresh lands.
