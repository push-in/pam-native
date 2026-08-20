#!/usr/bin/env python3
"""Fail closed when PAM Native protocol identifiers drift between hosts."""

from __future__ import annotations

import argparse
import re
from pathlib import Path
from typing import NamedTuple


ROOT = Path(__file__).resolve().parents[1]


class Entry(NamedTuple):
    name: str
    value: int


def normalized(name: str) -> str:
    return re.sub(r"[^a-z0-9]", "", name.lower())


def entries(pattern: str, source: str, label: str) -> dict[int, Entry]:
    parsed = [Entry(name, int(value)) for name, value in re.findall(pattern, source, re.MULTILINE)]
    if not parsed:
        raise ValueError(f"{label} did not contain protocol entries")
    by_value: dict[int, Entry] = {}
    names: set[str] = set()
    for entry in parsed:
        key = normalized(entry.name)
        if entry.value in by_value:
            raise ValueError(f"{label} duplicates protocol ID {entry.value}")
        if key in names:
            raise ValueError(f"{label} duplicates protocol name {entry.name}")
        by_value[entry.value] = entry
        names.add(key)
    return by_value


def php_entries(source: str, label: str) -> dict[int, Entry]:
    return entries(r"^\s*case\s+(\w+)\s*=\s*(\d+);$", source, label)


def kotlin_entries(source: str, enum_name: str) -> dict[int, Entry]:
    start = source.find(f"enum class {enum_name}")
    if start < 0:
        raise ValueError(f"Kotlin {enum_name} enum is missing")
    end = source.find("\n}", start)
    if end < 0:
        raise ValueError(f"Kotlin {enum_name} enum is unterminated")
    return entries(
        r"^\s*([A-Z][A-Z0-9_]*)\((\d+)\)[,;]$",
        source[start:end],
        f"Kotlin {enum_name}",
    )


def swift_cases(source: str, enum_name: str) -> dict[int, Entry]:
    start = source.find(f"public enum {enum_name}")
    if start < 0:
        raise ValueError(f"Swift {enum_name} enum is missing")
    end = source.find("\n}", start)
    if end < 0:
        raise ValueError(f"Swift {enum_name} enum is unterminated")
    return entries(r"^\s*case\s+(\w+)\s*=\s*(\d+)$", source[start:end], f"Swift {enum_name}")


def swift_properties(source: str) -> dict[int, Entry]:
    start = source.find("public enum PamConstants")
    if start < 0:
        raise ValueError("Swift PamConstants enum is missing")
    end = source.find("\n}", start)
    if end < 0:
        raise ValueError("Swift PamConstants enum is unterminated")
    return entries(
        r"^\s*public static let\s+(\w+)\s*=\s*(\d+)$",
        source[start:end],
        "Swift PamConstants",
    )


def compare(authority: dict[int, Entry], candidate: dict[int, Entry], label: str, *, exact: bool) -> None:
    missing = sorted(set(authority) - set(candidate)) if exact else []
    extra = sorted(set(candidate) - set(authority))
    mismatched = sorted(
        value
        for value in set(authority) & set(candidate)
        if normalized(authority[value].name) != normalized(candidate[value].name)
    )
    problems: list[str] = []
    if missing:
        problems.append(f"missing IDs {missing}")
    if extra:
        problems.append(f"unknown IDs {extra}")
    if mismatched:
        details = [
            f"{value}:{authority[value].name}!={candidate[value].name}"
            for value in mismatched
        ]
        problems.append(f"name mismatches {details}")
    if problems:
        raise ValueError(f"{label} protocol parity failed: {'; '.join(problems)}")


def verify(root: Path = ROOT) -> None:
    php = root / "packages/native/src"
    kotlin_source = (root / "android/app/src/main/java/dev/pam/nativeapp/protocol/PamProtocol.kt").read_text(encoding="utf-8")
    swift_source = (root / "ios/Sources/PamNative/Protocol/PamProtocol.swift").read_text(encoding="utf-8")

    for enum_name in ("NodeKind", "EventKind"):
        authority = php_entries((php / f"{enum_name}.php").read_text(encoding="utf-8"), f"PHP {enum_name}")
        compare(authority, kotlin_entries(kotlin_source, enum_name), f"Kotlin {enum_name}", exact=True)
        swift = swift_cases(swift_source, enum_name)
        if enum_name == "NodeKind" and 14 in swift:
            swift[14] = Entry("Switch", 14)
        compare(authority, swift, f"Swift {enum_name}", exact=True)

    properties = php_entries((php / "PropKey.php").read_text(encoding="utf-8"), "PHP PropKey")
    compare(properties, kotlin_entries(kotlin_source, "PropKey"), "Kotlin PropKey", exact=True)
    compare(properties, swift_properties(swift_source), "Swift PamConstants", exact=False)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    verify(args.root.resolve())
    print("PAM Native protocol parity passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
