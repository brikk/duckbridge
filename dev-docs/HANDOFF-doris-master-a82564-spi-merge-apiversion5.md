# Handoff: sync duckbridge to apache/doris master `a82564ced5d` (SPI api→spi merge + API-version 1→5)

> **From the `doris-ducklake` sync, 2026-08-06.** duckbridge is currently pinned at
> `ded91fb9fb3` (`doris-patches/BASELINE`), which is *before* two breaking changes that landed in
> the `ded91fb9fb3..a82564ced5d` window (+74 commits). This note is the exact playbook to move
> duckbridge forward — I already hit and solved both on the ducklake connector. Reference commits:
> `doris-ducklake@8958021` (api→spi rewrite) and `doris-ducklake@5287a90` (api-version 5 + smoke
> verdict). Friction doc:
> `https://github.com/brikk/doris-ducklake/blob/main/dev-docs/ducklake-doris-friction.md`

New pin to adopt: **`b119273e3f0`** (apache/doris master, 2026-08-16) — see the 2026-08-16 delta
directly below; all the `a82564ced5d` migration steps still apply verbatim. `<revision>` is still
`1.2-SNAPSHOT` → `doris-m2` coordinates unchanged.

---

## UPDATE 2026-08-16 — ducklake re-vendored to `b119273e3f0` (+44 over a82564; still non-breaking)

Routine "stay-ready" bump; ducklake full smoke + corpus GREEN on master FE+BE both `b119273e3f0`.
No `fe-connector-spi` surface change since `a82564ced5d`, so the migration checklist below is
unchanged. Two things to fold in for duckbridge:
- **BE build env:** `#66783` bumped the thirdparty hadoop to `hadoop-3.4.2.3-for-doris`, so use a
  **build-env image ≥ 2026-08-15** (older ones fail the arrow/paimon thirdparty freshness guard).
  Unity builds are now on (`#66712/#66776/#66789`) — expect a near-full BE recompile the first time.
- **On the shared scan path:** `#66628` (normalize connector table errors) edits `PluginDrivenScanNode`
  — the FE scan node duckbridge's reads also flow through. ducklake smoke shows no regression; just
  re-smoke after your bump.
- **§12b DEFAULT backfill still open** (shared): a column added with a DEFAULT over pre-existing rows
  reads `0` not the default (crash long gone; correctness only). Relevant only if duckbridge reads
  schema-evolved DEFAULT columns. **timestamptz is fully resolved** on master (zone-aware read works).
Reference: `doris-ducklake@22bcd4b`.

---

## UPDATE 2026-08-08 — bumped ducklake `a82564ced5d` → `b42e1ab294b` (+15, routine/non-breaking) and re-smoked

## UPDATE 2026-08-08 — bumped ducklake `a82564ced5d` → `b42e1ab294b` (+15, routine/non-breaking) and re-smoked

