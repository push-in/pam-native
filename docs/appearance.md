# Appearance (light/dark)

PAM Native owns the light/dark preference natively, so an app in dark mode
never shows a light frame: not in the system starting window, not in the
splash screen, not in the native window before PHP renders, and not in PHP's
first frame.

## Configure the native window colours

`pam-native.json`:

```json
{
  "appearance": {
    "defaultMode": "system",
    "light": { "background": "#F7F6F2" },
    "dark": { "background": "#111511" }
  }
}
```

| Field | Default | Purpose |
| --- | --- | --- |
| `defaultMode` | `system` | Preference used until the app calls `Appearance::set()` (`system`, `light`, `dark`). |
| `light.background` / `dark.background` | `#FFFFFF` / `#121212` | Window background painted before the first PHP frame. |
| `*.statusBar`, `*.navigationBar` | `background` | Bar colours before edge-to-edge content. Icon contrast follows their luminance. |
| `*.splashBackground` | `background` | Android 12+ SplashScreen background. |
| `legacyStartingWindow` | `theme` | `none` skips the Android 8–11 starting window (see below). |

Colours are opaque `#RGB` or `#RRGGBB`. The mobile build generates
`res/values/pam_appearance.xml` and `res/values-night/pam_appearance.xml` for
the Android DayNight theme and adds `PamAppearanceDefaultMode`,
`PamAppearanceLightBackground` and `PamAppearanceDarkBackground` to the iOS
`Info.plist`. Match these colours to your CSS root background.

## PHP API

```php
use Pam\Native\Appearance;
use Pam\Native\AppearanceMode;
use Pam\Native\UserInterfaceAppearance;

Appearance::mode();      // AppearanceMode::System | Light | Dark (persisted)
Appearance::current();   // UserInterfaceAppearance::Light | Dark (effective)
Appearance::isDark();    // bool
Appearance::system();    // ?UserInterfaceAppearance, the OS scheme when observable

Appearance::set(AppearanceMode::Dark);            // restyles now, persists natively
Appearance::set(AppearanceMode::System, static function (bool $persisted): void {});

$subscription = Appearance::onChange(
    static function (UserInterfaceAppearance $current, AppearanceMode $mode): void {},
);
Appearance::unsubscribe($subscription);
```

`AppearanceMode` is an int-backed enum (`System = 1`, `Light = 2`,
`Dark = 3`). Every read is synchronous: the host exports the persisted mode and
the effective scheme (`PAM_APPEARANCE_MODE`, `PAM_SYSTEM_APPEARANCE`,
`PAM_SYSTEM_DARK`) before PHP starts, so `Appearance::current()`,
`App::appearance()`, `WindowMetrics::$appearance` and CSS
`@media (prefers-color-scheme: dark)` already describe the effective scheme
during the first render. No storage round-trip is needed at boot.

`Appearance::set()` updates `WindowMetrics::$appearance`, invalidates the tree
and re-renders it in place (components are not remounted), notifies
`onChange()` listeners, and asks the host to persist the mode. Host metrics
that arrive before the host confirmed the write cannot revert it.

## Pure-CSS theming

Prefer CSS over passing a `$darkTheme` flag through every screen:

```css
.screen { background-color: #F7F6F2; }
.title { color: #111511; }

@media (prefers-color-scheme: dark) {
  .screen { background-color: #111511; }
  .title { color: #F7F6F2; }
}
```

Declare the dark overrides directly inside the media block; custom properties
are resolved when the stylesheet compiles, so redefining a `--variable` inside
`@media` does not change rules outside it.

The query follows the effective appearance: the persisted override when one is
set, otherwise the system. System changes restyle at runtime without remounting.

## Verifying on a device

`scripts/android-appearance-first-frame.py` records a cold start on an emulator
and fails if any frame shows light content before or after the dark window, and
optionally asserts the persisted mode after the forced process restart:

```bash
python3 scripts/android-appearance-first-frame.py --serial emulator-5558 \
  --package com.example.app --dark '#111511' --light '#F7F6F2' --expect-mode 3
```

