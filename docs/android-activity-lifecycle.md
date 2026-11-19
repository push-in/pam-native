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

## Locking the orientation

`android.orientations` in `pam-native.json` (same values as
`ios.orientations`) becomes `android:screenOrientation` on `PamActivity`:

| `android.orientations` | `screenOrientation` |
| --- | --- |
| `["portrait"]` | `portrait` |
| `["portraitUpsideDown"]` | `reversePortrait` |
| `["portrait", "portraitUpsideDown"]` | `sensorPortrait` |
| `["landscapeRight"]` | `landscape` (natural landscape, home side on the right) |
| `["landscapeLeft"]` | `reverseLandscape` |
| `["landscapeLeft", "landscapeRight"]` | `sensorLandscape` |
| omitted, or portrait and landscape mixed | not set: follows the sensor and the user's rotation lock |

A route's `NavigationOrientation::PlatformDefault` returns to this value, so
a portrait app stays portrait after a route that freed rotation. With
`targetSdk` 36, Android 16 ignores the lock on large screens
(`smallestScreenWidthDp` >= 600), so tablets and unfolded foldables still
rotate. `orientation` stays in `configChanges`: rotations that do happen are
applied in place.

```json
{ "android": { "orientations": ["portrait"] } }
```

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
registry replacement. These changes come from system settings, so they usually
happen while the app is in the background.

### View state kept across the remount

Node ids are stable across the replay, so the renderer captures the native
state PHP does not own right before it drops the old views and puts it back on
the rebuilt ones:

- **Scroll position, by anchor row.** A `ScrollView` records the node under its
  leading edge (descending through content wrappers at least a viewport long to
  the row the reader sees) and how far into it the edge is, as a fraction of
  its extent; a `FlatList`/`VirtualizedList`/`SectionList` records the row
  under the top (left) edge the same way, by item id. After the replay the
  same row is put back under the edge with its re-measured extent, so a font
  that grew does not push the reader elsewhere. A list or scroll resting at
  either end stays there (an inverted chat at its newest message, a feed at
  its top, a log at its end). The retained `contentOffset` and
  `initialScrollIndex` do not apply again: the reader had already left them.
- **The focused input**: it gets the focus back with its selection, and the
  keyboard reopens if it was open. The handoff is invisible to PHP (no
  `onBlur`/`onEndEditing`/`onFocus`); a pending draft is still delivered
  before the old editor goes, as on any blur. The input is captured when the
  configuration change arrives: the IME restarts for the new font scale or
  locale and hides for a moment, which does not count as the user closing it
  (the usual blur on a hidden keyboard is held until 2 s after the remount). If
  the IME hid before the change reached the Activity, PHP saw that blur and then
  sees the refocus.

Not kept: a fling in progress (it stops) and state inside views that are not
mounted when the remount runs (a nested list in an off-screen cell).

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
