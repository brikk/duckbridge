# Published brikk-sql intake — 2026-10-28

October-29 follow-up: [expanded JSON mutation audit](REPORT-json-mutations-2026-10-29.md),
[boundary/pool checks](REPORT-boundaries-lifecycle-2026-10-29.md), and a
[separately qualified Trino REGEXP_COUNT subset](../../trino-duckbridge/dev-docs/REPORT-regexp-count-qualification-2026-10-29.md).
The no-new-pushdown outcome below describes this initial generic-generator intake;
the later guarded Trino mapping does not widen the published generator's authority.

## Outcome

**Stable `brikk-sql` / `brikk-sql-metadata` 0.21.0 is integrated as Java-17 audit
tooling, not as a connector runtime dependency.** Published catalogs, live engine
contracts, generated SQL and host semantics are separate gates. No new predicate
pushdown was enabled.

The initial sibling checkout was stale. The published API now exposes both DuckDB
1.5.6 and 2.0.0 snapshots, with 2.0.0 as default. Do not scrape generated Kotlin
source or treat nearest-version metadata as an exact host contract.

## Publication and dependency gates

- Verified snapshot inputs were metadata `0.20.0-20261028.193904-9` and core
  `0.20.0-20261028.194009-9`, both classfile 61 / Java 17.
- The `0.20.0` finals already on Maven Central were different Java-21 artifacts.
  The real Java-17 launch failed; a new consumer bytecode guard rejects them
  explicitly. The negative regression checks the classfile-65 rejection reason;
  no JVM gate or test was bypassed.
- At the user's request a bounded publication watch checked 0.21.0 after fifteen
  minutes (then would check at five-minute intervals). The first check found both
  JVM publications and root Gradle metadata complete.
- Both 0.21.0 JARs are **byte-identical** to the verified `-9` inputs:
  - core: `a801f140827f913b10b394b220bc62c4bfaff2e6309edbc5376bb5a66742b5c6`;
  - metadata: `506ea02a67bbf983122a978fb3fb0657ff6d606a801820b0e247aa509f5678b2`.
- Seventeen common catalog/type/generation/oracle exports matched byte-for-byte
  between the snapshot and release runs. The stable pin now resolves from Maven
  Central only; the temporary snapshot repository/pins were removed.
- The existing **Kotlin 2.3.10 compiler** compiles the audit source without bypass
  flags. Its separate runtime uses the libraries' declared Kotlin **2.4.10** and
  serialization **1.11.0** on Java **17.0.19**. This is not proof that those libraries
  can be dropped into Doris's parent-first classloader.
- `verifyFunctionAuditIsolation` proves no brikk-sql libraries/audit classes enter
  the connector JAR/ZIP or runtime graph and the connector retains Kotlin 2.3.10.
  The Doris production JAR remains byte-identical to the qualified API-24 JAR.

Evidence is under `build/function-audit-*.log`,
`build/brikk-sql-snapshot-review/watch-021.log`, and
`doris-duckbridge/build/function-audit/`. The snapshot/release comparison is
`snapshot9-vs-release021.json`. These are local audit/build outputs, not releases.

## Same-policy overload/type diff

The public `DuckdbCapture` decoder captures the actual **DuckDB JDBC 2.0.0.0**
registry and types; the same `DuckdbDump.toSnapshot()` mapping produces both the
bundled and live catalogs. Full normalized function contracts and type catalogs
match, not merely their counts.

| Contract | 1.5.6 | 2.0.0 |
|---|---:|---:|
| Function definitions (name + kind) | 874 | 1,093 |
| Overloads | 2,675 | 2,963 |

- **221 added names; 2 removed locale wrappers** (`icu_collate_yue`,
  `icu_collate_yue_cn`). These are same-JDBC-policy facts, unlike the older
  Python-versus-CLI name inventory.
- **13 aggregate→window kind changes**, not thirteen removed functions.
- **68 existing definitions change signature shape**. Other changes include
  parameter labels; they are not automatically type changes.
- Added type names are **TIMESTAMPTZ_NS** and **TUPLE**. This does not enable their
  transport or silently widen the existing microsecond/text contracts.
- The candidate report excludes internal functions, isolates collation work,
  and holds side-effecting/session-dependent/unknown-purity functions. Even its
  pure scalar candidates have `pushdown_qualified=false`.
- Doris 4.1.5 and Trino 484 are the bundled host catalogs. Selection for our
  master Doris / Trino 483 returns **exact=false**. Case-insensitive primary-name
  and alias overlap is an advisory candidate hint, never semantic authority.

The complete diff/candidates/provenance are in `intake-021.json`; the input exports
include `artifacts.tsv`, `runtime.tsv`, `capture.tsv`, `selections.tsv`, and the
published/live JSON and type TSVs.

