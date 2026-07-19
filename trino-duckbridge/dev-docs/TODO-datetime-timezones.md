# TODO — date/time/timestamp & timezone coverage

The DuckLake connector accumulated a lot of hard-won date/time/timezone correctness
work for its Trino→DuckDB read path. Status of what carried over here, and what still
needs doing.

## Carried over (working, tested)

- **Session zone alignment** — `TrinoTimeZoneNormaliser` translates the Trino session
  zone for DuckDB's `SET TimeZone` (`Z`→`UTC`, integer-hour `±HH:00`→`Etc/GMT∓H` POSIX
  inversion, named IANA pass-through) and the client sets it on every session
  connection (embedded and Quack — verified over the wire).
- **Fail loud on unsettable zones** — if DuckDB rejects the zone (e.g. fractional bare
  offset `+05:30`) while `pushdown_timestamp_with_timezone` is enabled, the connection
  THROWS with an actionable message (tz-sensitive functions may already be pushed;
  evaluating them in a mismatched zone would return wrong rows). With the property off
  it's a one-shot warn (nothing tz-sensitive pushes). Tested both ways in
  `TestDuckBridgePushdown`.
- **Tier gating in the translator** — date/time functions push over `DATE` /
  `TIMESTAMP` always; over `TIMESTAMP WITH TIME ZONE` only when the
  `pushdown_timestamp_with_timezone` session property is on (default on).
- **20 date/time parity functions** (`year` ... `with_timezone/2`) via `trino_parity`,
  drift-tested against `trino_meta()`.
- **TIMESTAMP columns** map to DuckDB microsecond `TIMESTAMP` (`TIMESTAMP_MICROS`),
  read + write.
- **DATE** read/write (string-rendered write with `CAST(? AS DATE)`).

## Gaps (the remaining shizola)

1. **`TIMESTAMP WITH TIME ZONE` columns are unmapped.** A DuckDB `TIMESTAMPTZ` column
   falls to unsupported-type handling (error or convert-to-varchar). The DuckLake path
   read these through its Arrow converter; the JDBC column-mapping equivalent
   (instant-semantics ↔ Trino `TIMESTAMP(6) WITH TIME ZONE`) was never written.
2. **`TIMESTAMP` precision > 6.** No write mapping for `TIMESTAMP(7..12)` (throws
   NOT_SUPPORTED); DuckDB `TIMESTAMP_NS` columns are not read. Decide: round/deny on
   write, map `TIMESTAMP_NS` → `TIMESTAMP(9)` on read.
3. **`TIME` / `TIME WITH TIME ZONE`** — unmapped in both directions.
4. **DST edge semantics** — the DuckLake suite had explicit coverage around DST
   gaps/overlaps for `date_trunc`/day-boundary functions over TIMESTAMPTZ; only part
   of that case matrix was ported into the translator tests. Re-audit against the old
   `TestDucklakeDatetime*` suites and port the missing cases.
5. **Extreme dates** — DuckDB vs Trino range limits (year 294247 vs 9999 etc.) are
   untested here; the domain path renders date literals via base-jdbc, verify the
   boundaries round-trip (or are refused) rather than wrap.
6. **`from_unixtime`/`to_unixtime`/`with_timezone` zone-dependence** — parity fixtures
   cover them in UTC-ish zones; add exotic-zone fixtures (Kathmandu +05:45,
   Lord Howe +10:30/DST+11) to the drift/aliases test.
7. **Arrow engine (`DUCKDB_LOCAL`) date/time coverage** — the Arrow→Page converter
   handles the types the tests exercise; give it the same date/time matrix as the JDBC
   path before promoting it beyond benchmark status.
