import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import reproducibility as subject


class ReproducibilityEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.primary = self.root / "primary.zip"
        self.rebuilt = self.root / "rebuilt.zip"
        self.primary.write_bytes(b"same release bytes")
        self.rebuilt.write_bytes(b"same release bytes")

    def tearDown(self):
        self.temporary.cleanup()

    def test_produces_and_reverifies_exact_bytes(self):
        report = subject.produce([(subject.ArtifactCode.IOS_SOURCE_ARCHIVE, self.primary, self.rebuilt)])
        report_path = self.root / "report.json"
        subject.write_report(report_path, report)

        subject.verify([(subject.ArtifactCode.IOS_SOURCE_ARCHIVE, self.primary)], report_path)
        self.assertEqual(subject.ResultCode.PASSED, report["resultCode"])
        self.assertEqual(1, report["artifacts"][0]["artifactCode"])

    def test_mismatch_is_recorded_and_rejected_as_trusted_evidence(self):
        self.rebuilt.write_bytes(b"different release bytes")
        report = subject.produce([(subject.ArtifactCode.ANDROID_RENDERER_ARCHIVE, self.primary, self.rebuilt)])
        self.assertEqual(subject.ResultCode.MISMATCHED, report["resultCode"])
        with self.assertRaisesRegex(ValueError, "passed resultCode 1"):
            subject.validate_report(report)

    def test_rejects_duplicate_codes_and_tampered_artifact(self):
        pair = (subject.ArtifactCode.PHP_SDK_ARCHIVE, self.primary, self.rebuilt)
        with self.assertRaisesRegex(ValueError, "must not be duplicated"):
            subject.produce([pair, pair])
        report_path = self.root / "report.json"
        subject.write_report(report_path, subject.produce([pair]))
        self.primary.write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "does not match"):
            subject.verify([(subject.ArtifactCode.PHP_SDK_ARCHIVE, self.primary)], report_path)

    def test_rejects_unknown_fields_non_integer_codes_and_wrong_artifact_set(self):
        report = subject.produce([(subject.ArtifactCode.ANDROID_PLUGIN_API, self.primary, self.rebuilt)])
        report["unexpected"] = True
        with self.assertRaisesRegex(ValueError, "root shape"):
            subject.validate_report(report)
        report.pop("unexpected")
        report["artifacts"][0]["artifactCode"] = "3"
        with self.assertRaisesRegex(ValueError, "codes must be integers"):
            subject.validate_report(report)

    def test_refuses_symlink_artifacts_and_oversized_reports(self):
        linked = self.root / "linked.zip"
        linked.symlink_to(self.primary)
        with self.assertRaisesRegex(ValueError, "regular file"):
            subject.digest(linked, "linked")
        report = self.root / "large.json"
        report.write_bytes(b"{" + b" " * subject.MAX_DOCUMENT_BYTES + b"}")
        with self.assertRaisesRegex(ValueError, "1 MiB"):
            subject.regular_document(report)

    def test_schema_codes_are_sequential_and_bounded(self):
        schema = json.loads(Path(__file__).with_name("reproducibility.schema.json").read_text())
        properties = schema["properties"]["artifacts"]["items"]["properties"]
        self.assertEqual(1, properties["artifactCode"]["minimum"])
        self.assertEqual(4, properties["artifactCode"]["maximum"])
        self.assertEqual([1, 2], properties["resultCode"]["enum"])
        self.assertEqual(
            [1, 2], schema["properties"]["resultCode"]["enum"]
        )

    def test_release_workflow_attests_and_reverifies_all_artifacts(self):
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/release.yml").read_text()
        for code in range(1, 5):
            self.assertIn(f'--pair "{code}=', workflow)
            self.assertIn(f'--artifact "{code}=', workflow)
        self.assertEqual(
            3,
            len(re.findall(r'--output "dist/pam-native-[^"]+\.reproducibility\.json"', workflow)),
        )
        self.assertEqual(3, workflow.count("dist/*.reproducibility.json"))
        publish = workflow.split("  publish:\n", 1)[1]
        self.assertEqual(3, publish.count(".reproducibility.json\""))
        self.assertLess(
            publish.index("Reverify downloaded reproducibility evidence"),
            publish.index("softprops/action-gh-release@v3"),
        )
        self.assertLess(
            publish.index("actions/attest-sbom@v4"),
            publish.index("softprops/action-gh-release@v3"),
        )


if __name__ == "__main__":
    unittest.main()
