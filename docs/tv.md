# TV apps (Android TV, Google TV, Fire TV, Samsung, LG)

A PAM Native app runs on Android TV from the same code as the phone app. TV is
one more target of the project, not another project: the same APK installs on
phones, tablets and TVs, and the framework handles the remote control.

Samsung (Tizen) and LG (webOS) TVs run HTML/JS apps: for them the same app
is compiled to a static HTML/JS build, with no PHP and no server on the TV
(see [Samsung and LG TVs](#samsung-and-lg-tvs-tizen-and-webos)). Apple TV
(tvOS) is not a target yet. Android TVs need 64-bit Android (`arm64-v8a`, or `x86_64` emulators):
devices that run 32-bit Android (`armeabi-v7a` only, such as the 2020
Chromecast with Google TV and older Fire TV sticks) are not supported yet,
because the PHP SDK needs 64-bit integers (ARGB colors, node ids).

## Add the target

```bash
pam init my-app --template native --platform android,ios,android-tv
```

or, in an existing app, run `pam add target android-tv` (`pam remove target
android-tv` takes it out), which edits `targets` in `pam-native.json`:

```json
{
    "targets": ["android", "ios", "android-tv"],
    "android": {
        "tv": {
            "banner": "resources/tv-banner.png",
            "overscan": true
        }
    }
}
```

With `android-tv` in `targets`, `pam mobile build` and `pam mobile run`:

- add the TV launcher entry (`LEANBACK_LAUNCHER`) next to the phone one;
- declare `android.software.leanback` and `android.hardware.touchscreen` as
  optional, so TVs without a touch screen can install the APK;
- use `android.tv.banner` (a 320x180 PNG with the app name) as the launcher
  banner; without one, the launcher icon is drawn on the splash colour;
- warn about elements a remote cannot reach (see [Checks](#checks)).

`pam mobile run` installs on the connected device. For an emulator, create an
Android TV or Google TV AVD (`system-images;android-36;google-tv;x86_64`) and
set `ANDROID_SERIAL` when a phone is also connected.

## The same screens on a TV

Nothing has to change for a screen to work on a TV:

- **Focus.** Every `Pressable`, `Button`, `Link` and `Input` is a focus stop.
  The remote's D-pad moves between them, and the select button presses.
- **Focus ring.** While a remote, game pad or keyboard drives the app, the
  focused element gets a white ring that follows its corners. A `:focus` style
  replaces it (`.card:focus { transform: scale(1.08); border-color: #FFF }`),
  and `focusRing="none"` turns it off.
- **Overscan.** TVs may crop the picture edges, so on a TV the safe area gets
  5% margins (48 x 27 dp on a 960 x 540 dp screen). `SafeAreaView` and
  `useSafeAreaInsets()` keep content inside them. `android.tv.overscan: false`
  turns them off.
- **Size.** A 1080p TV is 960 x 540 dp at density 2: Android already scales dp
  and sp for viewing distance, so the phone's sizes stay readable.

## Adapting to the TV

### `useFormFactor()`

```php
App::globals(['form' => useFormFactor()]);
```

```html
<Column p-if="!$form.phone" class="menu">…</Column>
```

`->type` (`phone`, `tablet` or `tv`), `->tv`, `->phone`, `->tablet`,
`->remote` (a D-pad drives the focus), `->touch`, `->inputMode` and
`->pointer`. `useWindowDimensions()->deviceClass` is `DeviceClass::Television`
on a TV. In CSS, `@media (device-type: tv)` and `@media (input-mode: remote)`.

### Variants: `Name.tv.pam.php`

A component can have another template for TVs. `Home.tv.pam.php` beside
`Home.pam.php` holds only a `<template>` (and optionally `<style>`); it uses
the class of `Home.pam.php`, so the state, actions and props are the same:

```html
<!-- src/Screens/Home.tv.pam.php -->
<template>
    <Row class="tv-home">…</Row>
</template>
```

On a TV the variant renders; elsewhere the base template does. `.phone` and
`.tablet` variants work the same way.

## Focus and keys

| Attribute | |
| --- | --- |
| `focusable="true"` | Makes any box a focus stop (`false` takes a pressable out). |
| `autoFocus="true"` | Takes the focus when it appears: the first focus on a screen, or the item a player returns to. |
| `focusGroup="true"` | A row, rail or menu that remembers its last focused child: coming back into it with the D-pad returns there instead of the nearest child. |
| `nextFocusUp`, `nextFocusDown`, `nextFocusLeft`, `nextFocusRight` | The `testId` the D-pad goes to from here, instead of the nearest element. |
| `focusRing="none"` | No default ring on this element. |
| `on:keyDown` / `@keyDown` | Keys while this element or a descendant has the focus, nearest listener first. |
| `captureKeys="ArrowLeft,ArrowRight,GoBack"` | These keys stop here: they reach `on:keyDown` and do not move the focus, press or go back. |

`on:keyDown` receives a `Pam\Native\KeyEvent`: `->key` is the W3C name
(`ArrowUp`, `Enter` for the select button, `GoBack` for Back, `ContextMenu`
for the menu button, `MediaPlayPause`, `MediaFastForward`, `MediaRewind`,
`ChannelUp`, `ColorF0Red`…), plus `->repeat`, `->shift`, `->control`,
`->alt`, `->meta`, `->isArrow()` and `->isMedia()`. Without a focused element
the key goes to every visible listener. Keys with Control, Alt or Meta are
never captured. `on:keyDown` and `captureKeys` work the same way in PAM Desktop.

A full-screen player takes the focus and keeps the D-pad to itself:

```html
<Column p-if="$playing" focusable="true" autoFocus="true" focusRing="none"
        captureKeys="ArrowLeft,ArrowRight,Enter,GoBack" @keyDown="key">
```

```php
public function key(KeyEvent $event): void
{
    match ($event->key) {
        'Enter', 'MediaPlayPause' => $this->paused = !$this->paused,
        'ArrowRight', 'MediaFastForward' => $this->seek(10),
        'ArrowLeft', 'MediaRewind' => $this->seek(-10),
        'GoBack' => $this->playing = null,
        default => null,
    };
}
```

## Checks

When the app targets `android-tv`, the build warns about elements that only
react to touch (`on:longPress`, `on:doubleTap`, gestures, drag, raw touches)
and have no `on:press`, `on:keyDown` or `focusable`: a remote cannot reach
them. Add a press, a key handler, or a `.tv.pam.php` variant. A component
with a TV variant is checked through its variant. The warning never fails
the build.

## Samsung and LG TVs (Tizen and webOS)

Tizen and webOS apps are web pages, so `pushinbr/pam-native-web` compiles the
PHP app ahead of time into `index.html`, `app.js` and `app.css`: components and
classes become JavaScript, templates become DOM builders and `<style scoped>`
becomes CSS. A small runtime (about 20 KB gzip with
[`@preact/signals-core`](https://github.com/preactjs/signals)) keeps the
reactivity, the focus engine (same distance rules as Android, `focusGroup`
memory, `nextFocus*`, `autoFocus`), the keys of each remote and the 960x540
logical screen with the 5% overscan margins. Nothing runs on a server.

```bash
pam composer require --dev pushinbr/pam-native-web
pam add target tizen        # or webos; edits targets in pam-native.json
pam mobile tizen:build      # dist/tizen: index.html, app.js, app.css, config.xml
pam mobile webos:build      # dist/webos: ..., appinfo.json
pam mobile tizen:package --sign <profile>   # artifacts/*.wgt (Tizen CLI)
pam mobile webos:package    # artifacts/*.ipk (webOS CLI, ares-package)
pam mobile tizen:run --device <serial>      # install and launch (TV or emulator)
pam mobile webos:run --device <name>
```

`web.icon` sets the launcher icon (default `android.icon`) and
`web.tv.overscan` the safe margins (default `android.tv.overscan`, `true`).
The build targets the oldest browsers still in use (Tizen 5.0 with Chromium
63, webOS 5 with Chromium 68): ES2017, no optional chaining, `gap`
emulated with margins.

The remote keys arrive with the same W3C names as on Android: Tizen's Return
(10009) and webOS Back (461) are `GoBack`, and the media keys (`MediaPlayPause`,
`MediaPlay`, `MediaFastForward`...) are registered with
`tizen.tvinputdevice` at launch. `useFormFactor()` reports a TV with a remote.

What compiles: components, variants (`.tv.pam.php` wins), classes, enums,
interfaces, closures, `match`, exceptions, string/array functions and
`sprintf`, the template directives and the `@media (device-type: tv)` and
`(input-mode: remote)` queries. What the build refuses, with the file and
line: native modules and plugins, file and network I/O, references (`&`) other
than the sort and array functions, `goto`, generators and PHP functions with no
JavaScript version. Tags the web runtime does not know are drawn as `View`,
with a warning.

`scripts/web-e2e.py` runs the same end-to-end scripts on the build, in
headless Chrome with each TV's key codes:

```bash
scripts/web-e2e.py examples/tv/tests/e2e.json --app examples/tv/dist/tizen --target tizen
```

With `--cdp http://127.0.0.1:<port>` the same script drives the app already
open on a TV, the Tizen emulator or the
[webOS TV Simulator](https://webostv.developer.lge.com/develop/tools/simulator-installation)
through its DevTools port (no Playwright needed). `examples/tv` passes the 25
steps on the webOS TV 22 Simulator (Chromium 87):

```bash
webOS_TV_22_Simulator_1.4.1.appimage examples/tv/dist/webos --remote-debugging-port=9333 &
scripts/web-e2e.py examples/tv/tests/e2e.json --cdp http://127.0.0.1:9333 --target webos
```

Samsung TVs and the Samsung TV emulator only install packages signed with a
Samsung certificate (Tizen Studio, Certificate Manager, with a Samsung
account); `tizen:package --sign <profile>` uses that profile. Run the
simulators and emulators on a virtual display (`Xvfb`) in CI.

## End-to-end tests

`scripts/android-e2e.py` drives an installed app with the remote and checks
the screen and the focus:

```json
[
    {"launch": true},
    {"wait": "Trending now"},
    {"expectFocus": "Night Shift"},
    {"key": "ArrowRight"},
    {"expectFocus": "Deep Orbit"},
    {"key": "Enter"},
    {"wait": "Playing"},
    {"key": "GoBack"},
    {"expectFocus": "Deep Orbit"}
]
```

```bash
ANDROID_SERIAL=emulator-5554 scripts/android-e2e.py examples/tv/tests/e2e.json --package dev.pam.tv.debug
```

Steps: `launch`, `wait`, `expect`, `expectNot`, `expectFocus` (text inside
the focused view), `key` (W3C name or `KEYCODE_*`, with `times`), `tap` and
`sleep`. On failure it prints the visible texts and what has the focus.

`examples/tv` (PAM TV) is a complete TV app: a side menu, a hero that follows
the focused poster, rails with focus memory and a player driven by the
remote's arrows and media keys.
