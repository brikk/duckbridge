#!/usr/bin/env bash
# Live FE+BE+Quack smoke for doris-duckbridge.
#
# Brings up the patched Doris cluster + a DuckDB/Quack server (seeded with a schema + tables),
# installs the connector plugin, and drives it end-to-end:
#   • the connector LOADS + CREATE CATALOG type=duckbridge passes the FE gate;
#   • METADATA is REAL (probe P4): SHOW DATABASES/TABLES + DESC resolve the seeded quack schema;
#   • the SCAN is REAL (probes P5/P2): planScan emits a JDBC scan range so rows flow through the
#     BE JdbcJniScanner + our patched DuckDbTypeHandler. Asserts EXACT rows (incl. LARGEINT,
#     ARRAY and VARBINARY), an exact predicate result, count(*),
#     and EXPLAIN carrying the pushed predicate. Plus a P2 load probe (sequential + concurrent).
#
# Usage:
#   ./smoke.sh             # build plugin → up → wait healthy → install plugin → drive → down -v
#   ./smoke.sh --no-build  # skip the gradle plugin rebuild (assume the zip is current)
#   ./smoke.sh --up-only   # up + healthy + plugin installed, then STOP (manual dev loop)
#   ./smoke.sh --keep      # drive, but do NOT tear down at the end
#   ./smoke.sh --down      # tear everything down (-v) and exit
#
# Function pushdown is allowlist-only: audited identical functions may push; everything else stays
# above the scan. Comparisons/IN/IS NULL remain the domain floor.
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

