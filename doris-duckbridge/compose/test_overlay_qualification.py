import unittest

from overlay_qualification import ROWS, expected_overlay, hex_value


class OverlayQualificationTests(unittest.TestCase):
    def test_explicit_boundaries_not_duckdb_append_semantics(self):
        for start in (-2, 0, 5, 2147483647):
            self.assertEqual(expected_overlay("abcd", start, 1, "X"), "abcd")
        self.assertEqual(expected_overlay("", 1, 0, "X"), "")
        self.assertEqual(expected_overlay("abcd", 2, 0, "X"), "aXbcd")
        self.assertEqual(expected_overlay("abcd", 2, -1, "X"), "aX")
        self.assertEqual(expected_overlay("abcd", 2, 2147483646, "X"), "aX")

    def test_nulls_propagate_before_out_of_bounds_identity(self):
        self.assertIsNone(expected_overlay("", 999, 1, None))
        self.assertIsNone(expected_overlay(None, 1, 1, "X"))
        self.assertIsNone(expected_overlay("abcd", None, 1, "X"))
        self.assertIsNone(expected_overlay("abcd", 1, None, "X"))
        self.assertNotEqual(hex_value(None), hex_value("NULL"))
        self.assertNotEqual(hex_value(None), hex_value(""))

    def test_unicode_is_codepoints_not_bytes_or_graphemes(self):
        self.assertEqual(expected_overlay("a😀bc", 2, 1, "界"), "a界bc")
        self.assertEqual(expected_overlay("éxy", 2, 1, "X"), "eXxy")
        self.assertEqual(expected_overlay("👨‍👩‍👧z", 2, 1, "q"), "👨q👩‍👧z")
        self.assertEqual(expected_overlay("ab'cd", 2, 1, "x'y"), "ax'y'cd")
        self.assertEqual(len({row[0] for row in ROWS}), len(ROWS))


if __name__ == "__main__":
    unittest.main()
