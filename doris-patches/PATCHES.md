# Doris patches for the `doris-duckbridge` connector

The duckbridge Doris connector is an out-of-tree **plugin (SPI) connector** for Doris's
`fe-connector` catalog SPI, which is now **merged into apache/doris `master`** (#64304 + the whole
`fe/fe-connector` tree; the pre-merge brikk `branch-catalog-spi` fork line is retired). The FE runs
the plugin **patch-free**; only the **BE** type handler is not yet upstream. As of pin
`1731787677f` (2026-08-25, apache/doris master) **exactly one** small, reapplyable patch carries the delta:

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
baseline is now plain **apache/doris `master`** (the pre-merge `brikk/doris` fork mirror is retired —
master keeps the pinned SHA alive itself). The pin is recorded in exactly one file,
[`BASELINE`](./BASELINE), which docs and `tools/doris-baseline.sh` read.

A read-only connector now needs **only** the BE handler (the FE whitelist guard is obsolete — see
the box above). The `pluginCatalogTypeToEngine` CREATE-TABLE patch that `doris-ducklake` used to
carry is likewise dead (#66135 made `ENGINE=` optional/connector-owned); duckbridge never carried it.

---

## ⚠ Never build blind — pin discipline

> apache/doris `master` moves fast. **Never build from a blind branch tip** — always build from the
> pin in [`BASELINE`](./BASELINE):
>
> - **`PIN_SHA`** = `1731787677f0199ccdb4fe6318f9116310627c52`
> - **subject** = `[chore](lance) update lance version to tag 0.1.7 (#67115)`
> - **upstream** = `apache/doris` `master` (`FORK_URL=git@github.com:apache/doris.git`)
>
> **Operating model: local-only, no fork push.** We apply the BE patch LOCALLY against an
> apache/doris `master` checkout at `PIN_SHA` (the worktree `~/DEV/OSS/doris`) and build the SPI jars
> from that checkout's `fe/`. Master keeps the SHA alive (no dated fork-mirror branch to maintain
> anymore). If a re-vendor moves the pin, re-diff the remaining patch (`git apply --3way --check`
> must be clean), record what moved and why it's benign in the Re-vendor log below, and update
> `BASELINE`. Keep `BASELINE`, this note, and the Re-vendor log in sync.

## Bootstrap: the project-local Doris SPI jars (SELF-CONTAINED)

The gradle module (`doris-duckbridge/`) compiles against two Doris SPI jars
(`org.apache.doris:fe-connector-spi`, `fe-thrift`, both `1.2-SNAPSHOT`; #66407 merged the old
`fe-connector-api` into `fe-connector-spi`). A fresh
clone on a clean machine builds end-to-end with **one** bootstrap command:

```bash
JAVA_HOME=<jdk17> tools/doris-baseline.sh --install-spi-jars
```

