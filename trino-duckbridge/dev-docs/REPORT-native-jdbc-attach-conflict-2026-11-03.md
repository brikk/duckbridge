# Native JDBC concurrent open/close conflict

## Upstream report

Added a Java reproducer to the existing open issue rather than creating a duplicate:

**[duckdb/duckdb#22277 — Java reproduction and results](https://github.com/duckdb/duckdb/issues/22277#issuecomment-6602525930)**

The user authorized reporting only, not an upstream JDBC fix or a connector
workaround. Opus 5.5 edited the draft for concise, friendly language. The posted
source was checked byte-for-byte against the [standalone reproducer](../../tools/repro/ConcurrentOpenClose.java).
The report separates observed behavior from an unverified root-cause hypothesis.

The JDBC repository has no issue template at `.github/ISSUE_TEMPLATE`; the core
bug template asks for behavior, reproduction, OS, version and client. The
follow-up supplies those facts. Since the existing issue's body is not ours to
edit, one comment contains a short visible report and a collapsible reproduction
section, instead of creating another issue or posting two notifications.

## Observed behavior

Eight threads independently open connections to the same fresh database file,
fully consume `SELECT 42`, and close their result, statement and connection.
The native driver intermittently throws:

```text
Resource In Use Error: Unique file handle conflict: Cannot attach "duckdb-open-close-..." - the database file "...db" is already attached by database "duckdb-open-close-..."
```

The exception starts in `DuckDBNative.duckdb_jdbc_startup`. No Trino, Quack,
Docker, application-loaded extension, explicit ATTACH, write or external dataset
is required. Default driver options are retained; no retry or pin/cache override
is used. This establishes a standalone native JDBC failure, not a precise faulty
source line or a verified native fix.

## Reproduction and checks

Save [ConcurrentOpenClose.java](../../tools/repro/ConcurrentOpenClose.java), then run:

```sh
curl -fLO https://repo.maven.apache.org/maven2/org/duckdb/duckdb_jdbc/2.0.0.1/duckdb_jdbc-2.0.0.1.jar
java --enable-native-access=ALL-UNNAMED -cp duckdb_jdbc-2.0.0.1.jar ConcurrentOpenClose.java
java --enable-native-access=ALL-UNNAMED -cp duckdb_jdbc-2.0.0.1.jar ConcurrentOpenClose.java 1
```

The default is eight threads, with up to 200 cycles each. Argument one sets the
thread count; optional argument two sets the output directory. Each process
creates a unique database name and leaves its generated file there for inspection.
The program stops after the first error; the reported cycle count can include
other threads finishing their current operation.

Verified on Ubuntu 26.04 LTS/x86_64/local filesystem:

- Native JDBC **2.0.0.0 and 2.0.0.1**, each with **Temurin 17.0.19+10 and 25.0.3+9**.
- One eight-thread run per combination: all four failed with the same attach
  conflict, reporting 15, 24, 9 and 9 completed cycles respectively.
- One one-thread control per combination: all four completed 200 cycles.
- Both published JARs report DuckDB **v2.0.0**, source **d54cbbe3df**.
- JAR SHA-256s match Maven Central sidecars. Testing 2.0.0.1 did not change the
  connector's selected native dependency or rebuild any native code.

Evidence, initial draft, Opus-edited public text and posting receipt are retained
under `build/release-closeout/native-jdbc-bug-report/`. The POST was followed by
a read-back verifying exactly one matching comment; the posting helper checks for
an existing matching report before submitting and does not blindly retry a failed
POST. No new issue, issue-type change or label change was made.

## Duplicate review and release boundary

Open #22277 already describes concurrent cached opens/closes on the same paths
through the C API. The Java report cautiously says it looks related, rather than
claiming a proved shared source-level cause. Closed #19171 concerns explicit
read-only ATTACH; open #25965 concerns an endless cache loop. Neither was treated
as the exact thrown-error report.

The connector's native pin remains **2.0.0.0**. No fixture was skipped or seeded
through a substitute path, and no production retry, serialization or instance-pinning
mitigation was introduced. The user subsequently accepted the upstream race as a
release limitation and approved test-only containment: the predicate-parity fixture
keeps one native connection alive until its query runner is closed. Every predicate
case, source value and assertion remains; the standalone race reproducer is retained.
This is not evidence that the production race is fixed. The original red 484
full-build receipt remains red. See the [preparation receipt](archive/REPORT-release-closeout-2026-11-03.md)
and [current publication receipt](REPORT-release-publication-2026-11-04.md).
