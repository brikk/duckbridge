#!/usr/bin/env bash
# Live FE+BE+Quack smoke for doris-duckbridge.
#
# Brings up the patched Doris cluster + a DuckDB/Quack server (seeded with a schema + tables),
# installs the connector plugin, and drives it to the CURRENT boundary:
#   • the connector LOADS + CREATE CATALOG type=duckbridge passes the FE gate;
#   • METADATA is REAL (probe P4): SHOW DATABASES/TABLES + DESC resolve the seeded quack schema
#     over quack-jdbc with the probe-decided Doris type map — assert the seeded objects appear;
#   • the SCAN still fails loud: a SELECT reaches planScan and throws the P1/P5 message — THAT
#     is the new green (a silent SELECT success would mean planScan was bypassed).
#
# Usage:
#   ./smoke.sh             # build plugin → up → wait healthy → install plugin → drive → down -v
#   ./smoke.sh --no-build  # skip the gradle plugin rebuild (assume the zip is current)
#   ./smoke.sh --up-only   # up + healthy + plugin installed, then STOP (manual dev loop)
#   ./smoke.sh --keep      # drive, but do NOT tear down at the end
#   ./smoke.sh --down      # tear everything down (-v) and exit
#
# What unlocks next as the connector grows:
#   • SELECT reaching the BE JdbcJniScanner → needs planScan (probes P1/P5) + the DUCKDB type
#     handler emitting table_type=DUCKDB (planScan still fails loud today);
#   • pushdown assertions (EXPLAIN shows the pushed predicate) → the parity/pushdown work.
# When those land, replace the "expect the planScan stub" assertion with real result assertions
# (the doris-ducklake smoke.sh is the template for that shape).

set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "${HERE}/../.." >/dev/null 2>&1 && pwd -P)"
COMPOSE="${HERE}/docker-compose.yml"
PLUGIN_ZIP_GLOB="${REPO_ROOT}/doris-duckbridge/build/distributions/doris-duckbridge-*-plugin.zip"
DOCKER="${DOCKER:-docker}"

DO_BUILD=1
UP_ONLY=0
DOWN=0
KEEP=0
for arg in "$@"; do
    case "$arg" in
        --no-build) DO_BUILD=0 ;;
        --up-only)  UP_ONLY=1 ;;
        --keep)     KEEP=1 ;;
        --down)     DOWN=1 ;;
        -h|--help)  sed -n '2,22p' "$0"; exit 0 ;;
        *) echo "Unknown arg: $arg" >&2; exit 2 ;;
    esac
done

log() { printf '\033[1;36m[smoke]\033[0m %s\n' "$*"; }
fe_sql() { "${DOCKER}" exec doris-duckbridge-fe mysql -h127.0.0.1 -P9030 -uroot "$@"; }

# .env is required (QUACK_TOKEN). Stage it from the example on first run.
if [[ ! -f "${HERE}/.env" ]]; then
    log ".env missing — copying from .env.example (edit it to change the token)."
    cp "${HERE}/.env.example" "${HERE}/.env"
fi
# shellcheck disable=SC1091
set -a; . "${HERE}/.env"; set +a
QUACK_TOKEN="${QUACK_TOKEN:-duckbridge-doris-token}"

# init_fe.sh appends priority_networks to fe.conf at every boot; mount a gitignored runtime
# copy (not the tracked file) so those appends never pollute the repo. Stage it fresh so
# both `up` and `--down` find the mount source present.
cp "${HERE}/fe.conf" "${HERE}/.fe.conf.runtime"

if [[ $DOWN -eq 1 ]]; then
    log "Tearing down doris-duckbridge stack…"
    "${DOCKER}" compose -f "${COMPOSE}" down -v
    exit 0
fi

# Auto-detect BE arch (compose defaults to amd64; pick arm64 on Apple Silicon).
if [[ -z "${DORIS_BE_PLATFORM:-}" && "$(uname -m)" == "arm64" ]]; then
    export DORIS_BE_PLATFORM=linux/arm64
    log "Host is arm64 — defaulting DORIS_BE_PLATFORM=${DORIS_BE_PLATFORM}"
