"""End-to-end check of `pam dev` sync against a generated large application.

The test plays the device: it runs `pam-native dev:serve` without adb on a
generated project larger than the former 16 MiB bundle limit, performs the
first sync (manifest + streamed objects), then verifies that edits, vendor
updates and deletions arrive as one-round-trip deltas carrying only the
changed files.

Run with the CLI built (`cargo build -p pam-native-cli`), or point
PAM_NATIVE_CLI at a binary:

    python3 -m unittest scripts/test_hot_reload_large_app.py
"""

from __future__ import annotations

import hashlib
import http.client
import json
import os
import random
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLI = Path(os.environ.get("PAM_NATIVE_CLI", ROOT / "target/debug/pam-native"))
SOURCE_FILES = 2_400
VENDOR_FILES = 600
FILE_BYTES = 12 * 1024


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def generate(root: Path) -> None:
    generator = random.Random(1393)
    (root / "pam-native.json").write_text(json.dumps({
        "version": 1,
        "applicationId": "dev.pam.hotreload.large",
        "name": "Hot reload large app",
        "entry": "index.php",
        "runtime": {"php": "8.5", "channel": "stable"},
        "android": {"minSdk": 26, "targetSdk": 36},
        "modules": [],
        "views": [],
    }))
    (root / "index.php").write_text("<?php\nrequire __DIR__.'/vendor/autoload.php';\n")
    for index in range(SOURCE_FILES):
        path = root / f"src/Screens/Group{index % 40}/Screen{index}.pam.php"
        path.parent.mkdir(parents=True, exist_ok=True)
        body = "".join(generator.choice("abcdefghij \n") for _ in range(FILE_BYTES))
        path.write_text(f"<?php\n// Screen {index}\n/*{body}*/\n")
    for index in range(VENDOR_FILES):
        path = root / f"vendor/acme/library/src/Class{index}.php"
        path.parent.mkdir(parents=True, exist_ok=True)
        body = "".join(generator.choice("klmnopqrst \n") for _ in range(FILE_BYTES))
        path.write_text(f"<?php\n// Class {index}\n/*{body}*/\n")
    (root / "vendor/composer").mkdir(parents=True, exist_ok=True)
    (root / "vendor/composer/installed.json").write_text('{"packages": []}')
    (root / "vendor/autoload.php").write_text("<?php\n")
    (root / ".pam-native/android").mkdir(parents=True, exist_ok=True)
    (root / ".pam-native/android/never-synced.php").write_text("<?php\n")


class Device:
    """The Android client protocol, reduced to what the test asserts."""

    def __init__(self, port: int) -> None:
        self.port = port
        self.files: dict[str, tuple[str, int]] = {}
        self.contents: dict[str, bytes] = {}
        self.version = ""

    def request(self, method: str, target: str, body: bytes | None = None, timeout: float = 30) -> bytes:
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=timeout)
        connection.request(method, target, body=body)
        response = connection.getresponse()
        data = response.read()
        connection.close()
        if response.status >= 300:
            raise AssertionError(f"{method} {target} returned {response.status}")
        return data

    @staticmethod
    def canonical(files: dict[str, tuple[str, int]]) -> bytes:
        lines = "".join(f"{sha} {size} {path}\n" for path, (sha, size) in sorted(files.items()))
        return ("PNM1\n" + lines).encode()

    def read_objects(self, data: bytes, expected: set[str]) -> int:
        assert data[:4] == b"PNO1", data[:16]
        offset, received = 4, 0
        while offset < len(data):
            digest = data[offset:offset + 32].hex()
            (size,) = struct.unpack("<Q", data[offset + 32:offset + 40])
            contents = data[offset + 40:offset + 40 + size]
            assert hashlib.sha256(contents).hexdigest() == digest
            assert digest in expected, digest
            self.contents[digest] = contents
            offset += 40 + size
            received += size
        return received

    def first_sync(self) -> int:
        version = self.request("GET", "/status").decode().strip()
        manifest = self.request("GET", f"/manifest?version={version}")
        assert hashlib.sha256(manifest).hexdigest() == version
        files = {}
        for line in manifest.decode().splitlines()[1:]:
            sha, size, path = line.split(" ", 2)
            files[path] = (sha, int(size))
        digests = {sha for sha, _ in files.values()}
        body = "".join(f"{digest}\n" for digest in sorted(digests)).encode()
        received = self.read_objects(self.request("POST", "/objects", body), digests)
        self.files, self.version = files, version
        return received

    def wait_for_delta(self) -> tuple[list[str], list[str], int]:
        data = self.request("GET", f"/status?version={self.version}&wait=1&delta=1")
        header, rest = data.split(b"\n", 1)
        magic, version, length = header.decode().split(" ")
        assert magic == "PNS1"
        delta, objects = rest[:int(length)], rest[int(length):]
        lines = delta.decode().splitlines()
        assert lines[0] == f"PNMD1 {self.version}"
        added, removed = [], []
        files = dict(self.files)
        for line in lines[1:]:
            if line.startswith("+ "):
                sha, size, path = line[2:].split(" ", 2)
                files[path] = (sha, int(size))
                added.append(path)
            else:
                path = line[2:]
                del files[path]
                removed.append(path)
        assert hashlib.sha256(self.canonical(files)).hexdigest() == version
        received = self.read_objects(objects, {files[path][0] for path in added})
        self.files, self.version = files, version
        return added, removed, received


