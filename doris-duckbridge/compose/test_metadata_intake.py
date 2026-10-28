"""Contract-diff safety regressions; no cluster or published JAR required."""

import unittest
import csv
import json
from pathlib import Path
import tempfile

from metadata_intake import analyze, candidate_reason, diff_catalogs, known_names, verify_generated_corpus


def definition(kind="SCALAR", argument="VARCHAR", result="VARCHAR", arg_name="value"):
    return {"name": "example", "kind": kind,
            "overloads": [{"arg_types": [argument], "return_type": result, "arg_names": [arg_name]}]}


class MetadataIntakeTests(unittest.TestCase):
    def make_capture(self, directory):
        old = definition()
        new = {**definition(), "name": "new_function"}
        for name, rows in {
            "duckdb-1.5.6": [old], "duckdb-2.0.0": [old, new], "duckdb-live-2.0.0": [old, new],
            "doris-4.1.5": [{**new, "name": "NEW_FUNCTION"}], "trino-484": [new],
        }.items():
            (directory / f"{name}.json").write_text(json.dumps(rows))
        types = "name\tlogical_type\tcategory\nvarchar\tVARCHAR\tSTRING\n"
        for name in ("duckdb-1.5.6", "duckdb-2.0.0", "duckdb-live-2.0.0"):
            (directory / f"{name}-types.tsv").write_text(types)
        (directory / "selections.tsv").write_text(
            "dialect\trequested\tselected\texact\nduckdb\tv2.0.0\t2.0.0\ttrue\n"
            "doris\tmaster\t4.1.5\tfalse\ntrino\t483\t484\tfalse\n")
        (directory / "duckdb-live-2.0.0-properties.tsv").write_text(
            "name\tkind\thas_side_effects\tstability\nnew_function\tscalar\tfalse\tCONSISTENT\n")

    def test_exact_live_shape_mismatch_fails_even_when_counts_match(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.make_capture(directory)
            path = directory / "duckdb-live-2.0.0.json"
            rows = json.loads(path.read_text())
            rows[1]["overloads"][0]["return_type"] = "VARIANT"
            path.write_text(json.dumps(rows))
            with self.assertRaisesRegex(ValueError, "does not match"):
                analyze(directory)

    def test_host_overlap_or_purity_does_not_qualify_a_fallback_catalog(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.make_capture(directory)
            candidate = analyze(directory)["candidates"][0]
            self.assertEqual(candidate["host_catalog_overlap"], {"doris": True, "trino": True})
            self.assertEqual(candidate["host_catalog_exact"], {"doris": False, "trino": False})
            self.assertFalse(candidate["pushdown_qualified"])

    def test_generated_corpus_change_requires_review(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            columns = ["source", "case", "input_sql", "duckdb_sql", "status"]
            row = ["trino", "example", "SELECT 1", "SELECT 1", "EMITTED"]
            for name, value in (("generated.tsv", row), ("reviewed.tsv", row)):
                with (directory / name).open("w") as stream:
                    writer = csv.writer(stream, delimiter="\t")
                    writer.writerow(columns)
                    writer.writerow(value)
            self.assertEqual(verify_generated_corpus(directory, directory / "reviewed.tsv"), 1)
            (directory / "generated.tsv").write_text((directory / "generated.tsv").read_text().replace("SELECT 1", "SELECT 2"))
            with self.assertRaisesRegex(ValueError, "differs from"):
                verify_generated_corpus(directory, directory / "reviewed.tsv")

    def test_host_name_lookup_honors_case_and_aliases(self):
        catalog = {("JSONB_EXTRACT", "SCALAR"): {"name": "JSONB_EXTRACT", "aliases": ["json_extract"]}}
        self.assertIn("JSON_EXTRACT", known_names(catalog))
        self.assertIn("JSONB_EXTRACT", known_names(catalog))

    def test_parameter_name_change_is_not_a_type_change(self):
        result = diff_catalogs({("example", "SCALAR"): definition()},
                               {("example", "SCALAR"): definition(arg_name="input")})
        self.assertEqual(len(result["changed"]), 1)
        self.assertFalse(result["changed"][0]["signature_changed"])

    def test_return_type_and_argument_type_changes_are_contract_changes(self):
        for changed in (definition(result="VARIANT"), definition(argument="TUPLE")):
            result = diff_catalogs({("example", "SCALAR"): definition()}, {("example", "SCALAR"): changed})
            self.assertTrue(result["changed"][0]["signature_changed"])

    def test_window_kind_reclassification_is_not_function_removal(self):
        result = diff_catalogs({("example", "AGGREGATE"): definition(kind="AGGREGATE")},
                               {("example", "WINDOW"): definition(kind="WINDOW")})
        self.assertEqual(result["removed_names"], [])
        self.assertEqual(result["added_names"], [])
        self.assertEqual(result["kind_changes"][0]["after"], ["WINDOW"])

    def test_matching_overload_order_is_not_a_change(self):
        before = definition()
        before["overloads"].append({"arg_types": ["BIGINT"], "return_type": "BIGINT"})
        after = {**before, "overloads": list(reversed(before["overloads"]))}
        self.assertEqual(diff_catalogs({("example", "SCALAR"): before}, {("example", "SCALAR"): after})["changed"], [])

    def test_unknown_or_session_purity_is_not_a_safe_candidate(self):
        self.assertEqual(candidate_reason(definition(), {}), "side-effect-session-state-or-unknown-purity")
        facts = {"example": [{"has_side_effects": "false", "stability": "CONSISTENT_WITHIN_QUERY"}]}
        self.assertEqual(candidate_reason(definition(), facts), "side-effect-session-state-or-unknown-purity")
        facts["example"][0]["stability"] = "CONSISTENT"
        self.assertEqual(candidate_reason(definition(), facts), "requires-typed-live-semantic-proof")


if __name__ == "__main__":
    unittest.main()
