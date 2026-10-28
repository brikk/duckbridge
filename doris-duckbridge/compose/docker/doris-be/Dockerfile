# BE overlay image for the doris-duckbridge live/probe compose.
#
# doris-duckbridge rides the new JdbcJniScanner / isolated-plugin layout introduced by
# #66729 (BE JVM startup lazy). The jdbc plugin lives in output/be/plugins/jni/jdbc/;
# this Dockerfile overlays the full patched output/be distribution over stock 4.1.4.
#
# NOTE: the old thin-overlay recipe (splice DuckDbTypeHandler into the fat scanner JAR over
# a master-local base) is NO LONGER VALID. #66729 split the shared bootstrap/spi into
# lib/jni/spi/ and moved each scanner into plugins/jni/<name>/. A stale master-local base
# image will crash the BE at JNI init because java-common native ABI has changed.
# Always bake from the exact-pin build output.
#
# The compose file must NOT reference the ~/.cache/duckbridge/doris build tree directly:
# bake-images.sh stages a minimal build context (copies output/be out of the cache) and
# feeds it here.
#
# Bake (done by ../../bake-images.sh; shown here for reference):
#   S=/tmp/duckbridge-beimg; rm -rf "$S"; mkdir -p "$S/output"
#   cp -r "${DORIS_OUTPUT:-$HOME/.cache/duckbridge/doris/output}/be" "$S/output/be"
#   docker build \
#     -f doris-duckbridge/compose/docker/doris-be/Dockerfile \
#     -t doris-be:duckbridge-local \
#     --build-arg BASE_IMAGE=apache/doris:be-4.1.0 \
#     --build-arg OUTPUT_PATH=./output "$S"
#
# The BE C++ build is multi-hour. A Java-only delta can reuse a qualified exact-pin native
# binary, but the complete matching Java shared layer and JNI plugin tree must be rebuilt
# and packaged together. Never splice an older scanner or Java tree into this distribution.

ARG BASE_IMAGE=apache/doris:be-4.1.4
FROM ${BASE_IMAGE}

ARG OUTPUT_PATH=./output

# The qualified stock base's distribution root; storage/log mounts use the same path.
ENV DORIS_BE_HOME=/opt/apache-doris/be

# Replace the base build artifacts we overlay. We keep the base image's entrypoint /
# init_be.sh (in /opt/apache-doris) and its bundled JDK; all BE distribution content is
# swapped. storage/ and log/ are runtime volumes (docker-compose.yml) — not touched here.
RUN rm -rf ${DORIS_BE_HOME}/bin ${DORIS_BE_HOME}/lib ${DORIS_BE_HOME}/conf \
           ${DORIS_BE_HOME}/www ${DORIS_BE_HOME}/plugins

# output/be layout at pin 96d0ac68 (#66729 isolated-plugin layout):
#   bin/ conf/ lib/ (doris_be + jni/spi/ bootstrap jars + hadoop_hdfs/) www/
#   plugins/jni/{jdbc,hudi,iceberg,...}/   ← jdbc carries DuckDbTypeHandler
COPY ${OUTPUT_PATH}/be/bin     ${DORIS_BE_HOME}/bin
COPY ${OUTPUT_PATH}/be/lib     ${DORIS_BE_HOME}/lib
COPY ${OUTPUT_PATH}/be/conf    ${DORIS_BE_HOME}/conf
COPY ${OUTPUT_PATH}/be/www     ${DORIS_BE_HOME}/www
COPY ${OUTPUT_PATH}/be/plugins ${DORIS_BE_HOME}/plugins

# doris_be must stay executable after the COPY (build.sh chmods 550; COPY can normalize
# perms depending on the daemon). Re-assert it so start_be.sh's `-x` check passes.
RUN chmod 550 ${DORIS_BE_HOME}/lib/doris_be || true