Non-breaking for the SPI: the only `fe-connector-spi` change (#66507) touched `package-info.java`
only; api-version still **5.0** (no stamp change beyond the 1→5 above). Nothing new to do in the
checklist below for duckbridge — the api→spi rewrite + api-version-5 stamp + build-env≥2026-08-06 +
BE-patch re-verify all still apply. Reference: `doris-ducklake@86bc20e`.

**What changed in the smoke:** the §12b schema-evolution **BE crash is RESOLVED** on `b42e1ab294b`
(the `format_v2::TableReader::_evaluate_constant_filters` `Const(INT)` vs `Nullable(INT)` SIGSEGV is
gone; #66589 reworked the FileScannerV2 reader lifecycle). Full smoke now completes end-to-end incl.
§13 GC — first time on master.

**New, lesser §12b issue (shared, relevant to duckbridge reads):** the crash downgraded to a
**correctness miss** — a column added with a DEFAULT over pre-existing rows now reads **`0`, not the
DEFAULT** (`ALTER TABLE ADD COLUMN b INT DEFAULT 42` → old rows read `0`; explicit rows + non-evolved
reads are correct; no NULLs; `be-4.1.3` returned `42`). Root cause we traced: master's new
`format_v2/column_mapper.cpp` fills a missing column from the **BE table column's `initial_default_value`**
(the Iceberg-schema `initial-default`; `column_mapper.cpp:~2078`) and **no longer consults the FE-supplied
`TFileScanSlotInfo.default_value_expr`** channel that ducklake/quack feed via `ConnectorColumn.defaultValue`
(worked on 4.1.3). So if duckbridge surfaces column defaults through `ConnectorColumn.defaultValue`, expect
the same `0`-not-default read on master. Fix path (still under investigation on the ducklake side): emit
the default as the Iceberg V3 `initial-default` in the schema/`iceberg_params` handed to the BE, **or**
upstream restores `default_value_expr` honoring in `format_v2`. Tracking in the ducklake friction doc
(§12b entry, 2026-08-08).

---

## The two breaking changes (both hit duckbridge as-is)

### 1. `#66407` merged `fe-connector-api` INTO `fe-connector-spi` (+ package rename `api.` → `spi.`)

The `fe-connector-api` **module is deleted**; the whole `org.apache.doris.connector.api.*` package
tree was renamed to `org.apache.doris.connector.spi.*` (Trino-style single-module contract). The
`fe-connector-api` **artifact no longer exists**.

duckbridge changes (mirrors `doris-ducklake@8958021`):
- **`doris-duckbridge/build.gradle.kts`** — drop every `fe-connector-api` reference; `fe-connector-spi`
  now carries the whole contract:
  - the `dorisSpiAnchorJar` bootstrap check (currently points at
    `org/apache/doris/fe-connector-api/$dorisVersion/fe-connector-api-$dorisVersion.jar`) → point it
    at the **`fe-connector-spi`** jar instead.
  - `compileOnly("org.apache.doris:fe-connector-api:…")` and the matching `testImplementation` → **delete**.
  - the `exclude("fe-connector-api-*.jar")` in the plugin-zip assembly → harmless, but tidy to drop.
- **connector Kotlin source** — global rewrite `org.apache.doris.connector.api.` →
  `org.apache.doris.connector.spi.` (imports only; no logic change). On ducklake this was 34 files;
  do `grep -rl 'org\.apache\.doris\.connector\.api\.' <src>` and `sed -i 's/…api\./…spi./g'`.
  Check for import collisions first: ducklake already imported `connector.spi.ConnectorContext` and
  `connector.spi.ConnectorProvider` and neither collided — verify the same for duckbridge.
- **`tools/doris-baseline.sh`** — `--install-spi-jars` already builds `-pl fe-connector/fe-connector-spi -am`,
  which now installs the merged jar (no `fe-connector-api` produced). Just fix the stale comments.

### 2. ⚠️ API-version MAJOR bumped `1` → `5` — **fail-closed load gate** (every plugin author hits this)

The same window bumped `fe/fe-connector/pom.xml <connector.plugin.api.version>` to **`5.0`**. The FE
now **rejects** any connector plugin whose jar declares a different major at load:

```
Rejected plugin …: incompatible Doris-Connector-Plugin-Api-Version='1.0': major 1 but this FE
serves CONNECTOR plugin API 5.0 (major 5). Rebuild the plugin against this Doris release.
```

Symptom if you miss it: the plugin compiles fine but `CREATE CATALOG` fails with
**`No connector plugin claimed catalog type '<yours>'`** and the FE load summary shows
`failureCount=1, stage=apiVersion`.

Fix (mirrors `doris-ducklake@5287a90`): in **`doris-duckbridge/build.gradle.kts`** change the `jar`
manifest stamp `Doris-Connector-Plugin-Api-Version` from `"1.0"` to **`"5.0"`**. The gate compares
MAJOR only. (Sanity-check the current value from the rebuilt spi jar:
`unzip -p doris-m2/.../fe-connector-spi-1.2-SNAPSHOT.jar META-INF/doris/connector-plugin-api-version.properties`.)

---

## BE build gotcha (you build a patched BE; ducklake rides stock, so this is yours)

The newer `build.sh` added an **Arrow/Paimon thirdparty freshness guard** (from the arrow-adbc
thirdparty work, #66358/#66331). It checks `arrow-build-fingerprint.txt` / `paimon-build-fingerprint.txt`
in the installed thirdparty and, if stale, tries a partial Arrow/Paimon rebuild — which **fails** on
an install-only prefix (`build-thirdparty.sh is missing … install-only or incomplete prefix`).

Fix: **pull a fresh build-env image** before building the BE — the one dated **≥ 2026-08-06** ships
the matching Arrow/Paimon closure + fingerprints:
```
docker pull apache/doris:build-env-ldb-toolchain-latest   # 2026-08-06 image passes the guard
```
Verify (optional): run `. thirdparty/arrow-paimon-vars.sh && arrow_paimon_prebuilt_valid /var/local/thirdparty/installed`
inside the image → must print valid. Then `./build.sh --be` (incremental via ccache is fast).

Also: **re-verify your BE `DuckDbTypeHandler` patch applies on `a82564ced5d`**
(`git apply --3way --check doris-patches/be/0001-duckdb-type-handler.patch`) — rebase if the
jdbc-scanner moved.

---

## What I verified on a full master FE+BE (`a82564ced5d`) — reuse this, don't re-discover

Ran the ducklake smoke on freshly-built master FE **and** BE:
- ✅ **Fixed & confirmed:** `COUNT(<nullable col>)` pushdown (was garbage) and Iceberg-style
  **position-delete** reads (was `[CORRUPTION]`) — both green now. Reads + `corpusReplayTest` green.
- ✅ CREATE/DROP DDL, INSERT/CTAS/bucketed-partition writes green.
- ❌ **Still-open shared BE blocker:** a **schema-evolution DEFAULT-backfill read crashes the BE** —
  `[INTERNAL_ERROR]Column type Const(INT) is not compatible with data type Nullable(INT)` → SIGSEGV
  in `format_v2::TableReader::_evaluate_constant_filters` (`table_reader.h:576`). #66345/#65851/#65446
  did **not** fix it. If duckbridge reads columns added with a DEFAULT over pre-existing rows, expect
  this crash. (§13 GC + timestamptz-in-parquet remain unverified on master because the BE dies here first.)

---

## Checklist

1. `doris-patches/BASELINE`: `PIN_SHA=a82564ced5d…`, `PIN_SUBJECT=[fix](iceberg) Fix MVCC and nested schema evolution edge cases (#66345)`.
2. `git apply --check` the BE `DuckDbTypeHandler` patch on `a82564ced5d`; rebase if needed.
3. `docker pull apache/doris:build-env-ldb-toolchain-latest` (≥ 2026-08-06) before the BE build.
4. `build.gradle.kts`: drop `fe-connector-api` (dep + anchor-jar check + exclude); stamp `Doris-Connector-Plugin-Api-Version` = **`5.0`**.
5. Rewrite `connector.api.` → `connector.spi.` in connector source.
6. `tools/doris-baseline.sh --install-spi-jars` (rebuilds `fe-connector-spi` into `doris-m2`), then `:doris-duckbridge:compileKotlin :doris-duckbridge:test :doris-duckbridge:detekt`.
7. Rebuild FE+BE, `bake-images.sh`, live-smoke. Watch the FE load summary for `stage=apiVersion` rejections (means the 5.0 stamp didn't take).
