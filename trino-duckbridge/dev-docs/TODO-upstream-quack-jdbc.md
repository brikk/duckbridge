# TODO: upstream quack-jdbc tracking

Things blocked on gizmodata's `quack-jdbc` client driver (the pure-JVM Quack RPC
driver, `com.gizmodata:quack-jdbc`). Each entry links a filed issue and the
duckbridge canary that flips when it's fixed.

---

## Q1 — array/LIST result columns lose their element type

**Status:** FIXED and RELEASED — filed
**[gizmodata/quack-jdbc#6](https://github.com/gizmodata/quack-jdbc/issues/6)** (2026-07-20), fixed on
branch `fix/list-element-type-name` of `brikk/fork-quack-jdbc` and released as
**`dev.brikk.duckdb:quack-jdbc:0.3.0` on Maven Central** — a maintained fork that replaces gizmodata's
driver and carries this plus other fixes. duckbridge now depends on it (`gradle/libs.versions.toml`,
resolved from `mavenCentral()`), so arrays work over the Quack transport. The `JdbcTypeMap.typeName` fix
recurses LIST/ARRAY into the already-parsed `ListInfo.childType` / `ArrayInfo.childType` so
`getColumnTypeName` reports `INTEGER[]`, `DOUBLE[2]`, etc., matching duckdb-jdbc. gizmodata upstream has
been inactive since the issue was filed; we track our fork as the driver going forward.

**Verified in duckbridge:** `TestDuckBridgeQuackPassThroughQuery.arrayElementTypePreservedByQuackJdbc`
(quack-jdbc now reports `INTEGER[]`, parity with duckdb-jdbc) and `listAggregateThroughPassThroughReturnsArray`
(`list(id ORDER BY id)` pass-through returns `[1,2,3,4]` over QUACK). Full module suite green (289).

**Remaining follow-ups (duckbridge-side, separate from #6):**
- `DuckBridgeArrayColumnMapping.trinoElementType` only maps scalar element names, so an array with a
  parametric element (e.g. `DECIMAL(5,2)[]`) still won't resolve even with the driver fix. Extend the
  element-name parser if we need decimal/complex-element arrays over QUACK.
- Declared array **table** columns over T3 go through `DatabaseMetaData.getColumns` (a different path than
  the query/result describe #6 fixed); confirm they resolve now, or track separately.

**Historical (pre-fix) symptom.** Over the Quack transport, any LIST/array result column was reported by

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

**If gizmodata ever ships an equivalent fix upstream** and we want to switch back, point
`gradle/libs.versions.toml` at their coordinate/version and re-run — `arrayElementTypePreservedByQuackJdbc`
and `listAggregateThroughPassThroughReturnsArray` must stay green. Not planned: `dev.brikk.duckdb:quack-jdbc`
is the maintained driver now.
