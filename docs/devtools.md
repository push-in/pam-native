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

Capture the same live metrics as a machine-readable schema 1 snapshot
from the project root:

```bash
pam diagnostics
# Explicit form for automation:
pam mobile diagnostics .
# Explicit iOS simulator form:
pam mobile ios:diagnostics .
```

When more than one Android target is connected—or when certifying a physical
device—select it explicitly using the serial printed by `adb devices -l`:

```bash
pam mobile android:diagnostics . --device R58M1234
```

PAM validates the serial as bounded ASCII and prefixes every ADB operation with
the same `-s SERIAL` selector. The running-app check, one-use broadcast, private
`run-as` read, and immediate deletion therefore cannot drift to different
connected devices. USB and wireless targets still rely on Android's existing
ADB authorization/pairing boundary; PAM does not expose a network listener.

The receiver requires Android's privileged `DUMP` permission, which ADB's shell
holds but ordinary applications cannot request. The CLI also sends a one-use
128-bit request identifier to the running debug app,
reads the result from its private cache through Android `run-as`, enforces a
64 KiB response limit, and deletes the file immediately. The host keeps at most
one pending snapshot and performs file I/O on a dedicated executor. Timeline
entries expose only integer kind, duration and failure state. HTTP diagnostics
also expose an integer method code (`1` GET, `2` POST, `3` PUT, `4` PATCH, `5`
DELETE), status code when available, and bounded request/response byte counts.
URLs, origins, paths, query strings, headers, bodies, diagnostic labels and
application error messages are deliberately excluded. The timeline remains
bounded to its latest eight entries. Release builds do not register the capture
receiver.

`pam timeline <snapshot.json>` converts these fields into a bounded Chrome
Trace Event named `native.network`. The CLI rejects unknown method codes,
invalid HTTP status codes and byte counts above the Native transport limits,
so malformed snapshots fail closed instead of becoming trusted evidence.

The generated iOS debug host now installs and wires the UIKit overlay
automatically. `pam devtools` toggles it in an iOS-only project; the explicit
form is `pam mobile ios:devtools .`. Simulator capture opens an
application-scoped URL containing a
one-use request identifier, writes the redacted snapshot to the app's private
Caches directory on a utility queue, reads it through `simctl
get_app_container`, enforces the same 64 KiB contract and removes it. Only one
pending Native snapshot is retained. The URL handler is compiled out of release
builds. Physical-device iOS export remains intentionally unavailable because
Apple's public command-line documentation does not establish a portable,
bounded app-container extraction contract; PAM does not replace that gap with
an unauthenticated custom-URL or LAN listener.

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

Custom UIKit hosts can install the reusable `PamDevToolsOverlay` over their PAM
host view. The generated host already does this. Forward the existing runtime
frame callback and toggle it from a debug gesture:

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
