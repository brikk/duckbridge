"""Safety checks for the read-only function-intake comparison labels."""

import unittest
from unittest.mock import patch
import subprocess

from function_audit import CASES, compare, literal, query


class FunctionAuditTests(unittest.TestCase):
    def test_matching_errors_never_establish_parity(self):
        error = {"status": "error", "diagnostic": "function missing"}
        self.assertEqual(compare(error, error), "error-needs-review")

    def test_json_null_and_sql_null_remain_distinct(self):
        sql_null = {"status": "ok", "rows": [["NULL"]]}
        json_null = {"status": "ok", "rows": [["6E756C6C"]]}
        self.assertEqual(compare(sql_null, json_null), "different-result-bytes")
        self.assertEqual(compare(sql_null, sql_null), "matching-fixture-bytes-not-qualified")

    def test_case_surface_and_literal_escaping_are_explicit(self):
        self.assertEqual(len(CASES), 8)
        self.assertEqual(literal(None), "NULL")
        self.assertEqual(literal("a'b"), "'a''b'")

    def test_full_registry_cell_is_not_truncated_at_csv_default_limit(self):
        registry = "x" * 200000
        result = subprocess.CompletedProcess([], 0, stdout=registry + "\n", stderr="")
        with patch("function_audit.subprocess.run", return_value=result):
            self.assertEqual(query("duckdb", "SELECT registry"), {"status": "ok", "rows": [[registry]]})


if __name__ == "__main__":
    unittest.main()
