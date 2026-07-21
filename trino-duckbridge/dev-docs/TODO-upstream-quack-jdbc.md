# TODO: upstream quack-jdbc tracking

Things blocked on gizmodata's `quack-jdbc` client driver (the pure-JVM Quack RPC
driver, `com.gizmodata:quack-jdbc`). Each entry links a filed issue and the
duckbridge canary that flips when it's fixed.

---

## Q1 — array/LIST result columns lose their element type

**Status:** FIXED in our fork, PENDING upstream — filed
**[gizmodata/quack-jdbc#6](https://github.com/gizmodata/quack-jdbc/issues/6)** (2026-07-20), fixed on
branch `fix/list-element-type-name` of `brikk/fork-quack-jdbc` and published as
`dev.brikk.duckdb:quack-jdbc:0.3.0-brikk-SNAPSHOT` (Central snapshots). duckbridge now depends on that
brikk snapshot (`gradle/libs.versions.toml` + the `centralSnapshots` repo in
`trino-duckbridge/build.gradle.kts`), so arrays work over the Quack transport today. **Revert to the
upstream coordinate/version once gizmodata cuts a release with the fix** (they have been inactive on the
repo since filing, hence the interim fork). The `JdbcTypeMap.typeName` fix recurses LIST/ARRAY into the
already-parsed `ListInfo.childType` / `ArrayInfo.childType` so `getColumnTypeName` reports `INTEGER[]`,
`DOUBLE[2]`, etc., matching duckdb-jdbc.

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

**When upstream cuts a release with the fix.** Point `gradle/libs.versions.toml` back at
`com.gizmodata:quack-jdbc:<new-version>`, drop the `centralSnapshots` repo from
`trino-duckbridge/build.gradle.kts`, and re-run — `arrayElementTypePreservedByQuackJdbc` and
`listAggregateThroughPassThroughReturnsArray` must stay green.
