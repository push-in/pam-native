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
import base64
import contextlib
import json
import os
import socket
import struct
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

# Remote keys that are not keyboard keys, as each platform reports them.
TV_KEYS = {
    "tizen": {"GoBack": 10009, "MediaPlayPause": 10252, "MediaPlay": 415, "MediaPause": 19, "MediaStop": 413,
              "MediaFastForward": 417, "MediaRewind": 412, "ChannelUp": 427, "ChannelDown": 428},
    "webos": {"GoBack": 461, "MediaPlayPause": 179, "MediaPlay": 415, "MediaPause": 19, "MediaStop": 413,
              "MediaFastForward": 417, "MediaRewind": 412, "ChannelUp": 33, "ChannelDown": 34},
    "web": {},
}
ERROR_HOOK = "window.addEventListener('error', e => { window.__pamError = e.message; });"
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


def press(session, target, name):
    code = TV_KEYS[target].get(name)
    if code is not None:
        events = [{"windowsVirtualKeyCode": code, "nativeVirtualKeyCode": code, "key": "Unidentified"}]
    elif name in KEYBOARD and not (name == "GoBack" and target != "web"):
        key = "Escape" if name == "GoBack" else name
        events = [{"windowsVirtualKeyCode": KEYBOARD[name], "nativeVirtualKeyCode": KEYBOARD[name], "key": key,
                   "code": "Space" if key == " " else key}]
    else:
        raise AssertionError(f"{name} is not a {target} remote key")
    for kind in ("rawKeyDown", "keyUp"):
        session.send("Input.dispatchKeyEvent", dict(events[0], type=kind))


