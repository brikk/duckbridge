# Arrow/Quack lifecycle qualification — 2026-10-29

## Scope and current status

Follow-up to the Quack JDBC 0.11 / DuckDB 2 qualification. Tests use actual native
statements, Arrow C-data readers, an isolated Quack server and its diagnostic view.
No mocked rows, source-format substitution, transparent query retry or shared
service interruption is involved. Existing unsupported-type guards are unchanged.

The Arrow and healthy-network idle/cleanup checks below pass. The separate
**CANCEL-under-partition deadline gate is blocked on published Quack JDBC 0.11**:
the control acknowledgement can wait for the general request timeout. Passing
normal cancellation does not establish this deadline contract.

## Real bug found: close could miss lazy query startup

The old page-source close path sent cancellation once, then waited on the read
monitor. A reader could already own that monitor but not yet have entered native
execute. The DuckDB JDBC driver's `cancel()` intentionally returns immediately
when the statement is not running. The read could then start an expensive query
after that no-op, leaving close blocked until the query finished or timed out.

`TestArrowQueryCancellation.testCloseCannotMissTheLazyExecuteStartupWindow`
reproduced this deterministically: pause immediately before the real execute,
wait until the real cancellation call has returned, then release execute. The
two-second close gate failed on the old code. The eight-second native timeout is
only a cleanup fallback, never accepted as successful cancellation. Original red
evidence: `build/arrow-query-cancellation.log`.

The real fix is in our lifecycle coordination: use an interruptible/timed read
lock and continue requesting interruption while the **single owned read** holds
it. Never re-execute SQL and never free the reader's resources concurrently.
Both Arrow page-source variants use the same ordering. The closed flag now means
resource teardown completed; an interrupted closer still cleans up and restores
its interrupt flag. No engine or driver workaround was added.

## Arrow lifecycle proof

`TestArrowSourceLifecycle` and `TestArrowQueryCancellation` cover:

- Close before first read: no query starts, statement/connection close, subsequent
  reads are terminal, repeated close is harmless.
- Exact multi-batch EOF, automatic cleanup and pages that remain valid after the
  Arrow reader/allocator/connection have closed.
- Execution errors remain errors, conversion errors retain their original type,
  and both close all owned resources rather than becoming successful EOF.
- A barrier after a real native execute proves close requests interruption but
  cannot free the connection while a read holds the lock.
- Twenty actual read/two-closer races: every delivered row is an exact ordered
  prefix, no duplicates, no gaps, bounded completion, zero allocator memory and
  closed statement/connection.
- Cancellation of a genuinely running native query, observed through the driver's
  public query-progress API, plus the deterministic startup window described above.
- A caller already interrupted still gets completed teardown and its interrupt
  flag restored.

`TestQuackArrowSourceLifecycle` uses the production `QuackDuckBridgeExecutor` and
real native Arrow stream: EOF, early close, conversion failure, remote execution
failure, and eight read/two-closer races. Independent server snapshots must settle
to the observer session only after every execution. Local allocator memory must
return to zero. These are resource/session assertions, not a process-RSS benchmark.

Targeted command (**13 tests pass**, lint passes):

```bash
./gradlew :trino-duckbridge:test \
  --tests '*TestArrowSourceLifecycle' \
  --tests '*TestArrowQueryCancellation' \
  --tests '*TestQuackArrowSourceLifecycle' :trino-duckbridge:detekt
```

Green evidence: `build/arrow-lifecycle-fixed.log`.

## Real idle lease and physical-session cleanup

`TestQuackHeartbeatAndSessions` has two tests, both passing:

1. Leave the target connection completely SQL-idle for **65 seconds**, beyond the
   published driver's requested 60-second v3 lease. The scheduled heartbeat alone
   must keep the same connection ID alive. A per-session TEMP table must still be
   readable afterward, ruling out a transparent replacement session. Physical
   close must remove that exact server session and stop its owned heartbeat thread.
2. Twenty physical connection/query/early-result-close cycles. Each session ID and
   its identified Java heartbeat worker must disappear after physical close. The
   final server snapshot contains only the short-lived observer—no orphan idle
   sessions carrying old query/cache state.

No heartbeat interval or lease timeout was shortened to make the test convenient.
The observer uses a separate connection and never sends SQL on the idle target.
Result retention/reconnect caching is not enabled by this test.

```bash
./gradlew :trino-duckbridge:test --tests '*TestQuackHeartbeatAndSessions' :trino-duckbridge:detekt
```

Evidence: `build/quack-heartbeat-sessions.log`. It includes the actual elapsed wait,
not a mocked clock. Targeted success does not yet claim all partition, catalog
conflict or prepared-schema-change cases are qualified.

