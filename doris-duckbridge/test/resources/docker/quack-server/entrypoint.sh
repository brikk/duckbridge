#!/bin/sh
# Stand up a Quack RPC listener on 0.0.0.0:${QUACK_PORT} backed by an in-process DuckDB. The
# doris-duckbridge metadata plane (quack-jdbc DatabaseMetaData) connects via
# jdbc:quack://<host>:<port>. Module-local COPY of the compose fixture entrypoint.
#
# The trailing `sleep infinity` inside the brace group holds the writer side of the pipe open
# so duckdb (batch mode when stdin is a pipe) blocks on read forever, keeping the listener
# alive. Container teardown tears both down.

set -eu

PORT="${QUACK_PORT:-9494}"
TOKEN="${QUACK_TOKEN:-duckbridge-doris-token}"

# Optional seed SQL: if a file is mounted/copied to /seed/init.sql it runs before serving, so a
# test can bring the server up on a known schema. Opt-in — default serves an empty in-memory
# DuckDB.
SEED=""
if [ -f /seed/init.sql ]; then
    SEED="$(cat /seed/init.sql)"
fi

{
    printf "LOAD quack;\n"
    if [ -n "$SEED" ]; then
        printf "%s\n" "$SEED"
    fi
    # allow_other_hostname is required to bind to 0.0.0.0 — Quack refuses non-localhost binds by
    # default. Safe here because the container network namespace makes 0.0.0.0 mean "this
    # container's interfaces only".
    printf "SELECT * FROM quack_serve('quack://0.0.0.0:%s/', token := '%s', allow_other_hostname := true);\n" "$PORT" "$TOKEN"
    exec sleep infinity
} | duckdb -unsigned
