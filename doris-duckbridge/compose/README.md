# doris-duckbridge — live FE + BE + Quack compose

The slow, real-cluster counterpart to `:doris-duckbridge:test`. It runs a pristine pinned-master
FE, the matching patched BE, and DuckDB/Quack, then asserts the connector end-to-end. Doris images
must be baked before `up`; Compose does not compile Doris.

## Branch FE **and** branch BE — both required

The connector SPI and **`JdbcJniScanner`** are absent from stock 4.1.x. Both engines must
match the exact master pin, including IDL, shared Java layer and complete JNI plugin layout:

- **FE** — pristine pinned master; the SPI and provider-first type routing are upstream.
- **BE** — carries the `DuckDbTypeHandler` patch (decodes DuckDB result sets) plus master’s
  upstream `JdbcJniScanner` machinery.

Stock 4.1.4-rc04 still lacks both the SPI and `JdbcJniScanner`; see
`../../doris-patches/PATCHES.md` re-vendor log. See also the plan's §Which Doris.

## Prerequisites

1. **Pinned build output** at `~/.cache/duckbridge/doris/output/{fe,be}` or another `DORIS_OUTPUT`:
   ```bash
   BUILD_TYPE=ASAN tools/doris-baseline.sh --build-fe --build-be   # JDK 17; multi-hour BE build
   ```
2. **Baked images** `doris-fe:duckbridge-082736138b7-api24` +
   `doris-be:duckbridge-082736138b7-api24-asan` (see below). The old API-13 image tags remain intact.
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
- CREATE/ALTER driver-URL policy rejects prefix confusion, remote URLs outside the configured
  local path, and percent-encoded traversal before any driver download.
- JVM/plugin registry stays uninitialized before the first scan; first use loads only `jdbc` and
  publishes `doris_be_jvm_*` metrics.
- Exact unicode, LARGEINT, ARRAY, native UUID and arbitrary VARBINARY bytes; exact count and rows.
- Native TIMESTAMPTZ transport across Asia/Tokyo Quack, America/Los_Angeles JVM and UTC FE display.
- Exact pre-epoch fractional microseconds, distinct DST-overlap instants and temporal NULLs.
- Function, naive TIMESTAMP and explicit-UTC TIMESTAMPTZ pushdown in the emitted query.
- 20 sequential and 8 concurrent scans with zero failures.
- ALTER and same-name DROP/recreate retain replacement metadata/scans; a second FE restart
  retains the initialized catalog, FE name/address and exact values without recreating the catalog.
- An intentionally incompatible JDBC manifest fails only that plugin while native BE health
  survives; recreating the run-owned BE from the complete image restores scans.
- Any expected-value mismatch exits nonzero.

## Notes / gotchas

- **Plugin dir is `plugins/connector/<name>/` (singular `connector`).** The plugin zip
  unzips to `lib/…`; `smoke.sh` installs it into a named volume mounted at
  `…/plugins/connector/duckbridge` (named volume + `docker cp`, not a bind mount, so
  Podman-on-macOS host↔VM file sharing can't drop the jar tree).
- **No local-shuffle shim.** FE/BE source and Thrift must match; never disable planning paths
  to conceal a stale engine image.
- **Fresh API-24 volumes.** The Compose project is `doris-duckbridge-api24-08273613`; its
  FE metadata/plugin/log and BE storage/log volumes do not upgrade or remove API-13 volumes.
- **BE dist root** is `/opt/apache-doris/be`; keep the Dockerfile and storage/log mounts aligned.
- **Pinned remote lane:** DuckDB `2.0.0` (`duckbridge/duckdb-quack:2.0.0`) and Quack JDBC
  `0.10.0`. Build the server with `docker compose build duckdb-quack` after a version change.
  These isolated fixtures deliberately serve plain HTTP; production TLS settings are unchanged.
- **Read-only function intake:** `function_audit.py --output <file> [--brikk-sql-root <path>]`
  runs while the smoke is kept up. It records values/errors and the native registry; it never
  enables pushdowns. See the dated function-intake report in `../dev-docs/`.
- **`.env` and `.fe.conf.runtime` are gitignored.** The token is dev-only; never commit a
  real one.