## Platform behaviour

**Android.** `Theme.PamNative` is a DayNight theme (`values` / `values-night`)
whose window background, bar colours, bar icon contrast and Android 12+
splash background come from `pam-native.json`. The mode is stored in
SharedPreferences (`pam.appearance`) with a synchronous commit.

- Android 12+ (API 31+): the mode is also registered with
  `UiModeManager.setApplicationNightMode()`, so the system starting window and
  splash screen use the chosen scheme after a process restart. Runtime changes
  arrive as a handled `uiMode` configuration change; the activity is not
  recreated.
- Android 8–11 (API 26–30): the mode is applied as an override configuration in
  `attachBaseContext()`, before the theme and `setContentView()`. A runtime
  change restyles the window, bars and PHP in place; framework widgets themed
  by the activity pick up the new scheme on the next launch. The system draws
  the starting window before the app process exists, from the system theme,
  so with an override opposite to the system it shows the other scheme for a
  few frames. Set `"legacyStartingWindow": "none"` to skip that window on
  Android 8–11: the launcher stays visible until the correctly themed window
  is ready, and no mismatched frame is ever drawn.

**iOS.** The mode is stored in `UserDefaults` (`pam.appearance.mode`) and
applied as the window's `overrideUserInterfaceStyle` before
`makeKeyAndVisible()`. The launch storyboard follows the system scheme.

## Splash logo

```json
{
    "appearance": {
        "light": { "splashBackground": "#F7F6F2" },
        "dark": { "splashBackground": "#111511" },
        "splash": {
            "logo": "assets/logos/ze-chat.png",
            "darkLogo": "assets/logos/ze-chat-dark.png",
            "size": 120
        }
    }
}
```

`logo`/`darkLogo` are project-relative PNG or WebP files (same format for
both). `size` is the logo box in dp (24–240, default 96). Android 12+ shows it
as `windowSplashScreenAnimatedIcon` (centered in the 240 dp icon area; the
system masks icons to a 160 dp circle), and API 26–30 draw it centered on the
`splashBackground` starting window. Each scheme uses its own logo and
background. Without `splash.logo` the platform default icon is kept.

**iOS** (1.9.0): the build writes `App/PamLaunch.xcassets` with a
`PamSplashBackground` color set (light/dark `splashBackground`) and, for PNG
logos, a `PamSplashLogo` image set (light/dark) whose scale is chosen so its
point size is closest to `size`; `UILaunchScreen` shows both. The host keeps
the same logo centered on screen until the first PHP frame is committed.
WebP logos are Android-only (the iOS launch screen then shows the background).

### Keeping the launcher icon on Android 12+ (1.21.0)

A React Native or Expo app usually sets no `windowSplashScreenAnimatedIcon`:
Android 12+ then draws the launcher icon on its splash screen, and only the
API 26–30 starting window paints the theme's `windowBackground` logo. Port such
an app with `android.icon` (its adaptive icon layers, see
[App icon](app-icon.md)) and `"android12Icon": "appIcon"`:

```json
{
    "android": {
        "icon": { "foreground": "assets/icon/foreground.webp", "background": "#000000" }
    },
    "appearance": {
        "light": { "splashBackground": "#FFFFFF" },
        "dark": { "splashBackground": "#FFFFFF" },
        "splash": {
            "logo": "assets/icon/splash-logo.webp",
            "size": 288,
            "android12Icon": "appIcon"
        }
    }
}
```

The Android 12+ splash keeps the launcher icon, drawn by the same system code
as the original app, and the logo only paints the legacy starting window, so
`size` may go up to 288 dp (an `xxhdpi` 864 px `<bitmap>` is 288 dp). The
default, `"logo"`, keeps the 1.9 behaviour.

Android 12 shows the icon only for launches from the launcher. Launches
from `adb shell am start`, notifications or other
apps get a solid `splashBackground` without icon, for every app; measure the
icon splash by tapping the launcher icon.
