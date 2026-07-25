#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any

import compare


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fail when an Android Macrobenchmark report exceeds PAM budgets.",
    )
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument(
        "--budgets",
        type=Path,
        default=Path(__file__).with_name("budgets.json"),
    )
    return parser.parse_args()


def statistic(raw: Any, name: str) -> float | None:
    if isinstance(raw, (int, float)):
        value = float(raw)
        return value if math.isfinite(value) else None
    if not isinstance(raw, dict):
        return None
    aliases = {
        "median": ("median", "p50", "P50"),
        "P50": ("P50", "p50", "median"),
        "P95": ("P95", "p95"),
        "P99": ("P99", "p99"),
        "maximum": ("maximum", "max"),
    }
    for key in aliases.get(name, (name,)):
        value = raw.get(key)
        if isinstance(value, (int, float)) and math.isfinite(float(value)):
            return float(value)
    return None


def measurements(entries: list[dict[str, Any]]) -> dict[tuple[str, str, str], float]:
    result: dict[tuple[str, str, str], float] = {}
    for entry in entries:
        scenario = str(entry.get("name", "")).split(".")[-1]
        for group in ("metrics", "sampledMetrics"):
            values = entry.get(group, {})
            if not scenario or not isinstance(values, dict):
                continue
            for metric, raw in values.items():
                for name in ("median", "P50", "P95", "P99", "maximum"):
                    value = statistic(raw, name)
                    if value is not None:
                        result[(scenario, str(metric), name)] = value
    return result


def evaluate(
    values: dict[tuple[str, str, str], float],
    contract: dict[str, Any],
) -> list[str]:
    failures: list[str] = []
    for budget in contract.get("budgets", []):
        scenario = str(budget["scenario"])
        metric = str(budget["metric"])
        name = str(budget.get("statistic", "median"))
        maximum = float(budget["maximum"])
        key = (scenario, metric, name)
        actual = values.get(key)
        if actual is None:
            failures.append(f"missing {scenario}.{metric}.{name}")
        elif actual > maximum:
            failures.append(
                f"{scenario}.{metric}.{name}={actual:.3f} exceeds {maximum:.3f}",
            )
    return failures


def main() -> None:
    options = arguments()
    contract = json.loads(options.budgets.read_text(encoding="utf-8"))
    failures = evaluate(measurements(compare.reports(options.report)), contract)
    if failures:
        raise SystemExit("Performance gate failed:\n- " + "\n- ".join(failures))
    print(f"Performance gate passed ({len(contract.get('budgets', []))} budgets).")


if __name__ == "__main__":
    main()
