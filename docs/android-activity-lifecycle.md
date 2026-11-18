# Android Activity lifecycle and configuration changes

PHP runs once per process. `PamActivity` is only its surface: when Android
destroys and recreates the Activity while the process lives, the new Activity
re-attaches to the same runtime and remounts the retained tree (PHP state is
kept, nothing is re-executed). The native module registry, however, is bound
to the Activity: a recreation builds a new registry and **retires** the old
one. Modules that implement `SurfaceRetirableModule` release only their
surface resources (`retireSurface()`); every other `AutoCloseable` module is
**closed**, so its sockets, sessions, listeners and pending callbacks end.

To keep that rare, every configuration change the renderer can apply in
place is declared in the `PamActivity` manifest entry
(`android/app/src/main/AndroidManifest.xml`, copied into every generated
`.pam-native/android` workspace by `pam`).

## Declared `configChanges`

| Value | What PamActivity does in `onConfigurationChanged` |
| --- | --- |
| `orientation`, `screenSize`, `smallestScreenSize`, `screenLayout` | Engine relayout with the new window size, safe area and a `dimensions` event (deviceType follows `smallestScreenWidthDp`). |
| `keyboard`, `keyboardHidden`, `navigation` | Same relayout/`dimensions` event; `inputMode` is recomputed from the new configuration. |
| `uiMode` | Window background and system bars restyled, `dimensions` event with the new appearance (see [appearance.md](appearance.md)). |
| `fontScale` | Cached engine text measurements are dropped, the engine relayouts with the new effective text scale (`PamTextScale`), PHP receives the new `fontScale`, then the mounted views are rebuilt from the retained tree so every text size is applied again. |
| `density` | Same as `fontScale`: text is measured again and the remount converts every dp value (frames, borders, radii, shadows, image targets) with the new density. |
| `locale`, `layoutDirection` | Same remount (text transforms, number formats, hyphenation and RTL-resolved gravities are rebuilt); the window resolves the new direction itself. The `dimensions` event carries the system `locale`; `useLocale()` asks DeviceInfo again when it differs from the one it knows. |
| `mcc`, `mnc` | Nothing to apply: the PAM UI does not use carrier-qualified resources. |

The remount is the same operation a recreated Activity performs (all views
rebuilt from the engine's retained tree, PHP untouched), minus the module
registry replacement. Like a recreation, it resets native view-local state
that PHP does not own (scroll offsets not bound to state, an open IME). These
changes come from system settings, so they usually happen while the app is in
the background.

## Not declared (the Activity is still recreated)

- `fontWeightAdjustment` (Android 12 "Bold text"), `grammaticalGender`,
  `colorMode`, `touchscreen`: not applied in place yet. The text measurer and
  the views would disagree on bold text until a remount; they keep the
  platform behaviour.
- Resource overlay / asset path changes (runtime resource overlays, an
  installed split, a WebView update): they cannot be declared as
  `configChanges` at all; Android always recreates the Activity.

`PamWebActivity` (the WebView fallback) keeps its own, shorter list.

## Plugins: when to implement `SurfaceRetirableModule`

Recreations are now rare but not impossible (the cases above, plus the system
reclaiming a stopped Activity). A plugin module must implement
`SurfaceRetirableModule` when it owns state that belongs to the **runtime**,
not to the Activity:

- open network connections (WebSockets, realtime channels, calls, streams),
- event waiters PHP subscribed to (`onX` listeners that a later event
  completes),
- in-flight work whose result PHP is still awaiting.

`retireSurface()` releases only what is tied to the old Activity (an
`Activity` reference, a view, a window callback, a permission launcher) and
keeps the rest; the new registry's instance can pick the runtime state up from
a process-wide owner (a singleton or `Application` scoped object). `close()`
remains the final shutdown (process end, reload).

A module that holds nothing beyond a single call can stay a plain
`AutoCloseable` (or not be closeable at all).
