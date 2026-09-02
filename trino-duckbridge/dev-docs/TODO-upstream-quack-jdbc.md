# TODO: upstream quack-jdbc tracking

Things blocked on gizmodata's `quack-jdbc` client driver (the pure-JVM Quack RPC
driver, `com.gizmodata:quack-jdbc`). Entries link a filed issue when one exists
and name the duckbridge canary that flips when the work is complete.

DuckDB 2.0 readiness is tracked centrally in
[`dev-docs/TODO-duckdb-2.0.md`](../../dev-docs/TODO-duckdb-2.0.md).

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

**Remaining follow-ups (duckbridge-side, separate from #6) — BOTH RESOLVED 2026-07-24:**
- ✅ **Parametric element types** (`DECIMAL(5,2)[]`): `DuckBridgeArrayColumnMapping.trinoElementType`
  now parses `DECIMAL(p,s)` (→ `createDecimalType`) and `appendElement` writes short/long decimals via
  `Decimals.encode*ScaledValue`. Doris was already fine (`DuckDbToDorisTypeMapper` recurses arrays off
  the faithful `TYPE_NAME`). Covered by `TestDuckBridgeQuackArrayColumns.parametricDecimalArrayElementResolvesOverQuack`
  (trino) + `TestDuckDbToDorisTypeMapper.arrays` / `TestDuckBridgeDorisMetadataOverQuack` `c_list_dec` (doris).
- ✅ **Declared array table columns via `getColumns`**: root-caused — over the `DatabaseMetaData.getColumns`
  path BOTH quack-jdbc AND the reference **duckdb-jdbc** report arrays as `DATA_TYPE=1111` (`Types.OTHER`)
  with the element type still in `TYPE_NAME` (verified against duckdb_jdbc 1.5.5.0). So this was never a
  quack-jdbc bug: the connector's `toColumnMapping` keyed only on `Types.ARRAY` (2003) and dropped them.
  Fixed by handling `Types.OTHER` (array-shaped names) in `DuckBridgeClient.toColumnMapping`. This also
  fixes the in-process path (the earlier "in-process unaffected" note held only for result-set describe,
  which reports `2003`; the getColumns metadata path was affected on both transports). Covered by
  `TestDuckBridgeQuackArrayColumns.scalarArrayTableColumnsResolveViaGetColumns`.

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

---

## Q2 — DuckDB 2.0 Quack protocol v3

**Status:** OPEN, not yet filed separately (discovered by the 2026-08-28 DuckDB
2.0 commit-history scan).

Our released `dev.brikk.duckdb:quack-jdbc:0.6.0` and current
`0.7.0-SNAPSHOT` declare `QuackConstants.QUACK_VERSION = 1`. DuckDB 2.0's
`duckdb-quack` mainline declares protocol v3. Between v1 and current v3 the wire
added query/cancellation identity, indexed and acknowledged fetch batches,
read-ahead behavior, terminal batch counts, and heartbeat lease negotiation. The
v3 bump itself accompanies heartbeat messages. The server/client handshake
requires an overlapping protocol version, so changing the Maven version alone
cannot make the current driver connect.

**Impact:** both Trino's production `jdbc:quack://` transport and every Doris
metadata/scan connection use this driver. They are blocked from DuckDB 2.0 until
the driver is ported. Trino's experimental Quack/Arrow executor is different: it
uses a matching DuckDB native Quack extension as its client.

- [ ] Port the handshake and all v2/v3 message fields/types; do not only bump the
  version constant.
- [ ] Implement heartbeat scheduling, negotiated lease timeout, and deterministic
  shutdown for pooled/idle connections.
- [ ] Implement fetch batch indexes, cumulative acknowledgement, terminal batch
  counts, late errors, cancellation, and reconnect/result-retention behavior.
- [ ] Preserve metadata and value fidelity for arrays, wide integers, timestamp
  variants, `SQLNULL`, JSON, and `VARIANT` while changing the codec.
- [ ] Add mixed-version rejection tests plus a protocol-v3 conformance suite run
  against the exact DuckDB/Quack server build used by both connector modules.
- [ ] Publish a new `dev.brikk.duckdb:quack-jdbc` release and bump Duckbridge only
  after Trino and Doris integration suites are green.

Upstream references:
[`duckdb-quack#229`](https://github.com/duckdb/duckdb-quack/pull/229),
[`duckdb-quack#245`](https://github.com/duckdb/duckdb-quack/pull/245), and
[`duckdb-quack#240`](https://github.com/duckdb/duckdb-quack/pull/240).
