# TODO: upstream quack-jdbc tracking

Things blocked on gizmodata's `quack-jdbc` client driver (the pure-JVM Quack RPC
driver, `com.gizmodata:quack-jdbc`). Each entry links a filed issue and the
duckbridge canary that flips when it's fixed.

---

## Q1 — array/LIST result columns lose their element type

**Status:** OPEN — filed **[gizmodata/quack-jdbc#6](https://github.com/gizmodata/quack-jdbc/issues/6)** (2026-07-20).

**Symptom.** Over the Quack transport, any LIST/array result column is reported by
quack-jdbc as bare `LIST`: `ResultSetMetaData.getColumnTypeName` returns `"LIST"`
(no element type) and values come back as a plain `java.util.ArrayList` rather than
a typed `java.sql.Array`. `DuckBridgeClient.toColumnMapping` can't resolve the type
and the query fails loud at analysis (`Unsupported type ... LIST`).

**Isolated to the driver, not the protocol/server/DuckDB.**
- duckdb-jdbc (direct) reports the element type: `INTEGER[]`, `BIGINT[]` — even for a
  computed `list(x)`.
- The Quack RPC `PrepareResponse` serializes `vector<LogicalType> result_types`
  (`duckdb-quack/src/serialize_quack_message.cpp:123`); a DuckDB `LogicalType` for a
  LIST embeds its child type, so `LIST<INTEGER>` is fully on the wire.
- quack-jdbc drops it client-side. → fix belongs in quack-jdbc.

**Ask (in the issue).** Surface the element type in `getColumnTypeName` (e.g.
`INTEGER[]`) and return values as a `java.sql.Array` with a base type, matching
duckdb-jdbc.

**Impact while open.** Array columns (declared `T[]` *and* computed `list()`) can't be
read over the Quack transport (T3 quack-jdbc metadata, and therefore the QUACK T2
Arrow engine which resolves columns via the same describe path). The embedded /
DUCKDB_LOCAL path is unaffected (uses duckdb-jdbc, which is faithful). Pass-through
`query()` with a scalar result is fine; only array results are blocked.

**Canary / when fixed.**
- Watch test: `TestDuckBridgeQuackPassThroughQuery.arrayElementTypeDroppedByQuackJdbc_upstreamNotOurs`
  asserts quack-jdbc reports `"LIST"`. It flips green→red when upstream starts
  reporting `...[]`.
- Fail-loud pin: `TestDuckBridgeQuackPassThroughQuery.bareListResultTypeFailsLoud`.
- On fix: flip the canary assertion, drop the fail-loud pin, and confirm array
  columns resolve over QUACK (they already decode correctly on the Arrow data plane
  via `DuckBridgeArrowToPageConverter`; this is purely a metadata/describe gap).
