#!/usr/bin/env bash
# Install the signed trino_parity community extension with the stock DuckDB CLI for this runner.
# DuckDB 2 verifies community signatures using both the binary and its generated .info provenance,
# so release inputs are always captured as a pair rather than downloading the binary alone.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

jdbc_version="$(sed -n 's/^duckdb = "\([0-9.]*\)".*/\1/p' gradle/libs.versions.toml)"
if [ -z "${jdbc_version}" ]; then
  echo "::error::could not read duckdb version from gradle/libs.versions.toml" >&2
  exit 1
fi
duckdb_version="$(echo "${jdbc_version}" | cut -d. -f1-3)"

os="$(uname -s)"
arch="$(uname -m)"
case "${os}:${arch}" in
  Linux:x86_64)  asset_platform="linux-amd64"; duckdb_platform="linux_amd64"; connector_platform="linux-amd64"; exe="duckdb" ;;
  Linux:aarch64) asset_platform="linux-arm64"; duckdb_platform="linux_arm64"; connector_platform="linux-arm64"; exe="duckdb" ;;
  Darwin:arm64)  asset_platform="osx-arm64"; duckdb_platform="osx_arm64"; connector_platform="darwin-arm64"; exe="duckdb" ;;
  MINGW*:x86_64|MSYS*:x86_64|CYGWIN*:x86_64)
    asset_platform="windows-amd64"; duckdb_platform="windows_amd64"; connector_platform="windows-amd64"; exe="duckdb.exe" ;;
  *)
    echo "::error::trino_parity 0.5.1 has no DuckDB 2.0 community build for ${os}/${arch}" >&2
    exit 1
    ;;
esac

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

duckdb_bin="${DUCKDB_BIN:-}"
if [ -z "${duckdb_bin}" ]; then
  archive="${tmp}/duckdb.tar.gz"
  url="https://github.com/duckdb/duckdb/releases/download/v${duckdb_version}/duckdb-cli-${asset_platform}.tar.gz"
  echo "Fetching stock DuckDB ${duckdb_version} CLI for ${asset_platform}"
  curl -fsSL "$url" -o "$archive"
  tar -xzf "$archive" -C "$tmp" "$exe"
  duckdb_bin="${tmp}/${exe}"
fi

install_root="${tmp}/extensions"
mkdir -p "$install_root"
sql_install_root="$install_root"
if command -v cygpath >/dev/null 2>&1; then
  sql_install_root="$(cygpath -m "$install_root")"
fi
sql_install_root="${sql_install_root//\'/\'\'}"

echo "Installing signed trino_parity from community with signature verification enabled"
result="$(${duckdb_bin} -csv -noheader -c "
  SET extension_directories=['${sql_install_root}'];
  FORCE INSTALL trino_parity FROM community;
  LOAD trino_parity;
  SELECT CASE WHEN
    (SELECT extension_version FROM duckdb_extensions() WHERE extension_name='trino_parity') = 'v0.5.1'
    AND (SELECT count(*) FROM trino_meta()) = 10
    AND trino_lower('ABC') = 'abc'
    AND trino_upper('straße') = 'STRAßE'
    AND trino_reverse('abc') = 'cba'
    AND trino_trim(' x ') = 'x'
    AND trino_ltrim(' x ') = 'x '
    AND trino_rtrim(' x ') = ' x'
    AND trino_normalize('cafe' || chr(769)) = 'café'
    AND hex(trino_xxhash64('abc'::BLOB)) = '44BC2CF5AD770999'
    AND hex(trino_sha512('abc'::BLOB)) = 'DDAF35A193617ABACC417349AE20413112E6FA4E89A97EA20A9EEEE64B55D39A2192992A274FC1A836BA3C23A3FEEBBD454D4423643CE80E2A9AC94FA54CA49F'
    AND hex(trino_hmac_sha256('The quick brown fox jumps over the lazy dog'::BLOB, 'key'::BLOB)) = 'F7BC83F430538424B13298E6AA6FB143EF4D59A14946175997479DBC2D1A3CD8'
  THEN 'PASS' ELSE error('trino_parity smoke failed') END;")"
result="${result//$'\r'/}"
if [ "$result" != "PASS" ]; then
  echo "::error::unexpected trino_parity smoke result: ${result}" >&2
  exit 1
fi

installed_dir="${install_root}/v${duckdb_version}/${duckdb_platform}"
binary="${installed_dir}/trino_parity.duckdb_extension"
info="${binary}.info"
if [ ! -f "$binary" ] || [ ! -f "$info" ]; then
  echo "::error::DuckDB did not produce the expected binary + .info pair in ${installed_dir}" >&2
  exit 1
fi

dest="duckdb-trino-parity-extension/build/community/${connector_platform}"
rm -rf "$dest"
mkdir -p "$dest"
cp "$binary" "$info" "$dest/"

echo "Prepared signed trino_parity 0.5.1 for ${connector_platform}:"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$dest/trino_parity.duckdb_extension" "$dest/trino_parity.duckdb_extension.info"
else
  shasum -a 256 "$dest/trino_parity.duckdb_extension" "$dest/trino_parity.duckdb_extension.info"
fi
