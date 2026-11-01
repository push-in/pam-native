# Responsive layout: any screen size, any text size

How PAM Native sizes UI for small phones, large phones, tablets, split
screen and accessibility text sizes, and how that maps to React Native and
Flutter. Rule of thumb: **lay out relative to the window, the safe area and
the parent; never with a value measured on one device.**

## What the references do

### React Native
- `useWindowDimensions()` / `Dimensions.get('window' | 'screen')`: `width`,
  `height`, `scale`, `fontScale`, re-rendered on rotation, fold and split
  screen. `window` is the app's area; `screen` the whole display (rarely
  what layout needs).
- `PixelRatio.get()`, `getFontScale()`, `roundToNearestPixel()`; layout in
  dp/pt, snapped to physical pixels.
- `react-native-safe-area-context`: `SafeAreaProvider`, `useSafeAreaInsets()`,
  `SafeAreaView edges`.
- Text grows with the system font size by default (`allowFontScaling`
  true); `maxFontSizeMultiplier` caps one text; apps cap compact chrome
  (badges, tab labels) and let body text grow.
- Layout primitives: flex, `%`, `aspectRatio`, min/max sizes.
- Weak spot: every `useWindowDimensions()` reader re-renders on any change
  of the window object.

### Flutter
- `MediaQuery`: `size`, `padding` (safe area minus what the keyboard
  covers), `viewPadding` (raw system bars), `viewInsets` (keyboard),
  `textScaler`, `devicePixelRatio`.
- `MediaQuery.sizeOf(context)`, `paddingOf`, `textScalerOf`...: a widget
  rebuilds only when the aspect it read changes (`MediaQuery.of` rebuilds on
  any change).
- `LayoutBuilder` / `BoxConstraints`: size from the parent's constraints, not
  the window (the right input for reusable components).
- `SafeArea`, `Flexible`/`Expanded`, `AspectRatio`, `FractionallySizedBox`,
  `FittedBox` (scale content down to fit).
- `TextScaler.clamp(minScaleFactor, maxScaleFactor)` and
  `MediaQuery.withClampedTextScaling` bound text growth per subtree.
- Material window size classes: width compact < 600, medium 600–839,
  expanded >= 840 (large 1200, extra-large 1600); height compact < 480,
  medium 480–899, expanded >= 900.

### Copy
- Window metrics as reactive values with **per-property** rebuilds (Flutter).
- Safe-area insets as values and as CSS `env(safe-area-inset-*)`.
- Font scaling on by default, capped per text (`maxFontSizeMultiplier`) and
  per role (`TextScalePolicy`), plus a clamp for sizes derived from text.
- Material breakpoints as the only named breakpoints.
- Relative layout first: flex, `%`, `aspect-ratio`, `vw`/`vh`, `clamp()`.

### Avoid
- Whole-object window reads in hot components (re-render on keyboard
  resize, inset or font-scale changes).
- `screen` dimensions for layout; the window is what the app owns.
- Device-measured constants (`height: 412px` for a stage that "fits" one
  phone). Derive from the window or the parent and clamp.
- Disabling font scaling to keep a layout from breaking: give the row room
  (min height, wrap, `numberOfLines` + ellipsis) and cap with a multiplier.
- Fixed-height rows with unbounded text.

## PAM Native API

Core (always available):

| Need | API | Reference |
| --- | --- | --- |
| Window size | `useWindowDimensions()->width/height/scale/fontScale/landscape` (`$window.width` in templates when registered as a global) | RN `useWindowDimensions`, Flutter `MediaQuery.sizeOf` |
| Width/height class | `->deviceClass` (compact/medium/expanded), `->heightClass`, `AdaptiveLayout::classify()/classifyHeight()/select()` | Material window size classes |
| Safe area | `useSafeAreaInsets()->top/right/bottom/left`, CSS `env(safe-area-inset-bottom, 0px)`, `safeAreaBottom` style | `react-native-safe-area-context`, Flutter `SafeArea` |
| Keyboard | `useKeyboard()->visible/height` | Flutter `viewInsets` |
| Font scale | `useFontScale()->value/effective/display/for($size)`; `->clamp($min, $max)`; `->scaled($size, $max)` | RN `PixelRatio.getFontScale`, Flutter `TextScaler.clamp` |
| Per text | `allowFontScaling` (default true), `maxFontSizeMultiplier`, `numberOfLines` + `ellipsizeMode`, `adjustsFontSizeToFit` | RN `Text` |
| App-wide caps | `TextScalePolicy::set(maximum:, display:, displayFontSize:)` | theme-level caps |
| Pixels | `PixelRatio::get()/getFontScale()/roundToNearestPixel()/hairlineWidth()` | RN `PixelRatio` |
| Parent size | `onLayout(fn (LayoutEvent $e) => ...)` | RN `onLayout`; Flutter `LayoutBuilder` (see below) |
| PHP clamp | `AdaptiveLayout::clamp($extent, $fraction, $min, $max)` | CSS `clamp()` |

CSS (compiled to bytecode, re-evaluated natively on resize, no PHP render):
`%`, `vw`, `vh`, `vmin`, `vmax`, `dvh`/`svh`/`lvh`, `env(safe-area-inset-*)`,
`calc()`, `min()`, `max()`, `clamp()`, `aspect-ratio`, `@media (width >= 600px)`
and height/orientation queries. Prefer CSS for geometry: a resize never
re-renders PHP.

### Rebuild granularity
Every property of `useWindowDimensions()`, `useSafeAreaInsets()` and
`useFontScale()` is its own signal (`SharedSource::field()`): a component
reading `$window->width` is not re-rendered when the keyboard resizes the
window height, the insets change or the font scale changes. Read
`->metrics` only when you really need every field.

### Parent constraints (LayoutBuilder)
Layout runs in the native engine after PHP renders, so constraints are not
known while a template is built. The PAM equivalent is the RN one: lay out
with flex/percent/`aspect-ratio` first; when a value truly depends on the
parent size, store `onLayout` in state and render again (one extra frame,
only when the size changes). A synchronous `LayoutBuilder` would need the
engine to call back into PHP mid-layout and is intentionally not offered.

### Platform guards
- Android `minSdk` 26 (Android 8.0). Every API newer than 26 sits behind a
  `Build.VERSION.SDK_INT` check with a fallback; `lintDebug` (NewApi as an
  error) runs in CI.
- iOS deployment target from `pam-native.json` (`__PAM_IOS_MINIMUM__`);
  newer APIs behind `if #available` with a fallback.

## Checklist for screens
1. No fixed width >= 200 or height >= 120 on layout containers; use flex,
   `%`, `aspect-ratio`, window fractions with `clamp()`.
2. Fixed-height rows hold text with `numberOfLines` and ellipsis, or grow
   with `useFontScale()->scaled()`.
3. Sheets and stages bound by the window height minus insets and keyboard.
4. Test at 360x640 dp, at >= 450 dp width and at font scale 1.3.
