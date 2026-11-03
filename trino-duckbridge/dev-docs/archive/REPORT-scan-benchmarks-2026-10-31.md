# Round 4: embedded scalar scan benchmarks — 2026-10-31

## Decision

**Keep the current transport defaults.** Chunked reads are a modest numeric win
in this profile, not a universal improvement. Arrow wins substantially on text.
Streaming helps early close but is not uniformly faster for full ordered reads.
No production page source, execution-engine enum, JDBC streaming default or Doris
scanner is changed. The prototype and benchmark exist only in the test source
set; plugin assembly explicitly rejects their classes if they leak into its JAR.

This is a measured non-adoption, not a failed benchmark hidden behind a skip.

## Scope and method

Profile: native JDBC **2.0.0.0** / DuckDB **2.0.0**, Trino **483** Page types and
the existing production Arrow-to-Page converter, JDK **25.0.3+9-LTS**, Linux AMD64.
Quack is adopted at **0.12.0**, but these are **embedded API-level** measurements,
not Quack-network, Doris-JNI or end-to-end Trino planning benchmarks.

- Five public-API modes: materialized row JDBC, streaming row JDBC, chunked
  `DuckDBPreparedStatement.query()`, Arrow over ordinary JDBC results, and Arrow
  over explicitly streamed JDBC results. Streaming flags are local to owned test
  connections. No private-field/vector access or engine/format substitution.
- Six controlled workloads, each with the **same prepared SQL and two bound
  BIGINT parameters across modes**. This is not yet a production page source
  consuming base-jdbc's rendered split SQL.
- BIGINT, DOUBLE and VARCHAR only, including NULL fields and repeated Unicode
  strings. Wide data has 33 BIGINT columns. It does not qualify decimals, temporal
  precisions, unsigned/wide integers, binary, UUID or nested types for a new reader.
- All readers actually build Trino Pages, consume every selected field into an
  ordered value digest and verify the complete result against independent
  JDK-generated expected values. Getter and conversion work is not replaced by
  a COUNT/SUM shortcut. Correctness tests also use nonzero parameter bounds.
- A 2,048-row page target; actual page counts are recorded. Production Arrow's
  batch setting is not changed by this common benchmark target.
- One DuckDB execution thread, child JVM `-Xms256m -Xmx1g`, two active processors.
  One fresh JVM per workload/mode per run, three warmups and five measured
  iterations. Two complete runs give **60 child JVMs / 300 measured scans**, ten
  samples per cell. Mode order is rotated by workload. No samples are discarded.
- Timed work includes query preparation/binding/execution, Page conversion,
  digest consumption and statement/result teardown. Fixture creation and
  connection initialization are outside timed scans. Data is warm-cache.
- Full reads explicitly order by ID. The early-close case consumes only the
  first 2,048 of one million rows from a controlled single-thread physical scan,
  without ORDER BY, so it does not add a blocking sort before the prefix. Its
  prefix is verified; this is not a general SQL ordering guarantee.

## Observed wall-time medians

Milliseconds, ten observations across two independent JVMs per cell:

| Workload | Row JDBC | Row streaming | Chunked | Arrow | Arrow streaming |
|---|---:|---:|---:|---:|---:|
| 1,000 numeric rows | 2.095 | 1.773 | 1.721 | 2.063 | 2.834 |
| 1,000,000 numeric rows | 46.846 | 52.244 | 43.683 | 44.031 | 47.264 |
| Select 1,000 / 1,000,000 numeric rows | 1.917 | 1.876 | 1.845 | 2.314 | 2.181 |
| 250,000 Unicode text rows | 166.158 | 171.799 | 97.150 | 40.235 | 40.009 |
| 100,000 nullable, 33-column rows | 47.429 | 60.074 | 43.387 | 47.810 | 46.884 |
| Early close after 2,048 / 1,000,000 | 6.343 | 4.306 | 4.183 | 6.359 | 4.584 |

