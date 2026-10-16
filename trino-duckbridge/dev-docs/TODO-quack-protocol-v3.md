# TODO: Quack protocol v3

Duckbridge uses released `dev.brikk.duckdb:quack-jdbc:0.8.0`. Its constants advertise current wire
protocol v1 and recognize latest protocol v3. DuckDB 1.5.6 remains the qualified server lane;
DuckDB 2.x direct-Quack support is blocked until the client implements v3.

Required before claiming DuckDB 2.x remote support:

- negotiate an overlapping protocol version rather than changing a constant;
- implement query/cancellation identity, indexed fetch batches, cumulative acknowledgements and
  terminal batch counts;
- implement heartbeat scheduling and negotiated lease timeout;
- define deterministic reconnect, retained-result and late-error behavior;
- re-run Trino pass-through/pooled-concurrency tests and Doris metadata/JNI scans, including failure
  isolation and mixed-version rejection.

The broader engine/driver/extension matrix is tracked in
[`../../dev-docs/TODO-duckdb-2.0.md`](../../dev-docs/TODO-duckdb-2.0.md). Historical Quack fixes and
0.7 snapshot qualifications are preserved in [`archive/`](archive/).
