#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import tempfile
import xml.etree.ElementTree as ET
from enum import IntEnum
from pathlib import Path

MAX_SOURCE_BYTES = 67_108_864
MAX_FILES = 256
TARGETS = {
    1: {
        "android": "exposesSemanticTalkBackRoleStateRangeAndImportance",
        "ios": "testVoiceOverExposesSemanticRoleStateValueAndImportance",
    },
    2: {
        "android": "exposesAndDispatchesBoundedTalkBackCustomActions",
        "ios": "testVoiceOverCustomActionDispatchesItsBoundedIdentifier",
    },
}


class EnvironmentCode(IntEnum):
    ANDROID_API_26 = 1
    ANDROID_API_36 = 2
    IOS_SIMULATOR = 3


class PlatformCode(IntEnum):
    ANDROID = 1
    IOS = 2


class ResultCode(IntEnum):
    PASSED = 1
    FAILED = 2


def parse_android(value: str) -> tuple[EnvironmentCode, Path]:
    code, separator, path = value.partition("=")
    if separator == "" or path == "":
        raise argparse.ArgumentTypeError("Android input must use ENVIRONMENT_CODE=PATH")
    try:
        environment = EnvironmentCode(int(code))
    except ValueError as error:
        raise argparse.ArgumentTypeError("Android environment code must be integer 1 or 2") from error
    if environment == EnvironmentCode.IOS_SIMULATOR:
        raise argparse.ArgumentTypeError("Android environment code must be integer 1 or 2")
    return environment, Path(path)


def regular(path: Path, maximum: int, label: str) -> bytes:
    if path.is_symlink():
        raise ValueError(f"{label} must be a regular non-symlink file")
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    except OSError as error:
        raise ValueError(f"{label} must be a regular non-symlink file") from error
    metadata = os.fstat(descriptor)
    if not stat.S_ISREG(metadata.st_mode) or not 1 <= metadata.st_size <= maximum:
        os.close(descriptor)
        raise ValueError(f"{label} must contain between 1 and {maximum} bytes")
    with os.fdopen(descriptor, "rb") as handle:
        return handle.read()


def android_source(path: Path) -> tuple[list[bytes], int, str]:
    if path.is_symlink() or not path.is_dir():
        raise ValueError("Android test report must be a regular directory")
    files = sorted(path.rglob("*.xml"))
    if not 1 <= len(files) <= MAX_FILES:
        raise ValueError("Android report must contain between 1 and 256 XML files")
    payloads: list[bytes] = []
    total = 0
    digest = hashlib.sha256()
    for file in files:
        relative = file.relative_to(path).as_posix()
        data = regular(file, MAX_SOURCE_BYTES, "Android test XML")
        total += len(data)
        if total > MAX_SOURCE_BYTES:
            raise ValueError("Android report exceeds the 64 MiB aggregate limit")
        digest.update(relative.encode("utf-8") + b"\0" + data)
        payloads.append(data)
    return payloads, total, digest.hexdigest()


def android_checks(payloads: list[bytes]) -> list[dict[str, int]]:
    results: list[dict[str, int]] = []
    for code, names in TARGETS.items():
        matches: list[ET.Element] = []
        for payload in payloads:
            try:
                root = ET.fromstring(payload)
            except ET.ParseError as error:
                raise ValueError("Android test report contains invalid XML") from error
            matches.extend(
                case for case in root.iter("testcase")
                if case.attrib.get("name") == names["android"]
            )
        if len(matches) != 1:
            raise ValueError(f"Android report must contain accessibility check {code} exactly once")
        if any(case.find("failure") is not None or case.find("error") is not None or case.find("skipped") is not None for case in matches):
            raise ValueError(f"Android accessibility check {code} did not pass")
        results.append({"checkCode": code, "resultCode": ResultCode.PASSED.value})
    return results


def scalar(value: object) -> str | None:
    if isinstance(value, str):
        return value
    if isinstance(value, dict) and isinstance(value.get("_value"), str):
        return value["_value"]
    return None


def ios_statuses(node: object, target: str) -> list[str]:
    statuses: list[str] = []
    if isinstance(node, dict):
        identity = " ".join(
            value for key in ("name", "identifier", "testIdentifier", "identifierURL")
            if (value := scalar(node.get(key))) is not None
        )
        if target in identity:
            statuses.extend(
                value for key in ("testStatus", "status", "result")
                if (value := scalar(node.get(key))) is not None
            )
        for value in node.values():
            statuses.extend(ios_statuses(value, target))
    elif isinstance(node, list):
        for value in node:
            statuses.extend(ios_statuses(value, target))
    return statuses


def ios_checks(document: object) -> list[dict[str, int]]:
    results: list[dict[str, int]] = []
    passed = {"passed", "success", "succeeded"}
    for code, names in TARGETS.items():
        statuses = ios_statuses(document, names["ios"])
        if not statuses:
            raise ValueError(f"UIKit report is missing accessibility check {code}")
        if any(status.lower() not in passed for status in statuses):
            raise ValueError(f"UIKit accessibility check {code} did not pass")
        results.append({"checkCode": code, "resultCode": ResultCode.PASSED.value})
    return results


def produce(android: list[tuple[EnvironmentCode, Path]], ios: Path, revision: str) -> dict[str, object]:
    if not revision or len(revision) != 40 or any(char not in "0123456789abcdef" for char in revision):
        raise ValueError("revision must be a lowercase 40-character Git SHA")
    if len(android) != 2 or {code for code, _ in android} != {EnvironmentCode.ANDROID_API_26, EnvironmentCode.ANDROID_API_36}:
        raise ValueError("Android evidence must contain API 26 and API 36 exactly once")
    environments: list[dict[str, object]] = []
    for environment, path in sorted(android):
        payloads, size, sha256 = android_source(path)
        environments.append({
            "environmentCode": environment.value,
            "platformCode": PlatformCode.ANDROID.value,
            "resultCode": ResultCode.PASSED.value,
            "sourceBytes": size,
            "sourceSha256": sha256,
            "checks": android_checks(payloads),
        })
    ios_data = regular(ios, MAX_SOURCE_BYTES, "UIKit test JSON")
    try:
        ios_document = json.loads(ios_data)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("UIKit test report is not valid JSON") from error
    environments.append({
        "environmentCode": EnvironmentCode.IOS_SIMULATOR.value,
        "platformCode": PlatformCode.IOS.value,
        "resultCode": ResultCode.PASSED.value,
        "sourceBytes": len(ios_data),
        "sourceSha256": hashlib.sha256(ios_data).hexdigest(),
        "checks": ios_checks(ios_document),
    })
    return {"schemaVersion": 1, "resultCode": 1, "revision": revision, "environments": environments}


def write(path: Path, document: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink() or (path.exists() and not path.is_file()):
        raise ValueError("evidence output must be a regular path")
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            descriptor = -1
            json.dump(document, handle, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def main() -> int:
    parser = argparse.ArgumentParser(description="Produce PAM Native accessibility test evidence")
    parser.add_argument("--android", action="append", type=parse_android, default=[])
    parser.add_argument("--ios", type=Path, required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--verify-report", type=Path)
    options = parser.parse_args()
    if (options.output is None) == (options.verify_report is None):
        raise ValueError("choose exactly one output or verification mode")
    document = produce(options.android, options.ios, options.revision)
    if options.output is not None:
        write(options.output, document)
    else:
        recorded = json.loads(regular(options.verify_report, 1_048_576, "evidence report"))
        if recorded != document:
            raise ValueError("accessibility evidence is stale or does not match its reports")
    print(json.dumps(document, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
