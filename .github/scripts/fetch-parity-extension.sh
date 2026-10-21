#!/usr/bin/env bash
# Fetch the `trino_parity` binary matching the root DuckDB pin into the paths
# `:trino-duckbridge`'s bundle task reads. DuckDB 2.0.0 temporarily uses the already-qualified
# unsigned linux-amd64 artifact from parity CI run 39636617710; once community publishes a signed
# 2.0.0 binary, this special case can return to the CDN path used by older pins.
#
# The DuckDB version is derived from gradle/libs.versions.toml.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

# duckdb = "2.0.0.0" -> take the first three components -> v2.0.0
jdbc_version="$(sed -n 's/^duckdb = "\([0-9.]*\)".*/\1/p' gradle/libs.versions.toml)"
if [ -z "${jdbc_version}" ]; then
  echo "::error::could not read duckdb version from gradle/libs.versions.toml" >&2
  exit 1
fi
duckdb_version="v$(echo "${jdbc_version}" | cut -d. -f1-3)"

cdn="https://community-extensions.duckdb.org/${duckdb_version}"
ext_root="duckdb-trino-parity-extension"

if [[ "${duckdb_version}" == "v2.0.0" ]]; then
  echo "Fetching qualified unsigned DuckDB 2.0.0 binary from parity CI run 39636617710"
  # This run currently qualifies linux-amd64 only. Never let an older DuckDB pin's ARM binary
  # survive in the optional Gradle source path and masquerade as a 2.0 release artifact.
  rm -f "${ext_root}/build/linux-arm64/release/extension/trino_parity/trino_parity.duckdb_extension"
  (
    cd "${ext_root}"
    scripts/fetch-from-ci-artifacts.sh \
      --run 39636617710 \
      --duckdb-version 2.0.0 \
      --platform linux-amd64
  )
  source_binary="${ext_root}/build/linux-amd64/release/extension/trino_parity/trino_parity.duckdb_extension"
  host_binary="${ext_root}/build/release/extension/trino_parity/trino_parity.duckdb_extension"
  mkdir -p "$(dirname "${host_binary}")"
  cp "${source_binary}" "${host_binary}"
  echo "trino_parity 2.0.0 CI binary fetched (linux-amd64 only; unsigned)."
  exit 0
fi

echo "Fetching trino_parity for DuckDB ${duckdb_version} from community-extensions"

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
