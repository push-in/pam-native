#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compare two Android Macrobenchmark JSON reports.",
    )
    parser.add_argument("--pam", required=True, type=Path)
    parser.add_argument("--react-native", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def reports(path: Path) -> list[dict[str, Any]]:
    files = sorted(path.rglob("*.json")) if path.is_dir() else [path]
    result: list[dict[str, Any]] = []
    for file in files:
        payload = json.loads(file.read_text(encoding="utf-8"))
        candidates = payload.get("benchmarks", []) if isinstance(payload, dict) else []
        result.extend(item for item in candidates if isinstance(item, dict))
    if not result:
        raise ValueError(f"No Macrobenchmark entries found in {path}")
    return result


def median(value: Any) -> float | None:
    if isinstance(value, (int, float)) and math.isfinite(float(value)):
        return float(value)
    if not isinstance(value, dict):
        return None
    for key in ("median", "p50", "P50", "minimum"):
        result = value.get(key)
        if isinstance(result, (int, float)) and math.isfinite(float(result)):
            return float(result)
    runs = value.get("runs")
    if isinstance(runs, list):
        numbers = sorted(float(item) for item in runs if isinstance(item, (int, float)))
        if numbers:
            return numbers[len(numbers) // 2]
    return None


def metrics(entries: list[dict[str, Any]]) -> dict[tuple[str, str], float]:
    result: dict[tuple[str, str], float] = {}
    for entry in entries:
        name = str(entry.get("name", "")).split(".")[-1]
        if not name:
            continue
        for group in ("metrics", "sampledMetrics"):
            values = entry.get(group, {})
            if not isinstance(values, dict):
                continue
            for metric, raw in values.items():
                value = median(raw)
                if value is not None:
                    result[(name, str(metric))] = value
    return result


def render(pam: dict[tuple[str, str], float], react: dict[tuple[str, str], float]) -> str:
    shared = sorted(set(pam) & set(react))
    if not shared:
        raise ValueError("Reports do not contain matching scenario and metric names")
    lines = [
        "# Pam Native vs React Native/Nitro",
        "",
        "Lower is better. Values are medians from release-like Android Macrobenchmarks.",
        "",
        "| Scenario | Metric | Pam Native | React Native/Nitro | RN ÷ PAM |",
        "| --- | --- | ---: | ---: | ---: |",
    ]
    for scenario, metric in shared:
        pam_value = pam[(scenario, metric)]
        react_value = react[(scenario, metric)]
        ratio = react_value / pam_value if pam_value > 0.0 and react_value >= 0.0 else None
        ratio_text = f"{ratio:.2f}×" if ratio is not None else "—"
        lines.append(
            f"| `{scenario}` | `{metric}` | {pam_value:.3f} | "
            f"{react_value:.3f} | {ratio_text} |",
        )
    lines.extend(
        [
            "",
            "The ratio is valid only when both reports follow `contract.json`, use the same",
            "physical device, OS build, thermal state, compilation mode and application payload.",
            "",
        ],
    )
    return "\n".join(lines)


def main() -> None:
    options = arguments()
    output = render(metrics(reports(options.pam)), metrics(reports(options.react_native)))
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(output, encoding="utf-8")
    print(options.output)


if __name__ == "__main__":
    main()
