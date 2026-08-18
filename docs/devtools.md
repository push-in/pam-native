# DevTools

Debug builds include an on-device performance overlay. It reports smoothed frame
rate, decode and mount time, rendered node and batch counts, patch versus full
commits, native heap use and the last eight capability diagnostics. Diagnostics
include module latency, failures, semantic events, lifecycle changes and
runtime errors.

Start the application and toggle the overlay from another terminal:

```bash
pam mobile dev
pam mobile devtools
```

Run `pam mobile devtools` again to hide it. The receiver and overlay are not
registered in release builds.

The raw Android command is also available for integrations:

```bash
adb shell am broadcast \
  -a dev.pam.nativeapp.action.TOGGLE_DEVTOOLS \
  -p your.application.id.debug
```

Use the overlay alongside `pam mobile benchmark` and `pam mobile profile`.
The overlay helps identify an expensive interactive path; the benchmark and
baseline-profile commands provide repeatable evidence suitable for CI.

## iOS

UIKit hosts can install the reusable `PamDevToolsOverlay` over their PAM host
view. Forward the existing runtime frame callback and toggle it from a debug
gesture:

```swift
let devTools = PamDevToolsOverlay()
let runtime = PamRuntime(
    hostView: hostView,
    reportError: reportError,
    onFrameCommitted: { [weak devTools] metrics in
        devTools?.update(metrics)
    },
    onDiagnostic: { [weak devTools] diagnostic in
        devTools?.record(diagnostic)
    },
)

devTools.toggle()
```

The iOS overlay reports smoothed FPS, mount/decode duration, node and batch
counts, patch/full commits, retained renderer memory and the capability
timeline. Keep it out of the release view hierarchy.

## Navigation traces

`NavigationDevTools` records a bounded, nanosecond-stamped route timeline with
integer trace kinds. It observes actions, state changes, rejected actions,
transitions and gestures without participating in animation frames.

```php
$navigationTools = new NavigationDevTools($navigation, capacity: 256);
$metrics = $navigationTools->metrics();
$json = $navigationTools->exportJson();
```

Metrics include retained/dropped event counts, unhandled actions, gesture
events, completed transitions, average and p95 transition duration, and the
current route. JSON export version 2 contains the recursive state tree, metrics
and timeline. Call `detach()` for deterministic teardown; destruction also
detaches every observer.

Exports also implement the cross-host PAM DevTools snapshot envelope:
`schemaVersion: 1`, integer `surfaceCode: 2`, and `capturedAtUnixMs`. The legacy
navigation payload `version: 2` remains present for compatibility. This lets one
collector distinguish Native snapshots from Server (`1`) and Desktop (`3`)
snapshots without inspecting application data.

CI also runs `packages/native/tests/navigation_performance.php`, which gates
the average PHP-side cost of 2,000 push/pop render decisions and 1,000 parsed
deep links. Native frame pacing remains covered separately by the AndroidX
Macrobenchmark and physical-device budgets under `benchmarks/mobile`.
