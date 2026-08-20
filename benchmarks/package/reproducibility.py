#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import tempfile
from enum import IntEnum
from pathlib import Path


MAX_DOCUMENT_BYTES = 1_048_576
MAX_ARTIFACT_BYTES = 536_870_912
CHUNK_BYTES = 1024 * 1024


class ArtifactCode(IntEnum):
    IOS_SOURCE_ARCHIVE = 1
    ANDROID_RENDERER_ARCHIVE = 2
    ANDROID_PLUGIN_API = 3
    PHP_SDK_ARCHIVE = 4


class ResultCode(IntEnum):
    PASSED = 1
    MISMATCHED = 2


def parse_code(value: str) -> ArtifactCode:
    try:
        return ArtifactCode(int(value))
    except (TypeError, ValueError) as error:
        raise argparse.ArgumentTypeError("artifact code must be an integer from 1 through 4") from error


def parse_pair(value: str) -> tuple[ArtifactCode, Path, Path]:
    fields = value.split("=", 2)
    if len(fields) != 3 or not fields[1] or not fields[2]:
        raise argparse.ArgumentTypeError("pair must use CODE=PRIMARY=REBUILD")
    return parse_code(fields[0]), Path(fields[1]), Path(fields[2])


def parse_artifact(value: str) -> tuple[ArtifactCode, Path]:
    fields = value.split("=", 1)
    if len(fields) != 2 or not fields[1]:
        raise argparse.ArgumentTypeError("artifact must use CODE=PATH")
    return parse_code(fields[0]), Path(fields[1])


def open_regular(
    path: Path, label: str, maximum_bytes: int = MAX_ARTIFACT_BYTES
) -> tuple[int, int]:
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    except OSError as error:
        raise ValueError(f"{label} must be a non-empty regular file") from error
    metadata = os.fstat(descriptor)
    if not stat.S_ISREG(metadata.st_mode) or not 1 <= metadata.st_size <= maximum_bytes:
        os.close(descriptor)
        raise ValueError(
            f"{label} must be a non-empty regular file no larger than {maximum_bytes} bytes"
        )
    return descriptor, metadata.st_size


def digest(path: Path, label: str) -> tuple[int, str]:
    descriptor, size = open_regular(path, label)
    hasher = hashlib.sha256()
    with os.fdopen(descriptor, "rb") as handle:
        while chunk := handle.read(CHUNK_BYTES):
            hasher.update(chunk)
    return size, hasher.hexdigest()


def compare(primary: Path, rebuilt: Path, label: str) -> tuple[int, str, bool]:
    primary_descriptor, primary_size = open_regular(primary, f"{label} primary")
    rebuilt_descriptor, rebuilt_size = open_regular(rebuilt, f"{label} rebuild")
    primary_hash = hashlib.sha256()
    rebuilt_hash = hashlib.sha256()
    with os.fdopen(primary_descriptor, "rb") as primary_handle, os.fdopen(
        rebuilt_descriptor, "rb"
    ) as rebuilt_handle:
        while True:
            primary_chunk = primary_handle.read(CHUNK_BYTES)
            rebuilt_chunk = rebuilt_handle.read(CHUNK_BYTES)
            if not primary_chunk and not rebuilt_chunk:
                break
            primary_hash.update(primary_chunk)
            rebuilt_hash.update(rebuilt_chunk)
    primary_digest = primary_hash.hexdigest()
    return (
        primary_size,
        primary_digest,
        primary_size == rebuilt_size and primary_digest == rebuilt_hash.hexdigest(),
    )


def produce(pairs: list[tuple[ArtifactCode, Path, Path]]) -> dict[str, object]:
    if not pairs:
        raise ValueError("at least one reproducibility pair is required")
    if len({code for code, _, _ in pairs}) != len(pairs):
        raise ValueError("reproducibility artifact codes must not be duplicated")
    artifacts: list[dict[str, object]] = []
    for code, primary, rebuilt in sorted(pairs):
        size, sha256, matched = compare(primary, rebuilt, f"artifact {code.value}")
        artifacts.append(
            {
                "artifactCode": code.value,
                "resultCode": (ResultCode.PASSED if matched else ResultCode.MISMATCHED).value,
                "bytes": size,
                "sha256": sha256,
            }
        )
    passed = all(item["resultCode"] == ResultCode.PASSED for item in artifacts)
    return {
        "schemaVersion": 1,
        "resultCode": (ResultCode.PASSED if passed else ResultCode.MISMATCHED).value,
        "artifacts": artifacts,
    }


