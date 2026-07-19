#!/usr/bin/env bash
#
# doris-baseline.sh — manage the Doris baseline the duckbridge FE/BE patches apply against.
#
# Per PLAN-doris-duckbridge.md §Managing the Doris patches (item 3): there is NO Doris submodule
# in this repo (a full clone is multi-GB). Instead this script:
#   1. reads the pin from doris-patches/BASELINE (single source of truth);
#   2. clones/fetches the brikk/doris fork at that pin into a cache dir;
#   3. `git apply --3way --check` each patch, then (with --apply) applies them, failing loud with
#      the offending file+hunk on drift;
#   4. optional, opt-in: --build-fe / --build-be / --install-spi-jars (JDK 17 required).
#
# --install-spi-jars is the CANONICAL BOOTSTRAP for a fresh clone: it `mvn install`s the Doris SPI
# jars (fe-connector-api / fe-connector-spi / fe-thrift, built from OUR pin) into a PROJECT-LOCAL
# maven repo at doris-duckbridge/doris-m2/ (gitignored), which the gradle module resolves from
# INSTEAD of ~/.m2. This isolates us from ~/.m2 and from other projects (doris-ducklake publishes
# the same 1.2-SNAPSHOT coordinates from a different pin — last-build-wins clobbering).
#
# Default mode is --check-only: it proves the patches still apply at the pin and does nothing else.
# Builds are NEVER run by default (the BE C++ build is multi-hour).
#
# Plain bash, no cleverness. shellcheck-clean-ish.

set -euo pipefail

# --- locate repo root + inputs --------------------------------------------------------------

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd -P)"
BASELINE_FILE="${REPO_ROOT}/doris-patches/BASELINE"
PATCH_DIR="${REPO_ROOT}/doris-patches"

# FE first, BE second (FE is a one-liner; BE is the bigger delta).
PATCHES=(
    "${PATCH_DIR}/fe/0001-spi-ready-types-duckbridge.patch"
    "${PATCH_DIR}/be/0001-duckdb-type-handler.patch"
)

# Cache dir: DORIS_SRC overrides; default ~/.cache/duckbridge/doris.
DORIS_SRC="${DORIS_SRC:-${HOME}/.cache/duckbridge/doris}"

# Project-local Doris maven repo the gradle module resolves org.apache.doris from (gitignored).
DORIS_M2="${REPO_ROOT}/doris-duckbridge/doris-m2"

# --- args -----------------------------------------------------------------------------------

MODE_CHECK_ONLY=1   # default
DO_APPLY=0
DO_BUILD_FE=0
DO_BUILD_BE=0
DO_INSTALL_SPI=0

usage() {
    cat <<'USAGE'
Usage: tools/doris-baseline.sh [options]

  (no options)        same as --check-only
  --check-only        clone/fetch the fork at the pin and `git apply --3way --check`
                      each patch. Prove they apply; change nothing. [DEFAULT]
  --apply             apply the patches into the cache checkout (implies the check).
  --build-fe          run Doris `build.sh --fe` (JDK 17 required). Implies --apply.
  --build-be          run Doris `build.sh --be` (JDK 17; multi-hour C++). Implies --apply.
  --install-spi-jars  CANONICAL BOOTSTRAP. `mvn install` the fe-connector-api/-spi/fe-thrift
                      SPI jars (built from OUR pin) into the project-local repo
                      doris-duckbridge/doris-m2/, which gradle resolves from. Implies --apply.
                      JDK 17 + a thrift 0.16.x executable required (see DORIS_THRIFT).
  -h, --help          this help.

Env:
  DORIS_SRC     cache checkout dir (default: ~/.cache/duckbridge/doris)
  JAVA_HOME     must point at a JDK 17 for the build/install steps (Doris FE toolchain).
  MVN           mvn command for --install-spi-jars (default: mvn on PATH).
  DORIS_THRIFT  path to a thrift 0.16.x executable for fe-thrift codegen. If unset, the script
                probes: $PATH `thrift`, then $DORIS_THIRDPARTY/installed/bin/thrift, then the
                cache clone's own thirdparty/installed/bin/thrift. Fails with install
                instructions if none is a usable 0.16.x.
  MAVEN_JOBS    parallel maven jobs for --install-spi-jars (default: min(nproc, 8), capped at 8).

Pin is read from doris-patches/BASELINE. The fork mirror keeps the pinned SHA alive even after
upstream branch-catalog-spi rebases/GCs it — see doris-patches/PATCHES.md.
USAGE
}

