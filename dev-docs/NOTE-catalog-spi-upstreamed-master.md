# Note: the connector SPI is now in apache/doris `master` — retire the fork, build from master

> **STATUS 2026-07-31 — DONE for duckbridge too (FE/SPI move + live BE validation, all GREEN).**
> duckbridge is re-vendored to apache/doris `master` `ded91fb9fb3` (`doris-patches/PATCHES.md`
> §Re-vendor log 2026-08-01), compiles with zero source changes, and a full `compose/smoke.sh`
> ran end-to-end against a **master FE + master-BE-with-our-`DuckDbTypeHandler`** (a thin jar
> overlay — no C++ rebuild; see the re-vendor log): connector loads on the pristine FE, and
> LARGEINT/ARRAY/unicode reads, pushdown (P1/P3/P6), count and P2 load are all green. The steps
> below are the original migration recipe (kept for the record).
>
> **STATUS 2026-07-31 — DONE for `doris-ducklake`.** The `fe-connector`
> catalog SPI **merged into apache/doris `master`** (`#64304` *decouple external catalogs from FE
> core into loadable connector plugins*, plus the whole `fe/fe-connector` tree incl.
> `fe-connector-api` / `fe-connector-spi`). `doris-ducklake` re-vendored off the retired brikk fork
> `branch-catalog-spi` and now builds the FE + its `~/.m2` SPI jars straight from **apache/doris
> `master`**, pin **`ded91fb9fb3`**. Full detail + the exact process:
> `~/DEV/brikk/doris-ducklake/fe-patches/FE-PATCHES.md` → "Re-vendor log" (2026-07-31 entry) and
> commit **`7920720`** (`re-vendor to apache/doris master ded91fb9fb3`).

This note tells duckbridge how to do the same, and how to piggy-back on the live master FE that
`doris-ducklake`'s smoke cluster already runs so you don't have to rebuild the FE to start.

---

## The headline: the fork is retired — vendor from apache/doris `master`

`branch-catalog-spi` was the pre-merge staging branch. It's now upstream, so:

- **Source of truth = apache/doris `master`** (`~/DEV/OSS/doris`, remote `origin=apache/doris`).
  Pin `ded91fb9fb3` (`[fix](ci) Skip usage-limited Codex review accounts (#66319)`, 2026-08-01).
  Master is **+316 commits** over the old fork merge-base (`#65299`); the fork's 13-commit P0–P6
  series is now redundant (upstream landed its own SPI).
- **`<revision>` is still `1.2-SNAPSHOT`** in master's `fe/pom.xml`, so the installed
  `org.apache.doris:*:1.2-SNAPSHOT` coordinates are **unchanged** — no gradle edits for coords.