def regular_document(path: Path) -> object:
    try:
        descriptor, _ = open_regular(
            path, "reproducibility report", MAX_DOCUMENT_BYTES
        )
    except ValueError as error:
        raise ValueError(
            "reproducibility report must be a non-empty regular non-symlink file within the 1 MiB limit"
        ) from error
    with os.fdopen(descriptor, "rb") as handle:
        try:
            return json.load(handle)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError("reproducibility report is not valid JSON") from error


def validate_report(document: object) -> dict[ArtifactCode, dict[str, object]]:
    if not isinstance(document, dict) or set(document) != {"schemaVersion", "resultCode", "artifacts"}:
        raise ValueError("reproducibility report has an incompatible root shape")
    if document["schemaVersion"] != 1 or type(document["schemaVersion"]) is not int:
        raise ValueError("reproducibility report must use schemaVersion 1")
    if document["resultCode"] != ResultCode.PASSED or type(document["resultCode"]) is not int:
        raise ValueError("reproducibility report must have passed resultCode 1")
    entries = document["artifacts"]
    if not isinstance(entries, list) or not 1 <= len(entries) <= len(ArtifactCode):
        raise ValueError("reproducibility report must contain between one and four artifacts")
    parsed: dict[ArtifactCode, dict[str, object]] = {}
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"artifactCode", "resultCode", "bytes", "sha256"}:
            raise ValueError("reproducibility artifact has an incompatible shape")
        if type(entry["artifactCode"]) is not int or type(entry["resultCode"]) is not int:
            raise ValueError("reproducibility artifact codes must be integers")
        try:
            code = ArtifactCode(entry["artifactCode"])
        except ValueError as error:
            raise ValueError("reproducibility artifactCode is invalid") from error
        if code in parsed or entry["resultCode"] != ResultCode.PASSED:
            raise ValueError("reproducibility artifact codes must be unique and passed")
        if type(entry["bytes"]) is not int or not 1 <= entry["bytes"] <= MAX_ARTIFACT_BYTES:
            raise ValueError("reproducibility artifact bytes are invalid")
        sha256 = entry["sha256"]
        if not isinstance(sha256, str) or len(sha256) != 64 or any(
            character not in "0123456789abcdef" for character in sha256
        ):
            raise ValueError("reproducibility artifact sha256 is invalid")
        parsed[code] = entry
    return parsed


def verify(artifacts: list[tuple[ArtifactCode, Path]], report: Path) -> None:
    if not artifacts or len({code for code, _ in artifacts}) != len(artifacts):
        raise ValueError("verification artifacts must be non-empty and unique")
    recorded = validate_report(regular_document(report))
    if set(recorded) != {code for code, _ in artifacts}:
        raise ValueError("reproducibility report does not describe the supplied artifact set")
    for code, path in artifacts:
        size, sha256 = digest(path, f"artifact {code.value}")
        if recorded[code]["bytes"] != size or recorded[code]["sha256"] != sha256:
            raise ValueError(f"artifact {code.value} does not match reproducibility evidence")


def write_report(path: Path, report: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink() or (path.exists() and not path.is_file()):
        raise ValueError("reproducibility report output must be a regular path")
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            descriptor = -1
            handle.write(json.dumps(report, indent=2) + "\n")
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
    parser = argparse.ArgumentParser(description="Produce or verify PAM Native reproducibility evidence.")
    parser.add_argument("--pair", action="append", type=parse_pair, default=[])
    parser.add_argument("--artifact", action="append", type=parse_artifact, default=[])
    parser.add_argument("--output", type=Path)
    parser.add_argument("--verify-report", type=Path)
    options = parser.parse_args()
    producing = bool(options.pair) and options.output is not None and not options.artifact and options.verify_report is None
    verifying = bool(options.artifact) and options.verify_report is not None and not options.pair and options.output is None
    if producing == verifying:
        raise ValueError("choose exactly one producer or verifier mode")
    if producing:
        report = produce(options.pair)
        write_report(options.output, report)
        print(json.dumps(report, indent=2))
        return 0 if report["resultCode"] == ResultCode.PASSED else 1
    verify(options.artifact, options.verify_report)
    print(json.dumps(regular_document(options.verify_report), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
