# DuckDB CLI hosting a Quack RPC listener — the remote DuckDB server the doris-duckbridge
# connector's scans run against (jdbc:quack://duckdb-quack:PORT). Adapted from the trino
# module's testcontainers fixture (trino-duckbridge/test/resources/docker/quack-server/).
#
# LEAN by design: the Doris probes (P1–P6) do NOT need the parity extension initially —
# that's the divergence-audit's OUTPUT, not an input. So this image ships stock DuckDB +
# the core `quack` extension only, no trino_parity / doris_parity binary.
#
# Adding extra extensions later (e.g. a future duckdb-doris-parity plugin): either
#   (a) `RUN duckdb -c "INSTALL <ext>;"` here to bake it in, or
#   (b) mount a host dir of .duckdb_extension files into the container and `LOAD` them by
#       absolute path in entrypoint.sh (needs `-unsigned`, see the note there).
#
# Base image trixie-slim (GLIBC 2.41): matches the trino fixture. Pin duckdb to the
# repo-wide duckdb version (libs.versions.toml: duckdb = 1.5.4.0) so the server matches the
# quack-jdbc client's dialect.

FROM debian:trixie-slim

ARG DUCKDB_VERSION=v1.5.4

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl unzip \
 && rm -rf /var/lib/apt/lists/*

# Detect arch via `uname -m` (TARGETARCH is buildkit-only and unreliable under podman /
# vanilla docker build). DuckDB release assets use amd64 / arm64.
RUN set -eu; \
    case "$(uname -m)" in \
        x86_64)  DUCKDB_ARCH=amd64 ;; \
        aarch64) DUCKDB_ARCH=arm64 ;; \
        *) echo "unsupported arch: $(uname -m)" >&2; exit 1 ;; \
    esac; \
    curl -fsSL "https://github.com/duckdb/duckdb/releases/download/${DUCKDB_VERSION}/duckdb_cli-linux-${DUCKDB_ARCH}.zip" -o /tmp/duckdb.zip; \
    unzip /tmp/duckdb.zip -d /usr/local/bin; \
    rm /tmp/duckdb.zip; \
    chmod +x /usr/local/bin/duckdb

# Pre-pull the Quack core extension at build time so container startup pays no network
# round-trip. Quack is a core extension as of DuckDB 1.5.3.
RUN duckdb -c "INSTALL quack;"

COPY entrypoint.sh /usr/local/bin/quack-entrypoint.sh
RUN chmod +x /usr/local/bin/quack-entrypoint.sh

EXPOSE 9494
ENTRYPOINT ["/usr/local/bin/quack-entrypoint.sh"]