Numeric full-read differences are small: chunked is about 7% faster than ordinary
row JDBC on the million-row case and about 9% on the wide case. Those margins do
not establish an across-workload/platform win. Short/early-close results show
greater between-fork variation; raw min/max and per-fork medians are retained.

The text result is more decisive here: Arrow is about **4.1x faster** than row
JDBC and **2.4x faster** than chunked. Median measured caller-thread Java
allocation is approximately **454 MiB row / 480 MiB chunked / 194 MiB Arrow** per
text scan. The public chunk VARCHAR getter calls a native byte-string accessor,
constructs a Java String, then our Page writer re-encodes UTF-8; Arrow conversion
does not need that Java String round trip. Primitive chunk accessors use direct
ByteBuffer reads. These are inspected code paths, not a fabricated count of all
JNI calls; total JNI transitions were not instrumented.

## Memory and measurement limits

CSV preserves caller CPU time and caller-thread allocated bytes separately from
wall time. CPU is not asserted to include all native worker work, and allocation
does not include native memory or other threads.

Native `duckdb_memory()` and Java heap are sampled in a **separate untimed sweep**
through an independent duplicate connection; the active owner is never re-entered.
These are phase samples, not complete peak measurements. A zero sampled native
delta is **not** proof of no native allocation: transient sorting/execution can
finish before the first Page observation, and allocator/native-export accounting
has limits. Arrow-managed allocation likewise excludes unaccounted native C-data
export buffers. Process RSS high-water includes JVM/library warmup, all scans and
the memory sweep; it is not a per-query native-memory value. No memory claim is
made by subtracting Java heap from RSS. Raw memory observations are retained,
not used to justify a default change.

Two forks on one shared Linux host are not a platform matrix or statistical
confidence interval. These measurements guide the next qualification, not an
operator-wide performance guarantee.

## Correctness, cleanup and adoption boundary

`TestScanBenchmarkReaders` checks all five readers against independent values
for numeric, text and wide shapes over multiple Pages and nonzero binds. It also
checks verified early-close prefixes, independent memory sampling, completed
statement/Arrow allocator cleanup, owner-connection reuse, explicit upfront chunk
query errors and explicit parent-connection-close failure before another fetch.

It does **not** establish every in-flight read/cancel race, late streaming error,
full type surface, planner interaction or remote transport contract for a new
production chunk page source. The benchmark's known cardinality/digest rejects
unexpected early EOF; that is benchmark validation, not a general-purpose fix
for every possible driver error path. Existing production lifecycle guards/tests
remain in force. No unsupported value is stringified to make a reader pass.

## Reproduce and evidence

```bash
./gradlew :trino-duckbridge:test --tests '*TestScanBenchmarkReaders' :trino-duckbridge:detekt
./gradlew :trino-duckbridge:benchmarkScans
./gradlew :trino-duckbridge:benchmarkScans
```

Each invocation prints `BENCHMARK_RESULT_DIR`, with `samples.csv`, `memory.csv`,
profile, child argument files and logs. Both runs are retained under
`trino-duckbridge/build/scan-benchmark/owned-run-*`. Collated evidence is under
`build/round4-scan-benchmark/`: correctness/benchmark logs, per-run CSV/profile,
`summary.json` and `summary.csv`, including every measured sample and min/max.

Targeted correctness/lint and final Trino `check`/plugin verification pass:
**919 Trino tests**, zero failures/errors/skips. Doris `check`/isolation also passes
with its unchanged 89-test released-0.12.0 qualification. Evidence:
`full-checks.log`, `test-summary.json` and saved `test-results/`.

TODO **5bdd9ba4** was revisited after this round as requested. The isolated raw
native JDBC 2.0.0.0 tuple-list control **still reproduces SIGSEGV/string-cast** in
the final suite (`post-round4-native-tuple-negative-control.log`). Quack 0.12.0's
count fix is not a native JDBC getter fix. The temporary guard and open TODO
therefore remain; no benchmark result removes them by implication and no source
CAST is inserted automatically.
