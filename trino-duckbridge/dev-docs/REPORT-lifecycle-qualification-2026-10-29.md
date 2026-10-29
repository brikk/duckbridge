# Arrow/Quack lifecycle qualification — 2026-10-29

## Scope and current status

Follow-up to the Quack JDBC 0.11 / DuckDB 2 qualification. Tests use actual native
statements, Arrow C-data readers, an isolated Quack server and its diagnostic view.
No mocked rows, source-format substitution, transparent query retry or shared
service interruption is involved. Existing unsupported-type guards are unchanged.

The Arrow and healthy-network idle/cleanup checks below pass. Network-partition
cancellation is a separate acceptance contract and is recorded after its run;
passing normal cancellation does not establish a bounded control request under
a blackholed response.

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
