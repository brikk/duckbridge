# BE overlay image for the doris-duckbridge live/probe compose.
#
# UNLIKE doris-ducklake (which pairs its branch FE with a STOCK 4.1.x BE because it rides
# the BE's native parquet/iceberg reader), doris-duckbridge rides `JdbcJniScanner`, which
# is ABSENT from stock 4.1.x — so we need a BRANCH-BUILT BE too (plan §Which Doris). This
# overlay replaces the stock BE distribution with our locally-built, patched output/be
# (built by `tools/doris-baseline.sh --build-be`, carrying the BE DuckDbTypeHandler patch,
# see ../../../../doris-patches/be/).
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

ARG BASE_IMAGE=apache/doris:be-4.1.0
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
# init_be.sh (in /opt/apache-doris) and its bundled JDK; only the BE distribution content
# (bin/lib/conf/www + the java_extensions that carry our jdbc-scanner patch) is swapped.
# storage/ and log/ are runtime volumes (see docker-compose.yml) — not touched here.
RUN rm -rf ${DORIS_BE_HOME}/bin ${DORIS_BE_HOME}/lib ${DORIS_BE_HOME}/conf \
           ${DORIS_BE_HOME}/www

# output/be layout produced by build.sh --be (verified from the branch's build.sh install
# step at the pin): bin/ conf/ lib/ (doris_be + java_extensions/ + hadoop_hdfs/) www/
# dict/ tools/. We overlay the ones the runtime needs; dict/ and tools/ are optional
# profiling/dictionary aids — copied if present, harmless if the base already has them.
COPY ${OUTPUT_PATH}/be/bin  ${DORIS_BE_HOME}/bin
COPY ${OUTPUT_PATH}/be/lib  ${DORIS_BE_HOME}/lib
COPY ${OUTPUT_PATH}/be/conf ${DORIS_BE_HOME}/conf
COPY ${OUTPUT_PATH}/be/www  ${DORIS_BE_HOME}/www

# doris_be must stay executable after the COPY (build.sh chmods 550; COPY can normalize
# perms depending on the daemon). Re-assert it so start_be.sh's `-x` check passes.
RUN chmod 550 ${DORIS_BE_HOME}/lib/doris_be || true