class HotReloadLargeAppTest(unittest.TestCase):
    def setUp(self) -> None:
        if not CLI.is_file():
            self.skipTest(f"build the CLI first ({CLI} is missing)")
        self.root = Path(tempfile.mkdtemp(prefix="pam-hot-reload-large-"))
        generate(self.root)
        self.port = free_port()
        environment = dict(os.environ, PAM_NATIVE_DEV_NO_ADB="1", PAM_NATIVE_ANDROID_DEV_PORT=str(self.port))
        self.server = subprocess.Popen(
            [str(CLI), "dev:serve", str(self.root)],
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        self.output: list[str] = []
        self.ready = threading.Event()

        def collect() -> None:
            assert self.server.stdout is not None
            for line in self.server.stdout:
                self.output.append(line)
                if "sync: indexed" in line or line.startswith("pam-native:"):
                    self.ready.set()
            self.ready.set()

        threading.Thread(target=collect, daemon=True).start()
        self.ready.wait(60)
        self.assertIn("sync: indexed", "".join(self.output), "".join(self.output))

    def tearDown(self) -> None:
        if hasattr(self, "server"):
            self.server.terminate()
            self.server.wait(10)
            if self.server.stdout is not None:
                self.server.stdout.close()
        if hasattr(self, "root"):
            shutil.rmtree(self.root, ignore_errors=True)

    def edit_and_receive(self, device: Device, edit) -> tuple[list[str], list[str], int, float]:
        result: dict[str, object] = {}

        def wait() -> None:
            result["delta"] = device.wait_for_delta()

        waiter = threading.Thread(target=wait)
        waiter.start()
        time.sleep(0.3)
        started = time.monotonic()
        edit()
        waiter.join(30)
        self.assertIn("delta", result, "".join(self.output))
        added, removed, received = result["delta"]  # type: ignore[misc]
        return added, removed, received, time.monotonic() - started

    def test_large_app_first_sync_then_deltas_of_only_the_changed_files(self) -> None:
        device = Device(self.port)
        started = time.monotonic()
        received = device.first_sync()
        first_sync = time.monotonic() - started
        self.assertGreater(received, 16 * 1024 * 1024, "the generated app must exceed the old bundle cap")
        self.assertEqual(len(device.files), SOURCE_FILES + VENDOR_FILES + 4)
        self.assertNotIn(".pam-native/android/never-synced.php", device.files)

        edited = "src/Screens/Group7/Screen7.pam.php"
        added, removed, received, latency = self.edit_and_receive(
            device,
            lambda: (self.root / edited).write_text("<?php\n// Conversas edited\n"),
        )
        self.assertEqual((added, removed), ([edited], []))
        self.assertEqual(received, len("<?php\n// Conversas edited\n"))
        self.assertLess(latency, 1.0, f"edit to delta took {latency:.3f} s")

        vendor = "vendor/acme/library/src/Class3.php"

        def update_vendor() -> None:
            (self.root / vendor).write_text("<?php\n// upgraded\n")
            (self.root / "vendor/composer/installed.json").write_text('{"packages": [], "dev": false}')

        added, removed, _, _ = self.edit_and_receive(device, update_vendor)
        self.assertEqual(sorted(added), sorted([vendor, "vendor/composer/installed.json"]))

        gone = "src/Screens/Group1/Screen1.pam.php"
        added, removed, received, _ = self.edit_and_receive(device, lambda: (self.root / gone).unlink())
        self.assertEqual((added, removed, received), ([], [gone], 0))

        device.request(
            "POST",
            f"/reloaded?version={device.version}&outcome=1&ms=240&bytes=12&files=1",
            b"",
        )
        time.sleep(0.2)
        log = "".join(self.output)
        self.assertIn("1 file changed (src/Screens/Group7/Screen7.pam.php)", log)
        self.assertIn("screen updated in 240 ms", log)
        print(f"\nfirst sync {received_mib(device):.1f} MiB in {first_sync:.2f} s; edit to delta {latency * 1000:.0f} ms")


def received_mib(device: Device) -> float:
    return sum(size for _, size in device.files.values()) / (1024 * 1024)


if __name__ == "__main__":
    unittest.main()
