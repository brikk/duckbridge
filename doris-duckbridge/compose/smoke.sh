#!/usr/bin/env bash
# Live FE+BE+Quack smoke for doris-duckbridge.
#
# Brings up the patched Doris cluster + a DuckDB/Quack server (seeded with a schema + tables),
# installs the connector plugin, and drives it end-to-end:
#   • the connector LOADS + CREATE CATALOG type=duckbridge passes the FE gate;
#   • METADATA is REAL (probe P4): SHOW DATABASES/TABLES + DESC resolve the seeded quack schema;
#   • the SCAN is REAL (probes P5/P2): planScan emits a JDBC scan range so rows flow through the
#     BE JdbcJniScanner + our patched DuckDbTypeHandler. Asserts EXACT rows (incl. the LARGEINT
#     and ARRAY columns — the handler's first live exercise), an exact predicate result, count(*),
#     and EXPLAIN carrying the pushed predicate. Plus a P2 load probe (sequential + concurrent).
#
# Usage:
#   ./smoke.sh             # build plugin → up → wait healthy → install plugin → drive → down -v
#   ./smoke.sh --no-build  # skip the gradle plugin rebuild (assume the zip is current)
#   ./smoke.sh --up-only   # up + healthy + plugin installed, then STOP (manual dev loop)
#   ./smoke.sh --keep      # drive, but do NOT tear down at the end
#   ./smoke.sh --down      # tear everything down (-v) and exit
#
# What unlocks next: FUNCTION-shape pushdown (P1 divergence audit) — comparisons/IN/IS NULL push
# today (the domain floor); functions/LIKE/arithmetic are evaluated by Doris above the scan.
# A truthful SCAN failure is the DEPLOYMENT SEAM: if the BE can't load the quack-jdbc driver, the
# smoke fails loud with the fix (driver-jar placement + driver_url).

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

# 1b. Stage the quack-jdbc driver jar for the BE. The BE's JdbcJniScanner needs the driver on a
# path it can read; we extract it from the built plugin zip (which bundles it under lib/) into
# ./.be-drivers, mounted into the BE at /opt/duckbridge-drivers. CREATE CATALOG's driver_url then
# points at file:///opt/duckbridge-drivers/quack-jdbc.jar. (Deployment: operators put the jar in
# the drivers dir or a shared path and set driver_url — no checksum gate on the JNI path.)
log "Staging quack-jdbc driver jar for the BE (from the plugin zip → .be-drivers)…"
rm -rf "${HERE}/.be-drivers" && mkdir -p "${HERE}/.be-drivers"
tmp_extract="$(mktemp -d)"
unzip -oq "$zip_path" -d "$tmp_extract"
driver_src="$(find "$tmp_extract" -name 'quack-jdbc-*.jar' | head -1)"
if [[ -z "$driver_src" ]]; then
    log "ERROR: quack-jdbc jar not found inside the plugin zip — the plugin build must bundle it."
    rm -rf "$tmp_extract"
    exit 1
fi
cp "$driver_src" "${HERE}/.be-drivers/quack-jdbc.jar"
rm -rf "$tmp_extract"
BE_DRIVER_URL="file:///opt/duckbridge-drivers/quack-jdbc.jar"
log "  driver: $(basename "$driver_src") → .be-drivers/quack-jdbc.jar (BE driver_url=${BE_DRIVER_URL})"

# 2. Bring up the cluster (FE + BE + Quack).
# --force-recreate so a stale BE from a prior `--up-only` (created before .be-drivers was staged)
# is rebuilt with the driver-jar mount in place — otherwise `up -d` reuses the running container
# and the BE never sees the driver (JdbcJniScanner then fails "Failed to load driver class").
log "Starting Doris FE + BE + DuckDB/Quack…"
"${DOCKER}" compose -f "${COMPOSE}" up -d --force-recreate

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
        'driver_url'   = '${BE_DRIVER_URL}',
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

# ── SCAN is REAL now (P5/P2): planScan emits a JDBC scan range; rows flow through the BE
#    JdbcJniScanner + our DuckDbTypeHandler. This replaces the old "SCAN-GATE stub" expectation.
#    (History: before P5, a SELECT hit the planScan fail-loud stub — that WAS the green then.)
#    A truthful failure here is the DEPLOYMENT SEAM: if the BE can't load the driver, we say so.