## Network cancellation: restored delivery versus a prolonged partition

The existing active-query helper now observes an actual marked query with a
15-second engine fallback and 30-second HTTP timeout. Neither can explain a
successful two-second ordinary cancellation gate; its error must be SQLState
57014. After a confirmed ordinary cancel, the same autocommit connection must
execute its next control query successfully.

A new positive test holds all downstream responses, sends CANCEL, and observes
the target's **cancelled** state using a separate direct server connection. Only
then is response delivery restored. The original query must fail as cancellation,
not return rows or successful EOF. Two precisely checked safe outcomes are allowed:

- The cancellation ACK is confirmed and the same connection passes its control.
- The held TCP response is lost: cancel reports `Cancellation could not be
  confirmed`, the connection is explicitly closed, and reuse fails as closed.

Arbitrary errors are not equivalent. The target session ID must disappear after
physical close, and no SQL is replayed. Timeout-toxic removal can sever a held
response; a test must not demand a confirmed ACK when the network actually lost it.

### Blocked independent deadline

```bash
./gradlew :trino-duckbridge:qualifyQuackCancelPartition
```

This **strict additional acceptance task remains red on 0.11**. It holds the
CANCEL response beyond a five-second allowance for a two-second control request
plus fail-closed DISCONNECT, while retaining a 30-second ordinary timeout. The
observed failure is TimeoutException waiting for `cancel()`.

The source cause is exact: QuackHttpTransport uses `control` to suppress multiple
endpoint attempts, but only `disconnect` selects the short timeout. CANCEL still
uses the long query/request timeout; QuackSession.cancel blocks in that send
before its fail-closed catch can run. No timeout reduction, asynchronous adapter,
test skip or local driver substitution hides the failed contract.

[Driver handoff and reproduction](HANDOFF-quack-cancel-partition-2026-10-29.md).
Initial evidence: `build/quack-cancel-network-fault.log`. The default regression
suite and this explicit dependency-acceptance gate are reported separately; a
green module check is not a claim that this gate passed.

## Fixture correction exposed by restart qualification

Docker can reassign a dynamically published host port when a container is
restarted through the Docker API. Testcontainers' GenericContainer keeps its
original inspect response. Direct diagnostic observers therefore once connected
to the old port, even though the proxy path had already recovered.

The fixture now inspects the **current** mapping; the restart test verifies both
the proxy path and direct observer. This is a concrete stale-address fix, not an
environment-variance skip or relaxed network-failure assertion.

## Abandoned-session lease cleanup: second blocked peer contract

The hard-cut run proves a target request fails and its physical client closes
within the deadline while DISCONNECT cannot reach the server. A separate direct
observer confirms the target logical session exists. With no possible target
heartbeat or reconnect, wait 65 seconds and require that session to disappear.

On signed core `f96420ed46` it **remains present**. This is not the same as healthy
heartbeat renewal: in `QuackServer::RenewConnectionLease` the expired entry is
erased and cleaned only when a message addresses that session. The diagnostic
snapshot merely copies active handles. A closed/lost client may never address
its old ID again, so automatic abandoned-session resource cleanup is not proved.
Red evidence: `build/quack-network-and-partition.log` (65-second test).

```bash
./gradlew :trino-duckbridge:qualifyQuackLeaseReaping
```

**User-directed deferral, 2026-10-30:** another agent is handling the peer changes.
Keep both this gate and `qualifyQuackCancelPartition` as explicit failing
acceptance tasks and revisit after corrected artifacts are available. The exact
hard-cut test body and assertions are retained; it is not hidden with @Disabled,
globally excluded or claimed passing by a green default regression suite. No
target-session ping/watchdog, global timeout change or forced restart pretends
to reap the orphan. The reaper itself belongs to the Quack server, separately
from the JDBC CANCEL timeout correction.

A focused revisit TODO is in the shared project backlog. Independent prepared
schema/retry checks can proceed without waiting. No new driver artifact, native
rebuild or release is assumed by this deferral.

## Final independent verification

Trino `check` and `verifyPluginAssembly` pass with **701 tests, zero
failures/errors/skips** (`build/lifecycle-final-checks.log`). This includes the
healthy-network, Arrow race/cleanup and real heartbeat checks, plus the later
[prepared-schema/catalog-conflict tests](REPORT-prepared-catalog-lifecycle-2026-10-30.md).

The two explicit peer acceptance tasks remain **blocked/red**, not counted as
passing or silently relaxed. Their revisit TODO records the expected corrected
driver/server artifacts. No dependency switch, native rebuild, publication,
deployment or push was performed for this lifecycle follow-up.
