#!/usr/bin/env python3

from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path


def build(root: Path, version: str, commit: str, created_epoch: int) -> dict[str, object]:
    lock_text = (root / "Cargo.lock").read_text(encoding="utf-8")
    locked = []
    for block in lock_text.split("[[package]]")[1:]:
        values = {}
        for key in ("name", "version", "checksum"):
            match = re.search(rf'^\s*{key}\s*=\s*"([^"]+)"', block, re.MULTILINE)
            if match is not None:
                values[key] = match.group(1)
        if "name" in values and "version" in values:
            locked.append(values)
    packages = []
    seen: set[tuple[str, str]] = set()
    for package in sorted(locked, key=lambda item: (item["name"], item["version"])):
        identity = (str(package["name"]), str(package["version"]))
        if identity in seen:
            continue
        seen.add(identity)
        checksum = package.get("checksum")
        entry: dict[str, object] = {
            "SPDXID": "SPDXRef-Package-" + hashlib.sha256("@".join(identity).encode()).hexdigest()[:16],
            "name": identity[0],
            "versionInfo": identity[1],
            "downloadLocation": "NOASSERTION",
            "filesAnalyzed": False,
            "licenseConcluded": "NOASSERTION",
            "licenseDeclared": "NOASSERTION",
            "copyrightText": "NOASSERTION",
        }
        if isinstance(checksum, str):
            entry["checksums"] = [{"algorithm": "SHA256", "checksumValue": checksum}]
        packages.append(entry)
    namespace_hash = hashlib.sha256(f"{version}:{commit}".encode()).hexdigest()
    return {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": f"pam-native-{version}",
        "documentNamespace": f"https://github.com/push-in/pam-native/sbom/{namespace_hash}",
        "creationInfo": {
            "created": datetime.fromtimestamp(created_epoch, timezone.utc).isoformat().replace("+00:00", "Z"),
            "creators": ["Tool: pam-native-sbom/1"],
        },
        "documentDescribes": ["SPDXRef-PAM-Native"],
        "packages": [
            {
                "SPDXID": "SPDXRef-PAM-Native",
                "name": "pam-native",
                "versionInfo": version,
                "downloadLocation": f"https://github.com/push-in/pam-native/tree/{commit}",
                "filesAnalyzed": False,
                "licenseConcluded": "Apache-2.0",
                "licenseDeclared": "Apache-2.0",
                "copyrightText": "NOASSERTION",
            },
            *packages,
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Create the bounded PAM Native SPDX SBOM")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--created-epoch", required=True, type=int)
    parser.add_argument("--output", type=Path, required=True)
    options = parser.parse_args()
    payload = build(options.root.resolve(), options.version, options.commit, options.created_epoch)
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
