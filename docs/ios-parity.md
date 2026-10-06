# iOS rendering parity (1.9.0)

PAM Native 1.2.0–1.7.0 shipped rendering, layout, typography, CSS and runtime
features on Android first. 1.9.0 brings the iOS host and renderer to the same
contract (iOS gestures, animations and native modules shipped in 1.8.0).

> **Status: written and reviewed on Linux without Xcode — uncompiled.** Every
> item below needs a build and the XCTest suite on a Mac (see *Validation*).

| Release | Feature | iOS | Notes |
| --- | --- | --- | --- |
| 1.2.0 | Per-side border colors | ✅ | Drawn above the children with CSS miter joins; uniform borders keep `CALayer.border`. |
| 1.2.0 | `text-shadow` | ✅ | Core Graphics shadow while drawing the glyphs. |
| 1.2.0 | `font-variant-numeric` / `font-feature-settings` | ✅ | OpenType feature settings on the CoreText font. |
| 1.2.0 | `text-align: justify` | ✅ | `CTLineCreateJustifiedLine`, last line of each paragraph natural. |
| 1.2.0 | `transform-origin`, `%` translate | ✅ | One composed transform (translate, rotate, scale around the origin); size-dependent parts update on layout. |
| 1.2.0 | Per-corner radii | ✅ | `cornerRadius` + `maskedCorners` when possible, a path mask otherwise. |
| 1.2.1/1.2.2 | Stale coalesced events | ✅ | Events of nodes removed before the frame are dropped. |
| 1.2.2/1.5.1 | Virtual list view pooling | ✅ | Scrolled-out cell views (view/row/column/text/image/pressable/spacer) are pooled by kind + authored property set and fully re-applied; images, text, pressed state and UIActions are cleared. |
| 1.5.1 | Image cache identity cost | ✅ | Identities hashed once per source; hex without `String(format:)`. |
| 1.3.0 | Linear/radial/repeating gradients | ✅ | Core Graphics, premultiplied stops, clipped to the radius. |
| 1.3.0 | `border-image` gradient ring | ✅ | |
| 1.3.0 | Multiple / inset / spread `box-shadow` | ✅ | `shadowPath` layers (cached by Core Animation); outer shadows of clipping views move to a sibling layer. |
| 1.3.0 | `filter` | ⚠️ images | Images: blur (σ, opaque edges) + color matrix via Core Image. Other views: debug diagnostic (no public API). |
| 1.3.0 | `backdrop-filter` | ⚠️ blur | `UIVisualEffectView` scrubbed to the radius; color functions log a diagnostic. |
| 1.3.0 | `<Shimmer>` | ✅ | `CAGradientLayer` sweep below the children. |
| 1.3.0 | `<Image blurRadius>` | ✅ | |
| 1.3.0 | Modal/BottomSheet `backdropColor` | ✅ | Also `animationType`, `transparent`, swipe dismissal. |
| 1.4.0 | Exact text measurement | ✅ | CoreText measurer installed in the engine before the first frame. |
| 1.4.0 | Line height + `fontScale` (1.6.1) | ✅ | Line height scales with `fontScale`. |
| 1.4.0 | Nested spans + per-span press | ✅ | One paragraph; `SpanPress` with the slot. |
| 1.4.0 | `on:layout` | ✅ | Relative to the parent, coalesced per frame. |
| 1.4.0 | Engine safe areas (+1.6.1 race fix) | ✅ | Boot insets set before the worker starts; native SafeAreaView insets disabled once the engine owns them; `PAM_BOOT_METRICS` exported. |
| 1.4.0 | `appearance.splash` | ✅ | `UILaunchScreen` from `App/PamLaunch.xcassets` (PNG logo, light/dark background); the host keeps the logo until the first frame. WebP logos are Android-only. |
| 1.4.0 | Hairline / PixelRatio | ✅ | PHP side; iOS reports `density = UIScreen.scale`. |
| 1.5.0 | `on:textLayout`, marquee | ✅ | Reported from the CoreText layout that draws the text; `ellipsizeMode="marquee"` scrolls the drawn line. |
| 1.5.2 | Content-sized cells, scroll-to-end | ✅ | Frames come from the engine; scroll targets use the real viewport; a list resting at its end stays there. |
| 1.6.0 | `<Icon>` | ✅ | Packaged icon font through `asset://`. |
| 1.6.0 | Sticky headers | ✅ | ScrollView and VirtualizedList (the pinned cell stays mounted). |
| 1.6.0 | `fullSpan` | ✅ | Engine layout. |
| 1.6.0 | Pressed `translate` | ✅ | Points, composed with `pressScale`. |
| 1.6.0 | Media `loadStart`/`buffering`/`ready` | ✅ | `MediaReadyEvent` natural size + duration. |
| 1.6.0 | `Toast::message()` | ✅ | react-native-toast-message styled card. |
| 1.6.0 | `ScrollView keyboardInset` | ✅ | |
| 1.6.0 | `overflow: hidden` inside the radius | ✅ | Border ring stays above clipped content. |
| 1.6.0 | Bottom sheet slide + fade, `%` snap points minus top inset | ✅ | Also `animationType="slide-fade"` for modals. |
| 1.6.0 | Font family conventions | ✅ | `{Family}-{Weight}`, `_weight`, `_bold/_italic`, `{Family}` under `pam/assets/fonts` and `pam/fonts`. |
| 1.7.0 | Error overlay | ✅ | Toast + inspector, Dismiss/Copy/Reload, queue/counter, release fallback with retry, `devErrorOverlay` (`PamDevErrorOverlay` in Info.plist). |
| 1.14.1 | Interactive pinned sticky headers | ✅ (uncompiled) | ScrollView and VirtualizedList/VirtualGrid hosts hit-test the sticky children before the rows (`zPosition` only reorders drawing): presses reach the pinned header and never the row under it. `testPinnedVirtualListHeaderReceivesTouchesInsteadOfTheRowUnderIt`. |
| 1.14.2 | Moved views stay attached | ✅ (uncompiled) | `move()` keeps a view in its superview when its sibling position is unchanged, so a sheet moved by a closing overlay before it keeps its focused `autoFocus` field and keyboard. `testMoveThatKeepsThePositionLeavesTheFocusedInputInPlace`. |
| 1.15.0 | Splash held until the first PHP frame | ✅ (uncompiled) | The launch-screen cover is always installed (logo optional), hidden on the first committed frame, a fatal error or after 4 s; the first batch of a host mounts on arrival instead of the next display-link tick; PHP boots with the window safe area when the root view has not been laid out yet. |
| 1.16.0 | Share Extension activation rule from `plugins.shareExtension` | ✅ (uncompiled) | Prepare writes `NSExtensionActivationRule` from the accepted MIME types (dictionary for text/URL, images, movies and `*/*`; `SUBQUERY` predicate for specific file types). Validate on a Mac that the extension is offered only for the configured types. |
| 1.17.0 | ext-mbstring polyfill + extension audit | ✅ (PHP only) | The iOS runtime is built with the same `--disable-all` extension set as Android (no mbstring, no intl), so the SDK's PHP `mb_*` polyfill applies unchanged; no Swift change. |
| 1.18.0 | Single-file prebuilt component cache | ✅ (uncompiled) | `PamBundle/pam-prebuilt/components/components.pack` replaces four files per component. The app bundle is read-only, so a component's class/template files are written on first use under `Documents/pam/state/prebuilt-components/<pack id>/` (other packs' directories are dropped). No Swift change: iOS runs `PamBundle/` in place, there is no bundle install to speed up. |
| — | `ScrollView` content size | ✅ (fix) | iOS never set `contentSize` for `<ScrollView>`; it now follows its children. |