for arg in "$@"; do
    case "${arg}" in
        --check-only) MODE_CHECK_ONLY=1 ;;
        --apply)      DO_APPLY=1; MODE_CHECK_ONLY=0 ;;
        --build-fe)   DO_BUILD_FE=1; DO_APPLY=1; MODE_CHECK_ONLY=0 ;;
        --build-be)   DO_BUILD_BE=1; DO_APPLY=1; MODE_CHECK_ONLY=0 ;;
        --install-spi-jars) DO_INSTALL_SPI=1; DO_APPLY=1; MODE_CHECK_ONLY=0 ;;
        -h|--help)    usage; exit 0 ;;
        *) echo "error: unknown option '${arg}'" >&2; usage >&2; exit 2 ;;
    esac
done

# --- helpers --------------------------------------------------------------------------------

die() { echo "error: $*" >&2; exit 1; }
info() { echo ">> $*"; }

# Read KEY=VALUE from BASELINE (value is everything after the first '='; may contain '=', spaces).
baseline_get() {
    local key="$1" line
    line="$(grep -E "^${key}=" "${BASELINE_FILE}" | head -n1)" || true
    [ -n "${line}" ] || die "BASELINE is missing required key '${key}' (${BASELINE_FILE})"
    printf '%s' "${line#*=}"
}

# --- read the pin ---------------------------------------------------------------------------

[ -f "${BASELINE_FILE}" ] || die "BASELINE not found at ${BASELINE_FILE}"

PIN_SHA="$(baseline_get PIN_SHA)"
FORK_URL="$(baseline_get FORK_URL)"
FORK_BRANCH="$(baseline_get FORK_BRANCH)"
PIN_SUBJECT="$(baseline_get PIN_SUBJECT)"

info "pin      : ${PIN_SHA}"
info "subject  : ${PIN_SUBJECT}"
info "fork     : ${FORK_URL} (${FORK_BRANCH})"
info "cache    : ${DORIS_SRC}"

for p in "${PATCHES[@]}"; do
    [ -f "${p}" ] || die "patch not found: ${p}"
done

# --- clone / fetch the fork at the pin ------------------------------------------------------
#
# Strategy: a blobless partial clone (--filter=blob:none) keeps the initial fetch small (trees +
# commits, blobs fetched on demand) instead of pulling multi-GB of history. We fetch the exact
# baseline branch (immutable on the fork), then hard-check out the pinned SHA. If the SHA isn't
# reachable from the branch (shouldn't happen for our own immutable baseline), we fetch it directly.

ensure_checkout() {
    if [ ! -d "${DORIS_SRC}/.git" ]; then
        info "cloning fork (blobless partial clone; branch ${FORK_BRANCH}) ..."
        mkdir -p "$(dirname "${DORIS_SRC}")"
        git clone --filter=blob:none --branch "${FORK_BRANCH}" --single-branch \
            "${FORK_URL}" "${DORIS_SRC}"
    else
        info "fetching ${FORK_BRANCH} into existing cache ..."
        git -C "${DORIS_SRC}" remote set-url origin "${FORK_URL}"
        git -C "${DORIS_SRC}" fetch --filter=blob:none origin \
            "${FORK_BRANCH}:refs/remotes/origin/${FORK_BRANCH}"
    fi

    # Make sure the exact pin object is present, then reset to it (drops any prior patch apply).
    if ! git -C "${DORIS_SRC}" cat-file -e "${PIN_SHA}^{commit}" 2>/dev/null; then
        info "pin SHA not yet local; fetching it directly ..."
        git -C "${DORIS_SRC}" fetch --filter=blob:none origin "${PIN_SHA}" || \
            die "could not fetch pin ${PIN_SHA} from ${FORK_URL}. Upstream may have GC'd it, but the fork should keep it — verify FORK_URL/FORK_BRANCH in BASELINE."
    fi

    info "checking out pin ${PIN_SHA} (clean reset) ..."
    git -C "${DORIS_SRC}" checkout -q --detach "${PIN_SHA}"
    git -C "${DORIS_SRC}" reset -q --hard "${PIN_SHA}"
    git -C "${DORIS_SRC}" clean -qfd

    local head
    head="$(git -C "${DORIS_SRC}" rev-parse HEAD)"
    [ "${head}" = "${PIN_SHA}" ] || die "checkout HEAD ${head} != pin ${PIN_SHA}"
}