This clones apache/doris `master` at the `BASELINE` pin, applies the BE patch, and `mvn install`s the SPI jars —
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
  pulls every reactor dependency — `fe-connector-spi → fe-thrift` + `fe-foundation` /
  `fe-extension-spi` / `fe-filesystem-api` — so that single `-pl` target yields the two jars the
  module needs (`fe-connector-spi`, `fe-thrift`) plus the transitive Doris deps. (#66407 merged
  `fe-connector-api` into `fe-connector-spi`, so there is no separate api jar anymore.)
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
# 1. Verify the BE patch still applies at the pin (default mode; clones apache/doris master at the pin).
#    Simplest: skip the clone by pointing DORIS_SRC at the existing apache checkout:
#      DORIS_SRC=~/DEV/OSS/doris tools/doris-baseline.sh --check-only
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
# against a clean apache/doris master checkout at the pin. BE patch only —
# the FE builds patch-free (the SPI is upstreamed; #66135 removed the SPI_READY_TYPES whitelist).
git apply --3way doris-patches/be/0001-duckdb-type-handler.patch
```

The patch carries a rationale/upstream-ask header (lines above the `--- (patch body below ...)`
marker are commentary that `git apply` ignores) followed by a `git diff`-format body.

---

## Exit criteria (the goal is deletion)

The remaining patch is an upstream ask. The FE ask is already **paid** — the fe-connector SPI is now
in apache/doris `master` and #66135 removed the `SPI_READY_TYPES` whitelist, so the FE runs the
plugin unpatched (the FE patch was retired at pin `a0c10f0672b`). What's left: when our
`DuckDbTypeHandler` PR lands in a Doris **release**, `BASELINE` points at the release tag and
`doris-patches/` empties to a tombstone. A stock Doris carrying the fe-connector SPI + our BE handler
then runs the plugin fully unpatched.

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

- **2026-08-25 — re-vendor to apache/doris `master` `1731787677f`** (subject: *"[chore](lance) update
  lance version to tag 0.1.7 (#67115)"*). **Non-breaking except one required stamp bump.** Tracks the
  pin `doris-ducklake` adopted; verified against the local apache checkout `~/DEV/OSS/doris` at this SHA.
  - **⚠️ Plugin API-version MAJOR bumped `5` → `6`** (`#66413`; `fe/fe-connector/pom.xml
    <connector.plugin.api.version>` is now `6.0`). A plugin stamped `5.0` is rejected at load
    (`stage=apiVersion` → `CREATE CATALOG` fails "No connector plugin claimed catalog type"). Bumped
    the `jar` manifest stamp `Doris-Connector-Plugin-Api-Version` `5.0` → `6.0`; verified the rebuilt
    spi jar ships `api.version=6.0` and the plugin-zip jar stamps `6.0`.
  - **No other `fe-connector-spi` surface change** — `#66413` added only `default` methods. Zero
    connector source changes: SPI jars rebuilt from `1731787677f` into `doris-m2`;
    `:doris-duckbridge:test` (62) + `:detekt` + `:jar`/`:pluginZip` green.
  - **BE `DuckDbTypeHandler` patch unchanged** — `git apply --3way --check` clean at this pin.
  - **Live smoke GREEN** (thin-overlay BE `FROM doris-be:master-local` + retagged master FE): the
    **6.0 gate passes** (plugin loads, `CREATE CATALOG type=duckbridge`), metadata, LARGEINT/ARRAY/
    unicode decode, pushdown P1/P3/P6, `count(*)`, P2 load — all pass.
  - **§12b DEFAULT-backfill (shared, ducklake) N/A:** `#66413` briefly re-introduced then re-fixed the
    schema-evolution DEFAULT-read BE crash at this pin (back to a silent `0`-not-default miss);
    duckbridge passes `null` for `ConnectorColumn.defaultValue` and reads no schema-evolved parquet.

