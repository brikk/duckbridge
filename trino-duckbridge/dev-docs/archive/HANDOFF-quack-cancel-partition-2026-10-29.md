# Quack JDBC follow-up: bounded CANCEL under response loss

## Target / status

For the `brikk/duckdb-quack-jdbc` plugin/maintainer. Published
`dev.brikk.duckdb:quack-jdbc:0.11.0`, Java 17, JAR SHA-256
`7c5889640b38e494a39c0bf818f1e0e35fb68152a00e0dcc9f273a5f29935ff3`.
No dependency substitution or driver modification has been made in DuckBridge.

**Normal cancellation, SQLState 57014, streaming faults and bounded DISCONNECT
already pass.** This is a narrower missing contract: the CANCEL response itself
is blackholed while the general query/request timeout is long.

The server already supports CANCEL_REQUEST=12. Tested server: DuckDB 2.0.0,
signed core Quack `f96420ed46aee9c42064bdb99ddfd1230d6c0086`, protocol v3.
Do not invent a protocol action or replace statement cancellation with DISCONNECT.

## Reproduce

Current DuckBridge checkout:

```bash
./gradlew :trino-duckbridge:qualifyQuackCancelPartition
```

Source: `trino-duckbridge/test/src/dev/brikk/duckbridge/trino/plugin/TestQuackNetworkLifecycle.kt`,
`qualifyCancellationUnderPartition()`. The new gate is in the lifecycle follow-up
working tree; it is not present in the earlier published `4211393` commit alone.

1. Connect through an isolated Toxiproxy with `requestTimeout=PT30S`.
2. Set the target session's `max_execution_time=15000` as a cleanup fallback.
3. Run a marked `SELECT sum(range) FROM range(1000000000000)` on a worker.
   Independently observe that exact query as active before attempting cancel.
4. Add a downstream timeout toxic with duration 0: server receives requests but
   client responses are held indefinitely.
5. Call the real `Statement.cancel()` on another worker.
6. Require cancellation **or explicit fail-closed teardown** within five seconds:
   two seconds for a CANCEL request, up to two more for DISCONNECT, plus scheduling.
7. Restore the response path in `finally`, join workers and close the fixture.
   No SQL is retried and no shared/user service is interrupted.

On 0.11.0 the gate fails with a `TimeoutException` waiting for `cancel()`, rather
than a bounded SQLException/fail-closed result. Restoring delivery lets the
outstanding call finish, demonstrating an ACK wait rather than unsupported cancel.
Initial red evidence: `build/quack-cancel-network-fault.log`.

## Source cause and real fix

Published `QuackHttpTransport.send()` computes:

```java
boolean disconnect = request instanceof QuackMessage.DisconnectMessage;
boolean control = disconnect || request instanceof QuackMessage.CancelRequest;
Duration timeout = disconnect && requestTimeout.compareTo(DEFAULT_DISCONNECT_TIMEOUT) > 0
        ? DEFAULT_DISCONNECT_TIMEOUT : requestTimeout;
```

Both controls correctly avoid multiple endpoint attempts, but only DISCONNECT
gets the independent two-second timeout. CANCEL inherits 30/60 seconds.
`QuackSession.cancel(Query)` waits synchronously in `send()` while holding
`queryGate`; its fail-closed catch is not reached until that wait ends.

Please give CANCEL its own short request budget, without shortening ordinary
queries. For the existing HTTP path, applying the existing cap to `control`
instead of only `disconnect` addresses this wait; introduce a distinct CANCEL
constant if desired. Review custom-transport behavior separately rather than
claiming the HTTP timeout alone bounds every possible QuackTransport.

Preserve the existing behavior on an unconfirmed cancellation: close/invalidate
the session, return an explicit SQLException, and never advertise a reusable
ambiguous session. Keep single-attempt controls and statement-owned UUID targeting.
Do not retry the original SQL. The source catch already closes the connection;
with separate CANCEL and DISCONNECT budgets the five-second consumer gate allows
both operations to fail closed.

## Acceptance

- Healthy cancel remains actual query interruption with SQLState 57014, followed
  by healthy autocommit reuse.
- With CANCEL ACK loss and a 30s general timeout, cancel returns a failure and
  the connection is closed inside the five-second total allowance.
- Ordinary long-running requests keep their configured deadline.
- No second endpoint attempt or SQL replay is introduced.
- Statement/query UUID race and explicit-transaction invalidation tests remain.
- Repeat the existing bounded-DISCONNECT and streaming-fault gates.

Do not publish or bump DuckBridge's dependency without user approval. Return the
source commit, tests, artifact version and any remaining limitations.
