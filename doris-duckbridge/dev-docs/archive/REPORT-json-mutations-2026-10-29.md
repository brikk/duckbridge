# JSON INSERT/REMOVE qualification — 2026-10-29

## Decision

**Keep both functions local.** A valid JSON-typed input and a syntactically valid
path do not make these functions equivalent. No TRY_CAST, normalization, duplicate
key removal, implicit JSON-value coercion or error-to-NULL wrapper was introduced.
The existing no-pushdown regressions and remote-LIMIT withholding remain in force.

Native evidence: exact Doris FE/BE `082736138b7` / API 24 / JNI 6, and independent
DuckDB JDBC **2.0.0.0**. Seven reviewed source rows are read over the actual
JDBC/Quack scan, while Doris evaluates the JSON projection locally. The audit
checks input values, exact engine versions and successful control queries before
and after the matrix. Each outcome retains native JDBC result metadata, SQL NULL,
UTF-8 bytes, or the actual SQL exception; arbitrary/both errors never count as parity.

## Corpus and results

Seven inputs: ordinary object with nested object/array/JSON null, empty object,
SQL NULL, duplicate-key object, dotted-key object, root array, malformed JSON.
Fifteen paths: root, existing/missing member, nested existing/missing member,
quoted dotted member, array indices including negative and `#-1`, root index,
wildcard, malformed/empty path, SQL NULL path. INSERT uses six value shapes:
JSON number, JSON null, JSON string, JSON array, SQL NULL typed as JSON, SQL integer.

**735 observations**: 105 REMOVE and 630 INSERT.

- REMOVE: 65 matching fixtures, **6 value/NULL differences**, 34 error observations.
- INSERT: 258 matching fixtures, **85 value/NULL differences**, 287 error observations.
- Total: 323 matching fixtures (not qualification), **91 differences**, 321 cases
  requiring error review. The error cases include host-only, target-only and
  both-engine errors; none was counted as a match.

Concrete counterexamples, with both source/value arguments explicitly JSON-typed:

- `json_remove('{"a":1}', '$')`: Doris preserves the object; DuckDB returns SQL NULL.
  The same difference occurs for other non-NULL root objects/arrays in the corpus.
- `json_remove('{"arr":[null,2]}', '$.arr[-1]')`: Doris preserves the array;
  DuckDB removes the last element.
- `json_insert('{}', '$.nested.a', CAST('9' AS JSON))`: Doris leaves `{}`;
  DuckDB creates the missing parent and yields `{"nested":{"a":9}}`.
- `json_insert('{}', '$.a', CAST(NULL AS JSON))`: Doris inserts **JSON null**,
  yielding `{"a":null}`; DuckDB returns **SQL NULL**.
- `json_insert` with a SQL integer value binds in Doris but not in DuckDB; an
  implicit cast is not the same source call and would not resolve the other gaps.
- Malformed input and unsupported path forms have engine-specific NULL/error
  outcomes. Duplicate-key cases are retained byte-for-byte rather than parsed
  through a map that would erase duplicate members.

The published SET duplicate-key counterexample remains relevant but is separate
from this INSERT/REMOVE matrix; do not claim all JSON mutation functions have the
same duplicate-key policy.

## Reproduce / evidence

```bash
bash doris-duckbridge/compose/smoke.sh --no-build --keep
./gradlew :doris-duckbridge:auditJsonMutations
docker compose -f doris-duckbridge/compose/docker-compose.yml down # no -v
```

Full typed outcomes and exact per-engine SQL:
`doris-duckbridge/build/function-audit/json-mutations.json`.
Task log: `build/json-mutations-audit.log`.
The audit dependencies remain outside the connector runtime/package.
Final module/package checks pass with 85 Doris and 662 Trino tests, no
failures/errors/skips. The matrix's semantic differences are retained as blockers,
not disabled tests or a claim of new JSON support.

A possible future bounded domain needs a contract enforceable from the real
stored type/arguments and a native predicate/EXPLAIN proof. Finite matching
fixtures or advisory Doris 4.1.5 metadata cannot supply that contract. Generic
VARCHAR/JSON mappings remain blocked independently of the reported JNI Unicode bug.