- **2026-08-16 — re-vendor to apache/doris `master` `b119273e3f0`** (subject: *"[fix](load) Keep
  graceful BE stop bounded when an audit stream load is in flight (#66797)"*; +44 over `a82564ced5d`).
  **Routine, non-breaking bump — zero source changes.** Tracks the pin `doris-ducklake` adopted
  (its 2026-08-16 re-vendor); verified against the local apache checkout `~/DEV/OSS/doris` at this SHA.
  - **No `fe-connector-spi` surface change since `a82564ced5d`** — the api→spi merge rewrite and the
    API-version `5.0` stamp from the 2026-08-06 entry still hold (re-verified: `<connector.plugin.api.version>`
    is `5.0` at this pin; `org.apache.doris.connector.spi.*` types incl. `ConnectorScanRequest` present;
    FE patch-free — `SPI_READY_TYPES` absent). SPI jars rebuilt from `b119` into `doris-m2`;
    `:doris-duckbridge:test` (62) + `:detekt` + `:jar`/`:pluginZip` green with **no `.kt` edits**.
  - **BE `DuckDbTypeHandler` patch unchanged** — `git apply --3way --check` clean at `b119` (jdbc-scanner
    factory anchors unmoved; no offset refresh needed).
  - **#66628 (normalize connector table errors) edits `PluginDrivenScanNode`** — the FE scan node our
    reads flow through. **Live smoke GREEN on `b119`** (thin-overlay BE `FROM doris-be:master-local` +
    retagged `b119` master FE): metadata, LARGEINT(HUGEINT-max)/ARRAY/unicode decode, predicate +
    `count(*)`, P1 function pushdown, P3/P6 temporal, P2 load — all pass. #66628 is a no-op for us.
  - **Not applicable to duckbridge (shared open items on the ducklake side):** §12b schema-evolution
    DEFAULT-backfill reads `0` not the default — duckbridge passes `null` for `ConnectorColumn.defaultValue`
    and reads no schema-evolved parquet, so it never hits this. timestamptz is fully resolved upstream.
  - **BE full-build note (unused by us):** a fresh master BE build needs a build-env image ≥ 2026-08-15
    (`#66783` hadoop-3.4.2 thirdparty bump + unity builds) — irrelevant here since we thin-overlay the
    jdbc-scanner jar rather than rebuild the BE C++.

- **2026-08-06 — re-vendor to apache/doris `master` `a82564ced5d`** (subject: *"[fix](iceberg) Fix
  MVCC and nested schema evolution edge cases (#66345)"*). Tracks the pin `doris-ducklake` adopted;
  verified against the local apache checkout `~/DEV/OSS/doris` at this SHA. **Two breaking changes in
  the `ded91fb9fb3..a82564ced5d` window (+74 commits), both handled; `<revision>` stays
  `1.2-SNAPSHOT` so `doris-m2` coordinates are unchanged.**
  - **#66407 — `fe-connector-api` merged INTO `fe-connector-spi`, package `connector.api.*` →
    `connector.spi.*`.** The `fe-connector-api` module/artifact is gone; `fe-connector-spi` now
    carries the whole contract (verified: all types duckbridge uses — `Connector`,
    `ConnectorMetadata`, `ConnectorType`, `handle.*`, `pushdown.*`, `scan.*`,
    `ConnectorScanRequest` — live under `org.apache.doris.connector.spi.*`). Changes: rewrote the
    imports `connector.api.` → `connector.spi.` across **16** connector `.kt` files (imports only,
    no logic; no duplicate-import collisions); `build.gradle.kts` dropped the `fe-connector-api`
    `compileOnly`/`testImplementation`, repointed the bootstrap anchor-jar check at `fe-connector-spi`,
    and dropped the `exclude("fe-connector-api-*.jar")` from the plugin zip.
  - **#66xxx — CONNECTOR plugin API-version MAJOR bumped `1` → `5` (fail-closed gate).**
    `fe/fe-connector/pom.xml <connector.plugin.api.version>` is now `5.0`; a plugin stamped `1.0` is
    rejected at load (`stage=apiVersion`, then `CREATE CATALOG` → "No connector plugin claimed catalog
    type"). Bumped the `jar` manifest stamp `Doris-Connector-Plugin-Api-Version` `1.0` → `5.0`
    (verified the rebuilt spi jar ships `api.version=5.0` and the plugin-zip jar stamps `5.0`).
  - **BE `DuckDbTypeHandler` patch — regenerated against `a82564ced5d`.** Still required (no upstream
    DUCKDB handler / registration seam). Its factory hunk offsets drifted (imports shifted on master),
    so `git apply --3way --check` was failing on the stale `@@` offsets; re-diffed the body against
    the pin (correct offsets + real blob hash) and updated the header anchor line — `--3way --check`
    now clean.
  - **No connector source churn beyond the import rewrite.** SPI jars rebuilt from master into
    `doris-m2` (had to `rm` root-owned reactor `target/` dirs left by doris-ducklake's containerized
    build first — regenerable outputs, no effect on `output/fe`/`output/be` or images);
    `:doris-duckbridge:test` (62) + `:detekt` + `:jar`/`:pluginZip` green.
  - **BE image + live smoke: DONE, FULL PASS on master `a82564` (2026-08-07).** Thin-overlaid the
    patched jdbc-scanner jar (compiled against this pin's stock jar) `FROM doris-be:master-local`
    (doris-ducklake's fresh a82564 master BE) and retagged its a82564 master FE — no C++ rebuild.
    `compose/smoke.sh` GREEN end-to-end: the **API-version 5.0 gate passes** (FE load
    `registered types: [adbc, duckbridge, …]`, `CREATE CATALOG type=duckbridge` on the pristine FE),
    metadata (HUGEINT→LARGEINT, VARCHAR[]→ARRAY), full-row decode of LARGEINT (HUGEINT max) + ARRAY +
    unicode via the BE `DuckDbTypeHandler`, predicate/`count(*)`, P1 function pushdown, P3/P6 temporal,
    P2 load (20 seq + 8 concurrent, 0 failures). The handoff's still-open master BE blocker
    (schema-evolution DEFAULT-backfill read SIGSEGV in `format_v2::TableReader`) does **not** touch
    duckbridge — it reads a fixed quack schema with no schema evolution.

- **2026-08-01 — re-vendor to apache/doris `master` `ded91fb9fb3`** (subject: *"[fix](ci) Skip
  usage-limited Codex review accounts (#66319)"*). **The fe-connector SPI is UPSTREAMED — the brikk
  `branch-catalog-spi` fork line is retired; we now vendor from apache/doris `master`.** Tracks the
  sync point `doris-ducklake` adopted (its 2026-07-31 move to `master`); verified against the local
  apache checkout `~/DEV/OSS/doris` at this SHA.
  - **Baseline retargeted to master.** `BASELINE`: `FORK_URL=git@github.com:apache/doris.git`,
    `UPSTREAM_BRANCH=master`, `FORK_BRANCH` relabeled to `master` (no dated fork-mirror branch —
    master keeps the SHA alive). `<revision>` in master's `fe/pom.xml` is still `1.2-SNAPSHOT`, so
    the `org.apache.doris:*:1.2-SNAPSHOT` coordinates are unchanged — **no gradle-coord edits**.
  - **Zero connector source changes.** master is +316 commits over the old fork merge-base with
    ~7k lines of api/spi churn (`ConnectorScanRangeType`→`ConnectorScanRequest`/`Profile`,
    `ConnectorContext` refactor + new `ConnectorStorageContext`/`ForwardingConnectorContext`,
    `ScanNodePropertyKeys`), but **none of it touches the subset duckbridge uses** — the #66135-era
    `planScan(session, ConnectorScanRequest)` collapse we already absorbed covered the shape.
    Re-verified: `:doris-duckbridge:compileKotlin`+`compileTestKotlin` clean, `test` (62) + `detekt`
    + `jar`/`pluginZip` green. (Matches `doris-ducklake`: zero-change move.)
  - **FE patch-free on master.** `CatalogFactory.SPI_READY_TYPES` still absent; routing via
    `ConnectorFactory.createStandaloneCatalogConnector` (registered-provider lookup). Re-checked.
  - **#66211 API-version gate unchanged** — both sides ship major `1.0`
    (`fe/fe-connector/pom.xml <connector.plugin.api.version>1.0`); our jar's
    `Doris-Connector-Plugin-Api-Version: 1.0` manifest stamp still valid (confirmed in the plugin zip).
  - **BE patch UNCHANGED and still required.** `be/0001-duckdb-type-handler.patch` re-verified
    `git apply --3way --check` clean on master. Re-confirmed the need against master's `jdbc-scanner`:
    no `DuckDbTypeHandler`/`case "DUCKDB"` upstream, no ServiceLoader registration seam, and the
    three-way stock-handler split still holds (Default = LARGEINT+VARBINARY no ARRAY; ClickHouse =
    LARGEINT+ARRAY no VARBINARY; Trino = ARRAY+VARBINARY drops LARGEINT) — DuckDB needs all three, and
    the factory default falls to `DefaultTypeHandler` (no ARRAY decode). SPI jars rebuilt from master
    into `doris-m2/`.
  - **BE image + live smoke — DONE, FULL PASS on master (2026-07-31).** No heavy rebuild was
    needed: the BE patch is jdbc-scanner-only (Java), so I compiled the two classes
    (`DuckDbTypeHandler` + `JdbcTypeHandlerFactory`) against the stock master jar and spliced them
    into `jdbc-scanner-jar-with-dependencies.jar`, then baked a **thin overlay** image
    `doris-be:duckbridge-local` `FROM doris-be:master-local` (doris-ducklake's master BE) that just
    `COPY`s the patched jar over — no C++ rebuild, no 16 GB re-bake. FE = doris-ducklake's master FE
    (`doris-fe:pr62767-local`, connector-agnostic + patch-free) retagged. `compose/smoke.sh` GREEN
    end-to-end against master: connector loads (`registered types: [duckbridge, …]`),
    `CREATE CATALOG type=duckbridge` on the **pristine master FE**, metadata (HUGEINT→LARGEINT,
    VARCHAR[]→ARRAY), full-row SELECT decoding **LARGEINT (HUGEINT max) + ARRAY + unicode** via the
    BE `DuckDbTypeHandler`, predicate/`count(*)`, P1 function pushdown (`character_length→length`),
    P3/P6 temporal (naive `TIMESTAMP` + explicit-UTC `TIMESTAMPTZ '…+00'`), and P2 load (20 seq + 8
    concurrent, 0 failures). The DuckDbTypeHandler patch is now proven on master, not just
    apply-clean.

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
