from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("pam_sbom", ROOT / "scripts" / "sbom.py")
assert SPEC is not None and SPEC.loader is not None
SBOM = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SBOM)


class SbomTest(unittest.TestCase):
    def test_builds_spdx_with_unique_locked_packages(self) -> None:
        payload = SBOM.build(ROOT, "0.7.0", "a" * 40, 1_700_000_000)
        self.assertEqual(payload["spdxVersion"], "SPDX-2.3")
        self.assertEqual(payload["documentDescribes"], ["SPDXRef-PAM-Native"])
        packages = payload["packages"]
        identities = [(item["name"], item["versionInfo"]) for item in packages]
        self.assertEqual(len(identities), len(set(identities)))
        self.assertIn(("pam-native", "0.7.0"), identities)
        self.assertEqual(payload["creationInfo"]["created"], "2023-11-14T22:13:20Z")

    def test_same_inputs_are_byte_for_byte_reproducible(self) -> None:
        first = SBOM.build(ROOT, "0.7.0", "b" * 40, 1_700_000_000)
        second = SBOM.build(ROOT, "0.7.0", "b" * 40, 1_700_000_000)
        self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main()