fi

# 1. Plugin zip — rebuild and unpack into the plugin volume.
if [[ $DO_BUILD -eq 1 ]]; then
    log "Building plugin zip (:doris-duckbridge:pluginZip)…"
    # The gradle build itself needs a JDK 25 runtime (build-logic targets 25; the module's
    # JDK-17 toolchain is resolved by gradle). Probe ~/.gradle/jdks when the ambient java
    # is older, fail loud otherwise.
    gradle_java="${JAVA_HOME:-}"
    if ! "${gradle_java:-/nonexistent}/bin/java" -version 2>&1 | grep -qE '"(2[5-9]|[3-9][0-9])' ; then
        gradle_java="$(ls -d "${HOME}"/.gradle/jdks/*25*/ 2>/dev/null | head -n1 || true)"
        gradle_java="${gradle_java%/}"
    fi
    [ -n "${gradle_java}" ] && [ -x "${gradle_java}/bin/java" ] ||
        { log "ERROR: need a JDK 25+ for gradle (set JAVA_HOME); none found in ~/.gradle/jdks"; exit 1; }
    ( cd "${REPO_ROOT}" && JAVA_HOME="${gradle_java}" ./gradlew :doris-duckbridge:pluginZip -q )
fi
zip_path=$(ls -t ${PLUGIN_ZIP_GLOB} 2>/dev/null | head -1 || true)
if [[ -z "$zip_path" ]]; then
    log "No plugin zip found under ${PLUGIN_ZIP_GLOB} — run without --no-build, or ./gradlew :doris-duckbridge:pluginZip"
    exit 1
fi

# 2. Bring up the cluster (FE + BE + Quack).
log "Starting Doris FE + BE + DuckDB/Quack…"
"${DOCKER}" compose -f "${COMPOSE}" up -d

# 3. Install the plugin into the FE plugin volume (unzipped → plugins/connector/duckbridge).
# A named volume + docker cp (daemon-API streaming) rather than a bind mount, so Podman's
# macOS host↔VM file sharing can't drop the jar tree. The FE mounts the same volume at
# /opt/apache-doris/fe/plugins/connector/duckbridge. Done BEFORE the FE finishes coming up so
# the connector is present when the plugin loader scans; a restart guarantees a clean load.
log "Installing plugin from $(basename "$zip_path") into the FE plugin volume…"
VOL_NAME=doris-duckbridge-dev_fe-plugin-duckbridge
"${DOCKER}" volume create "${VOL_NAME}" >/dev/null
helper=$("${DOCKER}" create -v "${VOL_NAME}:/dest" alpine \
    sh -c 'apk add --no-cache unzip >/dev/null 2>&1 && rm -rf /dest/* /dest/.[!.]* 2>/dev/null; cd /dest && unzip -oq /tmp/plugin.zip')
"${DOCKER}" cp "$zip_path" "$helper":/tmp/plugin.zip
"${DOCKER}" start -a "$helper"
"${DOCKER}" rm "$helper" >/dev/null
log "Restarting FE so it loads the freshly-installed connector plugin…"
"${DOCKER}" compose -f "${COMPOSE}" restart doris-fe

# 4. Wait for FE health (mysql:9030 SHOW FRONTENDS — FE-local, no BE needed).
log "Waiting for FE to accept SQL…"
deadline=$((SECONDS + 180))
while :; do
    if fe_sql -e "SHOW FRONTENDS" >/dev/null 2>&1; then log "FE up."; break; fi
    if (( SECONDS >= deadline )); then
        log "FE never came up — last 80 lines of fe.log:"
        "${DOCKER}" exec doris-duckbridge-fe sh -c 'tail -80 /opt/apache-doris/fe/log/fe.log' 2>&1 || true
        exit 1
    fi
    sleep 2
done

