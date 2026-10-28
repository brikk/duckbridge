#!/bin/sh
# Stand up a Quack RPC listener on 0.0.0.0:${QUACK_PORT} backed by an in-process DuckDB.
# The doris-duckbridge BE's JdbcJniScanner (and the FE's metadata resolution) connect via
# jdbc:quack://<host>:<port> and run SQL server-side. Adapted from the trino module's
# fixture (trino-duckbridge/test/resources/docker/quack-server/entrypoint.sh).
#
# The trailing `sleep infinity` inside the brace group holds the writer side of the pipe
# open so duckdb (batch mode when stdin is a pipe) blocks on read forever, keeping the
# listener alive. Container teardown tears both down.

set -eu

PORT="${QUACK_PORT:-9494}"
TOKEN="${QUACK_TOKEN:-duckbridge-doris-token}"

# Optional seed SQL: if a file is mounted at /seed/init.sql, run it before serving so
# probes can start against a known schema (tables/functions the divergence audit needs).
# Purely opt-in — the lean default serves an empty in-memory DuckDB.
SEED=""
if [ -f /seed/init.sql ]; then
    SEED="$(cat /seed/init.sql)"
fi

{
    printf "LOAD quack;\n"
    if [ -n "$SEED" ]; then
        printf "%s\n" "$SEED"
    fi
    # allow_other_hostname is required to bind to 0.0.0.0 — Quack refuses non-localhost
    # binds by default. Safe here because the container network namespace makes 0.0.0.0
    # mean "this container's interfaces only".
    # This local development fixture uses a plain-HTTP jdbc:quack:// endpoint, not
    # DuckDB 2's TLS default. Production URL/TLS policy is not changed by this fixture.
    printf "SELECT * FROM quack_serve('quack://0.0.0.0:%s/', token := '%s', allow_other_hostname := true, disable_ssl := true);\n" "$PORT" "$TOKEN"
    exec sleep infinity
# Only the signed core Quack extension is loaded; keep signature verification enabled.
} | duckdb
