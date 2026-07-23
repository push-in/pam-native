from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare


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

        self.assertEqual(values[("virtualizedListScroll", "frameCount")], 16)
        self.assertEqual(values[("virtualizedListScroll", "frameOverrunMs")], 0.835)

    def test_renders_only_shared_scenario_metrics(self) -> None:
        pam = {
            ("coldStartup", "timeToFullDisplayMs"): 400.0,
            ("propertyPatches", "frameOverrunMs"): -3.0,
            ("pamOnly", "frameCount"): 1.0,
        }
        react = {
            ("coldStartup", "timeToFullDisplayMs"): 800.0,
            ("propertyPatches", "frameOverrunMs"): -1.0,
            ("reactOnly", "frameCount"): 1.0,
        }

        report = compare.render(pam, react)

        self.assertIn("2.00×", report)
        self.assertIn("-3.000 | -1.000 | —", report)
        self.assertNotIn("pamOnly", report)
        self.assertNotIn("reactOnly", report)


if __name__ == "__main__":
    unittest.main()
