# Trino connector release notes

## 0.3.0 — Trino 483 and 484

Compatibility: DuckDB **2.0.0**, native JDBC **2.0.0.0**, Quack JDBC **0.13.0**,
and signed `trino_parity` **0.5.2**. Use matching DuckDB versions on remote servers.
Use `483-0.3.0` with Trino 483 and `484-0.3.0` with Trino 484. Their Trino libraries
are ABI-specific. The DuckDB/Quack/parity versions are shared. Doris is a separate
development connector, not part of these archives.

### Evaluation behavior

- Quack JDBC finishes unread result streams when closing or superseding them,
  preserving healthy manual transactions. Closing an unfinished result can wait
  for the remaining query and transfer; closing FETCH failures are reported.
- Quack's compact sequence/primitive-constant decoding avoids expanding those
  vectors into row-count-sized arrays; JDBC types, values and NULLs are unchanged.
- Parity functions expose descriptions and examples in DuckDB's SQL catalog;
  the ten-function API and the admitted pushdown surface are unchanged.

- Ordinary TIMESTAMP(6) reads preserve finite microseconds through both endpoints
  and reject infinity/reserved source carriers instead of returning finite values.

- Correct floating-point domain/IEEE comparison handling, floating SIGN and
  explicit unbounded DATE-to-text formatting.
- Correct finite DATE writes and type-aware temporal unit validation.
- Exact known-session-zone sub-day timestamp truncation and astronomical zoned
  YEAR handling, including the qualified overlap/non-hour transition cases.
- Exact UTC/fixed-offset WITH_TIMEZONE consumers over admitted microsecond
  sources, including wide finite intermediates. Named-zone attachment remains
  evaluated in Trino.
- The 484 build evaluates all WITH_TIMEZONE, TO_UNIXTIME and zoned DATE_TRUNC
  except milliseconds locally, preserving that engine's early constructor bounds
  and compensated epoch rounding. The native temporal rules above apply to 483;
  source data types and actual engine error contracts remain unchanged.
- DATE-input `date_trunc` is evaluated in Trino for all units. This preserves
  DATE types, range/error behavior and NULLs at the cost of that pushdown.
- POWER/ATAN/ATAN2/COSH and other documented unqualified calls remain local;
  unsupported type/carrier paths still fail explicitly instead of losing data.

### Accepted limitations

- **REAL membership / signed zero (Trino 483 only):** REAL is the 32-bit floating-point
  type; `x IN (0,1)` is a membership predicate. Trino's optimized compiled path
  can disagree with its equality/domain/constant-folded paths for negative zero.
  A pushed domain and a retained Trino filter can therefore produce different
  zero handling. The regression preserves this native engine inconsistency;
  the connector does not normalize stored values. This specific IN/NOT IN engine
  bug is fixed in stock Trino 484.
- The specific documented DECIMAL narrowing-overflow JDBC_ERROR versus Trino
  NUMERIC_VALUE_OUT_OF_RANGE difference is accepted; successful values and NULLs
  may not change on that account.
- Named-zone WITH_TIMEZONE and nanos expressions are local. Aggregate pushdown
  is not implemented. The Arrow execution engines are experimental.
- Quack's documented published-port streaming and autonomous orphan-session
  expiry limitations remain unresolved. Direct-bridge successes are not a claim
  that those separate acceptance routes have passed.
- Native JDBC 2.0.0.0 can fail during concurrent connection open/close against the
  same embedded database file. The upstream issue remains open; no automatic retry
  or connector-side mitigation is included. This affects connection availability,
  not a substitute type or accepted wrong-row result.

See the [pushdown reference](README-pushdown-reference.md) for precise gates and
local behavior. Developer build/artifact evidence is maintained separately.
