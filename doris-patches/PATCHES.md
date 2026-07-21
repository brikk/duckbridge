# Doris patches for the `doris-duckbridge` connector

The duckbridge Doris connector is an out-of-tree **plugin (SPI) connector** for Doris's
`fe-connector` catalog SPI (the `branch-catalog-spi` line, pre-release). It runs **only on our
own patched FE + BE** until the SPI (and our BE type handler) land in a Doris release. Two small,
reapplyable patches carry that delta:

| # | Patch | Touches | What |
|---|---|---|---|
| FE | `fe/0001-spi-ready-types-duckbridge.patch` | `fe-core` `CatalogFactory.java` | whitelist catalog type `"duckbridge"` in `SPI_READY_TYPES` |
| BE | `be/0001-duckdb-type-handler.patch` | `be-java-extensions/jdbc-scanner` | new `DuckDbTypeHandler` + a `case "DUCKDB"` in `JdbcTypeHandlerFactory` |

**Patch files are the canonical artifact** (not fork commits): they live here as visible diffs so
the upstream-PR obligation is impossible to forget and the delta is reviewable at a glance. The
fork mirror (`brikk/doris`) exists only to keep the **pinned baseline SHA alive** — upstream
`branch-catalog-spi` rebases constantly and GCs SHAs. The pin is recorded in exactly one file,
[`BASELINE`](./BASELINE), which docs and `tools/doris-baseline.sh` read.

A read-only connector needs **only** the FE whitelist guard + the BE handler. The
`pluginCatalogTypeToEngine` CREATE-TABLE patch that `doris-ducklake` carries is **not** included
here (it's a write/DDL gate) — see the plan's Deploy shape.

---

## ⚠ Never build blind — pin discipline

> `branch-catalog-spi` **rebases constantly**; upstream SHAs get GC'd. **Never build from a blind
> branch tip.** Always build from the pin in [`BASELINE`](./BASELINE):
>
> - **`PIN_SHA`** = `568c4bb4571e23836a9ff659e6c4ef2fc7508f83`
> - **subject** = `[perf](catalog) two-level cross-query cache for external partition derived views (#65829)`
> - **fork branch** = `duckbridge/baseline-20260721` on `https://github.com/brikk/doris.git`
>
> The pin survives upstream GC because it's pushed to the fork as an **immutable dated baseline
> branch**. If you ever see the SHA missing from *upstream*, that's expected — fetch it from the
> **fork**. If a re-vendor moves the pin, re-diff both patches (`git apply --3way --check` must be
> clean), record what moved and why it's benign in the Re-vendor log below, and update `BASELINE`.
> Keep `BASELINE`, this note, and the Re-vendor log in sync.

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
# 1. Verify the patches still apply at the pin (default mode; clones the fork at the pin):
tools/doris-baseline.sh --check-only

# 2. Apply the patches into the cache checkout:
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
# against a clean checkout of the pin (fork mirror keeps the SHA alive):
git apply --3way doris-patches/fe/0001-spi-ready-types-duckbridge.patch
git apply --3way doris-patches/be/0001-duckdb-type-handler.patch
```

Both patches carry a rationale/upstream-ask header (lines above the `--- (patch body below ...)`
marker are commentary that `git apply` ignores) followed by a `git diff`-format body.

---

## Exit criteria (the goal is deletion)

Each patch is an upstream ask. When the fe-connector SPI **and** our `DuckDbTypeHandler` PR land in
a Doris **release**: `BASELINE` points at the release tag, the fork mirror becomes optional, and
`doris-patches/` empties to a tombstone. A stock Doris carrying the SPI + our handler then runs the
plugin unpatched.

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
