# DevTools

Debug builds include an on-device performance overlay. It reports smoothed frame
rate, decode and mount time, rendered node and batch counts, patch versus full
commits, native heap use and the last eight capability diagnostics. Diagnostics
include module latency, failures, semantic events, lifecycle changes and
runtime errors. Uncaught runtime errors use the LogBox-style error overlay
described in [error-overlay.md](error-overlay.md).

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

## Hot reload (`pam dev`)

`pam dev` builds and installs the debug host once, then keeps a sync server
running. Editing any bundled file (`.pam.php`, PHP classes, styles, assets,
`vendor/` after `composer update`) swaps the changed files on the device and
restarts the PHP request in the same process. No size limit applies: a large
application (the first sync of Zé Chat is about 2,100 files and 17 MiB of
sources) works the same as a small one.

How it works:

1. The CLI indexes the project (same files and exclusions as a build) into a
   manifest of `SHA-256, size, path`. Unchanged files are not read again; the
   project is polled every 100 ms, and `vendor/` is re-indexed when
   `vendor/composer/installed.json` changes.
2. The device long-polls `/status`. The first sync downloads the manifest and
   only the files it does not already hold: files of the bundle it booted
   from and the APK assets are verified by digest and reused, the rest stream
   to disk in one response.
3. Later saves arrive in the same long-poll response: the version, a delta
   manifest and the edited files (one round trip). Only the edited files are
   sent; `vendor/` is sent only when it changed.
4. The device writes into a stable tree (`files/pam/dev/live`). The previous
   PHP request shuts down first (its lazy autoloads still see the old files);
   the edited files are swapped in place before the next request starts.
   Stable paths keep OPcache and the component compile cache warm, so only the
   edited files recompile. Each written file gets a strictly newer
   modification second, so two saves in one second never serve stale code.
5. The navigation state is restored as usual (persisted navigators and
   `Restorable` components), and **component state survives the reload**
   (Fast Refresh): right before the old request shuts down, the `#[State]`
   properties of every mounted component are recorded by tree position
   (class and index among same-class siblings). A component of the same class
   at the same position gets them back before its `mount()` runs, so
   `mount()` and effects see the preserved state. Props, services, objects
   and plain fields are never carried over; a structural edit above a
   component, a changed class, or a value the new property type rejects makes
   it start fresh. A screen whose `mount()` resets its fields starts with
   `mount()`'s values. Snapshots are consumed by the next request and expire
   after 5 s, so a cold start never restores development state.

The terminal reports every step:

```text
Android sync: indexed 2108 files (16.8 MiB) in 464 ms; the device fetches only files it does not hold
Android change e7a2501762af: 1 file changed (src/Components/Inbox/InboxHeader.pam.php), 8.0 KiB to send, indexed in 42 ms
Android sync: sent 1 file (8.0 KiB) in 0 ms
Android reload e7a2501762af: screen updated in 305 ms, 1 file (8.0 KiB) applied; change to screen 531 ms
```

`screen updated` is measured on the device (new version received to first
committed frame); `change to screen` adds indexing and transport, from the
moment the CLI saw the change. Saving a syntax error or throwing exception
opens the error overlay without closing the app (`... failed after N ms`);
the next good save clears it and renders the fixed code.

Transport:

- The device always connects to `127.0.0.1:39100`; `adb reverse` maps it to
  the host port (`PAM_NATIVE_ANDROID_DEV_PORT`, default 39100). This works the
  same over USB and over adb Wi-Fi (`adb pair` / `adb connect`, or the
  `adb-tls-connect` mDNS transports). Select a device with `ANDROID_SERIAL`.
- When the device goes silent the CLI checks `adb reverse --list` every 3 s
  and restores the mapping after a cable replug, an adb server restart or a
  Wi-Fi reconnect. The device client retries with a short backoff
  (0.3 s to 2 s) and resumes on its own.
- `pam native dev:serve .` runs only the sync server for a debug app that is
  already installed (no Gradle build, no reinstall), for example after
  restarting the terminal. Set `PAM_NATIVE_DEV_NO_ADB=1` to skip `adb`
  entirely (the end-to-end test does this).

Restart `pam dev` (native rebuild) after adding plugins, permissions, native
modules or changing `pam-native.json`; the CLI prints a note when those files
change. Device logcat uses the `PamNativeHotReload` tag.

Measured on a Galaxy S10 over adb Wi-Fi with Zé Chat (2,108 files), editing a
visible title: device `screen updated` 234 to 374 ms, CLI `change to screen`
368 to 847 ms (median about 530 ms), plus up to 100 ms of file polling.
The first switch to the development tree after installing compiles every
component once (about 5 s); after that a cold app start switches in under
1 s.

### Protocol

`crates/pam-native-cli/src/dev_sync.rs` documents the HTTP endpoints
(`/status`, `/manifest`, `/objects`, `/reloaded`) and the `PNM1`, `PNMD1`,
`PNO1` and `PNS1` formats. Android implements the client in
`HotReloadClient.kt` and `HotReloadStore.kt`, iOS in `PamHotReload.swift`
and `PamHotReloadStore.swift`.

### iOS simulator checklist (Mac)

The iOS client shares the protocol and layout (`Caches/pam/dev/live`) and
applies edits between two PHP requests through
`pam_native_runtime_reload_prepared`. It was written without a Mac; validate
it there before relying on it:

1. `swift test --package-path ios --filter 'PamHotReload'` passes
   (`PamHotReloadTests`, `PamHotReloadStoreTests`).
2. `pam ios:dev .` on a booted simulator prints `iOS sync: indexed ...` and
   the app switches to `live/` (`iOS reload ...: screen updated`).
3. Edit a visible text: one file is sent, the screen changes within 1 s and
   `#[State]` (for example a selected filter) is kept.
4. Save a syntax error: the error overlay opens and the app keeps running;
   fix it and the overlay clears by itself.
5. Restore the file, stop with Ctrl+C, run `pam native dev:serve .` with the
   app still open: it reconnects without reinstalling.
6. Check `Caches/pam/dev` holds only `live/` and `live.manifest` after a
   reload (old per-version directories are removed).

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
