"""Native Doris/Quack OVERLAY qualification, including deployed QUERY and exact-row gates.

Run after smoke.sh --keep. The fixture and reference model are deliberately independent
of the production renderer. No engine error counts as a matching result.
"""

import argparse
import hashlib
import json
from pathlib import Path

from function_audit import literal, query


ROWS = [
    (1, "abcd", "X"), (2, "a😀bc", "界"), (3, "straße", "é"),
    (4, "éxy", "👨‍👩‍👧"), (5, "", "X"), (6, None, "X"), (7, "abcd", None),
    (8, "abcd", ""), (9, "a", "long replacement"), (10, "ab'cd", "x'y"),
    (11, "abc", "😀"), (12, "👨‍👩‍👧z", "q"), (13, "中文测试", "ß"), (14, "", None),
]
TABLE = "duckbridge_test.sales.overlay_inputs"
VALUES = ",".join(f"({index},{literal(source)},{literal(replacement)})" for index, source, replacement in ROWS)
INPUT = f"WITH input(id,source,replacement) AS (VALUES {VALUES}) "


def expected_overlay(source, start, count, replacement):
    if source is None or start is None or count is None or replacement is None:
        return None
    if start < 1 or start > len(source):
        return source
    suffix = "" if count < 0 else source[start - 1 + count:]
    return source[:start - 1] + replacement + suffix


def hex_value(value):
    return "NULL" if value is None else value.encode().hex().upper()


def checked(engine, sql):
    result = query(engine, sql)
    if result["status"] != "ok":
        raise RuntimeError(f"{engine} failed (not parity): {sql}\n{result}")
    return result["rows"]


def remote_query(sql):
    plan = checked("doris", "EXPLAIN VERBOSE " + sql)
    queries = [cell.split("QUERY:", 1)[1].strip() for row in plan for cell in row if "QUERY:" in cell]
    if len(queries) != 1:
        raise AssertionError(f"Expected one JDBC QUERY, got {plan}")
    return queries[0]


def check_versions():
    baseline = Path(__file__).resolve().parents[2] / "doris-patches/BASELINE"
    pin = next(line.split("=", 1)[1] for line in baseline.read_text().splitlines() if line.startswith("PIN_SHA="))
    expected = "doris-0.0.0-" + pin[:11]
    for statement in ("SHOW FRONTENDS", "SHOW BACKENDS"):
        rows = checked("doris", statement)
        if not rows or any(expected not in row for row in rows):
            raise AssertionError(f"Wrong source in {statement}: {rows}")
    if checked("duckdb", "SELECT version()") != [["v2.0.0"]]:
        raise AssertionError("Expected DuckDB v2.0.0")
    return expected


def check_local_guards():
    cases = [
        "overlay(source,id,1,replacement)", "overlay(source,2,id,replacement)",
        "overlay(source,0,1,replacement)", "overlay(source,2,-1,replacement)",
        "overlay(source,2,2147483647,replacement)",
        "overlay(concat(source,''),2,1,replacement)",
    ]
    results = []
    for call in cases:
        sql = f"SELECT id FROM {TABLE} WHERE {call}='abcd' LIMIT 2"
        emitted = remote_query(sql)
        if " WHERE " in emitted.upper() or " LIMIT " in emitted.upper():
            raise AssertionError(f"Unqualified expression must also suppress remote LIMIT: {emitted}")
        results.append({"sql": sql, "query": emitted})
    return results