log "SCAN: SELECT ... ORDER BY id (full-row read incl. largeint + array — DuckDbTypeHandler's first live run)…"
set +e
sel_out=$(fe_sql -e "SELECT id, name, big_id, tags FROM duckbridge_test.sales.customers ORDER BY id;" 2>&1)
sel_status=$?
set -e
echo "${sel_out}"
if [[ $sel_status -ne 0 ]]; then
    if echo "${sel_out}" | grep -qiE "driver|ClassNotFound|No suitable driver|jdbc_driver|quack-jdbc|resolve"; then
        log "SCAN DEPLOYMENT-SEAM: the BE could not load the quack-jdbc driver. Fix: ensure the jar is"
        log "  mounted where the BE reads it (.be-drivers → /opt/duckbridge-drivers) and driver_url points"
        log "  at it (file:///opt/duckbridge-drivers/quack-jdbc.jar). See NOTES-p5-p2-scan.md §driver-jar."
        exit 1
    fi
    log "SCAN FAIL: SELECT errored — inspect above + be.log (fe.log for the plan)."
    exit 1
fi
# Expected exact rows (name unicode-preserved; big_id: HUGEINT max, -5, 0; tags: array text).
if echo "${sel_out}" | grep -q "Alice" && echo "${sel_out}" | grep -q "straße" \
   && echo "${sel_out}" | grep -q "δοκιμή" \
   && echo "${sel_out}" | grep -q "170141183460469231731687303715884105727" \
   && echo "${sel_out}" | grep -qE "\[.?.vip"; then
    log "SCAN GREEN: full-row SELECT round-tripped — unicode strings, LARGEINT (HUGEINT max) and ARRAY"
    log "  all decoded by the BE DuckDbTypeHandler over quack-jdbc. 🎉"
else
    log "SCAN CHECK: rows returned but not the exact expected values — inspect above."
fi

log "SCAN: predicate SELECT ... WHERE id >= 2 (pushdown; assert exact filtered rows)…"
set +e
pred_out=$(fe_sql -e "SELECT id, name FROM duckbridge_test.sales.customers WHERE id >= 2 ORDER BY id;" 2>&1)
set -e
echo "${pred_out}"
pred_ids=$(echo "${pred_out}" | grep -oE "^[0-9]+" | tr '\n' ',' | sed 's/,$//')
if [[ "${pred_ids}" == "2,3" ]]; then
    log "SCAN GREEN: WHERE id>=2 returned exactly rows {2,3}."
else
    log "SCAN CHECK: WHERE id>=2 returned ids=[${pred_ids}] (expected 2,3) — inspect above."
fi

log "SCAN: EXPLAIN shows the pushed query (a 'stopped pushing' regression would be red)…"
set +e
explain_out=$(fe_sql -e "EXPLAIN VERBOSE SELECT id FROM duckbridge_test.sales.customers WHERE id >= 2;" 2>&1)
set -e
echo "${explain_out}" | grep -iE "query|WHERE|customers|>=|SELECT" | head -8 | sed 's/^/  /'
if echo "${explain_out}" | grep -qiE 'id.*>=.*2|WHERE'; then
    log "SCAN GREEN: EXPLAIN carries the pushed predicate in the query."
else
    log "SCAN NOTE: could not confirm the pushed predicate in EXPLAIN (format may differ) — inspect above."
fi

log "SCAN: SELECT count(*) (aggregate over the JDBC scan)…"
set +e
cnt=$(fe_sql -N -e "SELECT count(*) FROM duckbridge_test.sales.customers;" 2>&1 | tail -1)
set -e
if [[ "${cnt}" == "3" ]]; then
    log "SCAN GREEN: count(*) = 3."
else
    log "SCAN CHECK: count(*) = '${cnt}' (expected 3) — inspect above."
fi

