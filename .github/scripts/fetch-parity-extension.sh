#!/usr/bin/env bash
# Fetch the signed `trino_parity` DuckDB extension from the community-extensions CDN and drop it
# into the paths `:trino-duckbridge`'s bundleParityExtension task reads, so the plugin jar ships it
# and PARITY-mode tests / the release artifact exercise the real extension. No local C++ build or
# cross-repo artifact download needed — `INSTALL trino_parity FROM community` is published per
# (DuckDB version x platform), and the same signed binary loads by path with allow_unsigned.
#
# The DuckDB version is derived from gradle/libs.versions.toml (duckdb = "1.5.5.0" -> v1.5.5) so it
# tracks the pinned engine automatically. Bundles linux_amd64 (the CI host) + linux_arm64.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

# duckdb = "1.5.5.0" -> take the first three components -> v1.5.5
jdbc_version="$(sed -n 's/^duckdb = "\([0-9.]*\)".*/\1/p' gradle/libs.versions.toml)"
if [ -z "${jdbc_version}" ]; then
  echo "::error::could not read duckdb version from gradle/libs.versions.toml" >&2
  exit 1
fi
duckdb_version="v$(echo "${jdbc_version}" | cut -d. -f1-3)"
echo "Fetching trino_parity for DuckDB ${duckdb_version} from community-extensions"

cdn="https://community-extensions.duckdb.org/${duckdb_version}"
ext_root="duckdb-trino-parity-extension"

# (community platform tag) -> (bundle path bundleParityExtension reads). The linux_amd64 build lands
# at build/release/... (the CI host = the task's hostPlatform); linux_arm64 at build/linux-arm64/...
fetch() {
  local plat="$1" dest="$2"
  mkdir -p "$(dirname "${dest}")"
  echo "  ${plat} -> ${dest}"
  curl -fsSL "${cdn}/${plat}/trino_parity.duckdb_extension.gz" | gunzip > "${dest}"
}

fetch linux_amd64 "${ext_root}/build/release/extension/trino_parity/trino_parity.duckdb_extension"
fetch linux_arm64 "${ext_root}/build/linux-arm64/release/extension/trino_parity/trino_parity.duckdb_extension"

echo "trino_parity extension fetched."