def qualify():
    version = check_versions()
    fixture = [[str(index), hex_value(source), hex_value(replacement)] for index, source, replacement in ROWS]
    if checked("doris", f"SELECT id,HEX(source),HEX(replacement) FROM {TABLE} ORDER BY id") != fixture:
        raise AssertionError("Native scanned fixture differs from the reviewed UTF-8/NULL input")
    probes = []
    for start in (-2, 0, 1, 2, 4, 5, 10, 2147483647):
        for count in (-2, 0, 1, 4, 10, 2147483647):
            # Avoid signed-int32 addition overflow in the source implementation.
            if start > 0 and count >= 0 and start - 1 + count > 2147483647:
                continue
            call = f"overlay(source,{start},{count},replacement)"
            host_sql = f"SELECT id,HEX({call}) FROM {TABLE} ORDER BY id"
            expected = [[str(index), hex_value(expected_overlay(source, start, count, replacement))]
                        for index, source, replacement in ROWS]
            native = checked("doris", host_sql)
            if native != expected:
                raise AssertionError(f"Doris differs from the explicit expected boundary model: {host_sql}\n{native}\n{expected}")
            # This must be native BE evaluation above a real scan, not the rewritten remote function.
            native_query = remote_query(host_sql)
            if "overlay(" in native_query.lower() or "case when" in native_query.lower():
                raise AssertionError(f"Native projection unexpectedly pushed: {native_query}")
            raw = checked("duckdb", INPUT + f"SELECT id,HEX(overlay(source,replacement,{start},{count})) FROM input ORDER BY id")
            qualified = start > 0 and count >= 0
            # Every distinct source result is an equality probe, including unchanged empty strings.
            targets = sorted({expected_overlay(source, start, count, replacement) for _, source, replacement in ROWS} - {None})
            predicates = []
            for target in targets:
                predicate = f"{call}={literal(target)}"
                sql = f"SELECT id FROM {TABLE} WHERE {predicate} ORDER BY id"
                ids = [[str(index)] for index, source, replacement in ROWS
                       if expected_overlay(source, start, count, replacement) == target]
                actual = checked("doris", sql)
                emitted = remote_query(sql)
                if actual != ids:
                    raise AssertionError(f"Deployed predicate lost/added rows: {sql}\n{actual}\n{ids}")
                pushed = "case when" in emitted.lower() and "overlay(" in emitted.lower()
                if pushed != qualified:
                    raise AssertionError(f"Wrong pushdown decision ({qualified}): {emitted}")
                if not qualified and " WHERE " in emitted.upper():
                    raise AssertionError(f"Unqualified call leaked a remote filter: {emitted}")
                predicates.append({"target_hex": hex_value(target), "ids": ids, "query": emitted})
            probes.append({"start": start, "count": count, "qualified": qualified,
                           "native": native, "direct_reordering_differs": raw != native,
                           "native_query": native_query, "predicates": predicates})
    nulls = []
    for position, count in (("CAST(NULL AS INT)", "1"), ("2", "CAST(NULL AS INT)")):
        sql = f"SELECT id,HEX(overlay(source,{position},{count},replacement)) FROM {TABLE} ORDER BY id"
        rows = checked("doris", sql)
        if rows != [[str(index), "NULL"] for index, _, _ in ROWS]:
            raise AssertionError(f"Bounds NULL must propagate: {rows}")
        nulls.append({"sql": sql, "rows": rows})
    guards = check_local_guards()
    # Same four-argument function via Doris's INSERT alias, with return/null semantics unchanged.
    alias_sql = f"SELECT id FROM {TABLE} WHERE insert(source,2,1,replacement)='aXcd' ORDER BY id"
    if checked("doris", alias_sql) != [["1"]] or "PLACING" not in remote_query(alias_sql):
        raise AssertionError("Doris INSERT alias must resolve to the same qualified OVERLAY mapping")
    check_versions()
    return {"doris_version": version, "duckdb_version": "v2.0.0", "rows": ROWS,
            "probes": probes, "null_bound_probes": nulls, "local_guards": guards,
            "policy": "Only the bounded, typed source-aware mapping may push; direct reordering is not equivalent."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = qualify()
    report["script_sha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({"position_length_pairs": len(report["probes"]),
                      "exact_row_and_explain_predicates": sum(len(row["predicates"]) for row in report["probes"]),
                      "report": str(args.output)}))


if __name__ == "__main__":
    main()