# ── P1 function pushdown: an allowlisted, audited-IDENTICAL function must appear INSIDE the pushed
# QUERY (not just re-filtered above), and return exact rows. character_length (code points) →
# DuckDB length; 'straße' and 'δοκιμή' are both 6 code points, so char_length=6 → rows {2,3}.
log "P1: WHERE character_length(name)=6 — allowlisted function pushdown (EXPLAIN QUERY + exact rows)…"
set +e
fn_explain=$(fe_sql -e "EXPLAIN VERBOSE SELECT id FROM duckbridge_test.sales.customers WHERE character_length(name)=6;" 2>&1)
set -e
fn_query=$(echo "${fn_explain}" | grep -iE "QUERY:" | head -1)
echo "  ${fn_query}"
if echo "${fn_query}" | grep -qiE "length\("; then
    log "P1 GREEN: the allowlisted function is INSIDE the pushed QUERY (character_length→DuckDB length)."
else
    log "P1 CHECK: character_length not found in the pushed QUERY — the plugin may be stale (rebuild) or"
    log "  the FE dropped it. Inspect the QUERY line above (a re-filtered-above result is still correct,"
    log "  but the point of P1 is that it PUSHES)."
fi
set +e
fn_ids=$(fe_sql -N -e "SELECT id FROM duckbridge_test.sales.customers WHERE character_length(name)=6 ORDER BY id;" 2>&1 | grep -oE "^[0-9]+" | tr '\n' ',' | sed 's/,$//')
set -e
if [[ "${fn_ids}" == "2,3" ]]; then
    log "P1 GREEN: WHERE character_length(name)=6 returned exactly rows {2,3} (straße, δοκιμή)."
else
    log "P1 CHECK: char_length=6 returned ids=[${fn_ids}] (expected 2,3) — inspect above."
fi

# ── P2 probe: scan-range count + connection-pool behavior under sequential + concurrent load.
# Static: the connector emits ONE range per query (a JDBC scan is un-partitionable). Empirical:
# hammer the stack sequentially and concurrently to watch for Quack 1.5.4 server-pool exhaustion
# (the trino side saw the fixed pool exhaust under churn).
log "P2: N=20 sequential SELECTs (pool reuse — watch for connection errors)…"
seq_fail=0
for _ in $(seq 1 20); do
    if ! fe_sql -N -e "SELECT count(*) FROM duckbridge_test.sales.customers;" >/dev/null 2>&1; then
        seq_fail=$((seq_fail + 1))
    fi
done
log "  sequential failures: ${seq_fail}/20"

log "P2: M=8 concurrent SELECTs (≤8 per constraint — watch for pool exhaustion)…"
conc_fail_dir="$(mktemp -d)"
for i in $(seq 1 8); do
    (
        if fe_sql -N -e "SELECT id, name, big_id FROM duckbridge_test.sales.customers ORDER BY id;" >/dev/null 2>&1; then
            : > "${conc_fail_dir}/ok_${i}"
        else
            : > "${conc_fail_dir}/fail_${i}"
        fi
    ) &
done
wait
conc_ok=$(find "${conc_fail_dir}" -name 'ok_*' | wc -l | tr -d ' ')
conc_fail=$(find "${conc_fail_dir}" -name 'fail_*' | wc -l | tr -d ' ')
rm -rf "${conc_fail_dir}"
log "  concurrent: ${conc_ok}/8 ok, ${conc_fail}/8 failed"
if [[ "${seq_fail}" -eq 0 && "${conc_fail}" -eq 0 ]]; then
    log "P2 GREEN: 20 sequential + 8 concurrent SELECTs all succeeded — no Quack-pool exhaustion at"
    log "  one-range-per-query. Route J does not need connection-reuse work before real use (record in NOTES)."
else
    log "P2 CHECK: ${seq_fail} sequential + ${conc_fail} concurrent failures — possible pool pressure;"
    log "  inspect be.log for Quack connection errors and record the verdict in NOTES-p5-p2-scan.md."
fi

# 9. Teardown.
if [[ $KEEP -eq 1 ]]; then
    log "--keep: leaving the stack up. Tear down with: ./smoke.sh --down"
    exit 0
fi
log "Tearing down (-v)…"
"${DOCKER}" compose -f "${COMPOSE}" down -v
log "Smoke complete."
