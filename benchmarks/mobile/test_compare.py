from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare
import gate


class CompareReportsTest(unittest.TestCase):
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