- **Still PATCH-FREE on the FE side** (unchanged since #66135). Your one remaining patch is the **BE**
  `DuckDbTypeHandler` (`doris-patches/be/0001-duckdb-type-handler.patch`) — see the BE section below.
- **The `Doris-Connector-Plugin-Api-Version` gate (#66211) is still major `1.0`** on both sides
  (`fe/fe-connector/pom.xml <connector.plugin.api.version>1.0`). No manifest bump needed.

### How much SPI churn to expect: for `doris-ducklake`, **zero**

Going from the old fork pin (`0da96f1ad3e`) to master needed **no `doris-ducklake` source changes at
all** — main + test compiled clean, unit tests + assemble + live smoke + corpus-replay all green. The
~7k-line api/spi surface churn in master (`ConnectorScanRangeType`→`ConnectorScanRequest/Profile`,
`ConnectorContext` refactor, new `ConnectorStorageContext`/`ForwardingConnectorContext`,
`ScanNodePropertyKeys`) does **not** touch the subset the plugin uses. You already absorbed the
bigger #66135-era break (the `planScan(session, ConnectorScanRequest)` collapse etc. — see
`NOTE-catalog-spi-66135-patchfree.md`), so expect duckbridge to be **compile-clean or near-clean**
against master too. Verify, don't assume.

---

## Migration steps for duckbridge

### 1. Retarget the baseline to apache master

Edit `doris-patches/BASELINE`:

- `PIN_SHA=ded91fb9fb3…` (or track master tip — but pin for reproducibility)
- `PIN_SUBJECT=[fix](ci) Skip usage-limited Codex review accounts (#66319)`
- `FORK_URL=git@github.com:apache/doris.git` and `UPSTREAM_BRANCH=master`
- Drop/relabel the `FORK_BRANCH` "immutable fork mirror" model — master keeps the SHA alive itself.

`tools/doris-baseline.sh` clones `FORK_URL` into `DORIS_SRC` (default `~/.cache/duckbridge/doris`),
checks out `PIN_SHA`, applies `doris-patches/`, and can build. Simplest: point `DORIS_SRC` at the
apache checkout that already exists (`DORIS_SRC=~/DEV/OSS/doris`, on `master`).

### 2. Re-verify the BE patch on master

The FE is patch-free; the BE `DuckDbTypeHandler` patch is the only delta and it must re-apply onto
master's `be-java-extensions/jdbc-scanner`:

```bash
git -C ~/DEV/OSS/doris apply --3way --check -v \
  ~/DEV/brikk/duckbridge/doris-patches/be/0001-duckdb-type-handler.patch
```

If it no longer applies (JdbcTypeHandlerFactory moved/renamed on master), rebase the patch. This is
mechanical — it's a new `DuckDbTypeHandler` class + one `case "DUCKDB"` switch arm.

### 3. Reinstall SPI jars from master into `doris-m2/`

Your build resolves `org.apache.doris:*` from the **project-local** `doris-duckbridge/doris-m2/`
(NOT `~/.m2` — deliberately, to avoid clobbering with doris-ducklake's `~/.m2`). Rebuild them from
master:

```bash
# JDK 17. DORIS_THIRDPARTY can be any doris thirdparty that has thrift+protoc installed
# (e.g. the fork worktree still has one: ~/DEV/OSS/doris-catalog-spi/thirdparty).
DORIS_SRC=~/DEV/OSS/doris tools/doris-baseline.sh --install-spi-jars
```

That `mvn install -P flatten -pl fe-connector/fe-connector-api,fe-connector/fe-connector-spi,fe-thrift`
(add `-am` on a clean tree) into `doris-m2/`. Coordinates stay `1.2-SNAPSHOT`.

### 4. Recompile + detekt, adapt any residual churn

```bash
./gradlew :doris-duckbridge:compileKotlin :doris-duckbridge:compileTestKotlin \
          :doris-duckbridge:test :doris-duckbridge:detekt
```

Expect green (see "zero churn" above). If anything breaks it'll be a mechanical signature tweak in
the scan-plan/context surface — cross-check against master's `fe-connector-api` sources.

---

## Reusing `doris-ducklake`'s live smoke cluster (skip the FE build)

`doris-ducklake`'s compose cluster is **up right now** with a **master-built FE** and a stock BE:

```
doris-ducklake-fe   doris-fe:pr62767-local   (FROM apache/doris:fe-4.1.0 + apache-master output/fe)
doris-ducklake-be   apache/doris:be-4.1.3    (stock — NO DuckDbTypeHandler)
```

The FE is **connector-agnostic** — plugins are dropped into the FE plugin volume at runtime, not
baked. Two ways to exploit that:

- **Fast FE image, no FE build.** The apache-master FE build output lives at
  `~/DEV/OSS/doris/output/fe`. Bake your FE image straight off it — skip the multi-minute FE build:
  ```bash
  DORIS_OUTPUT=~/DEV/OSS/doris/output ~/DEV/brikk/duckbridge/doris-duckbridge/compose/bake-images.sh --fe
  # → doris-fe:duckbridge-local (FROM apache/doris:fe-4.1.0 + master output/fe)
  ```
  (`bake-images.sh` reads `DORIS_OUTPUT`; default is `~/.cache/duckbridge/doris/output`.)

- **SPI-only validation on the live cluster, right now, nothing built.** Drop the duckbridge plugin
  zip into `doris-ducklake`'s running FE plugin volume and `CREATE CATALOG ... type="duckbridge"`
  against the master FE. This validates the FE-side SPI (registration, `SHOW`/`DESC`, `EXPLAIN`,
  scan planning) on master **without** building your own FE or BE. **Caveat:** any query whose data
  read needs the `DUCKDB` BE type handler will fail on the stock `be-4.1.3` in that cluster — that
  needs your patched BE (next section).

---

## The BE is the monster build — deferred, and it's yours specifically

`doris-ducklake` moved **FE-only**: its BE stayed on stock `apache/doris:be-4.1.3`, and the
master-FE ↔ 4.1.x-BE skew is tolerated (its full smoke — reads, INSERT/CTAS/bucket, DEFAULT
backfill, GC — is green; the two known-blocked items are pre-existing BE gaps, not skew).

duckbridge is different: your read path **requires** the patched BE (`DuckDbTypeHandler`), so stock
`be-4.1.3` won't do for data reads. A full move to master means **rebuild the BE from master + the
re-verified BE patch** (`bake-images.sh --be`, `FROM apache/doris:be-4.1.0` + master `output/be`) —
the heavy, hours-long build (thirdparty may need `build-thirdparty.sh` on a fresh cache clone). Do
the FE move + SPI-jar refresh + recompile first (cheap, above); defer the BE build until you
actually need live data reads.

---

## Upstream status on the master BE

`doris-ducklake` rebuilt the BE from master (`doris-be:master-local`) and confirmed **two of the
three prior blockers are FIXED** on the master native reader:

- ✅ **`COUNT(<nullable col>)` pushdown — FIXED on master.** Previously non-deterministic
  (`colUniqueId=-1`, e.g. `COUNT(v)=0`); ducklake now sees `COUNT(*)=4 COUNT(v)=2`. The #65548
  count-pushdown gate behaves. **duckbridge exposure:** minimal — duckbridge rides the JDBC path and
  does **not** push a metadata count (empty projection renders `SELECT 1`; the BE counts by reading),
  so this was never a live duckbridge blocker, but the shared plugin-scan hazard is now gone.
- ✅ **BE position-delete OPTIONAL columns — FIXED on master.** Step-7 DELETE previously died with
  `[CORRUPTION] Not nullable column has null values in parquet file`; ducklake's Step 7 is now GREEN
  (93/93 rows). **duckbridge exposure: none** — duckbridge is a read-only JDBC-over-Quack connector
  with no delete files; this item was inherited boilerplate from the ducklake handoff, not a
  duckbridge path.
- ⏳ **No channel for FE-computed rows to reach the BE** — still open (matters only if quack ever
  materializes rows FE-side; not a v1 duckbridge path).

---

## TL;DR

1. `BASELINE` → apache/doris `master`, pin `ded91fb9fb3` (coords stay `1.2-SNAPSHOT`).
2. `git apply --check` the BE `DuckDbTypeHandler` patch on master; rebase if needed.
3. `DORIS_SRC=~/DEV/OSS/doris tools/doris-baseline.sh --install-spi-jars` → `doris-m2/`.
4. Recompile + test + detekt — expect green (doris-ducklake needed zero changes).
5. FE: retag doris-ducklake's master FE (`doris-fe:pr62767-local` → `doris-fe:duckbridge-local`) —
   patch-free, connector-agnostic. BE: **no monster build** — the patch is jdbc-scanner-only, so
   splice `DuckDbTypeHandler` + the DUCKDB arm into the stock master jar (javac + `jar uf`) and thin-
   overlay it `FROM doris-be:master-local`. **All done + smoke GREEN (2026-07-31).**
