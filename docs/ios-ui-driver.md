# iOS UI driver (`PamNativeUITests`)

Every generated iOS host (`ios-host/`) ships a `PamNativeUITests` target
whose single test, `PamUIDriverTests.testRunScript`, drives the **installed**
application from a JSON step list. It is the installed-app Simulator gate the
headless XCTest host cannot provide (safe-area geometry, navigation, sheets,
presses that cross the responder chain) and it needs no GUI session on the
build host, so it runs on a headless Mac runner.

```bash
cd .pam-native/ios/App
env TEST_RUNNER_PAM_UI_SCRIPT='[{"launch":true},{"wait":6},{"tap":"Open local marketplace"},{"wait":2},{"shot":"marketplace"}]' \
    TEST_RUNNER_PAM_UI_SHOT_DIR="$PWD/shots" \
    xcodebuild test -project PamNativeApp.xcodeproj -scheme PamNativeUITests \
        -destination "platform=iOS Simulator,id=<UDID>" \
        -parallel-testing-enabled NO -disable-concurrent-destination-testing
```

`TEST_RUNNER_PAM_UI_SCRIPT` is the JSON array itself or an absolute path to a
file. `TEST_RUNNER_PAM_UI_SHOT_DIR` receives one PNG per `shot` (also attached
to the `.xcresult`). Pass `-parallel-testing-enabled NO`: with clones enabled
Xcode runs the test on a *clone* of the simulator, so `simctl` commands, logs
and app containers of the booted device never see the app under test.

Steps:

| Step | Effect |
| --- | --- |
| `{"launch": true}` | Cold start (default: `activate()` keeps the running state). |
| `{"tap": "label"}` | First element whose accessibility label equals or starts with `label`. |
| `{"tapIfExists": "label", "timeout": 2}` | Same, but continues when absent. |
| `{"tapId": "identifier"}` | Accessibility identifier (`testId`). |
| `{"tapXY": [0.5, 0.9]}` | Normalized window coordinates. |
| `{"longPress": "label", "seconds": 1}` | Long press. |
| `{"doubleTapXY": [0.5, 0.4]}` | Double tap at normalized window coordinates. |
| `{"longPressXY": [0.5, 0.4], "seconds": 1}` | Press and hold at normalized coordinates. |
| `{"drag": [0.5, 0.3, 0.5, 0.9], "velocity": 600, "hold": 0}` | Press, drag from one normalized point to another (points/s), optionally hold before lifting. |
| `{"pinch": 2.0, "velocity": 2}` | Two-finger pinch on the window (scale > 1 zooms in). |
| `{"type": "text"}` | Keyboard input into the focused field. |
| `{"swipe": "up"}` | `up`/`down`/`left`/`right` on the window. |
| `{"edgeSwipe": true}` | Press-and-drag from the left edge. |
| `{"home": true}` | Home button. |
| `{"alert": "Open"}` | Tap a SpringBoard alert button (e.g. the custom-scheme prompt). |
| `{"openurl": "scheme://path"}` | `XCUIApplication.open` (iOS 16.4+). |
| `{"wait": 1.5}` / `{"waitFor": "label", "timeout": 10}` | Delay / wait for an element. |
| `{"shot": "name"}` | Screenshot `name.png`. |
| `{"dump": "name"}` | Accessibility tree as `name.txt`. |
| `{"probe": "label", "name": "x"}` | `x.txt` lists the frame and `isHittable` of every element whose label matches, without tapping (diagnoses a "not hittable" tap). |

Each `tap` asserts that the element exists (10 s), so a missing screen fails
the run instead of producing misleading screenshots. Stack navigators persist
and restore their route (`Route::stack(restoreState:)`), so scripts that assume
the initial screen should start with `launch` and a `tapIfExists` on the back
button, or disable restoration for the test build.
