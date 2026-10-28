"""Compare published, versioned function/type contracts with a real engine capture.

This is an intake report, never a pushdown allowlist. Host metadata with exact=false
is advisory even if its names/signatures overlap the current target engine.
"""

import argparse
import csv
import hashlib
import json
from pathlib import Path


def load_catalog(path):
    rows = json.loads(path.read_text())
    result = {}
    for row in rows:
        key = (row["name"], row["kind"])
        if key in result:
            raise ValueError(f"Duplicate function identity in {path}: {key}")
        result[key] = row
    return result


def signature(overload):
    return (tuple(overload["arg_types"]), overload["return_type"],
            overload.get("variadic", False), overload.get("positional_count"))


def canonical(row):
    copy = dict(row)
    copy["overloads"] = sorted(copy["overloads"], key=lambda item: json.dumps(item, sort_keys=True))
    return copy


def diff_catalogs(before, after):
    added = sorted(after.keys() - before.keys())
    removed = sorted(before.keys() - after.keys())
    changed = []
    for key in sorted(before.keys() & after.keys()):
        old, new = canonical(before[key]), canonical(after[key])
        if old == new:
            continue
        old_shapes = {signature(row) for row in old["overloads"]}
        new_shapes = {signature(row) for row in new["overloads"]}
        changed.append({"name": key[0], "kind": key[1],
                        "signature_changed": old_shapes != new_shapes,
                        "removed_overloads": [row for row in old["overloads"] if signature(row) not in new_shapes],
                        "added_overloads": [row for row in new["overloads"] if signature(row) not in old_shapes],
                        "before": old, "after": new})
    old_names, new_names = {key[0] for key in before}, {key[0] for key in after}
    kind_changes = [{"name": name,
                     "before": sorted(kind for n, kind in before if n == name),
                     "after": sorted(kind for n, kind in after if n == name)}
                    for name in sorted(old_names & new_names)
                    if {kind for n, kind in before if n == name} != {kind for n, kind in after if n == name}]
    return {"added_identities": added, "removed_identities": removed,
            "added_names": sorted(new_names - old_names), "removed_names": sorted(old_names - new_names),
            "kind_changes": kind_changes, "changed": changed}


def read_tsv(path):
    with path.open() as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def known_names(catalog):
    # Doris/Trino catalogs use case-insensitive lookup and may register a peer via an alias.
    return {name.upper() for row in catalog.values() for name in [row["name"], *row.get("aliases", [])]}


def candidate_reason(definition, properties):
    name = definition["name"]
    if definition["kind"] != "SCALAR":
        return "not-a-scalar-predicate-candidate"
    if name.startswith("__"):
        return "engine-internal"
    if name.startswith(("collate_", "icu_collate_")):
        return "requires-separate-collation-proof"
    facts = properties.get(name, [])
    if not facts or any(row["has_side_effects"] != "false" or row["stability"] != "CONSISTENT" for row in facts):
        return "side-effect-session-state-or-unknown-purity"
    return "requires-typed-live-semantic-proof"


def verify_generated_corpus(metadata_dir, corpus):
    fields = ("source", "case", "input_sql", "duckdb_sql", "status")
    actual_rows = read_tsv(metadata_dir / "generated.tsv")
    actual = {(row["source"], row["case"]): row for row in actual_rows}
    if len(actual) != len(actual_rows):
        raise ValueError("Published generated corpus contains duplicate fixture identities")
    expected = read_tsv(corpus)
    if not expected:
        raise ValueError("Generated-SQL corpus is empty")
    if len({(row["source"], row["case"]) for row in expected}) != len(expected):
        raise ValueError("Reviewed generated corpus contains duplicate fixture identities")
    for row in expected:
        key = (row["source"], row["case"])
        if key not in actual or any(row[field] != actual[key][field] for field in fields):
            raise ValueError(f"Published generator differs from the reviewed module-local corpus for {key}; review, do not regenerate away the difference")
    return len(expected)