class Cdp:
    """DevTools over a page websocket, with only the standard library: TVs and
    simulators expose page targets, not the browser target Playwright needs."""

    def __init__(self, endpoint):
        targets = json.loads(urllib.request.urlopen(endpoint.rstrip("/") + "/json/list", timeout=10).read())
        pages = [t for t in targets if t.get("type") == "page" and t.get("webSocketDebuggerUrl")]
        # Simulators host their own UI in more pages: take the app's.
        target = next((t for t in pages if "app.asar" not in t["url"]), pages[0])
        url = urllib.parse.urlparse(target["webSocketDebuggerUrl"])
        self.socket = socket.create_connection((url.hostname, url.port or 80), timeout=30)
        key = base64.b64encode(os.urandom(16)).decode()
        self.socket.sendall((f"GET {url.path} HTTP/1.1\r\nHost: {url.netloc}\r\nUpgrade: websocket\r\n"
                             f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        response = b""
        while b"\r\n\r\n" not in response:
            response += self.socket.recv(4096)
        if b" 101 " not in response.split(b"\r\n")[0]:
            raise AssertionError(f"DevTools refused the connection: {response[:80]!r}")
        self.buffer = response.split(b"\r\n\r\n", 1)[1]
        self.next_id = 0
        self.console = []
        self.send("Runtime.enable")
        self.send("Page.enable")

    def _read(self, count):
        while len(self.buffer) < count:
            chunk = self.socket.recv(1 << 16)
            if not chunk:
                raise AssertionError("DevTools closed the connection")
            self.buffer += chunk
        data, self.buffer = self.buffer[:count], self.buffer[count:]
        return data

    def _message(self):
        payload = b""
        while True:
            head = self._read(2)
            length = head[1] & 0x7F
            if length == 126:
                length = struct.unpack(">H", self._read(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", self._read(8))[0]
            payload += self._read(length)
            if head[0] & 0x80:
                return json.loads(payload)

    def send(self, method, params=None):
        self.next_id += 1
        data = json.dumps({"id": self.next_id, "method": method, "params": params or {}}).encode()
        mask = os.urandom(4)
        header = bytes([0x81])
        if len(data) < 126:
            header += bytes([0x80 | len(data)])
        elif len(data) < 1 << 16:
            header += bytes([0x80 | 126]) + struct.pack(">H", len(data))
        else:
            header += bytes([0x80 | 127]) + struct.pack(">Q", len(data))
        self.socket.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))
        while True:
            message = self._message()
            if message.get("id") == self.next_id:
                if "error" in message:
                    raise AssertionError(f"{method}: {message['error']}")
                return message.get("result", {})
            if message.get("method") == "Runtime.consoleAPICalled":
                args = message["params"]["args"]
                self.console.append(f"{message['params']['type']}: " + " ".join(str(a.get("value", a.get("description", ""))) for a in args))
            elif message.get("method") == "Runtime.exceptionThrown":
                details = message["params"]["exceptionDetails"]
                self.console.append(f"pageerror: {details.get('exception', {}).get('description', details.get('text'))}")

    def evaluate(self, function):
        result = self.send("Runtime.evaluate", {"expression": f"({function})()", "returnByValue": True})
        if "exceptionDetails" in result:
            raise AssertionError(f"evaluate failed: {result['exceptionDetails'].get('text')}")
        return result["result"].get("value")

    def launch(self, url):
        self.send("Page.addScriptToEvaluateOnNewDocument", {"source": ERROR_HOOK})
        self.send("Page.navigate", {"url": url}) if url else self.send("Page.reload", {"ignoreCache": True})
        time.sleep(0.5)

    def tap(self, text):
        self.evaluate("() => { const all = document.querySelectorAll('#pam *'); for (const el of all) {"
                      f" if (el.children.length === 0 && el.textContent.includes({json.dumps(text)})) {{ el.click(); return; }} }}"
                      f" throw new Error('no element with ' + {json.dumps(text)}); }}")

    def screenshot(self, path):
        Path(path).write_bytes(base64.b64decode(self.send("Page.captureScreenshot", {"format": "png"})["data"]))

    def close(self):
        self.socket.close()


class Browser:
    """Headless Chrome on the GPU (ANGLE over EGL) through Playwright."""

    def __init__(self, playwright):
        self.browser = playwright.chromium.launch(
            executable_path=os.environ.get("CHROME", "/usr/bin/google-chrome"),
            args=["--use-angle=gl-egl", "--use-gl=angle", "--ignore-gpu-blocklist", "--enable-gpu-rasterization",
                  "--allow-file-access-from-files"],
        )
        self.page = self.browser.new_page(viewport={"width": 1920, "height": 1080})
        self.page.add_init_script(ERROR_HOOK)
        self.console = []
        self.page.on("console", lambda message: self.console.append(f"{message.type}: {message.text}"))
        self.page.on("pageerror", lambda error: self.console.append(f"pageerror: {error}"))
        self.cdp = self.page.context.new_cdp_session(self.page)

    def send(self, method, params=None):
        return self.cdp.send(method, params or {})

    def evaluate(self, function):
        return self.page.evaluate(function)

    def launch(self, url):
        self.page.goto(url)

    def tap(self, text):
        self.page.get_by_text(text, exact=False).first.click()

    def screenshot(self, path):
        self.page.screenshot(path=path)

    def close(self):
        self.browser.close()


def run(steps, app, target, shots, endpoint=None):
    url = (Path(app).resolve() / "index.html").as_uri() if app else None
    with contextlib.ExitStack() as stack:
        if endpoint:
            session = Cdp(endpoint)
        else:
            from playwright.sync_api import sync_playwright
            session = Browser(stack.enter_context(sync_playwright()))
        stack.callback(session.close)
        try:
            for number, step in enumerate(steps, 1):
                print(f"{number:>3} {json.dumps(step, ensure_ascii=False)}")
                if step.get("launch"):
                    session.launch(url)
                elif "wait" in step:
                    text = step["wait"]
                    until(session, lambda s: text in s["texts"], step.get("timeout", 15), f"{text!r} did not appear")
                elif "expect" in step:
                    text = step["expect"]
                    until(session, lambda s: text in s["texts"], step.get("timeout", 5), f"{text!r} is not on screen")
                elif "expectNot" in step:
                    text = step["expectNot"]
                    until(session, lambda s: text not in s["texts"], step.get("timeout", 5), f"{text!r} is still on screen")
                elif "expectFocus" in step:
                    text = step["expectFocus"]
                    until(session, lambda s: text in s["focus"], step.get("timeout", 5), f"the focus is not on {text!r}")
                elif "key" in step:
                    for _ in range(step.get("times", 1)):
                        press(session, target, step["key"])
                        time.sleep(step.get("delay", 0.08))
                elif "tap" in step:
                    session.tap(step["tap"])
                elif "sleep" in step:
                    time.sleep(step["sleep"])
                elif "shot" in step:
                    if shots:
                        Path(shots).mkdir(parents=True, exist_ok=True)
                        session.screenshot(str(Path(shots) / step["shot"]))
                else:
                    raise AssertionError(f"unknown step {step}")
        except AssertionError as error:
            if shots:
                Path(shots).mkdir(parents=True, exist_ok=True)
                session.screenshot(str(Path(shots) / "failure.png"))
            print(f"FAILED at step {number}: {error}", file=sys.stderr)
            for line in session.console[-20:]:
                print(f"  {line}", file=sys.stderr)
            sys.exit(1)
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
