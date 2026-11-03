# TODO: Quack protocol v3

## Current baseline — 2026-10-31

Both connectors select released `dev.brikk.duckdb:quack-jdbc:0.12.0` against
DuckDB 2.0.0. Test fixtures assert actual driver identity and negotiated **v3**;
the old v1-only/0.9.0 baseline is historical. Doris is separately qualified on
master `082736138b7`, connector API 24 / JNI API 6, not currently pinned to 1.5.6.
The old Doris API-13 / 1.5.6 / Quack-0.8 artifacts remain rollback only.

See [released 0.12.0 qualification](REPORT-quack-0.12-release-2026-10-31.md).
Latest [readiness reconciliation](REPORT-readiness-reconciliation-2026-10-31.md)
passes 920 Trino tests and assembly checks; Doris checks retain the unchanged
89-test baseline. Those results do not declare the separate red network/server
acceptance tasks passed.

## Completed consumer subgates

- [x] Verify actual v3 negotiation, not just a driver constant.
- [x] Observe a real server query, cancel it with SQLState 57014 and verify recovery.
- [x] Qualify bounded CANCEL-under-partition and bounded DISCONNECT independently
  of the ordinary request timeout. The old 0.11 CANCEL-deadline blocker was fixed
  in 0.11.1 and the gates pass on released 0.12.0.
- [x] Keep a SQL-idle session alive for 65 seconds via heartbeats alone; preserve
  its session-local state, then remove the physical session and heartbeat worker
  on close. Twenty physical-session cleanup cycles are also checked.
- [x] Qualify Arrow ownership/read/close races and session cleanup for the exact
  cases in the [lifecycle report](../REPORT-lifecycle-qualification-2026-10-29.md).

## Open acceptance issues

- [ ] **Server orphan reaping:** `qualifyQuackLeaseReaping` remains red against
  signed core `f96420ed46`; abandoned sessions persist beyond the lease without a
  later message addressing their ID. Await a real server correction, then rerun
  the unchanged hard-cut/expiry assertions. Healthy heartbeat renewal is not expiry.
- [ ] **Published-port streaming:** `qualifyQuackStreaming` remains intermittently
  red after the [HTTP investigation](../REPORT-http-response-truncation-2026-10-30.md).
  Receive-window stalls were independently reproduced on the host forwarding path;
  the exact networking correction is still unidentified/unapplied. Direct-bridge
  controls are diagnosis, not a replacement acceptance route.

## Wider qualification still open

- [ ] Complete shared wire-level conformance for indexed fetch batches, cumulative
  ACKs, terminal counts, read-ahead ordering and late errors. Do not infer complete
  protocol correctness from successful consumer scans.
- [ ] Qualify reconnect/result-retention semantics, explicit memory/TTL/row caps
  and producer backpressure. Existing heartbeat tests do not enable retention.
- [ ] Extend to TLS, long-running results, mixed-version rejection and the full
  network-partition/server-restart/resource-policy matrix.

Do not replay SQL, ping abandoned target sessions to force cleanup, weaken fault
assertions, raise global timeouts or silently switch transport routes to close
these gates. Driver fixes do not establish server expiry or repair host networking.

The broader engine/driver/extension matrix is tracked in
[`../../dev-docs/TODO-duckdb-2.0.md`](../../../dev-docs/archive/TODO-duckdb-2.0.md). Historical Quack fixes and
0.7 snapshot qualifications are preserved in [`archive/`](./).