def analyze(directory):
    old = load_catalog(directory / "duckdb-1.5.6.json")
    new = load_catalog(directory / "duckdb-2.0.0.json")
    live = load_catalog(directory / "duckdb-live-2.0.0.json")
    differences = diff_catalogs(new, live)
    if differences["added_identities"] or differences["removed_identities"] or differences["changed"]:
        raise ValueError("Published DuckDB-2 catalog does not match the real JDBC capture; inspect contracts before intake")
    old_types = {row["name"]: row for row in read_tsv(directory / "duckdb-1.5.6-types.tsv")}
    new_types = {row["name"]: row for row in read_tsv(directory / "duckdb-2.0.0-types.tsv")}
    live_types = {row["name"]: row for row in read_tsv(directory / "duckdb-live-2.0.0-types.tsv")}
    if new_types != live_types:
        raise ValueError("Published DuckDB-2 types do not match the real JDBC capture")
    selections = {row["dialect"]: row for row in read_tsv(directory / "selections.tsv")}
    if selections["duckdb"]["exact"] != "true" or selections["duckdb"]["selected"] != "2.0.0":
        raise ValueError("DuckDB metadata must be an exact 2.0.0 selection, never a nearest-version fallback")
    properties = {}
    for row in read_tsv(directory / "duckdb-live-2.0.0-properties.tsv"):
        properties.setdefault(row["name"], []).append(row)
    hosts = {dialect: load_catalog(directory / f'{dialect}-{selections[dialect]["selected"]}.json')
             for dialect in ("doris", "trino")}
    host_names = {dialect: known_names(catalog) for dialect, catalog in hosts.items()}
    delta = diff_catalogs(old, new)
    changed_keys = {(row["name"], row["kind"]) for row in delta["changed"] if row["signature_changed"]}
    candidate_keys = set(map(tuple, delta["added_identities"])) | changed_keys
    candidates = []
    for key in sorted(candidate_keys):
        definition = new[key]
        candidates.append({"name": key[0], "kind": key[1], "reason": candidate_reason(definition, properties),
                           "overloads": definition["overloads"],
                           "host_catalog_overlap": {host: key[0].upper() in names for host, names in host_names.items()},
                           "host_catalog_exact": {host: selections[host]["exact"] == "true" for host in hosts},
                           "pushdown_qualified": False})
    inputs = {"duckdb-1.5.6.json", "duckdb-2.0.0.json", "duckdb-live-2.0.0.json", "selections.tsv",
              "duckdb-1.5.6-types.tsv", "duckdb-2.0.0-types.tsv", "duckdb-live-2.0.0-types.tsv",
              "duckdb-live-2.0.0-properties.tsv"}
    inputs.update(f'{host}-{selections[host]["selected"]}.json' for host in hosts)
    inputs.update(name for name in ("artifacts.tsv", "runtime.tsv", "capture.tsv", "generated.tsv", "generated.json")
                  if (directory / name).is_file())
    return {"published_matches_live_functions": True, "published_matches_live_types": True,
            "before": {"version": "1.5.6", "definitions": len(old), "overloads": sum(len(row["overloads"]) for row in old.values())},
            "after": {"version": "2.0.0", "definitions": len(new), "overloads": sum(len(row["overloads"]) for row in new.values())},
            "function_delta": delta,
            "type_delta": {"added": [new_types[key] for key in sorted(new_types.keys() - old_types.keys())],
                           "removed": [old_types[key] for key in sorted(old_types.keys() - new_types.keys())],
                           "changed": [{"before": old_types[key], "after": new_types[key]}
                                       for key in sorted(old_types.keys() & new_types.keys()) if old_types[key] != new_types[key]]},
            "selections": selections, "candidates": candidates,
            "source_hashes": {name: hashlib.sha256((directory / name).read_bytes()).hexdigest() for name in sorted(inputs)},
            "policy": "Metadata is discovery evidence, never semantic qualification or permission to enable pushdown."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--trino-corpus", type=Path)
    args = parser.parse_args()
    if args.output.resolve().is_relative_to(args.metadata_dir.resolve()):
        parser.error("Write the report outside its metadata input directory to avoid self-referential provenance")
    report = analyze(args.metadata_dir)
    if args.trino_corpus:
        report["verified_trino_corpus_rows"] = verify_generated_corpus(args.metadata_dir, args.trino_corpus)
        report["trino_corpus_sha256"] = hashlib.sha256(args.trino_corpus.read_bytes()).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    changes = report["function_delta"]
    print(json.dumps({"live_contracts_match": True, "added_names": len(changes["added_names"]),
                      "removed_names": len(changes["removed_names"]), "kind_changes": len(changes["kind_changes"]),
                      "changed_signatures": sum(row["signature_changed"] for row in changes["changed"]),
                      "report": str(args.output)}))


if __name__ == "__main__":
    main()
