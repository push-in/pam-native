#!/usr/bin/env python3
"""Drives a PAM Native web build (Tizen, webOS or web) with the remote control.

Same steps as scripts/android-e2e.py: launch, wait, expect, expectNot,
expectFocus (text inside the focused element), key (W3C name, with times),
tap (text) and sleep, plus shot (saves a screenshot). Remote keys are sent with
the TV's own key codes (Tizen Return is 10009, webOS Back is 461), so the key
mapping of the runtime is what the test exercises.

    scripts/web-e2e.py examples/tv/tests/e2e.json --app examples/tv/dist/tizen --target tizen

Chrome runs headless on the GPU (ANGLE over EGL), 1920x1080 like a 1080p TV.
With --cdp the script attaches instead to the app already running on a TV or
emulator through its DevTools port (Tizen: `sdb shell 0 debug <app id>` and
`sdb forward tcp:<port> tcp:<port>`); `launch` then reloads the app.
"""

import argparse
import json
import os
import sys
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

# Remote keys that are not keyboard keys, as each platform reports them.
TV_KEYS = {
    "tizen": {"GoBack": 10009, "MediaPlayPause": 10252, "MediaPlay": 415, "MediaPause": 19, "MediaStop": 413,
              "MediaFastForward": 417, "MediaRewind": 412, "ChannelUp": 427, "ChannelDown": 428},
    "webos": {"GoBack": 461, "MediaPlayPause": 179, "MediaPlay": 415, "MediaPause": 19, "MediaStop": 413,
              "MediaFastForward": 417, "MediaRewind": 412, "ChannelUp": 33, "ChannelDown": 34},
    "web": {},
}
KEYBOARD = {"ArrowUp": 38, "ArrowDown": 40, "ArrowLeft": 37, "ArrowRight": 39, "Enter": 13, "GoBack": 27,
            "Escape": 27, " ": 32}

SCREEN_JS = """() => {
    const root = document.getElementById('pam');
    const focused = document.activeElement && document.activeElement !== document.body ? document.activeElement : null;
    return {texts: root ? root.innerText : '', focus: focused ? focused.innerText : '', error: window.__pamError || ''};
}"""


def until(page, condition, seconds, failure):
    deadline = time.time() + seconds
    while True:
        screen = page.evaluate(SCREEN_JS)
        if screen["error"]:
            raise AssertionError(f"app error: {screen['error']}")
        if condition(screen):
            return
        if time.time() > deadline:
            visible = " | ".join(line for line in screen["texts"].split("\n") if line.strip())
            raise AssertionError(f"{failure}\n  visible: {visible}\n  focus: {screen['focus']!r}")
        time.sleep(0.05)


def press(page, cdp, target, name):
    code = TV_KEYS[target].get(name)
    if code is None and name in KEYBOARD and not (name == "GoBack" and target != "web"):
        page.keyboard.press("Escape" if name == "GoBack" else ("Space" if name == " " else name))
        return
    if code is None:
        raise AssertionError(f"{name} is not a {target} remote key")
    for kind in ("rawKeyDown", "keyUp"):
        cdp.send("Input.dispatchKeyEvent", {"type": kind, "windowsVirtualKeyCode": code,
                                            "nativeVirtualKeyCode": code, "key": "Unidentified"})


def run(steps, app, target, shots, endpoint=None):
    url = (Path(app).resolve() / "index.html").as_uri() if app else None
    with sync_playwright() as playwright:
        if endpoint:
            browser = playwright.chromium.connect_over_cdp(endpoint)
            page = next(page for context in browser.contexts for page in context.pages)
        else:
            browser = playwright.chromium.launch(
                executable_path=os.environ.get("CHROME", "/usr/bin/google-chrome"),
                args=["--use-angle=gl-egl", "--use-gl=angle", "--ignore-gpu-blocklist", "--enable-gpu-rasterization",
                      "--allow-file-access-from-files"],
            )
            page = browser.new_page(viewport={"width": 1920, "height": 1080})
        page.add_init_script("window.addEventListener('error', e => { window.__pamError = e.message; });")
        console = []
        page.on("console", lambda message: console.append(f"{message.type}: {message.text}"))
        page.on("pageerror", lambda error: console.append(f"pageerror: {error}"))
        cdp = page.context.new_cdp_session(page)
        try:
            for number, step in enumerate(steps, 1):
                print(f"{number:>3} {json.dumps(step, ensure_ascii=False)}")
                if step.get("launch"):
                    page.goto(url) if url else page.reload()
                elif "wait" in step:
                    text = step["wait"]
                    until(page, lambda s: text in s["texts"], step.get("timeout", 15), f"{text!r} did not appear")
                elif "expect" in step:
                    text = step["expect"]
                    until(page, lambda s: text in s["texts"], step.get("timeout", 5), f"{text!r} is not on screen")
                elif "expectNot" in step:
                    text = step["expectNot"]
                    until(page, lambda s: text not in s["texts"], step.get("timeout", 5), f"{text!r} is still on screen")
                elif "expectFocus" in step:
                    text = step["expectFocus"]
                    until(page, lambda s: text in s["focus"], step.get("timeout", 5), f"the focus is not on {text!r}")
                elif "key" in step:
                    for _ in range(step.get("times", 1)):
                        press(page, cdp, target, step["key"])
                        time.sleep(step.get("delay", 0.08))
                elif "tap" in step:
                    page.get_by_text(step["tap"], exact=False).first.click()
                elif "sleep" in step:
                    time.sleep(step["sleep"])
                elif "shot" in step:
                    if shots:
                        Path(shots).mkdir(parents=True, exist_ok=True)
                        page.screenshot(path=str(Path(shots) / step["shot"]))
                else:
                    raise AssertionError(f"unknown step {step}")
        except AssertionError as error:
            if shots:
                Path(shots).mkdir(parents=True, exist_ok=True)
                page.screenshot(path=str(Path(shots) / "failure.png"))
            print(f"FAILED at step {number}: {error}", file=sys.stderr)
            for line in console[-20:]:
                print(f"  {line}", file=sys.stderr)
            sys.exit(1)
        finally:
            browser.close()
    print(f"OK: {len(steps)} steps")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("script")
    parser.add_argument("--app", help="the build directory (with index.html)")
    parser.add_argument("--cdp", help="DevTools endpoint of the app on a TV or emulator (http://127.0.0.1:<port>)")
    parser.add_argument("--target", choices=sorted(TV_KEYS), default="tizen")
    parser.add_argument("--shots", help="directory for shot steps and the failure screenshot")
    arguments = parser.parse_args()
    if not arguments.app and not arguments.cdp:
        parser.error("--app or --cdp is required")
    run(json.loads(Path(arguments.script).read_text()), arguments.app, arguments.target, arguments.shots, arguments.cdp)


if __name__ == "__main__":
    main()
