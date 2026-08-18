from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare
import evidence
import gate


class CompareReportsTest(unittest.TestCase):
    def test_evidence_manifest_rejects_changed_results_and_contracts(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            benchmark_root = root / "benchmarks" / "mobile"
            results = root / "results"
            benchmark_root.mkdir(parents=True)
            results.mkdir()
            (benchmark_root / "contract.json").write_text('{"version":1}\n')
            (benchmark_root / "budgets.json").write_text('{"version":1}\n')
            (results / "startup.json").write_text('{"median":412}\n')

            original_git_value = evidence.git_value
            evidence.git_value = lambda repository, *arguments: (
                "abc123" if arguments[-1] == "HEAD" else ""
            )
            try:
                manifest = evidence.create(
                    results,
                    evidence.EvidenceSuite.PAM_BASELINE,
                    benchmark_root,
                )
            finally:
                evidence.git_value = original_git_value

            self.assertEqual(manifest["schema_version"], 1)
            self.assertEqual(manifest["suite_id"], 1)
            self.assertEqual(len(manifest["inputs"]), 2)
            self.assertEqual(len(manifest["artifacts"]), 1)
            evidence.verify(
                results,
                evidence.EvidenceSuite.PAM_BASELINE,
                benchmark_root,
            )

            (results / "startup.json").write_text('{"median":999}\n')
            with self.assertRaisesRegex(ValueError, "artifacts do not match"):
                evidence.verify(
                    results,
                    evidence.EvidenceSuite.PAM_BASELINE,
                    benchmark_root,
                )

            (results / "startup.json").write_text('{"median":412}\n')
            (benchmark_root / "budgets.json").write_text('{"version":2}\n')
            with self.assertRaisesRegex(ValueError, "contract inputs do not match"):
                evidence.verify(
                    results,
                    evidence.EvidenceSuite.PAM_BASELINE,
                    benchmark_root,
                )

    def test_reads_scalar_summary_and_sampled_p50_metrics(self) -> None:
        values = compare.metrics(
            [
                {
                    "name": "dev.pam.Benchmark.virtualizedListScroll",
                    "metrics": {"frameCount": {"median": 16}},
                    "sampledMetrics": {
                        "frameOverrunMs": {"P50": 0.835, "P95": 23.48},
                    },
                },
            ],
        )

        self.assertEqual(values[("virtualizedListScroll", "frameCount", "median")], 16)
        self.assertEqual(values[("virtualizedListScroll", "frameOverrunMs", "P50")], 0.835)
        self.assertEqual(values[("virtualizedListScroll", "frameOverrunMs", "P95")], 23.48)

    def test_renders_only_shared_scenario_metrics(self) -> None:
        pam = {
            ("coldStartup", "timeToFullDisplayMs", "median"): 400.0,
            ("propertyPatches", "frameOverrunMs", "P95"): -3.0,
            ("pamOnly", "frameCount", "median"): 1.0,
        }
        react = {
            ("coldStartup", "timeToFullDisplayMs", "median"): 800.0,
            ("propertyPatches", "frameOverrunMs", "P95"): -1.0,
            ("reactOnly", "frameCount", "median"): 1.0,
        }

        report = compare.render(pam, react)

        self.assertIn("2.00×", report)
        self.assertIn("`P95` | -3.000 | -1.000 | —", report)
        self.assertNotIn("pamOnly", report)
        self.assertNotIn("reactOnly", report)

    def test_rejects_reports_that_omit_contract_metrics(self) -> None:
        contract = {
            "scenarios": [
                {
                    "id": "coldStartup",
                    "metric": "timeToFullDisplayMs",
                },
                {
                    "id": "propertyPatches",
                    "metrics": ["frameOverrunMs", "memoryRssKb"],
                },
            ],
        }
        pam = {
            ("coldStartup", "timeToFullDisplayMs", "median"): 400.0,
            ("propertyPatches", "frameOverrunMs", "P95"): 1.0,
            ("propertyPatches", "memoryRssKb", "median"): 45_000.0,
        }
        react = {
            ("coldStartup", "timeToFullDisplayMs", "median"): 500.0,
            ("propertyPatches", "frameOverrunMs", "P95"): 2.0,
        }

        with self.assertRaisesRegex(
            ValueError,
            "React Native report is missing contract metrics: propertyPatches.memoryRssKb",
        ):
            compare.validate_contract(pam, react, contract)

    def test_performance_gate_checks_tail_percentiles_and_missing_metrics(self) -> None:
        entries = [
            {
                "name": "dev.pam.Benchmark.virtualizedListScroll",
                "sampledMetrics": {
                    "frameOverrunMs": {"P50": 1.0, "P95": 24.0, "P99": 70.0},
                },
            },
        ]
        values = gate.measurements(entries)
        contract = {
            "budgets": [
                {
                    "scenario": "virtualizedListScroll",
                    "metric": "frameOverrunMs",
                    "statistic": "P95",
                    "maximum": 35.0,
                },
                {
                    "scenario": "virtualizedListScroll",
                    "metric": "frameOverrunMs",
                    "statistic": "P99",
                    "maximum": 60.0,
                },
                {
                    "scenario": "coldStartup",
                    "metric": "timeToFullDisplayMs",
                    "statistic": "median",
                    "maximum": 550.0,
                },
            ],
        }

        failures = gate.evaluate(values, contract)

        self.assertIn(
            "virtualizedListScroll.frameOverrunMs.P99=70.000 exceeds 60.000",
            failures,
        )
        self.assertIn("missing coldStartup.timeToFullDisplayMs.median", failures)


if __name__ == "__main__":
    unittest.main()
