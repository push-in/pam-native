#!/usr/bin/env python3
"""Cold-start pixel gate: a dark PAM Native app must never show a light frame.

Force-stops the application, records the screen while it cold starts, and
inspects every recorded frame at several sample points inside the window.

* Before the app's dark background first appears, no frame may show the light
  application background (the configured light colour, plain white, or any
  lighter content that is not a cross-fade between the launcher and the dark
  window), which catches a light starting window or splash screen.
* From the first dark frame on, every frame must stay dark, which catches a
  light native window before PHP's first frame and a light first PHP frame.

Optionally verifies the persisted appearance preference (SharedPreferences)
so the gate also proves the override survives a process restart.

Requires adb, ffmpeg and Pillow. Use an emulator, never a personal device.
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from PIL import Image


def parse_colour(value: str) -> tuple[int, int, int]:
    match = re.fullmatch(r"#([0-9A-Fa-f]{6})", value)
    if match is None:
        raise argparse.ArgumentTypeError(f"expected #RRGGBB, got {value!r}")
    rgb = int(match.group(1), 16)
    return (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF


def luminance(pixel: tuple[int, int, int]) -> float:
    r, g, b = (channel / 255 for channel in pixel[:3])
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def close(pixel: tuple[int, int, int], colour: tuple[int, int, int], tolerance: int) -> bool:
    return all(abs(a - b) <= tolerance for a, b in zip(pixel[:3], colour))


def adb(serial: str, *arguments: str, check: bool = True) -> str:
    return subprocess.run(
        ["adb", "-s", serial, *arguments],
        check=check,
        capture_output=True,
        text=True,
    ).stdout


def record_cold_start(serial: str, package: str, activity: str, seconds: int, workdir: Path) -> Path:
    adb(serial, "shell", "am", "force-stop", package)
    adb(serial, "shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(1.0)
    remote = "/sdcard/pam-appearance-first-frame.mp4"
    adb(serial, "shell", "rm", "-f", remote, check=False)
    recorder = subprocess.Popen(
        ["adb", "-s", serial, "shell", "screenrecord", "--time-limit", str(seconds), remote],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    time.sleep(1.0)
    adb(serial, "shell", "am", "start", "-n", f"{package}/{activity}")
    recorder.wait(timeout=seconds + 15)
    time.sleep(1.0)
    local = workdir / "cold-start.mp4"
    adb(serial, "pull", remote, str(local))
    adb(serial, "shell", "rm", "-f", remote, check=False)
    return local


def extract_frames(video: Path, workdir: Path) -> list[Path]:
    frames = workdir / "frames"
    frames.mkdir()
    subprocess.run(
        ["ffmpeg", "-loglevel", "fatal", "-i", str(video), "-vsync", "0", str(frames / "%05d.png")],
        check=True,
    )
    return sorted(frames.glob("*.png"))


def sample_points(width: int, height: int) -> list[tuple[int, int]]:
    # Lower half of the window: away from the status/navigation bars, the
    # centred splash icon and the top-aligned content of the probe app.
    return [
        (int(width * x), int(height * y))
        for x, y in ((0.1, 0.62), (0.9, 0.62), (0.1, 0.76), (0.9, 0.76), (0.5, 0.86))
    ]


def foreign_light(
    pixel: tuple[int, int, int],
    baseline: tuple[int, int, int],
    dark: tuple[int, int, int],
) -> bool:
    """True when a pixel is lighter than the dark window and cannot be
    explained as a cross-fade between the launcher frame and the dark window,
    i.e. a light starting window, splash or first frame is being composited."""
    direction = [b - d for b, d in zip(baseline, dark)]
    offset = [p - d for p, d in zip(pixel[:3], dark)]
    length = sum(component * component for component in direction)
    weight = 0.0 if length == 0 else max(0.0, min(1.0, sum(o * c for o, c in zip(offset, direction)) / length))
    residual = sum((o - weight * c) ** 2 for o, c in zip(offset, direction)) ** 0.5
    return residual > 36 and luminance(pixel) > luminance(dark) + 0.08


def analyse(frames: list[Path], dark: tuple[int, int, int], light: tuple[int, int, int]) -> list[str]:
    failures: list[str] = []
    first_dark: int | None = None
    baseline: list[tuple[int, int, int]] | None = None
    for index, frame in enumerate(frames, start=1):
        with Image.open(frame) as image:
            rgb = image.convert("RGB")
            pixels = [rgb.getpixel(point) for point in sample_points(*rgb.size)]
        if baseline is None:
            # Recording starts on the launcher, before the app is started.
            baseline = pixels
        if first_dark is None:
            if all(close(pixel, dark, 12) for pixel in pixels):
                first_dark = index
                continue
            light_points = [
                pixel for pixel, reference in zip(pixels, baseline)
                if close(pixel, light, 12)
                or close(pixel, (255, 255, 255), 6)
                or foreign_light(pixel, reference, dark)
            ]
            if len(light_points) >= 3:
                failures.append(f"frame {index}: light content before the dark window {light_points}")
        else:
            bright = [pixel for pixel in pixels if luminance(pixel) > 0.35]
            if bright:
                failures.append(f"frame {index}: light pixels after the first dark frame {bright}")
    if first_dark is None:
        failures.append("the dark application background never appeared")
    return failures


def stored_mode(serial: str, package: str) -> int | None:
    contents = adb(
        serial, "shell", "run-as", package, "cat", "shared_prefs/pam.appearance.xml", check=False,
    )
    match = re.search(r'name="mode" value="(\d+)"', contents)
    return int(match.group(1)) if match else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--activity", default="dev.pam.nativeapp.PamActivity")
    parser.add_argument("--dark", type=parse_colour, required=True, help="configured dark background")
    parser.add_argument("--light", type=parse_colour, required=True, help="configured light background")
    parser.add_argument("--expect-mode", type=int, choices=(1, 2, 3))
    parser.add_argument("--seconds", type=int, default=12)
    parser.add_argument("--keep", type=Path, help="copy the recording and frames here")
    arguments = parser.parse_args()

    for tool in ("adb", "ffmpeg"):
        if shutil.which(tool) is None:
            print(f"missing required tool: {tool}", file=sys.stderr)
            return 2

    with tempfile.TemporaryDirectory(prefix="pam-appearance-") as temporary:
        workdir = Path(temporary)
        video = record_cold_start(arguments.serial, arguments.package, arguments.activity, arguments.seconds, workdir)
        frames = extract_frames(video, workdir)
        failures = analyse(frames, arguments.dark, arguments.light)
        if arguments.keep is not None:
            shutil.copytree(workdir, arguments.keep, dirs_exist_ok=True)
    if arguments.expect_mode is not None:
        mode = stored_mode(arguments.serial, arguments.package)
        if mode != arguments.expect_mode:
            failures.append(f"persisted appearance mode is {mode}, expected {arguments.expect_mode}")
    if not frames:
        failures.append("no frames were recorded")
    for failure in failures:
        print(f"FAIL {failure}")
    if failures:
        return 1
    print(f"PASS {len(frames)} cold-start frames never showed a light frame")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
