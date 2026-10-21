# TODO: Quack protocol v3

Trino Duckbridge uses released `dev.brikk.duckdb:quack-jdbc:0.9.0`. Its constants advertise current
wire protocol v1 and recognize latest protocol v3. DuckDB 2.0.0's Quack server retains v1
compatibility: the complete pass-through, array, Arrow, parity, string and concurrency suites pass
against the 2.0 server. Doris remains separately pinned to Quack JDBC 0.8 / DuckDB 1.5.6.

Protocol v3 is therefore an enhancement rather than a DuckDB-2.0 release blocker. Before relying on
v3-only lifecycle/resource behavior:

- negotiate an overlapping protocol version rather than changing a constant;
- implement query/cancellation identity, indexed fetch batches, cumulative acknowledgements and
  terminal batch counts;
- implement heartbeat scheduling and negotiated lease timeout;
- define deterministic reconnect, retained-result and late-error behavior;
- add explicit v3 conformance, reconnect/result-retention and mixed-version rejection tests.

The broader engine/driver/extension matrix is tracked in
[`../../dev-docs/TODO-duckdb-2.0.md`](../../dev-docs/TODO-duckdb-2.0.md). Historical Quack fixes and
0.7 snapshot qualifications are preserved in [`archive/`](archive/).
