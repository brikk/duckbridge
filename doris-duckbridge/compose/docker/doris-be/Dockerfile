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
# The BE C++ build is multi-hour; rebuild the image only when the BE binary changes.
# NOTE: the BE patch touches ONLY be-java-extensions/jdbc-scanner (Java) — a patch-only
# change can rebuild just that jar and overlay ONLY output/be/lib/java_extensions/ onto an
# existing BE image, skipping the C++ rebuild (see ../../../../doris-patches/PATCHES.md).
# This Dockerfile does the full-dist overlay; a jar-only fast path is a future refinement.

ARG BASE_IMAGE=apache/doris:be-4.1.4
FROM ${BASE_IMAGE}

ARG OUTPUT_PATH=./output

# ⚠ UNCERTAINTY (verify once the BE build finishes — see ../../bake-images.sh and
#   ../../README.md): the stock apache/doris BE image lays the BE dist under
#   /opt/apache-doris/be, mirroring the FE at /opt/apache-doris/fe (this is the layout
#   doris-ducklake's compose relies on for the FE side and the BE storage/log mounts —
#   docker-compose.yml uses /opt/apache-doris/be/storage and /opt/apache-doris/be/log).
#   We overlay output/be onto that. If a `docker inspect`/`docker run ... ls` of the real
#   base image shows a different root (some Doris images have used /opt/apache/doris or a
#   versioned dir), FIX DORIS_BE_HOME here and the storage/log mount paths in
#   docker-compose.yml to match. We deliberately did NOT docker-pull to verify (constraint:
#   no pulls while the BE build runs); this follows the established /opt/apache-doris layout.
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
