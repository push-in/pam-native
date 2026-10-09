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
| 1.28.0 | `KeyboardAvoidingView` inside modals | ✅ (uncompiled) | `PamModalHost` publishes the keyboard overlap in its own coordinate space (sheets: 0, they ride on the keyboard); the renderer re-expresses it from the root view bottom and the runtime feeds `pam_native_runtime_set_surface_keyboard_inset`, animating the new frames with the keyboard's duration and curve. Engine lays out `resize`/`padding`/`position`. `swift test --filter PamModalKeyboardAvoidingTests`; on a device, a full-screen modal with `behavior="padding"` keeps its bottom bar above the keyboard. |
| 1.17.0 | ext-mbstring polyfill + extension audit | ✅ (PHP only) | The iOS runtime is built with the same `--disable-all` extension set as Android (no mbstring, no intl), so the SDK's PHP `mb_*` polyfill applies unchanged; no Swift change. |
| 1.18.0 | Single-file prebuilt component cache | ✅ (uncompiled) | `PamBundle/pam-prebuilt/components/components.pack` replaces four files per component. The app bundle is read-only, so a component's class/template files are written on first use under `Documents/pam/state/prebuilt-components/<pack id>/` (other packs' directories are dropped). No Swift change: iOS runs `PamBundle/` in place, there is no bundle install to speed up. |
| 1.19.0 | Native crypto for `Pam\Native\Crypto` | ✅ (uncompiled) | `PamCrypto.swift` installs the `pam_native_crypto()` provider (CryptoKit `AES.GCM`, `Curve25519.Signing` after libsodium's S/small-order/canonical checks) from `PamRuntime`; `PamCryptoTests` replays `packages/native/tests/Fixtures/crypto-vectors.json`. |
| 1.19.1 | Inline `data:image/*` images and `<Image tintColor>` | ✅ (uncompiled) | Data URIs decode synchronously into their own `NSCache` (`PamInlineImages`) instead of staying empty; `tintColor` renders the bitmap as an `.alwaysTemplate` image in that color (Android `imageTintList`). `PamInlineImageTests`. |
| 1.20.0 | Per-surface safe areas | ✅ (uncompiled) | The iOS bridge sets the engine surface policy `0` (in-window): a `SafeAreaView` inside a full-screen/dialog `Modal` gets every window inset, inside a `BottomSheet` or a page/form-sheet route (`modal`, `formSheet`) no top inset. `PamSurfaceSafeAreaTests` pins each rule to UIKit's own `safeAreaInsets` for the same surface. |
| 1.29.0 | `appearance.firstFrame: "window"` | ✅ | The host skips the launch cover held until PHP's first frame (`PamFirstFrameWaitsForPHP` = NO in Info.plist); the window shows `PamAppearance.backgroundColor` at once. |
| 1.29.0 | Drag config parsing per list row | ✅ | `PamDragConfig.cached` memoizes `parse` by source (bounded `PamParseCache`). |
| 1.29.0 | Prepend/append prefetch ramp, spinner style, opcache stats, queued module calls | n/a | Android-only causes (RecyclerView extra layout space, ProgressBar theme AVD, the Android opcache file cache, module registry built on the UI thread). |
| 1.29.0 | Boot from the bundle's component pack listing | ✅ | PHP SDK change, shared by both hosts. |
| 1.29.1 | In-cell moves keep their views | ✅ | iOS `move` already re-attaches the existing views of a list cell. |
| 1.29.2 | A Modal's `StatusBar` reaches its window | n/a | Android-only cause (the Dialog window is created after the StatusBar commits and `enableEdgeToEdge` resets its icons); the iOS `StatusBar` node does not drive the status bar yet. |
| 1.30.0 | `aspect-ratio` with min/max (Yoga rules) | ✅ | Rust layout engine, shared by both hosts. |
| 1.30.0 | A Modal's navigation bar follows the app theme | n/a | Android-only (Dialog window navigation bar); iOS has no navigation bar. |
| 1.30.0 | `StatusBar` on iOS | ✅ (uncompiled) | `PamStatusBarCoordinator` resolves the active `StatusBar` nodes (visible route, open or opening modals) in mount order; `PamHostViewController`, `PamNativeViewController` and presented route controllers report `preferredStatusBarStyle`/`prefersStatusBarHidden`/`preferredStatusBarUpdateAnimation` and every change calls `setNeedsStatusBarAppearanceUpdate()` (animated with `animated`). `UIViewControllerBasedStatusBarAppearance` NO falls back to the application status bar like RN. `PamStatusBarTests`. |
| 1.30.1 | Heavy batches committed after the frame | ✅ (uncompiled) | `PamRuntime.deferHeavyCommitPastFrame`: a batch of 32+ mutations runs in a one-shot `beforeWaiting` run loop observer ordered after Core Animation's commit (2_000_001) instead of inside the display link tick. Android also defers any batch while a list scrolls and lays the dirtied lists out between frames (RecyclerView passes). |
| 1.30.1 | Runtime statistics read lazily | ✅ (uncompiled) | `RuntimeFrameMetrics.stats` is read from the engine on first access (`PamLazyRuntimeStats`); the public memberwise-style initializer is kept. |
| 1.30.1 | Spinner without ProgressBar, pooled TextView size reset | n/a | Android-only causes (the ProgressBar constructor's AnimatedVectorDrawable, a pooled TextView's stale width laying text out twice). |
| 1.32.1 | Modal backdrop hit test across flattened siblings | n/a | Android-only cause (`PamModalContent` observed only the first child's bounds). |
| 1.31.0 | App text scale | ✅ (uncompiled) | `PamTextScale` (UserDefaults) multiplies the Dynamic Type scale given to the engine in `PamNativeViewController.start/updateViewport`; `accessibility` module `textScale`/`setTextScale`. Values apply on the next launch, like Android. |
| 1.31.0 | Biometrics and secure storage | ✅ (uncompiled) | `BiometricsModule` (LocalAuthentication, `deviceOwnerAuthenticationWithBiometrics`; apps declare `NSFaceIDUsageDescription`) and `SecureStorageModule` (Keychain, `WhenUnlockedThisDeviceOnly`, SHA-256 account names) in `Modules/SecurityModules.swift`. |
| 1.31.0 | Named secure-screen claims | n/a | PHP only (`Screen::claim/release`); the native `window.secure` call is unchanged. |
| 1.32.0 | Focal pinch zoom (`gestureNativeFocalZoom`) | ✅ (uncompiled) | `PamNativeGestureTransform.apply(focal:pivot:)`: the pinch keeps the content point under `location(in:)` fixed around the child's untranslated `center`; an adjacent pan rebases while the surface is in `focalZoomTargets`. XCTest `testFocalPinchKeepsTheContentPointUnderTheFingersAndPanResumesFromIt`. |
| 1.32.0 | System photo picker for images/videos | ✅ (uncompiled) | `FilesModule` presents `PHPickerViewController` (filter images/videos/both, selection limit) and copies each `loadFileRepresentation` file before importing it; other types keep `UIDocumentPickerViewController`. |
| 1.33.0 | `Share::files()` text | ✅ (uncompiled) | `FilesModule.shareFiles` appends the text to the `UIActivityViewController` items. |
| 1.33.0 | `BottomSheetKeyboardBehavior::Contain` | ✅ (uncompiled) | `PamModalHost.setBottomSheetKeyboardBehavior(4)`: no sheet lift, the keyboard overlap is published as the surface keyboard inset. |
| 1.33.0 | `Location.watch` / `clearWatch` | ✅ (uncompiled) | `LocationModule` creates one `CLLocationManager` per subscription (`LocationWatch`, `distanceFilter`, `desiredAccuracy`), buffers fixes in `WatchChannel` and stops on `stop`/module close. Android registers a `LocationListener` per watch with `minTime`/`minDistance`. |
| 1.33.1 | Header-less `Files::download*` | ✅ (PHP only) | PHP sends `{}` for no headers; `FilesModule.downloadHeaders` decodes `[String: String]` and rejected `[]`. |
| 1.34.0 | Absolute insets from the padding box | ✅ | Rust layout engine, shared by both hosts. |
| 1.34.0 | Wrapping text measures the available width | n/a | Android-only (RN 0.85 `TextLayoutManager`); iOS keeps RN iOS `usedRect` (widest line). |
| 1.34.0 | `opacity` without offscreen compositing, `needsOffscreenAlphaCompositing` (527) | n/a | Android-only (RN `ReactViewGroup.hasOverlappingRendering`); iOS keeps group opacity like RN iOS. `PamConstants.needsOffscreenAlphaCompositing` exists for protocol parity. |
| 1.34.0 | AppCompat `Switch` geometry | n/a | Android-only; iOS uses `UISwitch` like RN iOS. |
| 1.34.0 | Edge-to-edge `BottomSheet` window | ✅ | iOS sheets already lie in the host window (`InWindow`), with the detent over `bounds.height - safeAreaInsets.top`. |
| 1.34.0 | Removed transform/opacity runs the transition | ✅ (uncompiled) | `resetProperty` calls `motion.animateTransition` to the default (translate 0, scale 1, rotate 0, opacity 1) before `applyTransform`; a removed `opacity` now resets `alpha` to 1. |
| 1.34.0 | `preloadSeconds` forward buffer | ✅ (uncompiled) | `PamMediaView.setForwardBufferSeconds` sets `preferredForwardBufferDuration` on the current and every new `AVPlayerItem`. Android uses Media3 ExoPlayer only for those players. |
| 1.34.0 | PHP lifetimes serialized, `PamRuntime` init order, `Trace.isEnabled` | n/a | Android-only causes (JNI bridge worker threads, Kotlin initialisation order, API 29 call). |
| — | `ScrollView` content size | ✅ (fix) | iOS never set `contentSize` for `<ScrollView>`; it now follows its children. |

## Validation on a Mac

Run `swift test` (or the `PamNativeTests` scheme on an iOS simulator) for
`ios/Package.swift`, then a generated app (`pam run ios`). New XCTest files
mirror the Android instrumented tests: `PamCssPaintTests`,
`PamCssEffectsTests`, `PamTextParityTests`, `PamLayoutParityTests`,
`PamComponentParityTests`, `PamErrorOverlayTests`, `PamStatusBarTests`.

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
- Crypto (1.19.0): `swift test --filter PamCryptoTests` must pass, in
  particular the two mixed-order Ed25519 vectors (they fail if CryptoKit's
  verification were cofactored) and the small-order ones; then a signed OTA
  manifest must be approved on a device and an `EncryptedJournal` sealed on
  iOS must open on Android and on desktop PHP.
- `StatusBar` (1.30.0): `swift test --filter PamStatusBarTests`; then in a
  generated app (Info.plist has `UIViewControllerBasedStatusBarAppearance`
  YES) a screen with `barStyle="dark-content"` shows dark icons in light mode,
  a full-screen `Modal` with `barStyle="light-content"` switches them to
  light while it opens and back as it closes, `hidden="true"` hides the bar
  (fading with `animated="true"`), a pushed route's bar replaces the one
  below it and the previous one returns on pop (also after an interactive
  back swipe), and a route presented as a full-screen modal keeps the bar it
  declares. With the key set to NO the same screens must drive the bar
  through the application API (no console warning about the key).
- App text scale, biometrics and secure storage (1.31.0): `swift build` and
  the module tests must pass; then `Accessibility::setTextScale(1.3, 1.6)` and a
  relaunch must enlarge every text (and its measured layout) by 1.3, Face ID /
  Touch ID must prompt from `Biometrics::authenticate()` (cancel answers
  `Cancelled`), and a `SecureStorage` value must survive a relaunch and be
  absent from UserDefaults.
- Focal pinch zoom (1.32.0): `swift test --filter PamNativeGestureTransformTests`;
  then in Ze Chat, Editar perfil → foto: pinching on a corner of the photo
  must zoom around the fingers (not the center), moving two fingers must
  carry the photo, and releasing out of bounds must glide back inside.
- Photo picker (1.32.0): Criar → post opens PHPicker with photos and videos
  (multi-select up to 20), loop opens it with videos only, cancelling returns
  an empty selection, and the picked files import with their real names.
- Share with text and contained sheets (1.33.0): `swift build` and tests must
  pass; `Share::files(['card.jpg'], 'image/jpeg', text: 'link')` must offer the
  image with the text (Messages/WhatsApp keep both); a `BottomSheet` with
  `keyboardBehavior="contain"` and a bottom composer inside a
  `KeyboardAvoidingView` must keep the sheet's top edge in place while the
  composer rises right above the keyboard.
- Renderer parity (1.34.0): `swift build` and `swift test` must pass
  (`PamRenderer.resetProperty` gained `where motion.animateTransition` cases);
  then in Zé Chat: a view with `transition: transform` whose transform class
  is removed must slide back (not jump), and toggling the class twice within
  a frame must not move it; removing `opacity` must restore full opacity; a
  feed/reel video with `preloadSeconds="12"` must play and keep buffering
  ahead (Network Link Conditioner "3G": fewer stalls than without it); badges
  with `position: absolute; top: 0; right: 0` inside padded avatars must sit
  on the avatar's edge like the React Native build; OptionDialog/BottomSheet
  detents and backdrops must match the RN screenshots.
- Header-less downloads (1.33.1): in Zé Chat, Perfil → Compartilhar must offer the profile card image (downloaded from `/api/share-cards/users/...` without headers) with the link text; no "Download request headers are invalid or unsafe" failure.
- Location watch (1.33.0): `swift build`; in Zé Chat share a live
  location for 15 min, walk ~30 m with the app open and confirm the bubble
  moves (PATCH within 3 s), backgrounding stops the updates and `stop`
  releases the location indicator.
- Heavy commits after the frame (1.30.1): `swift build` and the runtime tests
  must pass; then in Ze Chat, scrolling a chat until older pages load and
  swiping Reels must show each new page on the frame after it is ready (no
  blank cell, no flash), typing in the composer must echo in the same frame
  as before, and the dev tools overlay must still show commit statistics.
