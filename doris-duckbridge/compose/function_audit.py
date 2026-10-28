"""Read-only DuckDB-2 function intake against a running DuckBridge smoke cluster.

This records observations, not a pushdown allowlist. Matching a finite fixture set
does not establish matching types, errors or semantics for all inputs.
"""

import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess

# The resolved registry is one JSON cell and exceeds csv's 128-KiB default.
# Keep a bounded parser budget without truncating the function/overload inventory.
csv.field_size_limit(16 * 1024 * 1024)


INPUTS = [
    '{"a":1,"b":null,"arr":[null,2],"nested":{"z":0,"a":1}}',
    '{}', None, '{"a":1,"a":2}', '{"a.b":1}', '[1,null,{"a":1}]', 'invalid',
]
JSON = "CAST(payload AS JSON)"
PATCH = "CAST('{\"a\":9}' AS JSON)"
VALUE = "CAST('9' AS JSON)"
CASES = {
    "json_set": f"json_set({JSON}, '$.a', {VALUE})",
    "json_insert": f"json_insert({JSON}, '$.a', {VALUE})",
    "json_replace": f"json_replace({JSON}, '$.a', {VALUE})",
    "json_remove": f"json_remove({JSON}, '$.a')",
    "json_merge_patch_diff": f"json_merge_patch_diff({JSON}, {PATCH})",
    "json_deep_merge": f"json_deep_merge({JSON}, {PATCH})",
    "json_normalize": f"json_normalize({JSON})",
    "json_strip_nulls": f"json_strip_nulls({JSON})",
}


def literal(value):
    return "NULL" if value is None else "'" + value.replace("'", "''") + "'"


def query(engine, sql):
    if engine == "doris":
        command = ["docker", "exec", "doris-duckbridge-fe", "mysql", "-h127.0.0.1",
                   "-P9030", "-uroot", "--batch", "--raw", "--skip-column-names", "-e", sql]
        delimiter = "\t"
    else:
        command = ["docker", "exec", "doris-duckbridge-quack", "duckdb", "-csv",
                   "-noheader", "-nullvalue", "NULL", "-c", sql]
        delimiter = ","
    result = subprocess.run(command, capture_output=True, text=True, timeout=60)
    if result.returncode:
        return {"status": "error", "diagnostic": (result.stderr + result.stdout).strip()}
    return {"status": "ok", "rows": list(csv.reader(io.StringIO(result.stdout), delimiter=delimiter))}


def compare(left, right):
    if left["status"] != "ok" or right["status"] != "ok":
        # Never equate unrelated errors or call an arbitrary runtime error unsupported.
        return "error-needs-review"
    return "matching-fixture-bytes-not-qualified" if left["rows"] == right["rows"] else "different-result-bytes"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--brikk-sql-root", type=Path)
    args = parser.parse_args()
    rows = ",".join(f"({index},{literal(value)})" for index, value in enumerate(INPUTS, 1))
    oracle_input = f"WITH input(id,payload) AS (VALUES {rows}) "
    host_input = "WITH input AS (SELECT id,payload FROM duckbridge_test.sales.function_inputs) "
    oracle_fixture = query("duckdb", oracle_input + "SELECT id, HEX(payload) FROM input ORDER BY id")
    host_fixture = query("doris", host_input + "SELECT id, HEX(payload) FROM input ORDER BY id")
    if oracle_fixture != host_fixture or oracle_fixture["status"] != "ok":
        raise RuntimeError(f"Fixture mismatch before comparison: {host_fixture!r} vs {oracle_fixture!r}")
    observations = []
    for name, expression in CASES.items():
        for index in range(1, len(INPUTS) + 1):
            suffix = f" FROM input WHERE id={index}"
            host_sql = host_input + f"SELECT HEX(CAST({expression} AS STRING))" + suffix
            oracle_sql = oracle_input + f"SELECT HEX(CAST({expression} AS VARCHAR))" + suffix
            host = query("doris", host_sql)
            oracle = query("duckdb", oracle_sql)
            observations.append({"function": name, "fixture": index, "doris": host, "duckdb": oracle,
                                 "doris_sql": host_sql, "duckdb_sql": oracle_sql,
                                 "observation": compare(host, oracle), "pushdown_qualified": False})
    registry = query("duckdb", """SELECT to_json(list(struct_pack(
        name := function_name, kind := function_type, parameters := parameter_types,
        result := return_type, variadic_type := varargs))) FROM duckdb_functions()
        WHERE function_type <> 'pragma' AND regexp_full_match(function_name, '[A-Za-z_][A-Za-z0-9_]*')""")
    if registry["status"] != "ok":
        raise RuntimeError(f"Cannot inventory the actual DuckDB registry: {registry!r}")
    definitions = sorted(json.loads(registry["rows"][0][0]), key=lambda row: json.dumps(row, sort_keys=True))
    inventory = {"duckdb_definitions": definitions}
    if args.brikk_sql_root:
        catalog = args.brikk_sql_root / "brikk-sql-metadata/src/dev.brikk.house.sql.metadata/GeneratedDuckdbFunctionCatalog.kt"
        source = catalog.read_text()
        old_names = set(re.findall(r'FunctionDef\("([^"\n]+)"', source))
        if not old_names:
            raise RuntimeError(f"No function definitions found in {catalog}")
        new_names = {definition["name"] for definition in definitions}
        inventory.update({
            "brikk_sql_commit": subprocess.check_output(
                ["git", "-C", str(args.brikk_sql_root), "rev-parse", "HEAD"], text=True).strip(),
            "brikk_sql_catalog": str(catalog),
            "brikk_sql_catalog_sha256": hashlib.sha256(source.encode()).hexdigest(),
            "brikk_sql_catalog_header": source.splitlines()[:5],
            "added_names": sorted(new_names - old_names),
            "removed_names": sorted(old_names - new_names),
            "scope": "Name inventory only; overload/type changes require separate review.",
        })
    report = {"duckdb_version": query("duckdb", "SELECT version()"),
              "doris_source": query("doris", "SHOW FRONTENDS"),
              "inventory": inventory, "inputs": INPUTS, "observations": observations,
              "policy": "No new function pushdown is enabled by this audit."}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    counts = {}
    for row in observations:
        counts[row["observation"]] = counts.get(row["observation"], 0) + 1
    print(json.dumps({"comparisons": len(observations), "observations": counts, "report": str(args.output)}))


if __name__ == "__main__":
    main()
