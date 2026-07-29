# Doris patches for the `doris-duckbridge` connector

The duckbridge Doris connector is an out-of-tree **plugin (SPI) connector** for Doris's
`fe-connector` catalog SPI (the `branch-catalog-spi` line, pre-release). It runs **only on our
own patched FE + BE** until the SPI (and our BE type handler) land in a Doris release. As of pin
`0da96f1ad3e` (2026-07-30 re-vendor) **exactly one** small, reapplyable patch carries that delta:

| # | Patch | Touches | What |
|---|---|---|---|
| BE | `be/0001-duckdb-type-handler.patch` | `be-java-extensions/jdbc-scanner` | new `DuckDbTypeHandler` + a `case "DUCKDB"` in `JdbcTypeHandlerFactory` |

> **The FE patch is RETIRED.** `fe/0001-spi-ready-types-duckbridge.patch` (which whitelisted
> `"duckbridge"` in `CatalogFactory.SPI_READY_TYPES`) is **gone as of pin `a0c10f0672b`**: upstream
> **#66135** (`fce5af4e041`) removed `SPI_READY_TYPES` entirely. `CatalogFactory.createCatalog` now
> asks the registered connector providers first (`ConnectorFactory.createStandaloneCatalogConnector`)
> — any provider whose `getType()` claims the `CREATE CATALOG` type wins, so
> `type="duckbridge"` routes to our `DuckBridgeConnectorProvider` on a **pristine, unpatched FE**.
> No whitelist to patch. The FE now builds **patch-free**; the deleted diff lives on in git history.

**Patch files are the canonical artifact** (not fork commits): they live here as visible diffs so
the upstream-PR obligation is impossible to forget and the delta is reviewable at a glance. The
fork mirror (`brikk/doris`) exists only to keep the **pinned baseline SHA alive** — upstream
`branch-catalog-spi` rebases constantly and GCs SHAs. The pin is recorded in exactly one file,
[`BASELINE`](./BASELINE), which docs and `tools/doris-baseline.sh` read.

