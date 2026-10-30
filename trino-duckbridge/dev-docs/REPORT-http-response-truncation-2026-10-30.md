# Initial HTTP response truncation investigation — 2026-10-30

## Outcome and boundaries

The pre-cut truncation is reproducible. Packet captures and socket/thread
snapshots locate the initiating stall on the **host-loopback leg of Docker's
published-port forwarding path**. Its small receive window produces TCP
silly-window-avoidance behavior; backpressure propagates through the proxy to
the server, which closes a partly sent fixed-length response. This is concrete
transport evidence, not an attribution to vague environment variance.

A raw HTTP control reproduces the stall with **no DuckDB, Quack JDBC or
Toxiproxy**. The default published-port acceptance gate remains intermittently
**red**. No test is skipped, retried to manufacture success, routed differently
by default or treated as passed because a diagnostic control passed.

No host/kernel/Docker configuration, global timeout, driver, native engine,
upstream checkout or production transport was changed. The exact kernel or
forwarder regression/fix revision is **not identified** by this investigation.

## Reproduction and evidence

Inputs remain released Quack JDBC **0.11.1** (SHA-256 `8cff...fe3b3`), signed
DuckDB **2.0.0** / Quack core **f96420ed46**, JDK **25.0.3+9-LTS**, Docker
**29.6.0**, host kernel **7.0.0-38-generic**. The query and limits are unchanged:

```sql
SELECT CAST(hash(range) & 9223372036854775807 AS BIGINT) AS value
FROM range(8000000)
```

The initial inline body declares **33,570,767 bytes**. In a healthy run it
contains 4,188,160 rows, still below the full eight-million-row result, so the
later intentional cut genuinely requires another FETCH.

Diagnostic instrumentation adds phase markers, optional repetitions, and failure
logs captured **before** destroying the owned containers. A first instrumented
run failed on iteration one after **13,541,543 bytes**. Subsequent packet-captured
runs failed after **13,341,719** and **12,060,239 bytes**, interspersed with healthy
initial responses taking roughly 0.15–0.31 seconds. No `initial-ready` or
`deliberate-cut` marker appears for the failing iteration.

The original two release-qualification failures are preserved separately in
`build/quack-0111-release-review/`; they had no packet captures. The new runs
reproduce their failure class rather than retroactively inventing missing traces.

### Three correlated TCP legs

The second capture includes only the owned fixture's server/proxy traffic and
the exact loopback published port. At the two-second stall snapshot:

- **Java client → published port:** actual client receive queue is **empty**;
  receive window is **24,576 bytes**, below the peer's **32,768-byte MSS**.
  The forwarding socket has **2,314,976 bytes** queued to send and reports
  100% receive-window-limited busy time. Captured payload delivery settles into
  approximately **200 ms** steps despite continuous body consumption.
- **Toxiproxy → forwarder:** **1,214,824 bytes** remain queued to send, with
  receive-window backpressure. This leg advertises zero windows during stalls.
- **Quack server → Toxiproxy:** the server has **1,698,696 bytes** queued to send;
  the proxy's receive queue holds **278,152 bytes**. It is likewise window-limited.

JVM thread snapshots place the main thread in `HttpResponseInputStream.current`
waiting for a body buffer; its HTTP selector is waiting in epoll, not decoding
rows. Flight recording shows no long GC pause explaining the stall (recorded
pauses are milliseconds). Heartbeat requests continue succeeding on another
connection. The server is running, `oomKilled=false`, and no deliberate toxic
or proxy disable has been applied to this iteration.

The server originates the partial-response FIN, which is relayed downstream.
The pinned HTTP implementation's write/provider path can terminate on a failed
write, and its default writable-wait timeout is five seconds; no logical SQL
error or result EOF is involved. We did not instrument native errno/timeout
returns, so the exact failed write call is not claimed observed.

The receive-window/MSS relationship and periodic transmission are a specific
TCP flow-control failure mechanism. Similar public kernel reports concern
window-clamp/SWS behavior, but we have not proved that any particular patch or
SO_RCVBUF-lock scenario is the one affecting this host.

### Independent controls (not substitutes for acceptance)

1. **Actual Quack through the container's bridge address:** same server,
   Toxiproxy, query, driver, default batch/allocation limits, fault injection and
   row assertions; only Docker's published-port loopback hop is omitted.
   **40/40 pass**, initial response 0.087–0.246 seconds. The initial-prefix and
   explicit post-cut SQLException assertions still execute.
2. **Raw HTTP, no engine/driver/proxy:** a fixed-length 33,570,767-byte response
   from a Java HTTP server behind Docker's published port. The same JDK input
   stream/read-growth algorithm stalls twice, at iterations 9 and 26, after only
   **2,372,608** and **1,632,368 bytes** within a ten-second observation window.
   Each stall is recorded as failure and its owned stream is cancelled; no
   partial transfer counts as success. This is byte-transfer diagnosis, not a
   fake JDBC result or an engine qualification result.
3. **Host-local raw HTTP without Docker forwarding:** 30/30 complete for whole
   response writes and another 30/30 with 8 KiB writes, with the same JDK reader.

The raw control's ten-second observation bound is not a changed connector query
timeout. Repetition means independent attempts that stop/fail on an unexpected
truncation, not transparent SQL replay.

## Durable diagnostics and remaining action

The normal task still uses the published endpoint:

```bash
./gradlew :trino-duckbridge:qualifyQuackStreaming
```

Optional repetition is controlled by `duckbridge.qualification.repetitions` in
the task JVM. A separately labelled direct-bridge diagnostic control is available
for isolating the forwarding hop; it is **not** published-port acceptance.
It has its own `:trino-duckbridge:probeQuackStreamingBridge` task and distinct
CLI gate name; setting a property cannot reroute the normal acceptance task.
The report must identify the route, not just quote a green task name.

The separately named bridge task was then rerun for another 40 iterations with
the default JUnit network class, lint and plugin assembly verification; all pass
(`final-diagnostic-checks.log`). This gives **80/80 bridge controls**, not 80
passed published-port acceptance trials. Full Trino `check` and assembly pass
with **891 tests, zero failures/errors/skips** (`full-trino-check.log` and saved
`full-trino-results/`); the explicit acceptance failure is reported separately.

Evidence under `build/http-unicode-investigation/`: phase/HTTP logs, original and
subsequent flight recordings, `previous-{server,proxy}.pcap`, `{server,proxy,host}.pcap`,
socket/thread snapshots, packet summaries, the 40-run bridge log, and raw HTTP
control sources/logs. Captures concern only owned test connections; they are not
traffic recordings of shared services.

Remaining action is to qualify a real host-network/kernel/forwarder correction
against the **original** published-port gate and raw control. Increasing socket
buffers, changing loopback MTU, reducing response size, extending timeouts or
switching acceptance to the bridge path would be mitigations, not a proven fix;
none was implemented. Consult the user before choosing one. The user-directed
wait on separate server-reaper and Doris JNI fixes is unchanged.
