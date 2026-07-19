# DuckDB CLI hosting a Quack RPC listener — the remote DuckDB server the doris-duckbridge
# metadata plane (and, later, the BE scan) talks to via quack-jdbc (jdbc:quack://host:port).
#
# Module-local COPY of the compose fixture (compose/docker/duckdb-quack/) per the repo's
# copy-don't-share posture: tests must not depend on trino-duckbridge or the compose tree at
# runtime. Lean — no parity extension (the P4 probe is pure metadata; parity is P1's output).
#
# Base trixie-slim (GLIBC 2.41): matches the trino fixture. duckdb pinned to the repo-wide
# duckdb version (libs.versions.toml: duckdb = 1.5.4.0) so the server matches the quack-jdbc
# client's dialect.

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
