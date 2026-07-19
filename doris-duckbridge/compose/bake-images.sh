#!/usr/bin/env bash
# Deferred image bake for the doris-duckbridge compose.
#
# Stages a MINIMAL build context (copies output/fe + output/be out of the branch build tree)
# and `docker build`s the two overlay images the compose consumes:
#   • doris-fe:duckbridge-local   (FROM apache/doris:fe-4.1.0 + output/fe)
#   • doris-be:duckbridge-local   (FROM apache/doris:be-4.1.0 + output/be)
#
# The compose file NEVER references the ~/.cache/duckbridge/doris build tree directly — this
# script is the only bridge from that tree to the images. Run it AFTER the branch FE+BE
# build finishes (tools/doris-baseline.sh --build-fe --build-be).
#
# Nothing here compiles, so the ≤8-parallelism cap is irrelevant to this script — but note
# it: the heavy work (FE/BE build) is done separately by doris-baseline.sh, which honors the
# cap. This script only copies + docker build.
#
# Usage:
#   ./bake-images.sh              # bake both images
#   ./bake-images.sh --fe         # bake only the FE image
#   ./bake-images.sh --be         # bake only the BE image (jar-only fast path is TODO)
#
# Env:
#   DORIS_OUTPUT   branch build output dir (default: ~/.cache/duckbridge/doris/output)
#   FE_BASE_IMAGE  stock FE base   (default: apache/doris:fe-4.1.0)
#   BE_BASE_IMAGE  stock BE base   (default: apache/doris:be-4.1.0)
#   DOCKER         container CLI    (default: docker; set to `podman` if needed)

set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"

DORIS_OUTPUT="${DORIS_OUTPUT:-${HOME}/.cache/duckbridge/doris/output}"
FE_BASE_IMAGE="${FE_BASE_IMAGE:-apache/doris:fe-4.1.0}"
BE_BASE_IMAGE="${BE_BASE_IMAGE:-apache/doris:be-4.1.0}"
DOCKER="${DOCKER:-docker}"

FE_IMAGE="doris-fe:duckbridge-local"
BE_IMAGE="doris-be:duckbridge-local"

DO_FE=1
DO_BE=1
for arg in "$@"; do
    case "${arg}" in
        --fe) DO_FE=1; DO_BE=0 ;;
        --be) DO_FE=0; DO_BE=1 ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "error: unknown option '${arg}'" >&2; exit 2 ;;
    esac
done

die() { echo "error: $*" >&2; exit 1; }
info() { echo ">> $*"; }

command -v "${DOCKER}" >/dev/null 2>&1 || die "container CLI '${DOCKER}' not found (set DOCKER=podman?)"

# GUARD: refuse to run while the BE build container is still writing the output tree.
# Baking mid-build would COPY a half-written output/be (broken doris_be / missing jars).
if "${DOCKER}" ps --format '{{.Names}}' 2>/dev/null | grep -qx 'doris-be-build'; then
    die "the 'doris-be-build' container is still running — the BE build hasn't finished. \
Baking now would copy a half-written output/be. Wait for it to exit, then re-run."
fi

[ -d "${DORIS_OUTPUT}" ] || die "DORIS_OUTPUT not found: ${DORIS_OUTPUT} (run tools/doris-baseline.sh --build-fe --build-be first)"

# --- FE ---
if [ "${DO_FE}" -eq 1 ]; then
    [ -d "${DORIS_OUTPUT}/fe" ] || die "output/fe not found under ${DORIS_OUTPUT} — build the FE first (tools/doris-baseline.sh --build-fe)"
    S="$(mktemp -d /tmp/duckbridge-feimg.XXXXXX)"
    trap 'rm -rf "${S}"' EXIT
    info "staging FE context in ${S} ..."
    mkdir -p "${S}/output"
    cp -r "${DORIS_OUTPUT}/fe" "${S}/output/fe"
    info "docker build ${FE_IMAGE} (base ${FE_BASE_IMAGE}) ..."
    "${DOCKER}" build \
        -f "${HERE}/docker/doris-fe/Dockerfile" \
        -t "${FE_IMAGE}" \
        --build-arg "BASE_IMAGE=${FE_BASE_IMAGE}" \
        --build-arg "OUTPUT_PATH=./output" \
        "${S}"
    rm -rf "${S}"
    trap - EXIT
    info "baked ${FE_IMAGE}"
fi

# --- BE ---
if [ "${DO_BE}" -eq 1 ]; then
    [ -d "${DORIS_OUTPUT}/be" ] || die "output/be not found under ${DORIS_OUTPUT} — build the BE first (tools/doris-baseline.sh --build-be)"
    # Sanity: the BE binary must be present and non-trivial (a truncated doris_be = a broken
    # or still-in-progress build).
    if [ ! -s "${DORIS_OUTPUT}/be/lib/doris_be" ]; then
        die "output/be/lib/doris_be missing or empty — the BE build looks incomplete. \
Confirm tools/doris-baseline.sh --build-be finished cleanly before baking."
    fi
    S="$(mktemp -d /tmp/duckbridge-beimg.XXXXXX)"
    trap 'rm -rf "${S}"' EXIT
    info "staging BE context in ${S} (this copies the large BE dist) ..."
    mkdir -p "${S}/output"
    cp -r "${DORIS_OUTPUT}/be" "${S}/output/be"
    info "docker build ${BE_IMAGE} (base ${BE_BASE_IMAGE}) ..."
    "${DOCKER}" build \
        -f "${HERE}/docker/doris-be/Dockerfile" \
        -t "${BE_IMAGE}" \
        --build-arg "BASE_IMAGE=${BE_BASE_IMAGE}" \
        --build-arg "OUTPUT_PATH=./output" \
        "${S}"
    rm -rf "${S}"
    trap - EXIT
    info "baked ${BE_IMAGE}"
fi

info "done. Next: cp .env.example .env (set QUACK_TOKEN), then ./smoke.sh"
