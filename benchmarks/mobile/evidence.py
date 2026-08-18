from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from enum import IntEnum
from pathlib import Path
from typing import Any


class EvidenceSuite(IntEnum):
    PAM_BASELINE = 1
    FRAMEWORK_COMPARISON = 2


MANIFEST_NAME = "evidence-manifest.json"
INPUT_NAMES = ("contract.json", "budgets.json")


def digest(path: Path) -> dict[str, Any]:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            hasher.update(chunk)
    return {
        "path": path.as_posix(),
        "bytes": path.stat().st_size,
        "sha256": hasher.hexdigest(),
    }


def artifacts(results: Path) -> list[dict[str, Any]]:
    return [
        digest(path).copy() | {"path": path.relative_to(results).as_posix()}
        for path in sorted(results.rglob("*"))
        if path.is_file() and path.name != MANIFEST_NAME
    ]


def inputs(benchmark_root: Path) -> list[dict[str, Any]]:
    return [
        digest(benchmark_root / name).copy() | {"path": name}
        for name in INPUT_NAMES
    ]


def git_value(repository: Path, *arguments: str) -> str:
    process = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        check=True,
        capture_output=True,
        text=True,
    )
    return process.stdout.strip()


def create(results: Path, suite: EvidenceSuite, benchmark_root: Path) -> dict[str, Any]:
    repository = benchmark_root.parents[1]
    manifest = {
        "schema_version": 1,
        "suite_id": int(suite),
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": {
            "commit": git_value(repository, "rev-parse", "HEAD"),
            "dirty": git_value(repository, "status", "--porcelain") != "",
        },
        "inputs": inputs(benchmark_root),
        "artifacts": artifacts(results),
    }
    (results / MANIFEST_NAME).write_text(
        json.dumps(manifest, indent=2) + "\n",
        encoding="utf-8",
    )
    return manifest


def verify(results: Path, suite: EvidenceSuite, benchmark_root: Path) -> None:
    manifest_path = results / MANIFEST_NAME
    if not manifest_path.is_file():
        raise ValueError(f"evidence manifest is missing: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1 or manifest.get("suite_id") != int(suite):
        raise ValueError("evidence manifest schema or suite does not match")
    if manifest.get("inputs") != inputs(benchmark_root):
        raise ValueError("benchmark contract inputs do not match their manifest")
    if manifest.get("artifacts") != artifacts(results):
        raise ValueError("benchmark artifacts do not match their manifest")


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create or verify a PAM Native benchmark evidence manifest.",
    )
    parser.add_argument("results", type=Path)
    parser.add_argument("suite_id", type=int, choices=[suite.value for suite in EvidenceSuite])
    parser.add_argument("--verify", action="store_true")
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    if not arguments.results.is_dir():
        raise ValueError(f"benchmark results directory does not exist: {arguments.results}")
    suite = EvidenceSuite(arguments.suite_id)
    benchmark_root = Path(__file__).resolve().parent
    if arguments.verify:
        verify(arguments.results.resolve(), suite, benchmark_root)
        print("PAM Native benchmark evidence verified.")
    else:
        manifest = create(arguments.results.resolve(), suite, benchmark_root)
        print(json.dumps(manifest, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
