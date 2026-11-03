# Doris pushdown reference

This is the read-only Doris connector's current renderer, not the Trino function
catalog. It requires the matching pinned Doris runtime and DuckDB 2.0.0/Quack
JDBC 0.12.1. Admission is conditional on the actual expression and column types;
a function name alone is not a guarantee for every overload/input.

## Predicates and projection

- Requested columns are projected. Empty projection uses `SELECT 1`; this is not
  aggregate COUNT pushdown.
- Scalar column/literal comparisons, literal IN/NOT IN, IS NULL/IS NOT NULL and
  supported Boolean combinations can render. Unsupported children keep the
  complete Boolean expression in Doris; an OR is not partially pushed.
- Temporal literals require the direct column's known native type: naive DATE/
  TIMESTAMP uses a naive literal; TIMESTAMPTZ comparisons use explicit UTC.
  Unknown temporal metadata declines emission.
- No per-scan remote TimeZone is set. Arbitrary timezone-function equivalence is
  not implied by the zone-explicit comparison rule.

## Function allowlist

| Doris name | DuckDB rendering / gate |
|---|---|
| `character_length/1` | `length`, counting code points |
| `length/1` | `strlen`, counting bytes |
| `substring/2`, `/3` | Constant start must be nonzero; children must render |
| `locate/2` | `strpos` with reversed arguments |
| `instr/2` | Native `instr` |
| `starts_with/2` | Native `starts_with` |
| `abs/1` | Native `abs`, subject to renderable arguments |
| `year/1`, `month/1`, `day/1`, `hour/1`, `minute/1`, `second/1` | Native calls; input/zone semantics still matter |

Other function shapes, including JSON mutation, OVERLAY and unsupported floating
calls, remain in Doris. Non-finite numeric literals, NUL-bearing strings,
unrecognized values and unsafe temporal literals are not forwarded.

Inspect Doris `EXPLAIN VERBOSE` and the emitted DuckDB query to determine where
a particular predicate is evaluated. A retained Doris filter does not repair
rows incorrectly removed by an unsafe remote prefilter.
