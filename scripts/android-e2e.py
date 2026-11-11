#!/usr/bin/env python3
"""End-to-end script driver for PAM Native apps on Android (phones and TVs).

    scripts/android-e2e.py examples/tv/tests/e2e.json --package dev.pam.tv

The script is a JSON list of steps, run in order against the installed app:

    {"launch": true}                  start (or restart) the app
    {"wait": "Night Shift"}           text on screen (default 15 s)
    {"expect": "Paused · 10%"}        text on screen now (5 s grace)
    {"expectNot": "Paused"}           text gone (5 s grace)
    {"expectFocus": "Iron Valley"}    the focused view shows this text
    {"key": "ArrowRight", "times": 2} remote/keyboard key (W3C name or KEYCODE_*)
    {"tap": "Play"}                   tap the view showing this text
    {"sleep": 300}                    milliseconds

Keys use the same W3C names as `onKeyDown` ("ArrowUp", "Enter", "GoBack",
"MediaPlayPause"…). The screen is read with `uiautomator dump`, whose
`focused` attribute marks the view the D-pad is on. On failure the visible
texts are printed. ANDROID_SERIAL picks the device.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ElementTree

KEYS = {
    "ArrowUp": "KEYCODE_DPAD_UP",
    "ArrowDown": "KEYCODE_DPAD_DOWN",
    "ArrowLeft": "KEYCODE_DPAD_LEFT",
    "ArrowRight": "KEYCODE_DPAD_RIGHT",
    "Enter": "KEYCODE_DPAD_CENTER",
    "GoBack": "KEYCODE_BACK",
    "Escape": "KEYCODE_ESCAPE",
    "ContextMenu": "KEYCODE_MENU",
    "MediaPlayPause": "KEYCODE_MEDIA_PLAY_PAUSE",
    "MediaPlay": "KEYCODE_MEDIA_PLAY",
    "MediaPause": "KEYCODE_MEDIA_PAUSE",
    "MediaStop": "KEYCODE_MEDIA_STOP",
    "MediaFastForward": "KEYCODE_MEDIA_FAST_FORWARD",
    "MediaRewind": "KEYCODE_MEDIA_REWIND",
    "MediaTrackNext": "KEYCODE_MEDIA_NEXT",
    "MediaTrackPrevious": "KEYCODE_MEDIA_PREVIOUS",
    "Home": "KEYCODE_HOME",
}


def adb(*arguments: str, check: bool = True) -> str:
    completed = subprocess.run(
        ["adb", *arguments], capture_output=True, text=True, timeout=60
    )
    if check and completed.returncode != 0:
        raise RuntimeError(f"adb {' '.join(arguments)} failed: {completed.stderr.strip()}")
    return completed.stdout


class Screen:
    def __init__(self, xml: str) -> None:
        self.root = ElementTree.fromstring(xml)

    @staticmethod
    def capture() -> "Screen":
        for _ in range(3):
            output = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
            start = output.find("<?xml")
            end = output.rfind("</hierarchy>")
            if start >= 0 and end > start:
                return Screen(output[start : end + len("</hierarchy>")])
            time.sleep(0.3)
        raise RuntimeError("uiautomator dump returned no hierarchy")

    def nodes(self):
        return self.root.iter("node")

    @staticmethod
    def label(node) -> str:
        return node.get("text") or node.get("content-desc") or ""

    def texts(self) -> list[str]:
        return [label for node in self.nodes() if (label := self.label(node))]

    def has(self, text: str) -> bool:
        return any(text in label for label in self.texts())

    def find(self, text: str):
        for node in self.nodes():
            if text in self.label(node):
                return node
        return None

    def focused_texts(self) -> list[str]:
        for node in self.nodes():
            if node.get("focused") == "true":
                return [label for child in node.iter("node") if (label := self.label(child))]
        return []


def center(node) -> tuple[int, int]:
    numbers = [int(value) for value in re.findall(r"\d+", node.get("bounds", ""))]
    if len(numbers) != 4:
        raise RuntimeError(f"node without bounds: {Screen.label(node)!r}")
    left, top, right, bottom = numbers
    return (left + right) // 2, (top + bottom) // 2


def until(condition, seconds: float, failure: str) -> None:
    deadline = time.monotonic() + seconds
    screen = None
    while True:
        screen = Screen.capture()
        if condition(screen):
            return
        if time.monotonic() >= deadline:
            visible = ", ".join(repr(text) for text in screen.texts()[:40])
            focused = ", ".join(repr(text) for text in screen.focused_texts()) or "nothing"
            raise AssertionError(f"{failure}\n  visible: {visible}\n  focused: {focused}")
        time.sleep(0.25)


def run(steps: list[dict], package: str) -> None:
    for index, step in enumerate(steps, start=1):
        try:
            if step.get("launch"):
                adb("shell", "am", "force-stop", package)
                adb("shell", "monkey", "-p", package, "1", check=False)
                time.sleep(1.0)
            elif "wait" in step:
                text = step["wait"]
                until(lambda screen: screen.has(text), step.get("timeout", 15), f"{text!r} did not appear")
            elif "expect" in step:
                text = step["expect"]
                until(lambda screen: screen.has(text), step.get("timeout", 5), f"{text!r} is not on screen")
            elif "expectNot" in step:
                text = step["expectNot"]
                until(lambda screen: not screen.has(text), step.get("timeout", 5), f"{text!r} is still on screen")
            elif "expectFocus" in step:
                text = step["expectFocus"]
                until(
                    lambda screen: any(text in label for label in screen.focused_texts()),
                    step.get("timeout", 5),
                    f"the focus is not on {text!r}",
                )
            elif "key" in step:
                key = KEYS.get(step["key"], step["key"])
                for _ in range(int(step.get("times", 1))):
                    adb("shell", "input", "keyevent", key)
                    time.sleep(step.get("gap", 250) / 1000)
            elif "tap" in step:
                node = None
                deadline = time.monotonic() + 5
                while node is None:
                    node = Screen.capture().find(step["tap"])
                    if node is None and time.monotonic() >= deadline:
                        raise AssertionError(f"nothing to tap for {step['tap']!r}")
                x, y = center(node)
                adb("shell", "input", "tap", str(x), str(y))
            elif "sleep" in step:
                time.sleep(step["sleep"] / 1000)
            else:
                raise ValueError(f"unknown step {step}")
        except (AssertionError, RuntimeError, ValueError) as error:
            raise SystemExit(f"e2e: FAILED at step {index} {json.dumps(step, ensure_ascii=False)}: {error}")
    print(f"e2e: passed ({len(steps)} steps)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("script")
    parser.add_argument("--package", required=True)
    options = parser.parse_args()
    with open(options.script, encoding="utf-8") as stream:
        steps = json.load(stream)
    if not isinstance(steps, list):
        raise SystemExit("e2e: the script must be a JSON list of steps")
    if os.environ.get("ANDROID_SERIAL") is None:
        print("e2e: ANDROID_SERIAL is not set; adb uses its only device", file=sys.stderr)
    run(steps, options.package)


if __name__ == "__main__":
    main()