## Generated SQL is not a safe predicate compiler

The exact-pin Doris FE/BE and an independent embedded DuckDB 2 oracle execute
the published generator's explicit scalar corpus. Real JDBC result metadata is
recorded: native type, precision, scale, nullability, SQL NULL and UTF-8 result
bytes. Source versions and control queries are checked before/after the probes.
An initial experiment assumed portable TYPEOF support; Doris has no such function.
It was replaced by real `ResultSetMetaData`, not by dropping type checks. Its
failed observations remain local exploratory evidence, not parity results.

Eleven Doris observations: **3 value differences, 2 matching-value/metadata
differences, 6 DuckDB errors for successful Doris source calls**.

| Shape | Concrete observation | Decision |
|---|---|---|
| Doris `LENGTH('straße')` | Doris 7 bytes; unchanged generated DuckDB LENGTH returns 6 code points | Do not use generic generation; existing connector's STRLEN rewrite remains correct |
| Doris `LENGTH('a😀b')` | Doris 6 bytes; DuckDB 3 code points | Same blocker |
| `CHARACTER_LENGTH('straße')` | Values match (6), protocol return metadata differs | Existing audited mapping retained; not a new qualification |
| JSON SET/INSERT with SQL integer value | Doris succeeds; generated DuckDB call does not bind (JSON-typed value required) | Hold |
| JSON SET with explicit JSON value | Doris retains the second duplicate key, DuckDB removes it | Hold |
| JSON REMOVE, valid object | Result bytes match; precision metadata differs | Not broad JSON/path/error proof |
| JSON REMOVE, malformed JSON | Doris yields SQL NULL, DuckDB errors | Hold |
| Doris OVERLAY `(string,start,length,replacement)` | Valid source calls succeed; generation uses DuckDB's different argument order and fails binding | Hold; do not silently reorder without the remaining semantic corpus |

The source-valid OVERLAY corpus includes Unicode and zero start. The report is
`metadata/published-doris-observations.json`, with exact SQL, types and diagnostics;
`oracle-versions.tsv` records the verified engines. These scalar probes do not
replace the earlier real JDBC/Quack transport qualification.

Eight source-valid Trino REGEXP_COUNT lowerings run against **Trino 483** and
DuckDB 2: six value/type matches (including empty, Unicode and SQL NULL cases),
two **valid Trino lookaround patterns that error in DuckDB/RE2**. There is no generic
regex alias. The production translator is asserted to continue declining the
unqualified function. Unrelated/both-errors are never counted as parity; a host
failure or dead/poisoned oracle fails the test.

The module-local reviewed corpus is
`trino-duckbridge/test/resources/function-intake/brikk-sql-regexp-count.tsv`.
The metadata audit verifies it against the actual published generator; it is not
regenerated to erase differences. Native observations are in
`trino-duckbridge/build/function-audit/published-trino-observations.tsv`.

## Reproduce

```bash
# Java-17 publication guard, versioned exports, real JDBC capture, artifact hashes.
./gradlew :doris-duckbridge:exportFunctionMetadata :doris-duckbridge:verifyFunctionAuditIsolation

# Closed input-contract diff plus the reviewed Trino generator corpus.
python3 doris-duckbridge/compose/metadata_intake.py \
  --metadata-dir doris-duckbridge/build/function-audit/metadata \
  --trino-corpus trino-duckbridge/test/resources/function-intake/brikk-sql-regexp-count.tsv \
  --output doris-duckbridge/build/function-audit/intake-021.json

# Native Trino observations; no brikk-sql dependency on the connector/test runtime.
./gradlew :trino-duckbridge:test --tests '*TestPublishedSqlIntake'

# Start only this project's qualified development stack, retaining old volumes.
bash doris-duckbridge/compose/smoke.sh --no-build --up-only
./gradlew :doris-duckbridge:auditGeneratedSql
docker compose -f doris-duckbridge/compose/docker-compose.yml down  # no -v
```

Optional producer qualification: `-PbrikkSqlAuditVersion=<stable version>` changes
only the audit dependency pair; `-PfunctionAuditOutput=<project-relative directory>`
preserves separate exports for comparison. No publishing, remote push, native
engine rebuild or connector capability expansion is part of this work.

## Verification status

Audit compile/Java-17 execution, real engine probes, metadata/corpus comparisons,
bytecode-negative regression, isolation and lint passed. Forced full module checks
and packaging verification passed: **72 Doris tests, 645 Trino tests, zero
failures/errors/skips**. Cluster-free orchestration/intake/bootstrap checks:
**19 passed**. Final logs: `build/function-audit-final-checks.log` and the dated
audit-export/native-oracle logs. The run-owned Doris stack was stopped without
deleting volumes; sibling services and shared staged Doris source were untouched.
