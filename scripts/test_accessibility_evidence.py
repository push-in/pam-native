import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("accessibility_evidence", ROOT / "accessibility-evidence.py")
assert SPEC is not None and SPEC.loader is not None
subject = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(subject)


class AccessibilityEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.android26 = self.android_report("api26")
        self.android36 = self.android_report("api36")
        self.ios = self.root / "ios.json"
        self.ios.write_text(json.dumps({"tests": [
            {"name": names["ios"], "testStatus": "Success"}
            for names in subject.TARGETS.values()
        ]}), encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def android_report(self, name: str) -> Path:
        directory = self.root / name
        directory.mkdir()
        cases = "".join(
            f'<testcase classname="PamRendererInstrumentedTest" name="{names["android"]}" />'
            for names in subject.TARGETS.values()
        )
        (directory / "TEST-accessibility.xml").write_text(
            f'<testsuite tests="2">{cases}</testsuite>', encoding="utf-8"
        )
        return directory

    def produce(self):
        return subject.produce([
            (subject.EnvironmentCode.ANDROID_API_26, self.android26),
            (subject.EnvironmentCode.ANDROID_API_36, self.android36),
        ], self.ios, "a" * 40)

    def test_produces_three_environment_integer_evidence(self):
        document = self.produce()
        self.assertEqual(1, document["resultCode"])
        self.assertEqual([1, 2, 3], [item["environmentCode"] for item in document["environments"]])
        self.assertTrue(all([1, 2, 3, 4, 5, 6, 7] == [check["checkCode"] for check in item["checks"]] for item in document["environments"]))

    def test_rejects_missing_failed_and_skipped_android_checks(self):
        report = self.android26 / "TEST-accessibility.xml"
        report.write_text('<testsuite><testcase name="missing" /></testsuite>', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "exactly once"):
            self.produce()
        self.android26 = self.android_report("api26-replacement")
        contents = (self.android26 / "TEST-accessibility.xml").read_text(encoding="utf-8")
        contents = contents.replace(" />", "><skipped /></testcase>", 1)
        (self.android26 / "TEST-accessibility.xml").write_text(contents, encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "did not pass"):
            self.produce()

    def test_rejects_failed_or_missing_uikit_checks(self):
        self.ios.write_text(json.dumps({"tests": [
            {"name": names["ios"], "testStatus": "Failure" if code == 1 else "Success"}
            for code, names in subject.TARGETS.items()
        ]}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "did not pass"):
            self.produce()
        self.ios.write_text("{}", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "missing accessibility check"):
            self.produce()

    def test_rejects_symlink_sources_and_wrong_environment_set(self):
        linked = self.root / "linked.json"
        linked.symlink_to(self.ios)
        with self.assertRaisesRegex(ValueError, "non-symlink"):
            subject.produce([
                (subject.EnvironmentCode.ANDROID_API_26, self.android26),
                (subject.EnvironmentCode.ANDROID_API_36, self.android36),
            ], linked, "a" * 40)
        with self.assertRaisesRegex(ValueError, "API 26 and API 36"):
            subject.produce([
                (subject.EnvironmentCode.ANDROID_API_26, self.android26),
            ], self.ios, "a" * 40)

    def test_rejects_duplicate_android_environment_and_check(self):
        with self.assertRaisesRegex(ValueError, "API 26 and API 36"):
            subject.produce([
                (subject.EnvironmentCode.ANDROID_API_26, self.android26),
                (subject.EnvironmentCode.ANDROID_API_26, self.android26),
                (subject.EnvironmentCode.ANDROID_API_36, self.android36),
            ], self.ios, "a" * 40)
        report = self.android26 / "TEST-duplicate.xml"
        report.write_text(
            '<testsuite><testcase name="exposesSemanticTalkBackRoleStateRangeAndImportance" /></testsuite>',
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "exactly once"):
            self.produce()

    def test_atomic_report_reverifies_exact_inputs(self):
        report = self.root / "evidence.json"
        document = self.produce()
        subject.write(report, document)
        self.assertEqual(document, json.loads(report.read_text(encoding="utf-8")))
        changed = json.loads(self.ios.read_text(encoding="utf-8"))
        changed["extra"] = True
        self.ios.write_text(json.dumps(changed), encoding="utf-8")
        self.assertNotEqual(document, self.produce())


if __name__ == "__main__":
    unittest.main()
