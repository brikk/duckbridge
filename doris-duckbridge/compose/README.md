# doris-duckbridge — live FE + BE + Quack compose

The slow, real-cluster counterpart to `:doris-duckbridge:test`. It runs a pristine pinned-master
FE, the matching patched BE, and DuckDB/Quack, then asserts the connector end-to-end. Doris images
must be baked before `up`; Compose does not compile Doris.

## Branch FE **and** branch BE — both required

Unlike `doris-ducklake` (which pairs its branch FE with a **stock** 4.1.x BE because it
rides the BE's native parquet/iceberg reader), doris-duckbridge rides **`JdbcJniScanner`**,
which is **absent from stock 4.1.x**. So both images are branch-built:

- **FE** — pristine pinned master; the SPI and provider-first type routing are upstream.
- **BE** — carries the `DuckDbTypeHandler` patch (decodes DuckDB result sets) plus master’s
  upstream `JdbcJniScanner` machinery.

Stock 4.1.4-rc04 still lacks both the SPI and `JdbcJniScanner`; see
`../../doris-patches/PATCHES.md` re-vendor log. See also the plan's §Which Doris.

## Prerequisites

1. **Pinned build output** at `~/.cache/duckbridge/doris/output/{fe,be}` or another `DORIS_OUTPUT`:
   ```bash
   tools/doris-baseline.sh --build-fe --build-be   # JDK 17; BE is a multi-hour C++ build
   ```
2. **Baked images** `doris-fe:duckbridge-local` + `doris-be:duckbridge-local` (see below).
3. **Plugin zip** — built on demand by `smoke.sh` (`./gradlew :doris-duckbridge:pluginZip`).
4. **`.env`** — `cp .env.example .env` and set `QUACK_TOKEN` (smoke.sh auto-copies if absent).
5. Docker (or podman — set `DOCKER=podman`).

Cluster-free orchestration regression:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest test_smoke_driver.py
```

## Three commands

```bash
# 1. Bake the overlay images from the branch build output (after the FE/BE build finishes).
#    Refuses to run while the `doris-be-build` container is still writing the output tree.
./bake-images.sh                 # both; --fe / --be for one

# 2. Up + smoke: build/install plugin → restart persisted FE → metadata → lazy JVM →
#    exact scans, pushdowns and concurrency → down -v.
./smoke.sh                       # --up-only to stop after health; --keep to skip teardown

# 3. Down (wipes FE meta + plugin + BE storage volumes).
./smoke.sh --down
```

## Smoke coverage

- API-version-gated provider registration and real schema/table/type metadata.
- Exact FE/BE source-version guard before catalog fixture allocation.
- Persisted FE restart retains `172.31.80.10`.
- JVM/plugin registry stays uninitialized before the first scan; first use loads only `jdbc` and
  publishes `doris_be_jvm_*` metrics.
- Exact unicode, LARGEINT, ARRAY, native UUID and arbitrary VARBINARY bytes; exact count and rows.
- Native TIMESTAMPTZ transport across Asia/Tokyo Quack, America/Los_Angeles JVM and UTC FE display.
- Function, naive TIMESTAMP and explicit-UTC TIMESTAMPTZ pushdown in the emitted query.
- 20 sequential and 8 concurrent scans with zero failures.
- Any expected-value mismatch exits nonzero.

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
- **BE dist root** is `/opt/apache-doris/be`; keep the Dockerfile and storage/log mounts aligned.
- **`.env` and `.fe.conf.runtime` are gitignored.** The token is dev-only; never commit a
  real one.
