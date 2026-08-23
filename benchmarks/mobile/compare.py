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
    parser.add_argument("--flutter", type=Path)
    parser.add_argument("--native", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--contract",
        type=Path,
        default=Path(__file__).with_name("contract.json"),
    )
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


def statistic(value: Any, name: str) -> float | None:
    if isinstance(value, (int, float)) and math.isfinite(float(value)):
        return float(value)
    if not isinstance(value, dict):
        return None
    aliases = {
        "median": ("median", "p50", "P50", "minimum"),
        "P50": ("P50", "p50", "median"),
        "P95": ("P95", "p95"),
        "P99": ("P99", "p99"),
        "maximum": ("maximum", "max"),
    }
    for key in aliases.get(name, (name,)):
        result = value.get(key)
        if isinstance(result, (int, float)) and math.isfinite(float(result)):
            return float(result)
    runs = value.get("runs")
    if name == "median" and isinstance(runs, list):
        numbers = sorted(float(item) for item in runs if isinstance(item, (int, float)))
        if numbers:
            return numbers[len(numbers) // 2]
    return None


def metrics(entries: list[dict[str, Any]]) -> dict[tuple[str, str, str], float]:
    result: dict[tuple[str, str, str], float] = {}
    for entry in entries:
        name = str(entry.get("name", "")).split(".")[-1]
        if not name:
            continue
        for group in ("metrics", "sampledMetrics"):
            values = entry.get(group, {})
            if not isinstance(values, dict):
                continue
            for metric, raw in values.items():
                for summary in ("median", "P50", "P95", "P99", "maximum"):
                    value = statistic(raw, summary)
                    if value is not None:
                        result[(name, str(metric), summary)] = value
    return result


def required_metrics(contract: dict[str, Any]) -> set[tuple[str, str]]:
    required: set[tuple[str, str]] = set()
    for scenario in contract.get("scenarios", []):
        identifier = str(scenario["id"])
        names = scenario.get("metrics")
        if not isinstance(names, list):
            metric = scenario.get("metric")
            names = [metric] if isinstance(metric, str) else []
        required.update((identifier, str(metric)) for metric in names)
    return required


def validate_contract(
    pam: dict[tuple[str, str, str], float],
    react: dict[tuple[str, str, str], float],
    contract: dict[str, Any],
) -> None:
    for label, values in (("Pam Native", pam), ("React Native", react)):
        available = {(scenario, metric) for scenario, metric, _ in values}
        missing = sorted(required_metrics(contract) - available)
        if missing:
            names = ", ".join(f"{scenario}.{metric}" for scenario, metric in missing)
            raise ValueError(f"{label} report is missing contract metrics: {names}")


def render(
    pam: dict[tuple[str, str, str], float],
    react: dict[tuple[str, str, str], float],
) -> str:
    shared = sorted(set(pam) & set(react))
    if not shared:
        raise ValueError("Reports do not contain matching scenario and metric names")
    lines = [
        "# Pam Native vs React Native/Nitro",
        "",
        "Lower is better. Values are medians from release-like Android Macrobenchmarks.",
        "",
        "| Scenario | Metric | Statistic | Pam Native | React Native | RN ÷ PAM |",
        "| --- | --- | --- | ---: | ---: | ---: |",
    ]
    for scenario, metric, summary in shared:
        pam_value = pam[(scenario, metric, summary)]
        react_value = react[(scenario, metric, summary)]
        ratio = react_value / pam_value if pam_value > 0.0 and react_value >= 0.0 else None
        ratio_text = f"{ratio:.2f}×" if ratio is not None else "—"
        lines.append(
            f"| `{scenario}` | `{metric}` | `{summary}` | {pam_value:.3f} | "
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


def render_matrix(
    frameworks: dict[str, dict[tuple[str, str, str], float]],
) -> str:
    """Render only measurements shared by every certified framework."""
    if "PAM Native" not in frameworks or len(frameworks) < 2:
        raise ValueError("A framework matrix requires PAM Native and at least one baseline")
    shared = set.intersection(*(set(values) for values in frameworks.values()))
    if not shared:
        raise ValueError("Framework reports do not share any measurements")
    names = list(frameworks)
    lines = [
        "# PAM Native framework matrix",
        "",
        "Lower is better. Every value must come from the same physical-device contract.",
        "",
        "| Scenario | Metric | Statistic | " + " | ".join(names) + " |",
        "| --- | --- | --- | " + " | ".join("---:" for _ in names) + " |",
    ]
    for scenario, metric, summary in sorted(shared):
        values = [frameworks[name][(scenario, metric, summary)] for name in names]
        lines.append(
            f"| `{scenario}` | `{metric}` | `{summary}` | "
            + " | ".join(f"{value:.3f}" for value in values)
            + " |",
        )
    return "\n".join(lines) + "\n"


def main() -> None:
    options = arguments()
    pam = metrics(reports(options.pam))
    react = metrics(reports(options.react_native))
    contract = json.loads(options.contract.read_text(encoding="utf-8"))
    frameworks = {"PAM Native": pam, "React Native": react}
    for label, path in (("Flutter", options.flutter), ("Platform native", options.native)):
        if path is not None:
            values = metrics(reports(path))
            validate_contract(pam, values, contract)
            frameworks[label] = values
    validate_contract(pam, react, contract)
    output = render(pam, react) if len(frameworks) == 2 else render_matrix(frameworks)
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(output, encoding="utf-8")
    print(options.output)


if __name__ == "__main__":
    main()
