# Runtime errors and the error overlay

An uncaught PHP exception in a render, an event handler, a native module
result callback or the app bootstrap is reported once to `App::onError()`
listeners and then to the native host, which shows it according to the
build type. The Android host follows React Native's LogBox/RedBox model.

## Debug builds: LogBox-style overlay

- **Non-fatal errors** (event handlers, module callbacks) appear as a compact
  toast at the bottom of the screen. The app stays interactive: the toast
  only takes the touches that land on it. Tap it to open the inspector, or
  `×` to dismiss every queued error.
- **Fatal errors** (render and boot failures, native runtime failures) open
  the full-screen inspector directly.
- The inspector shows the exception class and message in large text, the
  app frame location, a source snippet around the failing line (when the
  file is readable), and the call stack with app frames first. Consecutive
  framework/vendor frames collapse into a tappable "N framework frames" row.
  Paths are app-relative: `/data/user/0/<pkg>/files/pam/releases/<hash>/`
  and the project root are stripped.
- **Dismiss** removes the current error (the next queued one is shown, or the
  overlay closes). **Copy** puts the full report on the clipboard. **Reload**
  restarts the PHP runtime on the active bundle (the latest hot-reload
  bundle in development). **Minimize** returns a non-fatal error to the
  toast. `‹ 1 / 3 ›` navigates queued errors; repeated occurrences of the
  same error increment a `×N` counter instead of queueing again.
- **Back** closes the inspector (dismissing queued errors). With only the
  toast visible, Back goes to the app as usual.
- A dismissed error is not shown again until the runtime reloads (Reload or
  hot reload), so an error raised on every frame or timer tick cannot trap
  the screen in a re-show loop.
- The overlay follows the app's light/dark appearance and the window safe
  area (status bar, navigation bar and display cutout).

## Release builds

Stacks are never shown to end users.

- Non-fatal errors are logged (`PamNativeErrors` logcat tag) and delivered to
  `App::onError()` listeners; nothing is shown and the UI keeps working.
- Fatal errors retry with a fresh runtime (up to three times with backoff);
  after that a friendly "Something went wrong" / "Algo deu errado" screen
  with a **Try again** button is shown. A frame committed afterwards hides
  it. Back from that screen closes the activity.

## Configuration

`pam-native.json`:

```json
{ "devErrorOverlay": true }
```

| Value | Behaviour |
| --- | --- |
| `true` / `"debug"` (default) | Overlay in debug builds, release fallback otherwise. |
| `false` / `"off"` | Never show the overlay; debug builds behave like release. |
| `"always"` | Also show the overlay in release builds (internal QA builds only). |

## Crash reporting (Sentry, observability)

```php
use Pam\Native\App;
use Pam\Native\Diagnostics\RuntimeError;

App::onError(static function (Throwable $error, RuntimeError $report): void {
    // $report->phase (Boot, Render, Event, ModuleResult, Other), $report->fatal(),
    // $report->frames (app-relative, classified app/framework/vendor/internal),
    // $report->appFrame, $report->fingerprint, $report->snippet (debug only).
    \Sentry\captureException($error);
});
```

Listeners run in every build mode, before the host is notified. A listener
that throws is ignored. Register them before `App::run()`; listeners are
cleared when the runtime reloads.

## Module failures

A failing native module result goes to the call's failure handler when one
is given, never to the overlay:

```php
SQLite::query(
    'chat.db',
    'SELECT * FROM messages WHERE thread = ?',
    [$threadId],
    static fn (array $rows) => $this->show($rows),
    static fn (NativeModuleException $error) => $this->showLoadError($error->getMessage()),
);
```

`SQLite::execute()`, `query()`, `executeMany()`, `transaction()`,
`Storage::get()` and `Storage::set()` accept `onError`; `NativeModules::call()`
and `callRaw()` accept `onFailure`. Without a handler the failure is reported
as a non-fatal `Pam\Native\Modules\NativeModuleException` (a
`RuntimeException`). SQLite query results larger than the 1 MiB bridge limit
fail with an explicit size message; page them with `LIMIT`/`OFFSET`.

## Diagnostic payload

`Runtime::reportError()` sends `PAMERR1\n` + JSON (version 2) to the host:
`type`, `message`, `file`, `line`, `column`, `phase`, `fatal`, `frames`
(`file`, `line`, `call`, `kind`), `appFrame`, `snippet` (`file`, `line`,
`start`, `lines`), `fingerprint` and the shortened `trace` text. Hosts still
accept version 1 payloads (shown full-screen) and plain-text native errors.