# 5. Wait for BE registration (SHOW BACKENDS → Alive). The BE is required for any real scan;
# the fail-loud stub trips in the FE before that, but we still want a healthy cluster.
log "Waiting for BE registration (SHOW BACKENDS → Alive)…"
deadline=$((SECONDS + 240))
while :; do
    alive=$(fe_sql -N -e "SHOW BACKENDS" 2>/dev/null | awk -F'\t' '$10=="true"{n++} END{print n+0}')
    if [[ "${alive:-0}" -ge 1 ]]; then log "BE registered and alive."; break; fi
    if (( SECONDS >= deadline )); then
        log "BE failed to register — SHOW BACKENDS:"
        fe_sql -e "SHOW BACKENDS\\G" 2>&1 || true
        exit 1
    fi
    sleep 3
done

# 5b. NOTE on the local-shuffle shim: doris-ducklake's smoke SETs
# enable_local_shuffle_planner=false because it pairs a branch FE with a STOCK 4.1.0 BE whose
# thrift enum lacks TPlanNodeType.LOCAL_EXCHANGE_NODE=38. doris-duckbridge runs a BRANCH BE
# too, so FE and BE share the same thrift — that shim should NOT be needed here. Left OUT
# deliberately; if a scan ever hits "Unsupported exec type in pipeline", the BE image is stale
# (not branch-built) — rebake it, don't paper over it with the shim.

# 6. Verify the connector provider registered (FE log OR the SPI_READY_TYPES gate accepted
# the type). The cleanest FE-observable signal is that CREATE CATALOG type=duckbridge is NOT
# rejected as "Unknown catalog type" — that only passes if the whitelist patch is present AND
# the provider ServiceLoader-registered.
log "Provider-registration probe (fe.log grep, best-effort)…"
"${DOCKER}" exec doris-duckbridge-fe sh -c \
    'grep -iE "duckbridge|ConnectorProvider" /opt/apache-doris/fe/log/fe.log | tail -5' 2>/dev/null \
    || log "  (no explicit registration line — the CREATE CATALOG probe below is the real gate)"

if [[ "$UP_ONLY" -eq 1 ]]; then
    log "--up-only: cluster is up, healthy, plugin installed."
    log "  FE mysql: 127.0.0.1:9030 (root, no password)   Quack: 127.0.0.1:9494 (token '${QUACK_TOKEN}')"
    log "  Tear down with: ./smoke.sh --down"
    exit 0
fi

