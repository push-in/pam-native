from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent
GATE = ROOT / "gate.py"


class PackageBudgetGateTests(unittest.TestCase):
    def run_gate(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(GATE), *arguments],
            text=True,
            capture_output=True,
            check=False,
        )

    def test_accepts_bounded_artifacts_and_emits_integer_codes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            budgets = root / "budgets.json"
            budgets.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "budgets": [
                            {"artifactCode": code, "maximumBytes": 16}
                            for code in range(1, 5)
                        ],
                    }
                ),
                encoding="utf-8",
            )
            artifact = root / "sdk.tar.gz"
            artifact.write_bytes(b"package")
            result = self.run_gate(
                "--budgets",
                str(budgets),
                "--artifact",
                f"4={artifact}",
                "--output",
                str(root / "report.json"),
            )
            persisted = json.loads((root / "report.json").read_text(encoding="utf-8"))
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report["resultCode"], 1)
        self.assertEqual(persisted, report)
        self.assertEqual(report["artifacts"][0]["artifactCode"], 4)
        self.assertEqual(report["artifacts"][0]["resultCode"], 1)
        self.assertEqual(len(report["artifacts"][0]["sha256"]), 64)

    def test_rejects_an_artifact_over_budget(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            budgets = root / "budgets.json"
            budgets.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "budgets": [
                            {"artifactCode": code, "maximumBytes": 4}
                            for code in range(1, 5)
                        ],
                    }
                ),
                encoding="utf-8",
            )
            artifact = root / "renderer.tar.gz"
            artifact.write_bytes(b"too-large")
            result = self.run_gate(
                "--budgets", str(budgets), "--artifact", f"2={artifact}"
            )
        self.assertEqual(result.returncode, 1)
        self.assertEqual(json.loads(result.stdout)["resultCode"], 2)

    def test_rejects_symlinked_artifacts_and_duplicate_codes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "target"
            target.write_bytes(b"artifact")
            linked = root / "linked"
            linked.symlink_to(target)
            symlink = self.run_gate("--artifact", f"1={linked}")
            duplicate = self.run_gate(
                "--artifact", f"1={target}", "--artifact", f"1={target}"
            )
        self.assertNotEqual(symlink.returncode, 0)
        self.assertIn("must be a non-empty regular file", symlink.stderr)
        self.assertNotEqual(duplicate.returncode, 0)
        self.assertIn("must not be duplicated", duplicate.stderr)

    def test_report_output_does_not_follow_a_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "artifact"
            artifact.write_bytes(b"package")
            protected = root / "protected"
            protected.write_text("preserve", encoding="utf-8")
            output = root / "report.json"
            output.symlink_to(protected)
            result = self.run_gate(
                "--artifact", f"4={artifact}", "--output", str(output)
            )
            protected_contents = protected.read_text(encoding="utf-8")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("output must be a regular path", result.stderr)
        self.assertEqual(protected_contents, "preserve")

    def test_release_workflow_gates_all_four_artifact_codes_before_upload(self) -> None:
        workflow = (ROOT.parents[1] / ".github/workflows/release.yml").read_text(
            encoding="utf-8"
        )
        for code in range(1, 5):
            self.assertIn(f'--artifact "{code}=', workflow)
        self.assertEqual(workflow.count("python3 benchmarks/package/gate.py"), 3)
        self.assertEqual(workflow.count("--output \"dist/pam-native-"), 3)
        self.assertEqual(workflow.count("dist/*.package-budget.json"), 3)
        self.assertLess(
            workflow.index("Enforce the iOS package budget"),
            workflow.index("actions/attest-build-provenance@v4"),
        )


if __name__ == "__main__":
    unittest.main()
