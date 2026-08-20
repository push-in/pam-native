import importlib.util
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("protocol_parity", ROOT / "protocol-parity.py")
assert SPEC is not None and SPEC.loader is not None
subject = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(subject)


class ProtocolParityTests(unittest.TestCase):
    def test_repository_protocols_match(self):
        subject.verify()

    def test_kotlin_parser_accepts_final_semicolon(self):
        parsed = subject.kotlin_entries(
            "enum class PropKey(val value: Int) {\n FIRST(1),\n LAST(2);\n}",
            "PropKey",
        )
        self.assertEqual([1, 2], sorted(parsed))

    def test_rejects_missing_unknown_and_renamed_entries(self):
        authority = {1: subject.Entry("FirstValue", 1), 2: subject.Entry("SecondValue", 2)}
        with self.assertRaisesRegex(ValueError, "missing IDs"):
            subject.compare(authority, {1: subject.Entry("FIRST_VALUE", 1)}, "fixture", exact=True)
        with self.assertRaisesRegex(ValueError, "unknown IDs"):
            subject.compare(authority, {
                1: subject.Entry("FIRST_VALUE", 1),
                2: subject.Entry("SECOND_VALUE", 2),
                3: subject.Entry("THIRD_VALUE", 3),
            }, "fixture", exact=True)
        with self.assertRaisesRegex(ValueError, "name mismatches"):
            subject.compare(authority, {
                1: subject.Entry("WRONG_VALUE", 1),
                2: subject.Entry("SECOND_VALUE", 2),
            }, "fixture", exact=True)

    def test_rejects_duplicate_ids_and_names(self):
        with self.assertRaisesRegex(ValueError, "duplicates protocol ID"):
            subject.php_entries("case First = 1;\ncase Second = 1;", "fixture")
        with self.assertRaisesRegex(ValueError, "duplicates protocol name"):
            subject.php_entries("case FirstValue = 1;\ncase first_value = 2;", "fixture")

    def test_numeric_limits_accept_literal_products_and_reject_code(self):
        self.assertEqual(
            16_777_216,
            subject.numeric_expression("16 * 1024 * 1024", "limit"),
        )
        self.assertEqual(1_048_576, subject.numeric_expression("1_048_576", "limit"))
        with self.assertRaisesRegex(ValueError, "literal integer product"):
            subject.numeric_expression("1024 + 1024", "limit")
        with self.assertRaisesRegex(ValueError, "exactly once"):
            subject.constant(
                "const val LIMIT = 1\nconst val LIMIT = 2",
                r"LIMIT = (\d+)",
                "limit",
            )


if __name__ == "__main__":
    unittest.main()