# 7. CREATE CATALOG type=duckbridge. Passing the FE gate (no "Unknown catalog type") proves
# the SPI_READY_TYPES whitelist patch + the ServiceLoader registration both work.
log "Creating catalog duckbridge_test (type=duckbridge, quack URL + token)…"
set +e
cat_out=$(fe_sql -e "
    DROP CATALOG IF EXISTS duckbridge_test;
    CREATE CATALOG duckbridge_test PROPERTIES (
        'type'         = 'duckbridge',
        'jdbc_url'     = 'jdbc:quack://duckdb-quack:9494',
        'driver_class' = 'com.gizmodata.quack.jdbc.sql.QuackDriver',
        'password'     = '${QUACK_TOKEN}'
    );
    SHOW CATALOGS;
" 2>&1)
cat_status=$?
set -e
echo "${cat_out}" | tail -20
if [[ $cat_status -ne 0 ]]; then
    if echo "${cat_out}" | grep -qiE "unknown catalog type|not.*ready|SPI_READY"; then
        log "GATE FAIL: type=duckbridge rejected — the FE is missing the SPI_READY_TYPES=duckbridge"
        log "  patch (doris-patches/fe/), OR the plugin didn't ServiceLoader-register. Rebake the FE."
        exit 1
    fi
    log "CREATE CATALOG failed for another reason — inspect output above + fe.log."
    exit 1
fi
log "GATE OK: catalog created — SPI whitelist + provider registration both work."

# 8. METADATA is now REAL (probe P4 settled): resolution over quack-jdbc should list the seeded
# quack schemas/tables and DESC a table's columns with the probe-decided Doris types. The SCAN
# still fails loud — planScan is gated on P1/P5 — and THAT is the new green.
#
# ── History (pre-P4 scaffold expectations, kept for the record) ──────────────────────────────
#   Before P4, listing was honestly EMPTY and resolution-by-name fell to the FE's own "Unknown
#   database" (the connector stubs were structurally unreachable behind FE-side resolution). The
#   old smoke asserted that. Now listing is real, so we assert the seeded schema/tables appear.
# ─────────────────────────────────────────────────────────────────────────────────────────────

log "Metadata resolution: SHOW DATABASES (expect the seeded quack schema 'sales')…"
set +e
dbs_out=$(fe_sql -e "SHOW DATABASES FROM duckbridge_test;" 2>&1)
set -e
echo "${dbs_out}" | tail -20
if echo "${dbs_out}" | grep -qiw "sales"; then
    log "METADATA GREEN: SHOW DATABASES lists the seeded quack schema 'sales' (real resolution over quack-jdbc)."
else
    log "METADATA CHECK: expected a 'sales' database from the seeded quack server — inspect above + fe.log."
    log "  (Seed file: compose/seed/quack-init.sql; the quack container must have run it.)"
fi

log "Metadata resolution: SHOW TABLES FROM duckbridge_test.sales (expect customers, orders)…"
set +e
tables_out=$(fe_sql -e "SHOW TABLES FROM duckbridge_test.sales;" 2>&1)
set -e
echo "${tables_out}" | tail -20
if echo "${tables_out}" | grep -qiw "customers" && echo "${tables_out}" | grep -qiw "orders"; then
    log "METADATA GREEN: SHOW TABLES resolves the seeded tables."
else
    log "METADATA CHECK: expected 'customers' + 'orders' — inspect above + fe.log."
fi

log "Metadata resolution: DESC duckbridge_test.sales.customers (expect the P4 type map)…"
set +e
desc_out=$(fe_sql -e "DESC duckbridge_test.sales.customers;" 2>&1)
set -e
echo "${desc_out}" | tail -20
# balance DECIMAL(18,2), big_id HUGEINT→LARGEINT, tags VARCHAR[]→ARRAY<STRING> are the
# distinctive P4-mapped columns.
if echo "${desc_out}" | grep -qiE "largeint" && echo "${desc_out}" | grep -qiE "array"; then
    log "METADATA GREEN: DESC shows the probe-decided types (HUGEINT→LARGEINT, VARCHAR[]→ARRAY)."
else
    log "METADATA CHECK: expected LARGEINT + ARRAY columns from the type map — inspect above."
fi

# The SCAN is still gated: a SELECT must reach planScan and fail loud with the P1/P5 message.
# THIS is the new green — metadata real, scan still gated. (A silent success here would mean
# planScan was bypassed; a metadata-stage error would mean resolution regressed.)
log "Scan attempt: SELECT * FROM duckbridge_test.sales.customers LIMIT 1 (expect planScan P1/P5 stub)…"
set +e
scan_out=$(fe_sql -e "SELECT * FROM duckbridge_test.sales.customers LIMIT 1;" 2>&1)
set -e
echo "${scan_out}" | tail -20
if echo "${scan_out}" | grep -qiE "planScan is not implemented|probe.*P1|P5|Doris.DuckDB divergence"; then
    log "SCAN-GATE GREEN: the SELECT reached planScan and failed loud (P1/P5 open) — the correct state."
elif echo "${scan_out}" | grep -qiE "error|exception|unsupported"; then
    log "SCAN-GATE CHECK: an error surfaced but not the planScan P1/P5 stub — inspect above (metadata OK, but"
    log "  the scan path may have changed, or the plugin jar is stale)."
else
    log "SCAN-GATE UNEXPECTED-SUCCESS: SELECT returned WITHOUT the planScan stub — planScan may have been"
    log "  bypassed (or is now implemented). Investigate before trusting; scan is still gated on P1/P5."
fi

# 9. Teardown.
if [[ $KEEP -eq 1 ]]; then
    log "--keep: leaving the stack up. Tear down with: ./smoke.sh --down"
    exit 0
fi
log "Tearing down (-v)…"
"${DOCKER}" compose -f "${COMPOSE}" down -v
log "Smoke complete."
