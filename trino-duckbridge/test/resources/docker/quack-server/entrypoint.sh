#!/bin/sh
# Stand up a Quack RPC listener on 0.0.0.0:${QUACK_PORT} backed by an in-process
# DuckDB. Clients (the test JVM's quack-jdbc driver) connect via
# jdbc:quack://host:port and run SQL directly server-side.
#
# The trailing `sleep infinity` inside the brace group holds the writer side of
# the pipe open so duckdb (running in batch mode when stdin is a pipe) blocks on
# read forever, keeping the listener alive. Container teardown tears both down.

set -eu

PORT="${QUACK_PORT:-9494}"
TOKEN="${QUACK_TOKEN:-duckbridge-test-token}"

if [ "${DUCKDB_ALLOW_UNSIGNED_EXTENSIONS:-false}" = "true" ]; then
    # Local extension development only. This is a startup setting; a runtime SET is rejected.
    DUCKDB_ARGS="-unsigned"
else
    # Release qualification keeps signature verification enabled.
    DUCKDB_ARGS=""
fi

{
    printf "LOAD quack;\n"
    # allow_other_hostname is required to bind to 0.0.0.0 — Quack refuses
    # non-localhost binds by default. Safe here because the container network
    # namespace makes 0.0.0.0 mean "this container's interfaces only".
    printf "SELECT * FROM quack_serve('quack://0.0.0.0:%s/', token := '%s', allow_other_hostname := true, disable_ssl := true);\n" "$PORT" "$TOKEN"
    exec sleep infinity
} | duckdb $DUCKDB_ARGS