## Validation on a Mac

Run `swift test` (or the `PamNativeTests` scheme on an iOS simulator) for
`ios/Package.swift`, then a generated app (`pam run ios`). New XCTest files
mirror the Android instrumented tests: `PamCssPaintTests`,
`PamCssEffectsTests`, `PamTextParityTests`, `PamLayoutParityTests`,
`PamComponentParityTests`, `PamErrorOverlayTests`.

Visual checks that tests cannot fully cover:

- `text-shadow` direction (positive `y` must go down) and blur strength.
- Text measured vs. drawn (no clipped descenders, same line breaks) with
  `lineHeight`, emoji, `fontScale` at the largest accessibility size.
- Backdrop blur strength vs. Android, and that it survives app
  background/foreground.
- Sticky headers in a chat list, and a pinned VirtualizedList header with a
  pressable tab rail: taps on the pinned tabs press them (pressed state
  visible, VoiceOver reaches them), never the row underneath; shadows of
  `overflow: hidden` cards while
  pressed (sibling shadow follows the press scale only at rest).
- Splash: logo size/position matches between the launch screen and the
  first-frame overlay, in light and dark.
- First launch after deleting and reinstalling the app (1.18.0): every
  screen renders; `Documents/pam/state/prebuilt-components/` holds one
  directory with `<key>.class.php`/`<key>.template.php` files only for the
  components shown; a second launch writes nothing there; after installing a
  build with changed components the old directory is gone.
- Cold start (1.15.0): no frame between the launch screen and the first PHP
  frame, with and without `appearance.splash.logo`; the first frame already
  has the notch/home-indicator safe area (no inset jump right after launch);
  a re-attached `PamNativeViewController` mounts its tree without waiting a
  display-link tick; a missing entry still shows its alert (splash removed).
- mbstring (1.17.0): confirm the iOS runtime still has no ext-mbstring
  (`runtime-builder/ios/build.sh` keeps `--disable-all` without
  `--enable-mbstring`) and that a screen calling `mb_strlen()`/`mb_substr()`/
  `mb_strtoupper()` on accented text and emoji renders the same values as on
  Android; `pam-native build ios` prints no extension-audit warning for the
  app.
