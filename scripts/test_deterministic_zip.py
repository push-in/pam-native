import hashlib
import importlib.util
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("deterministic_zip", ROOT / "deterministic-zip.py")
assert SPEC is not None and SPEC.loader is not None
SUBJECT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUBJECT)


class DeterministicZipTests(unittest.TestCase):
    def test_same_git_tree_is_byte_reproducible(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            subprocess.run(["git", "init", "-q"], cwd=repository, check=True)
            source = repository / "ios"
            source.mkdir()
            source.joinpath("Example.swift").write_text("public let value = 1\n", encoding="utf-8")
            subprocess.run(["git", "add", "ios"], cwd=repository, check=True)
            subprocess.run(
                ["git", "-c", "user.name=PAM", "-c", "user.email=pam@example.invalid", "commit", "-qm", "fixture"],
                cwd=repository,
                check=True,
            )
            first = repository / "first.zip"
            second = repository / "second.zip"
            previous = Path.cwd()
            try:
                import os
                os.chdir(repository)
                SUBJECT.archive("HEAD:ios", "pam-native-ios-1.0.5", 1_700_000_000, first)
                SUBJECT.archive("HEAD:ios", "pam-native-ios-1.0.5", 1_700_000_000, second)
            finally:
                os.chdir(previous)
            self.assertEqual(hashlib.sha256(first.read_bytes()).digest(), hashlib.sha256(second.read_bytes()).digest())
            with zipfile.ZipFile(first) as archive:
                self.assertEqual(
                    archive.read("pam-native-ios-1.0.5/Example.swift"),
                    b"public let value = 1\n",
                )

    def test_prefix_must_be_confined(self) -> None:
        with self.assertRaisesRegex(ValueError, "confined"):
            SUBJECT.archive("HEAD", "../escape", 1_700_000_000, Path("unused.zip"))


if __name__ == "__main__":
    unittest.main()