# --- patch application ----------------------------------------------------------------------

check_patches() {
    local failed=0 p
    for p in "${PATCHES[@]}"; do
        info "check: $(basename "${p}") ..."
        if git -C "${DORIS_SRC}" apply --3way --check -v "${p}"; then
            info "  OK"
        else
            echo "error: patch does NOT apply cleanly at pin ${PIN_SHA}: ${p}" >&2
            echo "       drift above shows the offending file+hunk — re-diff the patch against" >&2
            echo "       the pin and record the move in doris-patches/PATCHES.md §Re-vendor log." >&2
            failed=1
        fi
    done
    [ "${failed}" -eq 0 ] || die "one or more patches failed --3way --check (see above)"
}

apply_patches() {
    local p
    for p in "${PATCHES[@]}"; do
        info "apply: $(basename "${p}") ..."
        git -C "${DORIS_SRC}" apply --3way "${p}" || \
            die "failed to apply ${p} (unexpected — --check passed). Inspect ${DORIS_SRC}."
    done
    info "patches applied into ${DORIS_SRC}"
}

# --- optional build / install (JDK 17) ------------------------------------------------------

require_jdk17() {
    [ -n "${JAVA_HOME:-}" ] || die "JAVA_HOME must point at a JDK 17 for build/install steps (Doris FE toolchain)"
    local ver
    ver="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -n1 || true)"
    case "${ver}" in
        *\"17.*|*\"17\"*) : ;;
        *) echo "warning: JAVA_HOME java is not obviously 17: ${ver}" >&2 ;;
    esac
}

build_doris() {
    local targets=()
    [ "${DO_BUILD_FE}" -eq 1 ] && targets+=("--fe")
    [ "${DO_BUILD_BE}" -eq 1 ] && targets+=("--be")
    [ "${#targets[@]}" -gt 0 ] || return 0
    require_jdk17
    info "running Doris build.sh ${targets[*]} (JDK 17; this can take a LONG time) ..."
    ( cd "${DORIS_SRC}" && DISABLE_BUILD_UI=ON ./build.sh "${targets[@]}" )
}

# Locate a usable thrift 0.16.x executable for fe-thrift codegen, echoing its path on stdout.
# fe-thrift's thrift-maven-plugin (0.10.0) does NOT download a thrift binary — it requires an
# executable, and Doris pins thrift 0.16.0 (generator java:fullcamel). Probe order:
#   1. $DORIS_THRIFT (explicit override)
#   2. `thrift` on $PATH
#   3. $DORIS_THIRDPARTY/installed/bin/thrift (a doris thirdparty build)
#   4. the cache clone's own thirdparty/installed/bin/thrift (if a thirdparty build ran there)
# Fails loud with install instructions if none is a usable 0.16.x.
detect_thrift() {
    local candidates=() c ver
    [ -n "${DORIS_THRIFT:-}" ] && candidates+=("${DORIS_THRIFT}")
    command -v thrift >/dev/null 2>&1 && candidates+=("$(command -v thrift)")
    [ -n "${DORIS_THIRDPARTY:-}" ] && candidates+=("${DORIS_THIRDPARTY}/installed/bin/thrift")
    candidates+=("${DORIS_SRC}/thirdparty/installed/bin/thrift")

    for c in "${candidates[@]}"; do
        [ -x "${c}" ] || continue
        ver="$("${c}" --version 2>/dev/null || true)"
        case "${ver}" in
            *0.16.*) printf '%s' "${c}"; return 0 ;;
        esac
    done

    cat >&2 <<THRIFT_HELP
error: no usable thrift 0.16.x executable found for fe-thrift codegen.

Doris fe-thrift generates Java from .thrift IDL using thrift 0.16.0 (the thrift-maven-plugin does
NOT download a binary). Provide one of:

  * Set DORIS_THRIFT=/path/to/thrift   (a thrift 0.16.x executable), OR
  * Install thrift 0.16.0 on PATH:
      - macOS:  brew install thrift@0.16   (or build 0.16.0 from source)
      - Linux:  build from http://archive.apache.org/dist/thrift/0.16.0/thrift-0.16.0.tar.gz
                (distro packages are usually a different 0.1x — the codegen output must match
                 thrift 0.16.0; a mismatched compiler can miscompile the generated sources), OR
  * Run the Doris thirdparty build once (heavy) and set DORIS_THIRDPARTY to its root, so
    \${DORIS_THIRDPARTY}/installed/bin/thrift exists.

