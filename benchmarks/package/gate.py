#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
from enum import IntEnum
from pathlib import Path


MAX_DOCUMENT_BYTES = 1_048_576
MAX_ARTIFACT_BYTES = 536_870_912


class ArtifactCode(IntEnum):
    IOS_SOURCE_ARCHIVE = 1
    ANDROID_RENDERER_ARCHIVE = 2
    ANDROID_PLUGIN_API = 3
    PHP_SDK_ARCHIVE = 4


class ResultCode(IntEnum):
    PASSED = 1
    EXCEEDED = 2


def regular_bytes(path: Path, maximum: int, label: str) -> bytes:
    descriptor = -1
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size <= 0:
            raise ValueError(f"{label} must be a non-empty regular file")
        if metadata.st_size > maximum:
            raise ValueError(f"{label} exceeds the {maximum}-byte inspection limit")
        with os.fdopen(descriptor, "rb") as handle:
            descriptor = -1
            return handle.read()
    except OSError as error:
        raise ValueError(f"{label} must be a non-empty regular file") from error
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def artifact_digest(path: Path, label: str) -> tuple[int, str]:
    descriptor = -1
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size <= 0:
            raise ValueError(f"{label} must be a non-empty regular file")
        if metadata.st_size > MAX_ARTIFACT_BYTES:
            raise ValueError(f"{label} exceeds the {MAX_ARTIFACT_BYTES}-byte inspection limit")
        hasher = hashlib.sha256()
        with os.fdopen(descriptor, "rb") as handle:
            descriptor = -1
            while chunk := handle.read(1024 * 1024):
                hasher.update(chunk)
        return metadata.st_size, hasher.hexdigest()
    except OSError as error:
        raise ValueError(f"{label} must be a non-empty regular file") from error
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def load_budgets(path: Path) -> dict[ArtifactCode, int]:
    document = json.loads(regular_bytes(path, MAX_DOCUMENT_BYTES, "package budget contract"))
    if not isinstance(document, dict) or document.get("schemaVersion") != 1:
        raise ValueError("package budget contract must use schemaVersion 1")
    entries = document.get("budgets")
    if not isinstance(entries, list) or len(entries) != len(ArtifactCode):
        raise ValueError("package budget contract must define every artifact code once")
    budgets: dict[ArtifactCode, int] = {}
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"artifactCode", "maximumBytes"}:
            raise ValueError("package budget entries have an incompatible shape")
        if type(entry["artifactCode"]) is not int:
            raise ValueError("package budget artifactCode is invalid")
        try:
            code = ArtifactCode(entry["artifactCode"])
        except (TypeError, ValueError) as error:
            raise ValueError("package budget artifactCode is invalid") from error
        maximum = entry["maximumBytes"]
        if code in budgets or type(maximum) is not int or not 1 <= maximum <= MAX_ARTIFACT_BYTES:
            raise ValueError("package budget code or maximumBytes is invalid")
        budgets[code] = maximum
    if set(budgets) != set(ArtifactCode):
        raise ValueError("package budget codes must be sequential from 1 through 4")
    return budgets


def parse_artifact(value: str) -> tuple[ArtifactCode, Path]:
    code_value, separator, path = value.partition("=")
    if separator == "" or path == "":
        raise argparse.ArgumentTypeError("artifact must use CODE=PATH")
    try:
        code = ArtifactCode(int(code_value))
    except (TypeError, ValueError) as error:
        raise argparse.ArgumentTypeError("artifact code must be an integer from 1 through 4") from error
    return code, Path(path)


def evaluate(
    artifacts: list[tuple[ArtifactCode, Path]], budgets: dict[ArtifactCode, int]
) -> dict[str, object]:
    if not artifacts:
        raise ValueError("at least one package artifact is required")
    if len({code for code, _ in artifacts}) != len(artifacts):
        raise ValueError("package artifact codes must not be duplicated")
    results: list[dict[str, object]] = []
    for code, path in sorted(artifacts):
        actual, digest = artifact_digest(path, f"artifact {code.value}")
        maximum = budgets[code]
        result = ResultCode.PASSED if actual <= maximum else ResultCode.EXCEEDED
        results.append(
            {
                "artifactCode": code.value,
                "resultCode": result.value,
                "actualBytes": actual,
                "maximumBytes": maximum,
                "sha256": digest,
            }
        )
    passed = all(item["resultCode"] == ResultCode.PASSED for item in results)
    return {
        "schemaVersion": 1,
        "resultCode": (ResultCode.PASSED if passed else ResultCode.EXCEEDED).value,
        "artifacts": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Enforce PAM Native release package size budgets.")
    parser.add_argument(
        "--budgets",
        type=Path,
        default=Path(__file__).with_name("budgets.json"),
    )
    parser.add_argument("--artifact", action="append", type=parse_artifact, default=[])
    options = parser.parse_args()
    report = evaluate(options.artifact, load_budgets(options.budgets))
    print(json.dumps(report, indent=2))
    return 0 if report["resultCode"] == ResultCode.PASSED else 1


if __name__ == "__main__":
    raise SystemExit(main())
