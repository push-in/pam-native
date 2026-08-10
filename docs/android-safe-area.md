# Android edge-to-edge, safe areas and IME

Pam Native uses one edge-to-edge contract on Android. Applications do not
special-case manufacturers, navigation modes or display shapes. The host owns
the window contract and components consume the platform insets that apply to
their actual on-screen bounds.

## Invariants

- The activity and full-screen modal windows opt into edge-to-edge before PAM
  mounts content.
- Stable safe-area values combine system bars and display cutouts. A transient
  hidden navigation bar never turns a previously known safe bottom into zero.
- `SafeAreaView` intersects those stable insets with its own window bounds.
  Nested or decor-fitted views therefore do not apply the same inset twice.
- A bottom tab bar, composer, sheet action or media control must finish before
  the safe bottom. It must never be positioned behind gesture navigation or the
  three-button navigation bar.
- `KeyboardAvoidingView` calculates overlap from window and view geometry. IME
  movement does not assume a fixed keyboard height or subtract a manufacturer
  navigation bar twice.
- Resize-mode keyboard avoidance reduces the native viewport by the measured
  overlap and relays out flex descendants. Fixed composer/footer siblings move
  above the IME while the flexible list or content region absorbs the reduction.
- Padding-mode keyboard avoidance gives a contained vertical `ScrollView` the
  overlap inset and automatically reveals its focused input with 16 dp of
  clearance. `keyboardVerticalOffset` can reserve additional space for a form
  CTA that must remain visible with the field.
- A keyboard-avoiding view mounted during IME closing is reconciled after the
  current traversal and animation settling. It returns to the full viewport
  when Android reports the IME hidden even if its newly registered animation
  callback did not receive the transition's final event.
- While a panning container is translated, touch coordinates are remapped to
  its visible descendants. With the IME closed only text inputs use the
  geometry-aware fallback. The fallback follows Android sibling order and
  `z-index`, so an absolute camera or media overlay keeps ownership of its taps
  instead of focusing a visually covered input underneath it. Interactive
  descendants are refreshed at pointer-down, so a composer control that changes
  from microphone to send while the IME remains open is immediately tappable.
  A background `ScrollView` may geometrically extend behind the translated
  composer, but it is not treated as an overlay that can steal that touch.

## Platform matrix

| Android configuration | Contract |
| --- | --- |
| API 26–27 | Legacy system-window insets are retained and intersected with the view bounds. |
| API 28–29 | Legacy system-window insets include `DisplayCutout` safe edges. |
| API 30–34 | `WindowInsets.Type.systemBars()` and `displayCutout()` provide stable edges; IME uses `Type.ime()`. |
| API 35–36 | Enforced edge-to-edge uses the same explicit host contract and never depends on legacy decor fitting. |
| Gesture navigation | Bottom content remains outside the gesture handle region, including transient bar visibility changes. |
| Two/three-button navigation | The complete navigation-bar inset is reserved, including landscape side bars. |
| Cutout, waterfall and rounded displays | Every non-zero safe edge is preserved and applied only where the view intersects it. |
| Portrait, landscape and multi-window | Insets are recalculated from the current window bounds; physical display dimensions are not used for multi-window geometry. |
| Full-screen transparent modal | The dialog uses the PAM full-screen modal theme and receives its own edge-to-edge insets. |
| IME open/close and animation | Composer movement follows geometric overlap and keeps input/button hit testing aligned throughout the transition. |

This contract uses Android framework APIs instead of OEM detection and covers
Samsung One UI, Google Pixel Android, Xiaomi/Redmi/POCO HyperOS and MIUI,
Motorola My UX, OnePlus/Oppo/Realme OxygenOS and ColorOS, Vivo Funtouch OS,
Honor MagicOS, Huawei EMUI and other Android-compatible system images. OEM
validation checks the invariant above; it does not introduce manufacturer
branches that would diverge over time.

## Application usage

Wrap screen content in `SafeAreaView` and disable only edges already owned by a
fixed sibling. For example, a screen above a PAM bottom tab bar lets the tab
bar own the safe bottom:

```php
<SafeAreaView safeAreaEdgeBottom="false">
    <!-- screen content -->
</SafeAreaView>
<AppTabBar />
```

Bottom sheets should keep the bottom edge enabled and disable the top/side
edges when the sheet does not touch them:

```php
<SafeAreaView
    safeAreaEdgeTop="false"
    safeAreaEdgeLeft="false"
    safeAreaEdgeRight="false"
>
    <!-- sheet -->
</SafeAreaView>
```

Do not add status-bar heights, navigation-bar constants, device-model tables or
manual keyboard offsets to application code. Report a geometry that violates
the invariants above as a Pam Native host bug.

## Release validation

Before releasing an Android host change, exercise:

1. API 26, 28, 29, 30, the current target API and the newest available preview.
2. Gesture and three-button navigation in portrait and landscape.
3. A cutout device, a rounded-corner device and a rectangular emulator.
4. Cold start, process recreation, transparent full-screen modal and nested
   `SafeAreaView` layouts.
5. IME open/close, IME animation, multiline growth, attachment sheets and
   touch actions while the composer is translated.
6. `PamSafeAreaInsetsTest`, `PamSafeAreaLayoutTest`,
   `PamKeyboardAvoidanceTest` and Android instrumentation tests.
