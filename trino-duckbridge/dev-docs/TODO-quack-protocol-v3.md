# Quack protocol contracts and remaining work

## Maintained contract

The connectors use Quack JDBC 0.14.0 with DuckDB 2.0.0 and assert actual v3
negotiation. Ordinary cancellation/SQLState 57014, recovery, bounded partition
CANCEL/DISCONNECT and healthy heartbeat/close cases have consumer regressions.
They do not prove every blocked I/O operation can be forcibly interrupted.

Unfinished result close/supersession drains through terminal FETCH synchronously;
it can wait for the remaining execution/transfer and has no aggregate drain deadline.
Closing FETCH failures propagate without retries or SQL replay. Explicit cancel
remains distinct; Connection.close retains bounded best-effort DISCONNECT. This
does not correct server orphan reaping or the published-port transport issue.

Do not replay SQL, ping an abandoned session to trigger expiry, weaken fault
assertions, increase global timeouts or change a tested route to close a gate.
All successful data/type/NULL/error/resource guarantees remain specific to their
proved path. Experimental Arrow engines are not production JDBC adoption.

## Open acceptance issues

- [ ] **Autonomous server orphan reaping:** abandoned sessions persist without a
  later message addressing their ID. Await a real producer correction, then rerun
  unchanged expiry assertions. [Lifecycle evidence](REPORT-lifecycle-qualification-2026-10-29.md).
- [ ] **Published-port streaming:** affected Docker forwarding paths can stall or
  truncate; the actual correction remains unidentified. Raw HTTP reproduces it.
  Direct-bridge controls are diagnosis, not replacement acceptance.
  [HTTP evidence](REPORT-http-response-truncation-2026-10-30.md).

## Further qualification — not newly discovered defects

- [ ] Indexed batches, cumulative ACKs, terminal counts, read-ahead and late errors.
- [ ] Reconnect/retention semantics, TTL/memory/row caps and producer backpressure.
- [ ] TLS, mixed versions, longer-lived results and broader partition/restart races.

Use the [project backlog](../../dev-docs/TODO-backlog.md) to select work.
Completed driver intake, old protocol snapshots and earlier tracker detail are
retained in [archive/](archive/README.md).