A read-only connector now needs **only** the BE handler (the FE whitelist guard is obsolete — see
the box above). The `pluginCatalogTypeToEngine` CREATE-TABLE patch that `doris-ducklake` used to
carry is likewise dead (#66135 made `ENGINE=` optional/connector-owned); duckbridge never carried it.

---

## ⚠ Never build blind — pin discipline

> `branch-catalog-spi` **rebases constantly**; upstream SHAs get GC'd. **Never build from a blind
> branch tip.** Always build from the pin in [`BASELINE`](./BASELINE):
>
> - **`PIN_SHA`** = `0da96f1ad3e7b91b777195d148c921a8f23b1f96`
> - **subject** = `[chore](handoff) record the 2026-07-30 rebase onto 794d514479e (upstream #65991)`
> - **fork branch** = `duckbridge/baseline-20260730` (reserved name; **NOT pushed** — see below)
>
> **Current operating model (2026-07-28): local-only, no fork push.** We apply the patch LOCALLY
> against a checkout already at `PIN_SHA` — the worktree `~/DEV/OSS/doris-catalog-spi` — and document
> here + in the friction log. That worktree is what keeps the SHA alive for us; we are **not**
> committing/pushing a `brikk/doris` fork branch right now. (The fork-mirror discipline below is the
> future publish path if we ever want a fresh clone to bootstrap without a local Doris checkout.) If
> a re-vendor moves the pin, re-diff the remaining patch (`git apply --3way --check` must be clean),
> record what moved and why it's benign in the Re-vendor log below, and update `BASELINE`. Keep
> `BASELINE`, this note, and the Re-vendor log in sync.

## Bootstrap: the project-local Doris SPI jars (SELF-CONTAINED)

The gradle module (`doris-duckbridge/`) compiles against three Doris SPI jars
(`org.apache.doris:fe-connector-api`, `fe-connector-spi`, `fe-thrift`, all `1.2-SNAPSHOT`). A fresh
clone on a clean machine builds end-to-end with **one** bootstrap command:

```bash
JAVA_HOME=<jdk17> tools/doris-baseline.sh --install-spi-jars
```

This clones the fork at the `BASELINE` pin, applies the patches, and `mvn install`s the SPI jars —
built from **our** pin — into a **project-local maven repo** at `doris-duckbridge/doris-m2/`
(gitignored). The gradle module resolves `org.apache.doris:*` from **that** directory (scoped to the
`org.apache.doris` group via `exclusiveContent`), **not** `mavenLocal()`/`~/.m2`.

**Why project-local, not `~/.m2`:** `~/.m2` is shared across projects. A different project
(`doris-ducklake`) publishes the **same** `org.apache.doris:*:1.2-SNAPSHOT` SNAPSHOT coordinates
from a **different** pin — last-build-wins clobbering. A project-local repo isolates duckbridge from
`~/.m2` and from `doris-ducklake` completely, so the build is deterministic and self-contained.
(This retires the old "`~/.m2` jars are two commits behind" caveat — we no longer touch `~/.m2` at
all.)

**Not bootstrapped yet?** Any compile/test/detekt task fails fast with the exact command above
(a task-graph guard in `build.gradle.kts`), never a cryptic "could not resolve org.apache.doris:…".

### Under the hood

- **Minimal reactor:** `mvn install -pl fe-connector/fe-connector-spi -am`. The `-am` (also-make)
  pulls every reactor dependency — `fe-connector-api → fe-thrift`, and the spi's `fe-extension-spi`
  / `fe-filesystem-api` — so that single `-pl` target yields all three jars the module needs (plus
  those two transitive Doris deps). 8 reactor modules build: parent POM, fe-thrift, fe-filesystem
  (aggregator + API), fe-extension-spi, fe-connector (aggregator + API + SPI).
- **`-P flatten`** is **required**: the `flatten-maven-plugin` lives in `<pluginManagement>`, so
  only the `flatten` profile binds it. It resolves `${revision}` / parent refs in the installed
  POMs; without it the POMs keep `<version>${revision}</version>` and gradle can't parse them.
- **`-Dmaven.repo.local=doris-duckbridge/doris-m2`** targets the project-local repo (this is also
  the local repo maven *reads* from, so transitive deps land there too — fine, gradle sources only
  `org.apache.doris` from it).
- **Parallelism:** `-T` = `min(nproc, 8)` (override with `MAVEN_JOBS`, hard-capped at 8).
- **thrift codegen:** `fe-thrift` generates Java from `.thrift` IDL with **thrift 0.16.0**
  (generator `java:fullcamel`). The `thrift-maven-plugin` (0.10.0) does **not** download a binary —
  it needs an executable. The script probes, in order: `$DORIS_THRIFT`, `thrift` on `$PATH`,
  `$DORIS_THIRDPARTY/installed/bin/thrift`, then the cache clone's own
  `thirdparty/installed/bin/thrift`; the first that reports `0.16.x` wins. If none is found it
  **fails loud** with install instructions (`DORIS_THRIFT=…`, or install 0.16.0, or run the Doris
  thirdparty build). It passes the chosen binary via `-Ddoris.thrift.executable=…`.

---

## Apply + rebuild procedure

`tools/doris-baseline.sh` automates the clone/fetch-at-pin + `git apply --3way --check` + apply
(fail loud on drift). The build/install steps are **opt-in flags** (they are not run in the
default `--check-only` mode) and require **JDK 17** (the Doris FE toolchain):

```bash
# 1. Verify the BE patch still applies at the pin (default mode; clones the fork at the pin):
tools/doris-baseline.sh --check-only

# 2. Apply the BE patch into the cache checkout (the FE builds patch-free at this pin):
tools/doris-baseline.sh --apply

# 3. Build FE and/or BE (JDK 17 required; multi-hour for the BE C++ build):
JAVA_HOME=<jdk17> tools/doris-baseline.sh --apply --build-fe --build-be

# 4. Bootstrap the SPI jars into the project-local repo (doris-duckbridge/doris-m2/):
JAVA_HOME=<jdk17> tools/doris-baseline.sh --install-spi-jars   # see §Bootstrap above
```

The FE/BE build steps run Doris's own `build.sh --fe --be`. `--install-spi-jars` is the gradle
bootstrap (§Bootstrap). The BE patch touches only `be-java-extensions/jdbc-scanner` (Java) — a
patch-only change can rebuild just that jar and overlay it into an existing BE image without
re-running the C++ build.

### Manual apply (equivalent)

```bash
# against a clean checkout of the pin (fork mirror keeps the SHA alive). BE patch only —
# the FE builds patch-free at this pin (#66135 removed the SPI_READY_TYPES whitelist).
git apply --3way doris-patches/be/0001-duckdb-type-handler.patch
```

The patch carries a rationale/upstream-ask header (lines above the `--- (patch body below ...)`
marker are commentary that `git apply` ignores) followed by a `git diff`-format body.

---

## Exit criteria (the goal is deletion)

The remaining patch is an upstream ask. The FE ask is already **paid** — #66135 removed the
`SPI_READY_TYPES` whitelist upstream, so the FE runs the plugin unpatched (the FE patch was retired
at pin `a0c10f0672b`). What's left: when our `DuckDbTypeHandler` PR lands in a Doris **release**,
`BASELINE` points at the release tag, the fork mirror becomes optional, and `doris-patches/` empties
to a tombstone. A stock Doris carrying the fe-connector SPI + our BE handler then runs the plugin
fully unpatched.

---

## Noted future upstream asks (NOT patches — no current diff)

These are gaps a future feature would need Doris to close. None blocks v1; recorded so the asks
aren't lost.

- **A per-scan JDBC connection-init hook on `JdbcJniScanner` (probe P3/P6).** The BE's
  `JdbcJniScanner` configures HikariCP with a fixed set (`setDriverClassName/JdbcUrl/Username/
  Password` + pool sizing) and exposes **no `connectionInitSql` / per-scan connection property**.
  Combined with pooled-connection reuse, this makes any per-scan `SET TimeZone` (or other session
  init) impossible to apply soundly — a smuggled `SET` in `query_sql` persists on the pooled
  connection and poisons the next scan. **Ask:** a HikariCP `connectionInitSql` (applied on
  checkout/reset so it can't leak across pooled scans) or a per-scan connection-property map in
  `jdbc_params`. **Needed for:** any future tz-*sensitive* pushdown that depends on the DuckDB
  session zone (e.g. `at_timezone` rewrites). **Not needed for v1** — duckbridge's enabled temporal
  predicates are all zone-independent (naive wall-clock, or explicit-UTC `TIMESTAMPTZ '…+00'`
  literals; see `doris-duckbridge/dev-docs/REPORT-doris-timezone-probe.md`), so no `SET` is required.

---

## Re-vendor log

- **2026-07-30 — re-vendor to `0da96f1ad3e`** (subject: *"[chore](handoff) record the 2026-07-30
  rebase onto 794d514479e (upstream #65991)"*; SHAs churn on rebase — match by subject). Tracks the
  pin `doris-ducklake` adopted (its 2026-07-29 re-vendor); verified against the local worktree
  `~/DEV/OSS/doris-catalog-spi` already at this SHA. **One required plugin-side change (#66211); no
  connector `.kt` changes.**
  - **#66211 (`88abe41a4e3`) — fail-closed plugin API-version gate. REQUIRED, or the plugin won't
    load.** The FE's `ApiVersionGate` now REJECTS any directory-loaded connector plugin whose
    factory jar lacks a `Doris-Connector-Plugin-Api-Version` MANIFEST main attribute (absent ⇒
    refused at `STAGE_API_VERSION`). Verified against the gate source at the pin
    (`fe-extension-loader/.../ApiVersionGate.java`): the attribute name is exactly
    `Doris-Connector-Plugin-Api-Version` and the rule is **major-must-match, minor/patch ignored**;
    the kernel major comes from `fe-connector-spi`'s
    `META-INF/doris/connector-plugin-api-version.properties` (`api.version=1.0` ⇒ major 1). Stamped
    `Doris-Connector-Plugin-Api-Version: 1.0` into the connector `jar` task
    (`doris-duckbridge/build.gradle.kts`); confirmed present in the built jar AND in the jar bundled
    under `lib/` in the plugin zip (that's the artifact the FE loads). Bump when the SPI major changes.
  - **BE patch UNCHANGED and still required.** `be/0001-duckdb-type-handler.patch` re-verified
    `git apply --3way --check` clean at this pin (`case "CLICKHOUSE"`/`case "SQLSERVER"` anchors
    intact). The FE remains patch-free (`SPI_READY_TYPES` still absent — re-checked).
  - **No SPI compile churn.** SPI jars rebuilt from the pin into `doris-m2/`;
    `:doris-duckbridge:test` (62) + `:detekt` + `:jar`/`:pluginZip` green, zero `.kt` edits (the
    #66135 scan-surface adaptation from the 2026-07-28 entry already covered the API shape; nothing
    moved between `a0c10f0672b` and here that our surface touches — the intervening commits are
    hive/iceberg/paimon fixes + CTAS-atomicity + dead-credential-surface deletion, none of which we
    consume).
  - **Live smoke NOT run here** (compile + unit + detekt + patch-apply + manifest-in-zip verified).
    `doris-ducklake` ran a full live smoke on this same pin (FULL PASS incl. plugin load with
    `failureCount=0`), which exercises the same `ApiVersionGate` path duckbridge now satisfies.
  - **Operating model unchanged:** local-only, no fork push (patches applied against the worktree
    at the pin; SPI jars built from that checkout's `fe/`).

- **2026-07-28 — re-vendor to `a0c10f0672b`** (subject: *"[chore](handoff) record the 2026-07-27c
  rebase onto e7b7f1d1359 (upstream #66004 storage facade)"*; SHAs churn on rebase — match by
  subject). **First PATCH-FREE FE build — the FE patch is RETIRED.** Tracks the same pin
  `doris-ducklake` adopted (its 2026-07-27 re-vendor, upstream #66135 `fce5af4e041`); verified
  against the local worktree `~/DEV/OSS/doris-catalog-spi` already checked out at this SHA.
  - **FE patch DELETED (`fe/0001-spi-ready-types-duckbridge.patch`).** #66135 removed
    `CatalogFactory.SPI_READY_TYPES`; `createCatalog` now asks the registered providers first
    (`ConnectorFactory.createStandaloneCatalogConnector`), so a provider claiming
    `getType()=="duckbridge"` wins on a pristine FE — re-verified by reading `CatalogFactory.java`
    at this pin (the whitelist is gone; `"duckbridge"` is not a reserved `BUILTIN_CATALOG_TYPES`
    name, so `ConnectorPluginManager` accepts it). The diff survives in git history. The tool's
    `PATCHES` array and every `git apply` line here are now BE-only.
  - **BE patch UNCHANGED and still required.** `be/0001-duckdb-type-handler.patch` re-verified at
    this pin: `JdbcTypeHandlerFactory` still has `case "CLICKHOUSE"` / `case "SQLSERVER"` (we insert
    `case "DUCKDB"` between). The BE `jdbc-scanner` is not part of the fe-connector SPI, so #66135
    doesn't touch it.
  - **Connector source adapted to the #66135 SPI scan-surface consolidation** (compile-only churn,
    behaviour identical; the read-side metadata SPI + the pushdown surface are UNCHANGED, re-verified
    against the pin's `ConnectorMetadata`/`ConnectorSchemaOps`/`ConnectorTableMetadataOps`/
    `ConnectorType`/`ConnectorColumn`/`ConnectorTableSchema`):
    - `DuckBridgeScanPlanProvider`: the 4-arg/5-arg `planScan` overloads collapsed into one
      `planScan(session, ConnectorScanRequest)` (request carries handle/columns/filter/limit/
      requiredPartitions/countPushdown); `getScanRangeType()` and `estimateScanRangeCount()` are
      gone from `ConnectorScanPlanProvider` — removed. `getScanNodeProperties` (4-arg) is unchanged.
    - `DuckBridgeJdbcScanRange`: `getRangeType()`/`ConnectorScanRangeType` removed from
      `ConnectorScanRange` — dropped the override; the JDBC path is still selected by
      `getTableFormatType()=="jdbc"` and the default `populateRangeParams` → `jdbc_params`.
    - No test churn (no test constructed a scan range or called `planScan` directly). `structOf`
      (childless-`STRUCT` rejection, #66135 item 8) is a non-issue: the type mapper maps
      DuckDB STRUCT/MAP → `ConnectorType.of("STRING")`, never `of("STRUCT")`.
  - **Local-only operating model — no fork push.** We are NOT publishing a `brikk/doris` fork
    branch for this pin; patches are applied locally against the worktree `~/DEV/OSS/doris-catalog-spi`
    (already at `a0c10f0672b`), which is what keeps the SHA alive for us. SPI jars for the gradle
    build are produced by the `--install-spi-jars` maven invocation pointed at that checkout's `fe/`
    (that is exactly how this re-vendor was verified). `duckbridge/baseline-20260728` is a reserved
    name only, for if/when we later decide to publish a fork mirror.

- **2026-07-21 — re-vendor to `568c4bb457`** (subject: *"[perf](catalog) two-level cross-query
  cache for external partition derived views (#65829)"*), pushed to the fork as
  `duckbridge/baseline-20260721`. **Pure rebase — nothing in our patch surface moved.**
  - **Old pin `5f009592035` was rebased away** (`git merge-base --is-ancestor` → false): upstream
    `branch-catalog-spi` rebased and our old pin's twin is now `11f4deaa50` (identical subject).
    The old `duckbridge/baseline-20260719` fork branch still holds `5f009592035` alive; the new
    branch holds `568c4bb457`.
  - **Four new commits** sit on top of the old pin's twin (newest first): `568c4bb457`
    *[perf] two-level cross-query cache for external partition derived views (#65829)*;
    `777a61671a` *[perf] fe-connector-iceberg hot-path caching + fe-core per-statement metadata
    funnel*; `1ea735ff0a` *[fix] port #65676 iceberg deletion-vector metadata validation*;
    `e697837760` *[fix] port #65548 external COUNT(\*)/COUNT(col) semantics*. **None touch our two
    patched files** (`CatalogFactory.java`, `jdbc-scanner`); they're iceberg-scan / caching /
    `PluginDrivenScanNode` count-gating changes. The count(\*) one flips the `countPushdown` signal
    `PluginDrivenScanNode` hands `planScan()` from any-COUNT to `isTableLevelCountStarPushdown()`
    (COUNT(\*)-only) — duckbridge doesn't consume that signal today (we override only 4-arg/5-arg
    `planScan`, no `streamingSplitEstimate`), so it's a no-op for us; see the count-pushdown note.
  - **Patch anchors UNCHANGED at the new pin** (re-verified `git apply --3way --check`, clean for
    both): `SPI_READY_TYPES` is still `{jdbc, es, trino-connector, max_compute, paimon, iceberg,
    hms}` (append adds `"duckbridge"`); `JdbcTypeHandlerFactory` still has `case "CLICKHOUSE"` at
    :44 / `case "SQLSERVER"` at :46 (we insert `case "DUCKDB"` between). Byte-identical context to
    the 2026-07-19 pin — the rebase only moved the SHA.
  - **SPI jars rebuilt** from this pin into `doris-duckbridge/doris-m2/` via
    `tools/doris-baseline.sh --install-spi-jars` (FE compile plane refreshed).

- **2026-07-19 — initial baseline at `5f009592035`** (subject: *"[fix](catalog) iceberg
  system-table scan: restore #65262 positional JNI read + order-preserving projection"*), the
  current `branch-catalog-spi` tip, pushed to the fork as `duckbridge/baseline-20260719`. Both
  patches generated fresh against the real file content at this pin and verified with
  `git apply --3way --check` (clean). Notes:
  - **Upstream rebased since the `doris-ducklake` 2026-07-18 pin.** That project pinned
    `b2dff681aad`; upstream has since advanced (the intervening tip was `7b3821fe170`) plus **two
    iceberg system-table scan fixes** on top, the last of which is our pin `5f009592035`. Those
    fixes touch the iceberg scan path, **not** the connector API/SPI surface — so the connector
    compiles unchanged against the SPI jars.
  - **FE patch anchor:** `SPI_READY_TYPES` at the pin is
    `{jdbc, es, trino-connector, max_compute, paimon, iceberg, hms}` (`"hms"` was added by the Hive
    P11 migration). Our append adds `"duckbridge"` as the last element.
  - **BE patch anchors:** new `DuckDbTypeHandler.java` in
    `fe/be-java-extensions/jdbc-scanner/src/main/java/org/apache/doris/jdbc/`, and a
    `case "DUCKDB"` inserted after the existing `case "CLICKHOUSE"` arm in
    `JdbcTypeHandlerFactory.create()`.
  - **BE handler compile-checked** against the pinned `jdbc-scanner` deps (`java-common`
    `ColumnType`/`ColumnValueConverter` + Guava + HikariCP) with `javac` — compiles clean.
  - **SPI jars bootstrap:** `tools/doris-baseline.sh --install-spi-jars` builds the three SPI jars
    (+ 2 transitive Doris deps) from this pin into the project-local `doris-duckbridge/doris-m2/`
    repo (`-pl fe-connector/fe-connector-spi -am -P flatten`, thrift 0.16.0, `-T ≤8`), verified
    end-to-end: gradle `:doris-duckbridge:test :doris-duckbridge:detekt` resolves from `doris-m2/`
    (proven by a negative test — removing `doris-m2/` while `~/.m2` still holds doris jars fails
    with the actionable bootstrap message, never falling back to `~/.m2`). The old "`~/.m2` jars two
    commits behind" caveat is retired: we no longer resolve from `~/.m2`. FE/BE image rebuild not
    yet run for this pin — the patches are ready to apply when we next build Doris.