Probed (in order): \${DORIS_THRIFT:-<unset>}, \$(command -v thrift), \${DORIS_THIRDPARTY:-<unset>}/installed/bin/thrift, ${DORIS_SRC}/thirdparty/installed/bin/thrift
THRIFT_HELP
    return 1
}

install_spi_jars() {
    [ "${DO_INSTALL_SPI}" -eq 1 ] || return 0
    require_jdk17

    local mvn thrift_bin jobs nproc
    mvn="${MVN:-mvn}"
    command -v "${mvn}" >/dev/null 2>&1 || die "maven ('${mvn}') not found — set MVN or install maven"

    thrift_bin="$(detect_thrift)" || exit 1
    info "thrift    : ${thrift_bin} ($("${thrift_bin}" --version 2>/dev/null))"

    # Parallelism: min(nproc, 8), overridable via MAVEN_JOBS (hard-capped at 8 per task constraint).
    nproc="$( (command -v nproc >/dev/null 2>&1 && nproc) || echo 4)"
    jobs="${MAVEN_JOBS:-${nproc}}"
    [ "${jobs}" -gt 8 ] 2>/dev/null && jobs=8
    [ "${jobs}" -ge 1 ] 2>/dev/null || jobs=1

    mkdir -p "${DORIS_M2}"
    info "installing SPI jars into project-local repo: ${DORIS_M2}"
    info "  reactor: fe-connector/fe-connector-spi -am  (pulls fe-connector-api + fe-thrift +"
    info "           fe-extension-spi + fe-filesystem-api transitively), -T ${jobs}"

    # -pl fe-connector/fe-connector-spi -am : also-make builds (and installs) every reactor
    #   dependency, which covers fe-connector-api -> fe-thrift and the spi's fe-extension-spi /
    #   fe-filesystem-api. That single -pl target is the minimal set that yields all three jars
    #   the gradle module needs.
    # -Dmaven.repo.local=${DORIS_M2} : install into OUR project-local repo, NOT ~/.m2. (This is
    #   also the local repo maven reads from, so transitive deps land here too — fine, gradle
    #   sources only org.apache.doris from it.)
    # -Dthrift.executable / -Ddoris.thrift.executable : point fe-thrift codegen at our binary.
    # -P flatten : REQUIRED. The flatten-maven-plugin is in <pluginManagement> only; the `flatten`
    #   profile binds it so installed POMs have their ${revision} / parent references resolved.
    #   Without it the installed POMs keep `<version>${revision}</version>` and gradle can't parse
    #   them ("Could not find org.apache.doris:fe:${revision}").
    # -Dmaven.build.cache.enabled=false : REQUIRED. Doris ships the maven-build-cache extension
    #   (cache in ~/.m2/build-cache). On a repeat build it RESTORES cached module outputs and SKIPS
    #   install:install (and flatten:flatten) — so a fresh doris-m2/ would never receive the doris
    #   jars. Disabling the cache forces install:install to run into our project-local repo every
    #   time, and keeps us independent of the shared ~/.m2 cache. (A truly clean machine has no
    #   cache, but our own repeated runs must be idempotent.)
    ( cd "${DORIS_SRC}/fe" && JAVA_HOME="${JAVA_HOME}" "${mvn}" install \
        -pl fe-connector/fe-connector-spi -am \
        -P flatten \
        -Dmaven.build.cache.enabled=false \
        -Dmaven.repo.local="${DORIS_M2}" \
        -Ddoris.thrift.executable="${thrift_bin}" \
        -Dthrift.executable="${thrift_bin}" \
        -DskipTests -Dmaven.test.skip=true \
        -T "${jobs}" )

    info "SPI jars installed into ${DORIS_M2} (project-local; NOT ~/.m2)"
    info "gradle resolves org.apache.doris:*:1.2-SNAPSHOT from there (see doris-duckbridge/build.gradle.kts)."
}

# --- run ------------------------------------------------------------------------------------

ensure_checkout
check_patches

if [ "${MODE_CHECK_ONLY}" -eq 1 ] && [ "${DO_APPLY}" -eq 0 ]; then
    info "check-only: both patches apply cleanly at the pin. Done."
    exit 0
fi

apply_patches
build_doris
install_spi_jars
info "done."
