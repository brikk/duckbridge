# FE overlay image for the doris-duckbridge live/probe compose.
#
# Overlays our locally-built, PATCHED branch-catalog-spi FE (output/fe — built by
# `tools/doris-baseline.sh --build-fe`, carrying the SPI_READY_TYPES=duckbridge patch,
# see ../../../../doris-patches/) onto a stock released Doris FE image, so the compose
# cluster runs our connector-SPI FE while inheriting the base image's init scripts /
# JDK / entrypoint. Mirrors the doris-ducklake fe-overlay pattern.
#
# The compose file must NOT reference the ~/.cache/duckbridge/doris build tree directly:
# bake-images.sh stages a minimal build context (copies output/fe out of the cache) and
# feeds it here, so the multi-GB repo is never sent to the daemon.
#
# Bake (done by ../../bake-images.sh; shown here for reference):
#   S=/tmp/duckbridge-feimg; rm -rf "$S"; mkdir -p "$S/output"
#   cp -r "${DORIS_OUTPUT:-$HOME/.cache/duckbridge/doris/output}/fe" "$S/output/fe"
#   docker build \
#     -f doris-duckbridge/compose/docker/doris-fe/Dockerfile \
#     -t doris-fe:duckbridge-local \
#     --build-arg BASE_IMAGE=apache/doris:fe-4.1.0 \
#     --build-arg OUTPUT_PATH=./output "$S"
#
# Rebuild only when the FE itself changes; plugin-only changes are reinstalled into the
# FE plugin volume by smoke.sh (no image rebuild).

ARG BASE_IMAGE=apache/doris:fe-4.1.0
FROM ${BASE_IMAGE}

ARG OUTPUT_PATH=./output

# DORIS_HOME/fe in the base image; init_fe.sh (in /opt/apache-doris) + the JDK come from
# the base. Wipe the base build artifacts we replace, then overlay ours.
ENV DORIS_FE_HOME=/opt/apache-doris/fe

RUN rm -rf ${DORIS_FE_HOME}/bin ${DORIS_FE_HOME}/lib ${DORIS_FE_HOME}/conf \
           ${DORIS_FE_HOME}/plugins ${DORIS_FE_HOME}/webroot

COPY ${OUTPUT_PATH}/fe/bin     ${DORIS_FE_HOME}/bin
COPY ${OUTPUT_PATH}/fe/lib     ${DORIS_FE_HOME}/lib
COPY ${OUTPUT_PATH}/fe/conf    ${DORIS_FE_HOME}/conf
COPY ${OUTPUT_PATH}/fe/plugins ${DORIS_FE_HOME}/plugins
COPY ${OUTPUT_PATH}/fe/webroot ${DORIS_FE_HOME}/webroot

# The duckbridge connector plugin is NOT baked in — smoke.sh mounts it into
# ${DORIS_FE_HOME}/plugins/connector/duckbridge/ from the gradle-built plugin zip, so a
# plugin change needs no image rebuild. (See ../../docker-compose.yml + ../../smoke.sh.)