# Doris's init_fe.sh adds priority_networks only while initializing an empty metadata directory.
# We replace the mounted config on every run, so preserve the static election network ourselves;
# otherwise a restart can select the wrong IP and fail to rejoin its persisted FE identity.
# Stage it before --down too, because Compose still resolves the bind-mount source.
cp "${HERE}/fe.conf" "${HERE}/.fe.conf.runtime"
printf '\npriority_networks = 172.31.80.0/24\n' >> "${HERE}/.fe.conf.runtime"

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
    if ! "${gradle_java:-/nonexistent}/bin/java" -version 2>&1 | grep -qE '"(2[5-9]|[3-9][0-9])'; then
        gradle_java=""
        path_java="$(command -v java || true)"
        java_candidates=()
        [[ -n "${path_java}" ]] && java_candidates+=("$(dirname "$(dirname "${path_java}")")")
        for candidate in "${java_candidates[@]}" "${HOME}"/.gradle/jdks/*/; do
            candidate="${candidate%/}"
            if [[ -x "${candidate}/bin/java" ]] \
               && "${candidate}/bin/java" -version 2>&1 | grep -qE '"(2[5-9]|[3-9][0-9])'; then
                gradle_java="${candidate}"
                break
            fi
        done
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
expected_driver="quack-jdbc-0.10.0.jar"
if [[ "$(basename "$driver_src")" != "${expected_driver}" ]]; then
    log "ERROR: staged Quack driver is '$(basename "$driver_src")' (expected ${expected_driver})."
    rm -rf "$tmp_extract"
    exit 1
fi
cp "$driver_src" "${HERE}/.be-drivers/quack-jdbc.jar"
rm -rf "$tmp_extract"
BE_DRIVER_URL="file:///opt/duckbridge-drivers/quack-jdbc.jar"
log "  driver: $(basename "$driver_src") → .be-drivers/quack-jdbc.jar (BE driver_url=${BE_DRIVER_URL})"

# 2. Install the plugin into the FE plugin volume (unzipped → plugins/connector/duckbridge).
# Populate the named volume before the FE starts so its first plugin scan cannot observe an empty
# directory and record a transient load failure. docker cp avoids unreliable macOS VM bind mounts.
log "Installing plugin from $(basename "$zip_path") into the FE plugin volume…"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-doris-duckbridge-api24-08273613}"
VOL_NAME="${PROJECT_NAME}_fe-plugin-duckbridge"
"${DOCKER}" volume create \
    --label "com.docker.compose.project=${PROJECT_NAME}" \
    --label com.docker.compose.volume=fe-plugin-duckbridge \
    "${VOL_NAME}" >/dev/null
helper=$("${DOCKER}" create -v "${VOL_NAME}:/dest" alpine \
    sh -c 'apk add --no-cache unzip >/dev/null 2>&1 && rm -rf /dest/* /dest/.[!.]* 2>/dev/null; cd /dest && unzip -oq /tmp/plugin.zip')
"${DOCKER}" cp "$zip_path" "$helper":/tmp/plugin.zip
"${DOCKER}" start -a "$helper"
"${DOCKER}" rm "$helper" >/dev/null

# 3. Bring up the cluster (FE + BE + Quack).
# --force-recreate so a stale BE from a prior `--up-only` (created before .be-drivers was staged)
# is rebuilt with the driver-jar mount in place — otherwise `up -d` reuses the running container
# and the BE never sees the driver (JdbcJniScanner then fails "Failed to load driver class").
log "Starting Doris FE + BE + DuckDB/Quack…"
"${DOCKER}" compose -f "${COMPOSE}" up -d --force-recreate

duckdb_version=$("${DOCKER}" exec doris-duckbridge-quack \
    duckdb -csv -noheader -c "SELECT version();" 2>/dev/null | tr -d '\r')
if [[ "${duckdb_version}" != "v2.0.0" ]]; then
    log "DUCKDB VERSION FAIL: Quack server is '${duckdb_version:-<missing>}' (expected v2.0.0)."
    exit 1
fi
log "DUCKDB VERSION GREEN: Quack 0.10 driver lane is backed by DuckDB ${duckdb_version}."

# Exercise a real restart with persisted metadata. The staged priority_networks setting must keep
# the FE on the same static identity rather than selecting another container network.
log "Restarting FE with persisted metadata…"
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

frontend_ip=$(fe_sql -N -e "SHOW FRONTENDS" 2>/dev/null | awk -F'\t' 'NR==1 {print $2}')
if [[ "${frontend_ip}" != "172.31.80.10" ]]; then
    log "FE RESTART FAIL: persisted FE identity is '${frontend_ip:-<missing>}' (expected 172.31.80.10)."
    exit 1
fi
log "FE restart retained static identity 172.31.80.10."

# 5. Wait for BE registration (SHOW BACKENDS → Alive). Every real scan requires a healthy BE.
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

# API-major compatibility is not FE/BE wire compatibility. #68785 renumbered query options and
# #68837 changed report field 34. Both engines must report BASELINE's exact pin before fixtures.
live_version() {
    local statement="$1"
    fe_sql -B -e "${statement}" | awk -F'\t' \
        'NR==1 {for (i=1;i<=NF;i++) {if ($i=="Version") v=i; if ($i=="Alive") a=i}}
         NR>1 && v && a && $a=="true" {print $v}' | sort -u
}
expected_pin=$(sed -n 's/^PIN_SHA=//p' "${REPO_ROOT}/doris-patches/BASELINE")
[[ "${expected_pin}" =~ ^[0-9a-f]{40}$ ]] || { log "Invalid Doris BASELINE pin."; exit 1; }
expected_version="doris-0.0.0-${expected_pin:0:11}"
fe_version=$(live_version "SHOW FRONTENDS")
be_version=$(live_version "SHOW BACKENDS")
if [[ "${fe_version}" != "${expected_version}" || "${be_version}" != "${expected_version}" ]]; then
    log "VERSION FAIL: expected FE+BE ${expected_version}; FE='${fe_version}', BE='${be_version}'."
    exit 1
fi
log "VERSION GREEN: matching FE/BE runtime ${expected_version}."

# 5b. NOTE on the local-shuffle shim: doris-ducklake's smoke SETs
# enable_local_shuffle_planner=false because it pairs a branch FE with a STOCK 4.1.0 BE whose
# thrift enum lacks TPlanNodeType.LOCAL_EXCHANGE_NODE=38. doris-duckbridge runs a BRANCH BE
# too, so FE and BE share the same thrift — that shim should NOT be needed here. Left OUT
# deliberately; if a scan ever hits "Unsupported exec type in pipeline", the BE image is stale
# (not branch-built) — rebake it, don't paper over it with the shim.

# 6. Verify the connector provider registered. On master (SPI upstreamed, #66135) the FE routes
# CREATE CATALOG by REGISTERED PROVIDER TYPE — no whitelist patch. The cleanest FE-observable
# signal is that CREATE CATALOG type=duckbridge is NOT rejected as "Unknown catalog type", which
# passes on a pristine FE once the plugin ServiceLoader-registers (the FE log shows
# "registered types: [duckbridge, …]").
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

# 7. CREATE CATALOG type=duckbridge. Passing the FE gate (no "Unknown catalog type") proves the
# provider ServiceLoader-registered and the pristine master FE routed by type (no FE patch).
log "Creating catalog duckbridge_test (type=duckbridge, quack URL + token)…"
set +e
cat_out=$(fe_sql -e "
    DROP CATALOG IF EXISTS duckbridge_test;
    CREATE CATALOG duckbridge_test PROPERTIES (
        'type'         = 'duckbridge',
        'jdbc_url'     = 'jdbc:quack://duckdb-quack:9494',
        'driver_class' = 'com.gizmodata.quack.jdbc.sql.QuackDriver',
        'driver_url'   = '${BE_DRIVER_URL}',
        'enable.mapping.timestamp_tz' = 'true',
        'password'     = '${QUACK_TOKEN}'
    );
    SHOW CATALOGS;
" 2>&1)
cat_status=$?
set -e
echo "${cat_out}" | tail -20
if [[ $cat_status -ne 0 ]]; then
    if echo "${cat_out}" | grep -qiE "unknown catalog type|not.*ready|SPI_READY"; then
        log "GATE FAIL: type=duckbridge rejected as unknown — the plugin didn't ServiceLoader-register"
        log "  (check the FE log for 'registered types: [duckbridge, …]'; the FE needs no patch on master)."
        exit 1
    fi
    log "CREATE CATALOG failed for another reason — inspect output above + fe.log."
    exit 1
fi
log "GATE OK: catalog created — provider registration + type-routing on the pristine master FE both work."

# A declaration alone covers ALTER, not CREATE: both must honor the restricted operator path
# in fe.conf, and mandatory traversal rejection must remain active independently of that path.
expect_sql_rejection() {
    local statement="$1" pattern="$2" output
    if output=$(fe_sql -e "$statement" 2>&1); then
        log "EXPECTED-REJECTION FAIL: the statement was accepted."
        exit 1
    fi
    if ! grep -qiE "$pattern" <<< "$output"; then
        printf '%s\n' "$output"
        log "EXPECTED-REJECTION FAIL: statement failed for the wrong reason."
        exit 1
    fi
}
log "SECURITY: CREATE and ALTER must reject driver-path policy violations…"
for unsafe_url in \
    'file:///opt/duckbridge-drivers-evil/quack-jdbc.jar' \
    'https://example.invalid/quack-jdbc.jar'; do
    expect_sql_rejection "CREATE CATALOG duckbridge_security_probe PROPERTIES (
        'type'='duckbridge', 'jdbc_url'='jdbc:quack://duckdb-quack:9494',
        'driver_url'='${unsafe_url}');" 'allowed paths'
    expect_sql_rejection "ALTER CATALOG duckbridge_test SET PROPERTIES (
        'driver_url'='${unsafe_url}');" 'allowed paths'
done
expect_sql_rejection "CREATE CATALOG duckbridge_security_probe PROPERTIES (
    'type'='duckbridge', 'jdbc_url'='jdbc:quack://duckdb-quack:9494',
    'driver_url'='file:///opt/duckbridge-drivers/%2e%2e/evil.jar');" 'traversal'
expect_sql_rejection "ALTER CATALOG duckbridge_test SET PROPERTIES (
    'driver_url'='file:///opt/duckbridge-drivers/%2e%2e/evil.jar');" 'traversal'
log "SECURITY GREEN: CREATE/ALTER reject prefix confusion, remote paths and encoded traversal."

# 8. METADATA is now REAL (probe P4 settled): resolution over quack-jdbc should list the seeded
# quack schemas/tables and DESC a table's columns with the probe-decided Doris types.

log "Metadata resolution: SHOW DATABASES (expect the seeded quack schema 'sales')…"
set +e
dbs_out=$(fe_sql -e "SHOW DATABASES FROM duckbridge_test;" 2>&1)
set -e
echo "${dbs_out}" | tail -20
if echo "${dbs_out}" | grep -qiw "sales"; then
    log "METADATA GREEN: SHOW DATABASES lists the seeded quack schema 'sales' (real resolution over quack-jdbc)."
else
    log "METADATA FAIL: expected a 'sales' database from the seeded quack server — inspect above + fe.log."
    log "  (Seed file: compose/seed/quack-init.sql; the quack container must have run it.)"
    exit 1
fi

log "Metadata resolution: SHOW TABLES FROM duckbridge_test.sales (expect customers, orders)…"
set +e
tables_out=$(fe_sql -e "SHOW TABLES FROM duckbridge_test.sales;" 2>&1)
set -e
echo "${tables_out}" | tail -20
if echo "${tables_out}" | grep -qiw "customers" && echo "${tables_out}" | grep -qiw "orders"; then
    log "METADATA GREEN: SHOW TABLES resolves the seeded tables."
else
    log "METADATA FAIL: expected 'customers' + 'orders' — inspect above + fe.log."
    exit 1
fi

log "Metadata resolution: DESC duckbridge_test.sales.customers (expect the P4 type map)…"
set +e
desc_out=$(fe_sql -e "DESC duckbridge_test.sales.customers;" 2>&1)
set -e
echo "${desc_out}" | tail -20
# balance DECIMAL, HUGEINT→LARGEINT, LIST→ARRAY, BLOB→VARBINARY and native UUID are the
# distinctive P4-mapped columns.
if echo "${desc_out}" | grep -qiE "largeint" && echo "${desc_out}" | grep -qiE "array" \
   && echo "${desc_out}" | grep -qiE "varbinary" && echo "${desc_out}" | grep -qiE "uuid"; then
    log "METADATA GREEN: DESC shows LARGEINT, ARRAY, VARBINARY and native UUID."
else
    log "METADATA FAIL: expected LARGEINT + ARRAY + VARBINARY + UUID — inspect above."
    exit 1
fi

# ── SCAN is REAL now (P5/P2): planScan emits a JDBC scan range; rows flow through the BE
#    JdbcJniScanner + our DuckDbTypeHandler. This replaces the old "SCAN-GATE stub" expectation.
#    (History: before P5, a SELECT hit the planScan fail-loud stub — that WAS the green then.)
#    A truthful failure here is the DEPLOYMENT SEAM: if the BE can't load the driver, we say so.

log "JVM LAZY-LOAD: before the first scan, no JNI plugin or JVM metrics may be initialized…"
jni_before=$("${DOCKER}" exec doris-duckbridge-be curl -fsS \
    http://127.0.0.1:8040/api/jni_plugin_status)
if [[ "${jni_before}" != *'"registryInitialized":false'* ]]; then
    log "JVM LAZY-LOAD FAIL: expected registryInitialized=false before the first scan: ${jni_before}"
    exit 1
fi
metrics_before=$("${DOCKER}" exec doris-duckbridge-be curl -fsS http://127.0.0.1:8040/metrics)
if [[ "${metrics_before}" == *"doris_be_jvm_"* ]]; then
    log "JVM LAZY-LOAD FAIL: JVM metrics existed before the first scan."
    exit 1
fi
log "JVM LAZY-LOAD GREEN: registry is uninitialized and no JVM metrics exist before first use."

log "SCAN: exact LARGEINT + ARRAY + arbitrary binary + UUID values…"
set +e
sel_out=$(fe_sql -N -e "SELECT id, name, big_id, tags, HEX(payload), uuid_value FROM duckbridge_test.sales.customers ORDER BY id;" 2>&1)
sel_status=$?
set -e
echo "${sel_out}"
if [[ $sel_status -ne 0 ]]; then
    if echo "${sel_out}" | grep -qiE "driver|ClassNotFound|No suitable driver|jdbc_driver|quack-jdbc|resolve"; then
        log "SCAN DEPLOYMENT-SEAM: the BE could not load the quack-jdbc driver. Fix: ensure the jar is"
        log "  mounted where the BE reads it (.be-drivers → /opt/duckbridge-drivers) and driver_url points"
        log "  at it (file:///opt/duckbridge-drivers/quack-jdbc.jar). See DESIGN-jdbc-scan-contract.md."
        exit 1
    fi
    log "SCAN FAIL: SELECT errored — inspect above + be.log (fe.log for the plan)."
    exit 1
fi
# Exact rows pin row count, ordering, value association, arrays, binary bytes and SQL NULL.
expected_sel=$(printf '%s\n' \
    $'1\tAlice\t170141183460469231731687303715884105727\t["vip", "eu"]\t80FF000100\t00112233-4455-6677-8899-aabbccddeeff' \
    $'2\tstraße\t-5\t["de"]\t41004200\tffffffff-ffff-4fff-8fff-ffffffffffff' \
    $'3\tδοκιμή\t0\t[]\tNULL\tNULL')
if [[ "${sel_out}" == "${expected_sel}" ]]; then
    log "SCAN GREEN: unicode, LARGEINT, ARRAY, invalid/NUL/trailing-zero binary and UUID round-tripped"
    log "  all decoded by the BE DuckDbTypeHandler over quack-jdbc. 🎉"
else
    log "SCAN FAIL: rows returned but not the exact expected values — inspect above."
    exit 1
fi

jni_after=$("${DOCKER}" exec doris-duckbridge-be curl -fsS \
    http://127.0.0.1:8040/api/jni_plugin_status)
if [[ "${jni_after}" != *'"loadedCount":1'* || "${jni_after}" != *'"failedCount":0'* \
   || "${jni_after}" != *'"plugins":[{"name":"jdbc","state":"READY"'* ]]; then
    log "JVM LAZY-LOAD FAIL: first scan did not load exactly the healthy JDBC plugin: ${jni_after}"
    exit 1
fi
metrics_after=$("${DOCKER}" exec doris-duckbridge-be curl -fsS http://127.0.0.1:8040/metrics)
if [[ "${metrics_after}" != *"doris_be_jvm_"* ]]; then
    log "JVM LAZY-LOAD FAIL: JVM metrics did not appear after the first scan."
    exit 1
fi
jvm_zone=$("${DOCKER}" exec doris-duckbridge-be sh -c \
    'pid=$(jcmd -l 2>/dev/null | grep " DorisBE$" | cut -d" " -f1); jcmd "$pid" VM.system_properties 2>/dev/null' |
    awk -F= '$1=="user.timezone" {print $2}')
if [[ "${jvm_zone}" != "America/Los_Angeles" ]]; then
    log "JVM ZONE FAIL: embedded JVM zone is '${jvm_zone:-<missing>}' (expected America/Los_Angeles)."
    exit 1
fi
log "JVM LAZY-LOAD GREEN: first scan loaded only JDBC and published JVM metrics."

log "SCAN: predicate SELECT ... WHERE id >= 2 (pushdown; assert exact filtered rows)…"
set +e
pred_out=$(fe_sql -e "SELECT id, name FROM duckbridge_test.sales.customers WHERE id >= 2 ORDER BY id;" 2>&1)
set -e
echo "${pred_out}"
pred_ids=$(echo "${pred_out}" | grep -oE "^[0-9]+" | tr '\n' ',' | sed 's/,$//')
if [[ "${pred_ids}" == "2,3" ]]; then
    log "SCAN GREEN: WHERE id>=2 returned exactly rows {2,3}."
else
    log "SCAN FAIL: WHERE id>=2 returned ids=[${pred_ids}] (expected 2,3) — inspect above."
    exit 1
fi

log "SCAN: EXPLAIN shows the pushed query (a 'stopped pushing' regression would be red)…"
set +e
explain_out=$(fe_sql -e "EXPLAIN VERBOSE SELECT id FROM duckbridge_test.sales.customers WHERE id >= 2;" 2>&1)
set -e
echo "${explain_out}" | grep -iE "query|WHERE|customers|>=|SELECT" | head -8 | sed 's/^/  /'
explain_query=$(echo "${explain_out}" | grep -im1 'QUERY:' || true)
if echo "${explain_query}" | grep -qiE 'id.*>=.*2'; then
    log "SCAN GREEN: EXPLAIN carries the pushed predicate in the query."
else
    log "SCAN FAIL: could not confirm the pushed predicate in EXPLAIN — inspect above."
    exit 1
fi

log "SCAN: SELECT count(*) (aggregate over the JDBC scan)…"
set +e
cnt=$(fe_sql -N -e "SELECT count(*) FROM duckbridge_test.sales.customers;" 2>&1 | tail -1)
set -e
if [[ "${cnt}" == "3" ]]; then
    log "SCAN GREEN: count(*) = 3."
else
    log "SCAN FAIL: count(*) = '${cnt}' (expected 3) — inspect above."
    exit 1
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
    log "P1 FAIL: character_length not found in the pushed QUERY — the plugin may be stale (rebuild) or"
    log "  the FE dropped it. Inspect the QUERY line above (a re-filtered-above result is still correct,"
    log "  but the point of P1 is that it PUSHES)."
    exit 1
fi
set +e
fn_ids=$(fe_sql -N -e "SELECT id FROM duckbridge_test.sales.customers WHERE character_length(name)=6 ORDER BY id;" 2>&1 | grep -oE "^[0-9]+" | tr '\n' ',' | sed 's/,$//')
set -e
if [[ "${fn_ids}" == "2,3" ]]; then
    log "P1 GREEN: WHERE character_length(name)=6 returned exactly rows {2,3} (straße, δοκιμή)."
else
    log "P1 FAIL: char_length=6 returned ids=[${fn_ids}] (expected 2,3) — inspect above."
    exit 1
fi

# ── P3/P6 temporal pushdown: naive TIMESTAMP + TIMESTAMPTZ predicates render zone-safely and push.
# A naive TIMESTAMP col (placed_at) → naive literal in QUERY; a TIMESTAMPTZ col (shipped_at) →
# explicit-UTC 'TIMESTAMPTZ …+00' in QUERY (proven zone-independent). Both must appear IN the QUERY.
log "P3/P6: WHERE placed_at >= '2023-03-02 00:00:00' (naive TIMESTAMP pushdown)…"
set +e
tz1_explain=$(fe_sql -e "EXPLAIN VERBOSE SELECT order_id FROM duckbridge_test.sales.orders WHERE placed_at >= '2023-03-02 00:00:00';" 2>&1)
tz1_query=$(echo "${tz1_explain}" | grep -iE "QUERY:" | head -1)
tz1_ids=$(fe_sql -N -e "SELECT order_id FROM duckbridge_test.sales.orders WHERE placed_at >= '2023-03-02 00:00:00' ORDER BY order_id;" 2>&1 | grep -oE "^[0-9]+" | tr '\n' ',' | sed 's/,$//')
set -e
echo "  ${tz1_query}"
if echo "${tz1_query}" | grep -qiE "placed_at.*>=.*TIMESTAMP '2023-03-02" && [[ "${tz1_ids}" == "101" ]]; then
    log "P3/P6 GREEN: naive TIMESTAMP predicate pushed (naive literal) and returned exactly {101}."
else
    log "P3/P6 FAIL: naive TIMESTAMP push — QUERY above, ids=[${tz1_ids}] (expected 101). Inspect."
    exit 1
fi

log "P3/P6: WHERE shipped_at >= '2023-03-02 00:00:00' (TIMESTAMPTZ → explicit-UTC pushdown)…"
set +e
tz2_explain=$(fe_sql -e "EXPLAIN VERBOSE SELECT order_id FROM duckbridge_test.sales.orders WHERE shipped_at >= '2023-03-02 00:00:00';" 2>&1)
tz2_query=$(echo "${tz2_explain}" | grep -iE "QUERY:" | head -1)
tz2_ids=$(fe_sql -N -e "SELECT order_id FROM duckbridge_test.sales.orders WHERE shipped_at >= '2023-03-02 00:00:00' ORDER BY order_id;" 2>&1 | grep -oE "^[0-9]+" | tr '\n' ',' | sed 's/,$//')
set -e
echo "  ${tz2_query}"
# The distinctive marker: the TIMESTAMPTZ column's literal is rendered explicit-UTC (…+00).
if echo "${tz2_query}" | grep -qiE "shipped_at.*>=.*TIMESTAMPTZ '2023-03-02.*\+00'" && [[ "${tz2_ids}" == "101" ]]; then
    log "P3/P6 GREEN: TIMESTAMPTZ predicate pushed as explicit-UTC ('…+00') and returned exactly {101} —"
    log "  zone-safe instant comparison, no session-zone dependence (option 2)."
else
    log "P3/P6 FAIL: TIMESTAMPTZ push — QUERY above, ids=[${tz2_ids}] (expected 101). Expected the"
    log "  literal rendered as TIMESTAMPTZ '…+00'. Inspect (plugin may be stale, or rendering regressed)."
    exit 1
fi

log "P3/P6: exact temporal values with Quack in Asia/Tokyo and BE JVM in America/Los_Angeles…"
temporal_out=$(fe_sql -N -e "
    SET time_zone='UTC';
    SELECT order_id, placed_at, shipped_at
    FROM duckbridge_test.sales.orders ORDER BY order_id;
" 2>&1)
expected_temporal=$(printf '%s\n' \
    $'100\t2023-03-01 10:00:00.000000\t2023-03-01 18:00:00.000000+00:00' \
    $'101\t2023-03-02 11:30:00.000000\t2023-03-02 20:00:00.000000+00:00')
if [[ "${temporal_out}" != "${expected_temporal}" ]]; then
    echo "${temporal_out}"
    log "P3/P6 FAIL: temporal values changed across the Quack/JVM/FE timezone boundaries."
    exit 1
fi
log "P3/P6 GREEN: naive wall clocks and TIMESTAMPTZ instants retained exact microsecond values."

assert_temporal_edges() {
    local actual expected
    actual=$(fe_sql -N -e "SET time_zone='UTC';
        SELECT id, local_value, instant_value FROM duckbridge_test.sales.temporal_edges ORDER BY id;")
    expected=$(printf '%s\n' \
        $'1\t1969-12-31 23:59:59.123456\t1969-12-31 23:59:59.123456+00:00' \
        $'2\t2024-11-03 01:30:00.654321\t2024-11-03 08:30:00.654321+00:00' \
        $'3\t2024-11-03 01:30:00.654321\t2024-11-03 09:30:00.654321+00:00' \
        $'4\tNULL\tNULL')
    if [[ "${actual}" != "${expected}" ]]; then
        printf '%s\n' "${actual}"
        log "P3/P6 FAIL: pre-epoch fractions, DST overlap or temporal NULL changed."; exit 1
    fi
}
assert_temporal_edges
log "P3/P6 GREEN: pre-epoch microseconds, distinct DST-overlap instants and SQL NULL retained."

# ── P2 probe: scan-range count + connection-pool behavior under sequential + concurrent load.
# Static: the connector emits ONE range per query (a JDBC scan is un-partitionable). Empirical:
# hammer the stack sequentially and concurrently to catch pool exhaustion or connection leaks.
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
    log "P2 FAIL: ${seq_fail} sequential + ${conc_fail} concurrent failures — possible pool pressure;"
    log "  inspect be.log for Quack connection errors and update the scan-contract evidence."
    exit 1
fi

# Latest master's catalog retirement changes must not close replacement instances. Initialize
# the old connector first (the scans above did), then ALTER, DROP/recreate and read again.
log "LIFECYCLE: ALTER and same-name DROP/recreate retain healthy replacement metadata/scans…"
fe_sql -e "ALTER CATALOG duckbridge_test SET PROPERTIES ('password'='${QUACK_TOKEN}');"
[[ "$(fe_sql -N -e "SELECT count(*) FROM duckbridge_test.sales.customers;")" == "3" ]] || {
    log "LIFECYCLE FAIL: ALTER replacement scan changed."; exit 1;
}
fe_sql -e "DROP CATALOG duckbridge_test;
    CREATE CATALOG duckbridge_test PROPERTIES (
        'type'='duckbridge', 'jdbc_url'='jdbc:quack://duckdb-quack:9494',
        'driver_class'='com.gizmodata.quack.jdbc.sql.QuackDriver',
        'driver_url'='${BE_DRIVER_URL}', 'enable.mapping.timestamp_tz'='true',
        'password'='${QUACK_TOKEN}');"
[[ "$(fe_sql -N -e "SELECT count(*) FROM duckbridge_test.sales.customers;")" == "3" ]] || {
    log "LIFECYCLE FAIL: DROP/recreate replacement scan changed."; exit 1;
}

# The early startup restart is not enough to prove persisted catalogs. Restart after real
# metadata and scan use; the same FE identity and existing catalog must return without CREATE.
log "RESTART: retaining an initialized catalog and FE identity across a persisted-metadata restart…"
identity_before=$(fe_sql -N -e "SHOW FRONTENDS;" | cut -f1,2)
"${DOCKER}" compose -f "${COMPOSE}" restart doris-fe
deadline=$((SECONDS + 180))
while :; do
    if [[ "$(fe_sql -N -e "SELECT count(*) FROM duckbridge_test.sales.customers;" 2>/dev/null || true)" == "3" ]]; then
        break
    fi
    if (( SECONDS >= deadline )); then
        log "RESTART FAIL: persisted catalog did not recover."; exit 1
    fi
    sleep 2
done
identity_after=$(fe_sql -N -e "SHOW FRONTENDS;" | cut -f1,2)
[[ "${identity_before}" == "${identity_after}" ]] || {
    log "RESTART FAIL: FE name/address changed."; exit 1;
}
[[ "$(fe_sql -N -e "SELECT id, name, big_id, tags, HEX(payload), uuid_value FROM duckbridge_test.sales.customers ORDER BY id;")" == "${expected_sel}" ]] || {
    log "RESTART FAIL: exact persisted-catalog values changed."; exit 1;
}
log "LIFECYCLE/RESTART GREEN: ALTER, DROP/recreate, persisted identity/catalog and exact values retained."
assert_temporal_edges

# Invalidate only this run-owned container's JDBC manifest, never the image or a shared tree.
# A failed lazy load must be plugin-scoped: native BE health/status survives. Recreating from
# the immutable complete image restores the original plugin and must recover real scans.
log "PLUGIN ISOLATION: an incompatible JDBC plugin must fail without crashing the native BE…"
trap '"${DOCKER}" compose -f "${COMPOSE}" up -d --no-deps --force-recreate doris-be >/dev/null 2>&1 || true' EXIT
"${DOCKER}" exec doris-duckbridge-be bash -c '
    printf "Doris-Jni-Plugin-Api-Version: 0.0\n" > /tmp/duckbridge-invalid-jni-manifest
    jar ufm /opt/apache-doris/be/plugins/jni/jdbc/jdbc-scanner.jar /tmp/duckbridge-invalid-jni-manifest
'
"${DOCKER}" compose -f "${COMPOSE}" restart doris-be
deadline=$((SECONDS + 180))
while ! "${DOCKER}" exec doris-duckbridge-be curl -fsS http://127.0.0.1:8040/api/jni_plugin_status >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
        log "PLUGIN ISOLATION FAIL: native BE did not restart with the invalid plugin."; exit 1
    fi
    sleep 2
done
# The native HTTP endpoint becomes ready before FE heartbeat registration. Wait for both;
# otherwise a generic 'no available backends' error would not exercise plugin isolation at all.
while [[ "$(fe_sql -N -e "SHOW BACKENDS;" | awk -F'\t' '$10=="true"{n++} END{print n+0}')" -lt 1 ]]; do
    if (( SECONDS >= deadline )); then
        log "PLUGIN ISOLATION FAIL: restarted BE did not re-register with the FE."; exit 1
    fi
    sleep 2
done
expect_sql_rejection "SELECT count(*) FROM duckbridge_test.sales.customers;" 'jdbc|plugin|API'
jni_failed=$("${DOCKER}" exec doris-duckbridge-be curl -fsS http://127.0.0.1:8040/api/jni_plugin_status)
if [[ "${jni_failed}" != *'"failedCount":1'* || "${jni_failed}" != *'"name":"jdbc","state":"FAILED"'* ]]; then
    log "PLUGIN ISOLATION FAIL: expected only JDBC to fail: ${jni_failed}"; exit 1
fi
"${DOCKER}" compose -f "${COMPOSE}" up -d --no-deps --force-recreate doris-be
trap - EXIT
deadline=$((SECONDS + 180))
while [[ "$(fe_sql -N -e "SELECT count(*) FROM duckbridge_test.sales.customers;" 2>/dev/null || true)" != "3" ]]; do
    if (( SECONDS >= deadline )); then
        log "PLUGIN ISOLATION FAIL: recreating the BE did not restore JDBC scans."; exit 1
    fi
    sleep 2
done
log "PLUGIN ISOLATION GREEN: only JDBC failed, native health survived, complete-image recreation restored scans."

# 9. Teardown.
if [[ $KEEP -eq 1 ]]; then
    log "--keep: leaving the stack up. Tear down with: ./smoke.sh --down"
    exit 0
fi
log "Tearing down (-v)…"
"${DOCKER}" compose -f "${COMPOSE}" down -v
log "Smoke complete."
