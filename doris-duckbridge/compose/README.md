# doris-duckbridge — live FE + BE + Quack compose

The slow, real-cluster counterpart to `:doris-duckbridge:test`. Brings up a **patched**
Doris cluster + a DuckDB/Quack server and drives the connector to the current scaffold
boundary (provider loads, catalog creates, scan fails loud with the P1–P6 probe message —
that error IS the green state today).

> **WIP:** the compose is scaffolded but the FE/BE images must be **baked first** (below),
> which needs the branch FE+BE build (`tools/doris-baseline.sh --build-fe --build-be`).
> Nothing here builds the Doris images at `up` time.

## Branch FE **and** branch BE — both required

Unlike `doris-ducklake` (which pairs its branch FE with a **stock** 4.1.x BE because it
rides the BE's native parquet/iceberg reader), doris-duckbridge rides **`JdbcJniScanner`**,
which is **absent from stock 4.1.x**. So both images are branch-built:

- **FE** — carries the `SPI_READY_TYPES=duckbridge` patch (routes `type=duckbridge` to our
  connector).
- **BE** — carries the `DuckDbTypeHandler` patch (decodes DuckDB result sets) **and** the
  `JdbcJniScanner` machinery that only exists on `branch-catalog-spi`.

See `../../dev-docs/PLAN-doris-duckbridge.md` §Which Doris and `../../doris-patches/`.

## Prerequisites

1. **Branch build output** at `~/.cache/duckbridge/doris/output/{fe,be}`:
   ```bash
   tools/doris-baseline.sh --build-fe --build-be   # JDK 17; BE is a multi-hour C++ build
   ```
2. **Baked images** `doris-fe:duckbridge-local` + `doris-be:duckbridge-local` (see below).
3. **Plugin zip** — built on demand by `smoke.sh` (`./gradlew :doris-duckbridge:pluginZip`).
4. **`.env`** — `cp .env.example .env` and set `QUACK_TOKEN` (smoke.sh auto-copies if absent).
5. Docker (or podman — set `DOCKER=podman`).

## Three commands

```bash
# 1. Bake the overlay images from the branch build output (after the FE/BE build finishes).
#    Refuses to run while the `doris-be-build` container is still writing the output tree.
./bake-images.sh                 # both; --fe / --be for one

# 2. Up + smoke: build plugin → up → wait healthy → install plugin → CREATE CATALOG →
#    drive the fail-loud stub (asserts on the P1–P6 message) → down -v.
./smoke.sh                       # --up-only to stop after health; --keep to skip teardown

# 3. Down (wipes FE meta + plugin + BE storage volumes).
./smoke.sh --down
```

## What the smoke asserts today (and what unlocks later)

| Step | Today (scaffold) | Unlocks with |
|---|---|---|
| provider registration | `CREATE CATALOG type=duckbridge` not rejected | FE `SPI_READY_TYPES` patch + ServiceLoader |
| metadata listing | honestly empty / fail-loud (probe P4) | quack-jdbc `DatabaseMetaData` fidelity |
| `SELECT` → BE scan | fail-loud P1–P6 message (**this is green**) | `planScan` + `table_type=DUCKDB` handler |
| pushdown EXPLAIN | n/a | parity/pushdown work (P1) |

When behavior lands, swap the "expect the stub error" assertions for real result checks —
`jvm/doris-ducklake/compose/smoke.sh` is the template for that shape.

## Notes / gotchas

- **Plugin dir is `plugins/connector/<name>/` (singular `connector`).** The plugin zip
  unzips to `lib/…`; `smoke.sh` installs it into a named volume mounted at
  `…/plugins/connector/duckbridge` (named volume + `docker cp`, not a bind mount, so
  Podman-on-macOS host↔VM file sharing can't drop the jar tree).
- **No local-shuffle shim.** doris-ducklake needs `SET GLOBAL
  enable_local_shuffle_planner=false` for its branch-FE-on-stock-BE thrift skew. We run a
  **branch BE too**, so FE/BE thrift matches and the shim is not needed — deliberately
  omitted. If a scan ever hits "Unsupported exec type in pipeline", the BE image is stale
  (not branch-built) — rebake it.
- **BE dist root** is assumed to be `/opt/apache-doris/be` (the layout doris-ducklake's
  compose relies on). ⚠ Verify once the image is baked — if the real base image uses a
  different root, fix `DORIS_BE_HOME` in `docker/doris-be/Dockerfile` **and** the
  `be-storage`/`be-log` mount paths in `docker-compose.yml` together.
- **`.env` and `.fe.conf.runtime` are gitignored.** The token is dev-only; never commit a
  real one.
