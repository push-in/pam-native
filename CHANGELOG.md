# Changelog

## 1.23.0 - 2026-10-06

- Notification action endpoints authenticate per account while the app is
  killed: `ActionEndpoint::bearerFromCredential('user_id', 'recipient_user_id')`
  resolves natively the token of the account named by the push data, from the
  new `Pam\Native\Notifications\NotificationCredentials` store
  (`set`, `remove`, `sync`, `clear`).
- Android seals credentials with an Android Keystore AES-256-GCM key; iOS keeps
  them in the Keychain (this device only, after first unlock). Slots are keyed
  by a SHA-256 of the account id.
- A push whose account has no credential sends nothing and is dismissed;
  `NotificationAction::$credentialMissing` reports it. Credentials resolve only
  in headers.
- Android JVM and emulator (API 35) tests cover resolution, Keystore sealing,
  the per-account request and the dismissed no-credential case; the iOS
  XCTests need Mac validation. Rebuild the native hosts; see
  [migration notes](docs/migration-1.23.0.md).

## 1.22.7 - 2026-10-06

- Android scroll views consume an authored content offset once that axis reaches
  its requested position. Later child layouts or insertions no longer replay a
  completed initial offset after the user reaches the last page or scrolls away.
- Requests clamped by insufficient content remain pending until content grows
  enough to reach them. Explicit property changes can request another position;
  end anchoring, keyboard clearance and tokenized scroll requests retain their
  existing contracts.
- Three regressions fail on 1.22.6 and pass with the correction on Android
  APIs 26 and 36; the twelve existing scroll-container regressions also pass.
  Final application swipe/zoom acceptance is separate from these native tests.
- No PHP API, numeric protocol identifiers or dependency requirements changed.
  Rebuild the Android host; see [migration notes](docs/migration-1.22.7.md).

## 1.22.6 - 2026-10-06

- Prebuilt PHP class/template caches recover empty or truncated derived files
  from their unchanged bundle pack on first materialization. Complete caches
  remain untouched; repairs use atomic publication and invalidate only the
  repaired opcode. Failed pack reads remain retryable without adding checks
  to the render hot path.
- Native module completions remain tied to the PHP request that issued them.
  Android and iOS bridges discard callbacks from a previous request, including
  cleanup callbacks and late asynchronous results after hot reload. Internal
  opaque callback IDs prevent reused PHP IDs from consuming unrelated results;
  the public module and wire contracts remain unchanged.
- Compiled image handlers typed as `ImageLoadEvent`, `ImageErrorEvent` or
  `ImageProgressEvent` receive decoded event objects instead of wire strings.
  Direct handlers, explicit event arguments and generated/interpreted template
  expressions share the existing decoder. String, untyped and zero-argument
  handlers retain their contracts; forwarded event objects retain identity.
- Android applies initial rich-text effects once after the complete property
  pass, avoiding repeated content, font, sizing and letter-spacing work while
  native cells materialize. Incremental mutations remain immediate.
- Android batches initial PamContainer background requests and applies the
  final authored colors once. Native controls, ImageBackground and custom views
  keep their existing paths; drawable, ripple, clipping and shadow behavior
  are unchanged.
- Regression coverage exercises actual compiled image callbacks and checks
  text assignments/spans, background pixels, interaction state and incremental
  updates. Local application samples showed less initial main-thread work;
  they do not establish sustained 60 FPS or remove cold shader costs.
- No protocol identifiers or dependency requirements changed. Rebuild the
  application bundle and Android host; see
  [migration notes](docs/migration-1.22.6.md).

## 1.22.5 - 2026-10-06

- Android virtual lists retain native cells when stable keyed rows move within
  the same list. Inserting loading headers or prepending pages no longer
  destroys visible text, decoded images or active row state before RecyclerView
  applies its position changes.
- Regression coverage checks view and decoded-image identity during header
  insertion and pagination, and a delayed native image request without PHP
  callbacks. Moves between different lists retain their existing lifecycle.
- Native Android gestures take over the currently displayed transform instead
  of competing with an unfinished CSS zoom or translation. Independent opacity
  animations continue, and the decoded image remains mounted.
- Adjacent native pan/pinch detectors share their content surface; an explicit
  intervening View keeps independent targets. The iOS renderer implements the
  same shared-target and component-preserving transform contract. UIKit tests
  are provided; iOS execution requires an Xcode host.
- Optional `GestureEvent::nativeScale`, `nativeTranslationX` and
  `nativeTranslationY` report the applied native transform; existing relative
  gesture deltas and positional constructor parameters remain compatible.
- Rebuild the Android host after updating. See
  [migration notes](docs/migration-1.22.5.md).

## 1.22.4 - 2026-10-06

- Android keyboard-aware scroll views follow keyboard animations and native
  focus changes while preserving the authored viewport height. The existing
  `keyboardVerticalOffset` adds focused-field clearance to `keyboardInset`.
- Repeated unchanged keyboard insets and keyboard dismissal preserve manual
  scrolling. Deferred reveal requests ignore stale insets and old focus targets;
  removing a scroll view or its keyboard-aware property releases its observers.
- Android 26–29 detects the real keyboard while the Activity keeps
  `adjustNothing`. Scroll and keyboard-avoiding overlays share one invisible,
  non-interactive measurement window per root, released when unused. Dialogs
  retain their own compatible window-inset path.
- No PHP API, protocol identifiers or dependency requirements changed. See
  [migration notes](docs/migration-1.22.4.md).

## 1.22.3 - 2026-10-06

- Android media observes its route view lifecycle after attachment. A player
  created before the route resumes can start when it becomes visible, while
  host backgrounding, route suspension and manual pause remain respected.
- Initial press and gesture properties are configured together, avoiding
  repeated gesture reconstruction for every property of virtualized rows.
  Subsequent property updates retain their immediate behavior.
- Android packages TTF/OTF font assets without ZIP compression, preserving
  their bytes and allowing the platform to map them directly.
- Android hot reload retains the executing PHP bundle until request shutdown
  finishes. Edits and restores are serialized around that acknowledgement;
  old files remain available for lazy autoloads and obsolete bundles are removed.
- No PHP API, protocol identifiers or dependency requirements changed.

## 1.22.2 - 2026-10-06

- Device bundles omit the official SDK's host sources and documentation while
  retaining PHP, resources and Composer dependencies. Fresh native/native-ui
  templates stay within the development hot-reload limit without manual ignores.
- Pan gestures honor an explicit minimum hold duration before activation.
  The default zero-duration behavior remains immediate. This enables media
  reordering after a hold without dispatching move events to PHP beforehand.
- Android media keeps playback intent separate from host/attachment visibility.
  A late prepare or playback-rate update cannot start sound in the background;
  native pause controls remain respected after resume and hidden views pause.
- Drag can update a referenced numeric/clock label on the native UI thread,
  follow the absolute touch position and retain a continuous release position.
  Existing drag contracts keep their defaults. Android and iOS share the API;
  iOS source validation requires a macOS/Xcode host and was not run here.
- No protocol identifiers or dependency requirements changed.

## 1.22.1 - 2026-10-06

- Navigation sends removal notifications when route instances are actually
  released, including reset, stack pruning and interrupted transitions. Kept-alive
  routes retain their state while parked; ordinary pushes do not remove them.
- Android `p-intersect` observes the drawn viewport through scrolling, detach and
  reattach. It emits only visibility transitions, allowing media to release
  decoders outside the viewport without PHP callbacks for every scroll pixel.
- `ImageEditor::render(imageLayers: ...)` composes private bitmap overlays,
  including a static GIF frame, with normalized geometry and bounded sequential
  decoding. Existing calls remain compatible; Android and iOS implementations
  share the contract. iOS source is included, but was not executed on this Linux host.
- Android development accepts `PAM_NATIVE_ANDROID_DEV_PORT` for concurrent
  projects. ADB maps the unchanged device port to the selected host port.
- No protocol additions or dependency changes.

## 1.22.0 - 2026-10-06

App icons, a React Native-style splash and a shorter cold start. Zé Chat on a
Galaxy S10 (Android 12, dark mode) showed ~1.7 s of a dark splash with no
logo while React Native showed its own splash: the app declared no splash
logo, and Android 12 draws the launcher icon there, which could only be PAM's
generic icon.

- `android.icon` (new): adaptive launcher icon from `foreground`,
  `background` (colour or image) and `monochrome` layers. The manifest icon
  is now `@drawable/pam_launcher`; without `android.icon` it is the bundled
  PAM icon as before. Notification small icons keep `pam_icon`. See
  [App icon](docs/app-icon.md).
- `ios.icon` (new): a 1024x1024 PNG written as `AppIcon.appiconset` with
  `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon`. iOS uncompiled on the
  release machine; needs device validation.
- `appearance.splash.android12Icon` (new): `"appIcon"` keeps the launcher
  icon on the Android 12+ splash (no `windowSplashScreenAnimatedIcon`), the
  way a React Native/Expo theme does, and the logo only paints the API 26-30
  starting window, so `size` may reach 288 dp (an xxhdpi 864 px bitmap).
  Default `"logo"` is unchanged.
- Android cold start: PHP boots right after the runtime is created. The
  native module registry (~13 ms of UI-thread work on the S10) is built while
  PHP boots; a module call that arrives earlier waits for it. Edge-to-edge
  and the bar colours, which do not change the boot metrics, are applied
  after the launch. The error overlay (~30 views, ~7 ms) is built the first
  time an error needs it.
- iOS: `PamErrorOverlay` builds its toast, inspector and fallback on first
  use. The module registry still starts with the runtime (the launch order
  differs from Android).
- Note for measurements: Android 12 shows the splash icon only for launches
  from the launcher; `adb shell am start` and notification launches get a
  plain `splashBackground` for every app.

## 1.21.0 - 2026-10-06

Keep-alive routes give a stack navigator React Navigation tab-screen
lifetime, and four Android fixes from the Zé Chat device QA on a Galaxy S10:
carousels page one page per fling, a swipe past the last loop no longer
pauses it, and decoded images and video players stop blocking scroll frames.

- Navigation: `Route::screen(...)->keepAlive()` (`Router::keepAlive()`). An
  app that draws its own bottom bar and switches tabs with `navigate()` lost
  the Feed scroll offset on every tab switch: the popped route was destroyed
  and rebuilt at offset zero. A kept-alive entry removed from the stack
  (`navigate()`, `pop()`, `popToTop()`, `replace()`) is now parked mounted
  and hidden, and a push or navigate with the same params reuses it with its
  state, native views and scroll offsets, without a visible jump. Kept-alive
  entries deeper in the stack stay mounted too. An optional `RouteContext`
  predicate limits it to some entries (the own-profile tab, not visited
  profiles); at most three entries per route are parked and `reset()`
  releases them. Parked screens get blur/focus events, not
  `navigationRemoved()`. PHP tests cover parking, reuse, deep entries,
  params and the predicate; instrumented tests cover the native host.
- iOS, navigation: `PamNavigationHost.insert()` reorders a moved route view
  instead of ignoring the move, so a parked route coming back on top is the
  transition destination (Android already moved it). Uncompiled here: needs
  Mac validation.
- Android, paging: Pressable pages consume `ACTION_DOWN`, so a paging
  `HorizontalScrollView`/`ScrollView` first saw the gesture at the
  intercepted move and paged from the dragged offset. A 700 px/250 ms fling
  on a feed carousel skipped two pages; a stale fling flag also skipped the
  snap of a later slow drag. The start page is now recorded in
  `onInterceptTouchEvent`. iOS already reads it in
  `scrollViewWillBeginDragging`.
- Android, lists: at its boundary a virtualized list released every drag to
  its parent even with no scrollable ancestor, so a row Pressable received
  the swipe as a press: each swipe up on the last Zé Chat loop toggled it to
  paused. The list now hands the drag only to an ancestor that can still
  scroll that way; otherwise it keeps it (overscroll), like React Native.
- Android, images: decoded bitmaps call `prepareToDraw()` on the decode
  thread, so the RenderThread texture upload (8.5 ms for a 1080x2042 photo)
  no longer lands in the scroll frame that first draws them.
- Android, video: `MediaPlayer` creation, data source, surface attach and
  detach, commands, progress polling and release run in order on one worker
  thread without a Looper (callbacks stay on the main thread). A feed settle
  that swapped the playing video blocked the UI thread 95-97 ms in binder
  calls to mediaserver. iOS is unaffected: AVPlayer item loading is already
  asynchronous.

Verified on the S10 (Zé Chat QA debug build, feed cold pass of 8 flings):
the Feed keeps its offset across tab switches with no visible jump, and the
remaining cold-pass jank is cell binding of the debug (JIT) build.

## 1.20.2 - 2026-10-06

`MediaPlayer` on Android keeps the video's aspect ratio and a paused player
stays paused. In Zé Chat on a Galaxy S10 a square (720x720) loop with
`resizeMode="contain"` filled the whole portrait screen, stretched and
upscaled (React Native letterboxes it), and the warm neighbour of the Loops
pager (`autoPlay` false, muted) decoded and ran next to the visible loop:
`dumpsys media.player` listed both players RUNNING.

- Android, fit: the `TextureView` fills the player box, so every frame is
  already stretched to the box; `resolveVideoScale` applied the fit scale on
  top of that. The view scale is now the displayed size divided by the box:
  `contain` letterboxes the whole frame, `cover` fills the box and crops the
  overflow (it over-zoomed one axis), `fill` keeps the stretch and `center`
  shows native pixels. Unit tests cover each mode.
- Android, paused players: `MediaPlayer.setPlaybackParams` with a non-zero
  speed starts a prepared or paused player, and every prepare applied the
  playback rate. The rate is applied only when it differs from 1, and a player
  that was not playing is paused again. Verified on the S10: one RUNNING
  player per Loops page, the warm neighbour PREPARED.
- iOS: unchanged. `AVPlayerLayer.videoGravity` already fits correctly and
  `AVPlayer.rate` is only set while playing.

## 1.20.1 - 2026-10-06

A property that gives a node inside a virtualized-list cell its own native
view, or takes it away, no longer rebuilds the whole cell on Android. In Zé
Chat on a Galaxy S10 a double tap on a feed post liked it and its like button
started its pop animation: that View had been flattened (no host property
until the animation appeared), so the renderer dematerialized and rebuilt the
entire post card. The media flashed for a frame, a carousel jumped back to
page 1 while its dots stayed on page 2, the header's "⋯" could vanish and the
double-tap heart, already playing on the UI thread, was dropped.

- Android: when a node's hosting flips (`update()` of a host property such as
  `animation`, `nativeRef`, `opacity` on a layout-only View) inside a mounted
  cell whose root is hosted, only that node is promoted or demoted in place
  (`promote()`/`demote()` with the hosted insertion index and the cell layout).
  The rest of the cell keeps its decoded images, players, scroll offsets and
  running motions. The cell is still rebuilt when the node is the cell root,
  the cell root is itself flattened, or the cell is not mounted
  (`virtualCellHostingRepair`, unit-tested). Verified on the S10: the
  double-tap heart plays its full ~700 ms and a carousel keeps its page.
- iOS: unchanged. It never flattens views, so the same update only applies
  the property to the existing view.

## 1.20.0 - 2026-10-06

Safe areas are per presentation surface. On Android a full-screen `Modal`
that was not `statusBarTranslucent`/`navigationBarTranslucent` showed an
extra status-bar-tall gap at the top (and a doubled gap above the navigation
bar): its Dialog window already starts below the status bar, and the
`SafeAreaView` inside it padded the activity window's insets again. Zé Chat
worked around it by making its modals translucent.

- Engine: every `Modal`/`BottomSheet` is laid out on its own surface
  (`surface.rs`). The surface viewport and the insets its `SafeAreaView`s
  see come from the window insets and a host surface policy,
  `pam_native_engine_set_surface_policy` (`SurfacePolicy`): `0` in-window
  (iOS) - full-screen and dialog modals get every inset; `1` system windows
  (Android 14 and older) - a non-translucent Dialog fits the system bars, so
  its viewport excludes them and its insets are zero, a translucent one is
  edge-to-edge and gets the real insets; `2` edge-to-edge windows (Android
  15+ with target SDK 35+, where `setDecorFitsSystemWindows(true)` is
  ignored) - every Dialog gets the real insets. Bottom sheets never get the
  top inset (snap points resolve below the status bar), and neither does the
  active route of a navigation `formSheet` (and, on iOS, `modal`, a page
  sheet). `position: fixed` inside a modal now anchors to its surface.
  Incremental layout keeps the surface insets.
- Android: the host derives the policy from the release and target SDK
  (`modalWindowSurfacePolicy`) and passes it to the engine before the first
  frame. iOS: the bridge sets policy `0` explicitly.
- Tests: Rust `surface::tests` and layout tests (fitted, translucent,
  enforced edge-to-edge, sheets, content-sized dialogs, incremental relayout,
  sheet routes, FFI). Android `PamSurfaceSafeAreaInstrumentedTest` lays
  modals out with the real engine (debug-only JNI `PamEngineLayoutProbe`) and
  shows them in real Dialog windows: on API 31 the fitted window starts at the
  status bar and the header sits there once (it was twice); on API 36 the
  window is edge-to-edge and the `SafeAreaView` pads once; translucent and
  not, full screen and sheet, top and bottom. JVM `PamModalSurfacePolicyTest`.
  XCTest `PamSurfaceSafeAreaTests` (uncompiled on this release host).
- Apps: a translucent modal wrapped in `SafeAreaView` keeps rendering the
  same; the translucency is no longer needed to avoid the doubled gap.

## 1.19.1 - 2026-10-06

Inline icon images (`data:image/*`, the masks behind an app's icon
component) paint in the frame that lays them out and can no longer vanish.
In Zé Chat on a Galaxy S10 (Android 12), the tab bar "Buscar" magnifier, the
Inbox search field magnifier and the ▶ badge of explore videos sometimes
stayed blank until the screen was rebuilt, and a reopened Profile painted its
first frame with no icons at all.

- Cause (Android): a decode was shared through
  `ConcurrentHashMap.computeIfAbsent` and removed itself from the map in its
  completion stage. A tiny inline decode on the idle inline lane can finish
  before `computeIfAbsent` returns; the completion stage then runs inside the
  map update, `remove()` throws "Recursive update", and the future stored for
  that key is already failed and never removed. While the bitmap stayed in
  memory the 32 ms retry hid it; once photos evicted it (inline glyphs shared
  the 32 MiB photo cache), every later load of that key (same source, same
  64 px bucket: the 22 dp tab magnifier and the 20 dp field magnifier share
  one) failed through the poisoned future, and after two retries the image
  stayed empty for good.
- Android: one load per decoded key is registered with `putIfAbsent` before
  its work starts and unregisters itself by identity when it completes, on
  any thread (`InFlightImageLoads`); a failed load is never reused. Inline
  sources up to 16 KiB decode synchronously on the UI thread in the layout
  pass (well under a millisecond for an icon mask), so an icon is in the
  first frame like a font glyph; they live in their own 4 MiB memory cache,
  which photos cannot evict and only a critical memory trim clears.
- iOS: `data:image/*` sources were not decoded at all (the image stayed
  empty) and `tintColor` on `<Image>` was ignored. Inline images now decode
  synchronously into their own cache (`PamInlineImages`), and `tintColor`
  renders the bitmap as a template in that color (Android `imageTintList`).
  Uncompiled on this release host; listed in `docs/ios-parity.md` for Mac
  validation.
- Tests: `InFlightImageLoadsTest` (a load finishing before `share` returns,
  a failed load, a throwing start, a shared pending load, the synchronous
  inline threshold) with `NativeImagePriorityTest`; XCTest
  `PamInlineImageTests`. Verified on the Galaxy S10 with Zé Chat: the
  reopened Profile, the tab bar and the Inbox paint every icon in their
  first frame.

## 1.19.0 - 2026-10-06

The Android and iOS PHP runtimes have neither ext-sodium nor ext-openssl, yet
the SDK called them directly: `Update\UpdateVerifier` (Ed25519 signature of
signed OTA manifests) and `LocalFirst\EncryptedJournal` (AES-256-GCM) died
with "Call to undefined function" on the device only. Both now go through
`Pam\Native\Crypto`, which the native host backs on Android and iOS with the
same bytes and decisions as libsodium and OpenSSL.

- PHP SDK: `Pam\Native\Crypto::ed25519Verify()`, `aes256GcmEncrypt()`
  (ciphertext . 16-byte tag) and `aes256GcmDecrypt()` (null when not
  authentic). ext-sodium/ext-openssl are used when loaded (desktop, server,
  tests); otherwise the host function `pam_native_crypto()`. With neither
  (a device host older than 1.19.0) they throw `CryptoUnavailableException`:
  `UpdateVerifier` refuses the update with `InvalidSignature`, and
  `EncryptedJournal` throws. Envelopes and signatures are unchanged: a journal
  sealed on desktop opens on a device and back.
- Android: `PamCrypto.kt` behind `PamRuntime.onNativeCrypto` (JNI, synchronous
  on the PHP worker). AES-256-GCM through the platform JCA/Conscrypt;
  Ed25519 verified in Kotlin on every API level with libsodium's rules
  (canonical S, no small-order R or public key, canonical public key,
  cofactorless equation); the platform Ed25519 (API 33+) skips the
  small-order checks, so it is not used. The 76 vectors take 235-291 ms on
  x86_64 emulators (under 10 ms per full verification). R8 keeps the callback.
- iOS: `PamCrypto.swift` installs the provider from `PamRuntime`: CryptoKit
  `AES.GCM`, and `Curve25519.Signing` after libsodium's encoding checks.
  Uncompiled here (Linux); `PamCryptoTests` to run on a Mac
  (`docs/ios-parity.md`).
- Why not compile the extensions: measured with the runtime's NDK flags,
  libsodium 1.0.20 is +267 KB (arm64-v8a) / +406 KB (x86_64) of code per ABI
  before ext-sodium's glue and its AES-256-GCM needs ARMv8 Crypto/AES-NI;
  OpenSSL 3.5's libcrypto is +4.2 MB per ABI. Either also needs a new PAM
  runtime and an iOS XCFramework rebuilt on a Mac.
- CLI: the extension audit now scans the SDK like any package (its own
  sources are clean and `tests/runtime_audit.php` keeps them so) and names the
  device-ready replacement in findings, e.g. `sodium_crypto_sign_verify_detached()`
  → `Pam\Native\Crypto::ed25519Verify()`, `openssl_encrypt()` →
  `aes256GcmEncrypt()`, `sodium_bin2hex()` → `bin2hex()`.
- Not covered: `Store\EncryptedStatePersistence` (XSalsa20-Poly1305
  secretbox) still requires ext-sodium and throws on the device, as before.
  HMAC/HKDF need nothing: `hash_hmac()`/`hash_hkdf()` are core.
- Tests: `tests/Fixtures/crypto-vectors.json` (`scripts/generate-crypto-vectors.php`:
  RFC 8032, GCM spec case 16, 76 Ed25519 and 48 AES-256-GCM vectors including
  S + L, every small-order encoding with and without the sign bit,
  non-canonical and off-curve keys, and mixed-order keys that separate
  cofactored from cofactorless verification) is replayed by
  `tests/native_crypto.php` (extensions, a fake of the host function, seeded
  random cross-checks, journal and OTA interop, fail-closed paths), the
  Android JVM test `PamCryptoTest`, the instrumented `NativeCryptoInstrumentedTest`
  and `NativeCryptoBridgeInstrumentedTest` (PHP → JNI → Kotlin through the
  real runtime) and the iOS `PamCryptoTests`. `tests/device/crypto_runtime.php`
  runs inside the Android PHP runtime. Passing on Android 8.0 (API 26) and
  Android 16 (API 36) x86_64 emulators.

## 1.18.0 - 2026-10-06

First launch after installing or updating an Android app: the PHP bundle is
one asset and the prebuilt component cache is one file. Zé Chat on a Galaxy
S10, release-optimized `.perf` build, process start → first commit (fresh
install each run; the phone was shared with other apps, so runs vary):

| | 1.14.0 | 1.18.0 |
|---|---|---|
| Bundle install (`bundleInstallMs`) | 1,720-2,167 ms | 164-278 ms |
| First launch after install | 2,172-2,683 ms | 453-681 ms |
| Normal cold start | 268-322 ms | 233-313 ms |
| Files created on device by the install | 2,165 | 1,261 |
| APK size | 69.1 MB | 67.9 MB |

- Android CLI: `pam-native build` writes `assets/pam-bundle.pnb` instead of
  ~2,000 `assets/pam/**` PHP assets (and drops 1.14's `pam-files.txt`): an
  index (`<sha256> <size> <p|a> <path>`, `c <compressed> <size>` per chunk)
  and the PHP code (`*.php`, `*.pam`, `pam-prebuilt/`) as independently
  raw-deflated chunks of ~512 KB, stored uncompressed in the APK
  (`noCompress "pnb"`). Images, fonts, CSS and every other file stay plain
  `assets/pam/` entries (`asset://`, fonts and `Files.copyAsset` read them
  from the APK as before) and are listed in the index with their SHA-256.
- Android host: the install starts with the process (`PamBundleInstallProvider`,
  before `Application.onCreate` and the Activity) instead of after the
  Activity is created. One sequential read of the pack feeds compressed
  chunks to 2-4 workers that inflate them and verify each file's size and
  SHA-256 against the index before writing it; plain assets are copied on
  their own thread; directories are created on first use. The index parser
  is hand-written (Regex parsing alone cost ~110 ms on a cold process).
  `PamStartup` (1.15.0) now waits for that install instead of running it.
  Staging, content-addressed activation, release pruning, OTA bundles and
  hot reload are unchanged. Debug/benchmark builds log `bundleInstallMs`
  (the unpack itself) and `bundleWaitMs` (how long startup waited for it).
- PHP SDK: `PamPhpCompiler::prebuild()` writes one
  `pam-prebuilt/components/components.pack` (`PNC1`, JSON index with every
  component's metadata, then class and template sources) instead of four
  files per component (904 files in Zé Chat). A component's class and
  template file (closures + template JSON in one opcode-cached file) are
  written from the pack the first time they are used: beside the pack when
  the bundle is writable (Android, per release), otherwise under
  `PAM_NATIVE_STATE_DIR` (a read-only iOS app bundle), keeping only the
  current pack's files. 1.14 prebuilt directories still load.
- iOS: the app runs `PamBundle/` in place from the signed app bundle, so it
  has no install step to speed up; it gets the same single-file prebuilt
  cache (one bundle file instead of four per component). Uncompiled on this
  release host; listed in `docs/ios-parity.md` for Mac validation.
- What remains of the gap to a normal cold start: creating ~1,260 files on
  f2fs + file-based encryption (~135 µs each, serialized by the kernel, so
  more writer threads do not help) and the opcode compilation + file-cache
  writes of the first PHP run (~130 ms).

## 1.17.0 - 2026-10-06

The mobile PHP runtime has no ext-mbstring (and no intl), so `mb_*` calls in
apps and community packages (`pam-native-calls`'s `Calls::text()`) died with
"Call to undefined function" on the device only; Zé Chat carried its own
partial polyfill. PAM Native now covers it in the SDK and warns at build time
about the extensions it cannot cover.

- PHP SDK: `src/Polyfill/mbstring.php` is autoloaded by Composer before any
  application code and defines 41 `mb_*` functions (every one in common use:
  length/width, substr/strcut/str_split/strimwidth/str_pad/trim, the strpos
  and strstr families, case conversion with all `MB_CASE_*` modes, ucfirst,
  convert_encoding/variables, detect_encoding, check_encoding/scrub,
  ord/chr, numeric entities, settings) and the `MB_CASE_*` constants, each
  only when missing: a real ext-mbstring or an app's own polyfill loaded
  first wins. `Pam\Native\Polyfill\Mbstring` follows PHP 8.5's
  ext-mbstring, including malformed input, substitution modes, full Unicode
  case mapping (final sigma, Turkish ISO-8859-9), East Asian widths and the
  same `ValueError`s, for UTF-8/16/32, UCS-2/4, ASCII, 8bit and every
  single-byte code page. Not covered: SJIS/EUC/BIG-5/GB18030/ISO-2022/UTF-7,
  `mb_ereg*`, `mb_convert_kana`, MIME headers, `mb_send_mail`.
- Why not compile ext-mbstring: +1.14 MB (arm64-v8a) / +1.16 MB (x86_64) of
  code and data per ABI (+2.1 MB of `.so` on x86_64 after page alignment,
  +0.63 MB compressed), measured with the runtime's NDK flags and no
  Oniguruma; it would also need a new PAM runtime release and an iOS
  XCFramework rebuilt on a Mac. intl (ICU, ~35 MB per ABI) stays out.
- CLI: staging an Android/iOS build runs
  `Pam\Native\Tooling\MobileRuntimeAudit` and prints a warning with file
  and line for each function or class (new, ::, extends, implements) of an
  extension the selected runtime does not compile (intl, iconv, zlib, gd,
  curl, dom...) that no bundled file or polyfill declares. Guarded uses,
  package tests/binaries and the SDK itself are skipped; findings never fail
  the build.
- Docs: `docs/platform-runtime.md#php-extensions` lists what Android and iOS
  have (identical sets), the polyfill coverage and the audit.
- iOS: same runtime extension set, so the PHP polyfill applies unchanged; no
  Swift change. Listed in `docs/ios-parity.md` for Mac validation.
- Tests: `tests/mbstring_polyfill.php` (recorded ext-mbstring results; with
  ext-mbstring on the host, every function compared over seeded valid and
  malformed strings in every encoding and substitution mode),
  `tests/runtime_audit.php`, and
  `scripts/android-php-runtime-test.sh`, which runs
  `tests/device/mbstring_runtime.php` inside the real Android runtime: passing
  on an Android 8.0 (API 26) x86_64 emulator and a Galaxy S10 (API 31,
  arm64-v8a). All 1.1M code points were also compared with ext-mbstring
  through every case mode, `mb_strlen()` and `mb_strwidth()`.

## 1.16.1 - 2026-10-06

- Plugins no longer declare a `plugins.share.v1` capability for `share`. 1.16.0
  accepted it at prepare, but the PHP runtime's `Protocol::CAPABILITIES` does
  not list it, so a plugin requiring it failed discovery at startup
  (`pam-native-share-extension` 0.3.0). Prepare now rejects it like any other
  unknown capability, and a test keeps the CLI's plugin capabilities within
  the runtime's. Gate `share` with `pamNative.minimum` 1.16.0 instead.

## 1.16.0 - 2026-10-06

Plugins configure the content they receive from share sheets per
application. Before, `pam-native-share-extension` hardcoded `*/*` (Android
`SEND` and `SEND_MULTIPLE`, iOS any file), so every app using it was offered
for every share, PDFs included.

- Plugin manifests declare `share` (`configKey`, default `accept` and
  `multiple`) with the new `plugins.share.v1` capability. Applications
  override `accept`/`multiple` under `plugins.<configKey>` in
  `pam-native.json` (new `plugins` section).
- Android prepare adds one `SEND` intent filter per accepted type to the
  launcher activity, with `SEND_MULTIPLE` only for the types in `multiple`,
  merged with `android.shareTargets`.
- iOS prepare replaces the plugin Share Extension's
  `NSExtensionActivationRule`: text/URL, image and movie counts for
  `text/plain`, `image/*`, `video/*` and `*/*` (unchanged legacy rule), and a
  `SUBQUERY` type-identifier predicate for specific types such as
  `application/pdf`. Uncompiled on this release host; listed in
  `docs/ios-parity.md` for Mac validation.
- Prepare fails on a `plugins` key no installed plugin declares, duplicate
  `configKey`s, invalid MIME types and `multiple` types missing from
  `accept`. JSON schemas and `docs/plugins.md` describe the contract.
- Tests: resolution, merge, Android filters on the real manifest template,
  legacy/media/predicate activation rules, invalid configuration.

## 1.15.0 - 2026-10-06

Telegram-style cold start: the system splash goes straight to the app's first
PHP frame, with nothing in between, and that frame arrives sooner. Measured on
a Galaxy S10 (Android 12), release-optimized `.perf` Zé Chat build, START to
the first frame with content (screen recording) and `Fully drawn`, 3+ cold
starts each (the shared `.perf` install was logged out, so the measured screen
is Auth; it streams in two PHP frames like the Inbox snapshot streams in one):

| | 1.14.x | 1.15.0 |
|---|---|---|
| START → first content frame (video) | 316–399 ms | 269–302 ms |
| Displayed (first window draw) | 213–238 ms, an empty window | = first PHP frame |
| Fully drawn (first PHP frame) | 331–377 ms | 303–316 ms |
| PHP worker start (from process start) | after the first draw | 77–101 ms, in onCreate |
| First-frame view creation | 11–14 ms (spinner alone 10–12 ms) | pooled/warmed |

- Android: the engine library and the bundle resolve on a startup thread from
  the top of `PamActivity.onCreate`; on API 30+ PHP boots before the rest of
  the native surface is built, with the window bounds and system-bar/cutout
  insets from `WindowMetrics` (below API 30 it boots from the held first
  pre-draw, once insets exist). The launch thread runs at display priority,
  which the PHP worker inherits.
- Android: the window's first draw is held (pre-draw) until the first PHP
  frame is committed, so the system splash / starting window stays up until
  the app has content. Released on runtime errors (the overlay or fallback
  must show) and after a 4 s safety timeout. A re-created Activity holds until
  its remount commits.
- Android and iOS: a surface's first batch mounts as soon as it reaches the UI
  thread instead of waiting for the next vsync / display-link tick.
- Android: while PHP boots, the UI thread prebuilds the first frame's views in
  2 ms slices (containers, text, pressables, images, inputs, one list) and
  warms the spinner and scroll classes; the first commit takes them from the
  pool. `ActivityIndicator`'s first instance cost 10–12 ms on the S10.
- iOS: the launch-screen cover is always installed (logo optional) until the
  first PHP frame, hidden on a fatal error and after 4 s; PHP boots with the
  window's safe area when the root view has not been laid out yet. Uncompiled
  on this release host; listed in `docs/ios-parity.md`.
- BottomSheet (Android): the first presentation was sized from
  `displayMetrics.heightPixels` before the dialog existed and never re-sized.
  It now estimates from the covered window and its insets and re-resolves on
  the first layout (and on width changes) within the same traversal. A fitted
  (base) sheet no longer subtracts the status bar twice and rests on the
  navigation bar; an edge-to-edge sheet (`navigationBarTranslucent` /
  `statusBarTranslucent`) resolves against the window minus the top inset and
  reaches the screen bottom. iOS already re-sized on layout.
- Navigation: `Navigator::reset()` followed one tick later by a push could
  land natively on the reset route while PHP's current route was the pushed
  one (a cold-start deep link opened the Inbox instead of the chat). When both
  reach the host in one commit, the new routes are created in node-id order
  and Android took the last inserted route as the destination, while the
  FragmentManager re-added each route after the previously added fragment's
  view. Route views now keep the engine's order and the destination is read
  from it, whatever the commit timing. iOS already used the route order.
- Tests: `PamBottomSheetSizingTest`;
  `resetThenPushLandsOnThePushedRouteWhateverTheCommitTiming` (fails on
  1.14.2); XCTest `testResetThenPushInOneCommitLandsOnThePushedRoute`.

## 1.14.2 - 2026-10-05

An `autoFocus` input in a Modal/BottomSheet presented while an overlay
before it closes gets the keyboard again. In Zé Chat, long-press on one's own
message → Editar opened the "Editar mensagem" sheet without the keyboard on a
Galaxy S10 (Android 12), 3 out of 3 times.

- Cause: removing the closing overlay shifts the sheet's index, so the engine
  emits a Move for it. The renderer detached and re-attached the moved view
  even though it kept its place among its host's views. Detaching a
  `PamModalHost` dismissed its Dialog and re-created the window 40 ms after
  it was shown, so the IME the autofocus pipeline had already requested was
  hidden (`HIDE_UNSPECIFIED_WINDOW`) and the new window had no focused
  editor (`SOFT_INPUT_STATE_ALWAYS_VISIBLE is ignored`).
- Android: `move()` leaves the view attached when its hosted position does
  not change; `PamModalHost` keeps its window across a detach that is undone
  in the same main-thread turn (a host that stays detached still closes it);
  the autofocus pipeline logs why it stops (`PamAutoFocus`).
- iOS: `move()` keeps the view in its superview when its sibling position
  does not change (removing it resigned the focused field). Uncompiled on
  this release host; listed in `docs/ios-parity.md` for Mac validation.
- Tests: `PamSheetKeyboardInstrumentedTest.autoFocusInASheetMovedByItsClosingOverlayKeepsWindowAndKeyboard`
  (fails on 1.14.1 with `focus=false`), passing with the rest of the
  render suite on an Android 12 (API 31) emulator; XCTest
  `testMoveThatKeepsThePositionLeavesTheFocusedInputInPlace`. Verified on the
  Galaxy S10: 3/3 openings with the keyboard up and the sheet above it.

## 1.14.1 - 2026-10-05

Pinned sticky headers in `VirtualizedList` and `VirtualGrid` are real,
interactive views (FlashList parity). Zé Chat's ChatDetails had to host its
sticky tab bar in a `ScrollView` because a tap on the pinned tabs reached the
row scrolling underneath.

- Android: the pinned header was a bitmap copy of the sticky cell drawn over
  the list (and re-captured on every frame), so presses, pressed state and
  accessibility went to the row below. It is now a real RecyclerView child:
  the layout manager ignores it (never scrapped or recycled while pinned),
  leaves it out of its child count and appends rows before it, so it stays
  the last child, drawn and hit-tested first. The cell's native views move
  into the pinned holder and back to their row (or unmount when the row is
  gone), so their state (a horizontal tab rail offset) survives pinning. A
  pinned holder owns every touch inside its bounds, its non-interactive
  background included. The next sticky cell still pushes it away. All of it
  runs inside the layout manager's scroll and layout passes: no PHP call and
  no bitmap copy per frame; rows keep virtualizing.
- iOS: ScrollView and VirtualizedList/VirtualGrid hosts hit-test their
  sticky children before the rows (`zPosition` only reorders drawing), so a
  press on a pinned header never reaches the row under it. Uncompiled on
  this release host; listed in `docs/ios-parity.md` for Mac validation.
- Tests: `PamVirtualListStickyHeaderInstrumentedTest` (press on the pinned
  button, background tap that no row receives, pressed state, accessibility
  visibility, push by the next header, VirtualGrid, stepping through the
  list with no blank row and exactly one mounted header, a commit while
  pinned), passing on an Android 12 (API 31) emulator;
  `PamStickyHeaderPositionsTest`; XCTest
  `testPinnedVirtualListHeaderReceivesTouchesInsteadOfTheRowUnderIt`.

## 1.14.0 - 2026-10-05

Per-component render cost and first launch after install (Zé Chat inbox on a
Galaxy S10, release-optimized `.perf` build, against 1.13.3):

| | 1.13.3 | 1.14.0 |
|---|---|---|
| One inbox row (`ChatListItem`), render + encode | 1.79 ms | 0.44 ms |
| Inbox first render (12 rows), render + encode | 43.4 ms | 13.1 ms |
| PHP component discovery on the first launch after install | 2,066 ms | 28-38 ms |
| First launch after install, process start → first commit | 3,396 ms | ~1,750 ms |

Host (`scripts/benchmark-inbox-render.php` / `benchmark-chat-render.php`,
PHP without JIT): inbox first render 9.6 → 2.7 ms, realtime row update
7.7 → 1.9 ms, filter back 6.7 → 1.7 ms; chat first render 17.8 → 7.1 ms,
incoming message 8.9 → 2.7 ms. Encoded frames are byte-identical.

- Templates: every node's directives, attribute classification, events and
  slot split are planned once; resolved styles/values are cached per (node,
  sheet environment, class list, ancestor chain, inherited styles); common
  native elements (View, Row, Column, Text, Image, Pressable...) and
  compiled component tags take fast paths that build each element as one
  object with precomputed constant properties, class tokens and inherited
  text styles. Native property conversions are memoized.
- Template expressions (and `:class` array literals) compile to plain PHP
  closures with the same grammar, eager evaluation order, missing-value
  rules and diagnostics; the closure-tree evaluator stays as reference and
  fallback (a differential test compares both).
- Encoder: memoized component subtrees (keyed rows and component elements)
  are reused through C-level array unions with their callbacks and encoded
  bytes, callback merging is incremental (was O(N) per reused subtree), and
  patches skip unchanged node objects.
- Build-time component cache: `pam-native build` and update bundles run
  `PamPhpCompiler::prebuild()` on the host, writing `pam-prebuilt/components`
  (class, runtime template, generated expressions) keyed by project-relative
  paths and validated by source/stylesheet fingerprints, so an installed or
  updated bundle compiles nothing at boot; an edited component (hot reload)
  still compiles into the writable cache.
- Android: the CLI writes `assets/pam-files.txt`; the first launch copies the
  bundle from that listing in parallel and verifies each file's SHA-256 while
  it streams (no `AssetManager.list()` walk). Debug/benchmark builds log
  `bundleInstallMs` (`PamNativePerf`).
- Runtime: press and gesture handlers that elements wrap to decode payloads
  (`on:pressIn`, `on:pressOut`, `on:pressMove`, `on:doubleTap`, image, media,
  input, modal and sheet events) now mark only their component, like
  `on:press`: one double tap or press-in re-renders one memoized row instead
  of the whole tree (no more `HoldPressable` workaround needed).
- Component snapshots and profiler spans are cheaper per render.

## 1.13.3 - 2026-10-05

- Android: `autoFocus` on an input inside a presented BottomSheet opens the
  keyboard on Android 11-14 (Zé's "Editar mensagem" on a Galaxy S10, where
  1.13.1 still issued no show request). The interactive sheet window keeps
  `adjustNothing` with an unspecified soft-input state, so the system never
  shows the IME when the window gains focus, and a request made before the
  IME serves the remounted input is dropped by the client. While an
  `autoFocus` input is pending, its modal window now asks for
  `SOFT_INPUT_STATE_ALWAYS_VISIBLE` (the platform itself shows the IME for
  the focused editor on focus gain) until the IME is visible (4 s at most);
  the input asks again as soon as the IME creates its input connection; and
  focus/keyboard retries are bounded by wall-clock time (3 s), not by attempt
  counts that a long UI-thread frame could burn in one burst.
- `PamAutoFocus` logcat lines (focus gave up, waiting for window focus,
  connection created, show issued, IME visible) trace the path on devices.
- `PamSheetKeyboardInstrumentedTest`: Zé's flow (kept sheet presented under
  a closing transparent overlay, multiline natively-synced input remounted
  with a new key and `autoFocus`, a 1.2 s UI-thread stall), passing on an
  Android 12 (API 31) emulator with three-button navigation.

## 1.13.2 - 2026-10-05

Zé Chat cold start on Android: a `position: absolute; top: -200px` child of
the root crashed every launch.

- Protocol: a layout frame's origin may be any finite value. CSS offsets and
  negative margins (`top: -200px`, `left: -34px`, `margin-top: -14px`) put a
  box outside its parent; only `width`/`height` must be finite and
  non-negative. The Android decoder read all four values as non-negative and
  threw `ProtocolException("Layout value must be finite and non-negative")`;
  the iOS decoder had the same rule ("Invalid layout value"). Both now read
  origins as finite coordinates and sizes as finite non-negative extents.
- Rust: `Layout::is_valid()`/`sanitized()`. The layout engine sanitizes every
  frame it emits (negative origins are kept), the encoder refuses an invalid
  frame and `decode_batch` rejects one (`ProtocolError::InvalidLayout`).
  `PROTOCOL.md` documents the rule.
- Android: a rejected batch no longer cascades into "Node N cannot contain
  children". The engine had already retained the dropped batch, so later
  patches targeted nodes the host never created. The runtime now replays the
  retained tree: a rejected batch, or a commit that throws part-way, clears
  the renderer (`PamRenderer.resetTree()`) and applies a full remount. A
  failing remount is reported and not retried.
- iOS: a rejected batch replays the retained tree onto a fresh renderer on
  the same host view.
- Tests: one golden negative-origin batch pinned by Rust, Android and iOS;
  invalid frames (NaN/infinite origins, negative/infinite sizes) rejected on
  all three; engine negative offsets and margins; instrumented negative-origin
  mount and reset + remount of a half-applied tree.

## 1.13.1 - 2026-10-05

Zé Chat device QA on 1.12.2 (Galaxy S10, Android 12).

- Android: `autoFocus` on an input inside a just-presented BottomSheet or
  Modal opens the keyboard, also when the input is remounted (a new key per
  open) in a re-presented or already-presented sheet. The renderer waited at
  most 200 ms for window focus and asked the IME once; on Android 11-12 a
  request from a window that is still gaining focus is dropped. It now waits
  for the input's own window focus (`OnWindowFocusChangeListener`), asks that
  window's insets controller (`show(ime())`) and the IMM, and re-asks until
  the IME is reported visible. The modal no longer moves focus to its first
  focusable (a header button) when an input already took it.
- Android: a `<Modal transparent presentation="fullScreen">` whose flattened
  full-height Column ends in a short options sheet (Zé's OptionDialog) is a
  short sheet over the translucent backdrop again. Every child hosted by a
  full-screen modal was forced to `MATCH_PARENT`, which drew the sheet as an
  opaque full-screen window with its title at the top. Only a child spanning
  the modal fills the window now; a bottom-anchored child keeps its height
  at the window bottom and other children keep their frames.
- `PamSheetKeyboardInstrumentedTest`: remounted `autoFocus` input with a
  focusable header button (first open, re-open, remount in an open sheet),
  and a screenshot pixel test of the transparent options modal (backdrop
  over the screen above, white sheet in its 44 % frame).
- iOS needs no change for either issue beyond 1.12.2 (`autoFocus` retried
  after presentation; no layout-only flattening).

## 1.13.0 - 2026-10-05

Cold start (Zé Chat on a Galaxy S10, release build): first PAM frame
870 ms -> 290-370 ms; the logged-in inbox shows its rows ~470 ms after launch.

- Component discovery no longer re-tokenizes every `.pam` file on each boot.
  An unchanged component (xxh128 of its source, the `src/app.css` location
  and every stylesheet it imported) reuses its cached class/tag, and its
  template tree is decoded lazily on first render. Zé Chat (103
  components, 4.3 MB of source): 590 ms -> 16 ms per cold start. Editing a
  component or any CSS it imports still recompiles it.
- An unchanged dimensions event (hosts re-send the window on every surface
  bind, including right after boot) keeps the `WindowMetrics` instance and
  no longer invalidates and re-renders the whole tree. Android exports the
  full style environment in `PAM_BOOT_METRICS` so the first event matches.
- `android.benchmarkApplicationIdSuffix` and `pam-native build --benchmark`:
  the installable release-optimized variant (R8, non-debuggable, baseline
  profile, signed with the local debug key) is written to
  `dist/<name>-<version>-android-benchmark.apk` and can install beside
  production for cold-start measurements; such builds omit Firebase.
  `run --release`, `benchmark` and `profile` use the same package.
- The Android baseline profile covers the whole `dev.pam.nativeapp` host.
- Android: an OTA slot activated over an older APK bundle is discarded when
  a new APK ships a different embedded bundle, so a reinstall never keeps
  running the previous PHP release until `pm clear`.
- iOS parity: the component cache and lazy templates are shared PHP; the
  stale OTA slot rule (keyed on the embedded `manifest.sha256`) and the full
  `PAM_BOOT_METRICS` environment are ported. **Uncompiled; needs Mac
  validation.**

## 1.12.2 - 2026-10-05

- Android: a BottomSheet with `keyboardBehavior="interactive"` (Zé's "Editar
  mensagem") rides above its own keyboard again. The sheet window keeps
  `adjustNothing` and read the IME from its content view, where a window
  fitting system windows (Android 11-14, the Galaxy S10) had already
  consumed the insets, so the field and Save button stayed behind the
  keyboard. The IME is now read on the dialog's decor view and followed
  frame by frame (`WindowInsetsAnimation`); the sheet's bottom edge is put
  on the IME top from the real window geometry (edge-to-edge or not).
- The covered window ignores the sheet's keyboard: a panning
  KeyboardAvoidingView (the chat composer) only avoids the IME of its own
  focused window, like the root host since 1.12.0.
- While the keyboard lifts a sheet, a tap on its upper part is no longer
  taken for a backdrop tap and dragging starts from the lifted position.
- `autoFocus` inside a sheet waits (up to 1 s) for the sheet window's focus
  before opening the keyboard.
- `PamSheetKeyboardInstrumentedTest`: tapped and auto-focused sheet inputs
  end directly above the IME with Save visible; the base composer and root
  IME inset stay at 0; Back settles the sheet.
- iOS: presented sheets follow `keyboardWillChangeFrame` with the keyboard's
  duration and curve, the screen below ignores the keyboard of an input
  inside a presented modal, and `autoFocus` inside a sheet runs once it is
  presented. **Uncompiled; needs Mac validation.**

## 1.12.1 - 2026-10-05

- Android: taps inside a Pressable nested in another Pressable reach the
  view under the finger again. Hit-slop delegates (every Pressable registers
  a minimum-touch-target delegate, routed before child hit testing) used the
  platform `TouchDelegate`, which re-centres each event in its target: a
  Pressable panel inside a Pressable backdrop sent every tap to the child at
  its centre, so all tiles of Zé's message-actions overlay fired the 5th
  ("react"). Delegates now keep the touch position (clamped onto the target
  only for slop points) and large-enough targets are not delegated at all.
  `PamNestedPressableInstrumentedTest` taps 12 tiles in nested Pressables and
  in a re-opened Modal and expects 12 distinct ids.
- Bottom sheets (Android and iOS) start drag-to-close only when the
  scrollable under the finger is at its top (gorhom semantics), instead of
  checking the sheet's non-scrolling direct child; a downward drag inside a
  scrolled list scrolls it. `PamSheetNestedScrollInstrumentedTest`.
- iOS needed no hit-test change (UIKit hit testing never re-centres); the new
  `PamNestedPressableTests` and the sheet change are **uncompiled and need Mac
  validation**.

## 1.12.0 - 2026-10-05

Keyboard, safe-area, input and text-fit fixes from Zé Chat device QA
(Galaxy S10, Android 12, three-button navigation).

- Keyboard avoidance is laid out by the engine. Hosts report the visible IME
  height (`pam_native_engine_set_keyboard_inset`; Android `WindowInsets` IME,
  iOS `keyboardWillChangeFrame`) and a `KeyboardAvoidingView
  behavior="pan"` that ends a vertical container (a chat composer after a
  flexible timeline) is laid out directly above the keyboard while the
  flexible siblings shrink, like `adjustResize` / react-native-keyboard-
  controller. The composer is visible, tappable and reported at its real
  bounds; the timeline keeps its last rows above the composer. Modal content
  is excluded (modal windows resize themselves). See
  [docs/android-safe-area.md](docs/android-safe-area.md).
- Android: a translated keyboard-avoiding container is lifted above siblings
  with a higher `z-index` (the composer was drawn under a `z-index: 1`
  timeline); the root host's inset handling keeps running while a KAV is
  mounted; the IME height is reconciled from the settled window insets.
- Closing the keyboard with Back or the IME key while an input keeps focus
  blurs it, so `on:blur` handlers restore resting layout (the composer went
  under the navigation bar).
- A window covered by a dialog, BottomSheet or permission prompt keeps its
  safe-area insets and ignores that window's IME.
- Controlled inputs: a rendered value echoing an older change event never
  overwrites newer IME text (React Native `mostRecentEventCount`; dropped
  characters such as "qa teste" -> "qa test"); authored values still apply.
  Plain inputs no longer remove a digit on Backspace ("J6pKK..." -> "JpK"):
  the masked-input digit rule now applies only to formatted inputs. iOS has
  the same echo guard.
- Text keeps its measured (ceiled) pixel width and height when frames are
  snapped, and a parent snapped one pixel narrower than its fractional frame
  is no longer treated as a reduced viewport. Fixes clipped avatar initials
  ("QA" drawn as "Q") and ellipsized bold labels ("Curtir" -> "Cur...",
  "Salvar" -> "Salv...").
- Modals: the dialog is tracked before it is shown, Back (down and up) is
  owned by the modal window, legacy cancellation suppresses the navigator
  Back, and a sheet opened while the keyboard hides settles at the bottom.
- PHP `error_log()` and logged errors reach logcat (tag `PamPHP`) for the
  whole process lifetime, including re-attached Activities.
- Tests: `PamKeyboardInstrumentedTest` (composer above the IME, Back blur,
  stale echoes, Backspace burst, unfocused window insets, overlay -> sheet in
  one commit), `PamTextClipInstrumentedTest` (pixel tests), engine layout
  tests, JVM and XCTest echo tests. iOS changes are not compiled here and
  need Mac validation.

## 1.11.1 - 2026-10-05

- `:key` (or `key`) on a `p-for` child is the loop identity exactly like
  `p-key`: the component instance, identity slot and native id follow the
  item, and duplicate keys are reported. Unkeyed loops keep one identity per
  iteration index.
- New device-path tests drive `p-for` loops of components (`:key`, `p-key`,
  unkeyed) through the runtime, decode the committed frames like a native
  host and dispatch presses by native node id with a pointer payload: every
  iteration runs its own callback on its own component instance across
  reorders and conditional siblings.

## 1.11.0 - 2026-10-05

Unkeyed siblings keep their native identity when a conditional sibling
appears or disappears (React-like reconciliation). Behaviour change: apps no
longer need `:key` on every sibling of a `p-if` to avoid remounts.

- The tree encoder identifies unkeyed children by their static slot (template
  position or builder argument position) instead of their output index. A
  false `p-if`, the inactive `p-if`/`p-else` branch, `If`/`Show`/`Match`/
  `Await` blocks, an empty `p-for` and `null`/`false` children passed to the
  Element API (`View`, `Column`, `Row`, `Screen`, `SafeAreaView`, `Pressable`,
  `Grid`, `VirtualizedList`, ...) leave a hole, so the siblings after them
  keep their identity: a sheet `Modal` no longer inherits the native host of
  a removed full-screen overlay `Modal`, and a `VirtualizedList` or
  `TextInput` after a conditional banner is not remounted (no empty timeline
  frame, focus and IME are kept).
- `p-for` children are identified by `p-key` (stable across reorders);
  unkeyed loops with more than one item log a one-time development warning.
- Slot content and component roots take the slot they are rendered at;
  slotted children mixed with positional children in PHP builders fall back
  to their position instead of colliding.
- Identities stay encoder-side: the wire protocol is unchanged and both
  Android and iOS apply the resulting moves by id. Unkeyed template nodes get
  new identities once after upgrading (a single remount on the first frame).

## 1.10.0 - 2026-10-05

Lifecycle and mount-cost fixes from Zé Chat device QA.

- Android relaunch after Back at the root no longer stays on the splash/dark
  window. Embedded PHP is now process scoped: a destroyed `PamActivity` only
  detaches its surface and the next Activity re-attaches to the live runtime
  (renderer, modules and callbacks rebound) while the engine replays the
  retained tree as a full mount (`pam_native_engine_remount`), in order with
  the worker's batches. PHP state survives; nothing re-executes. Relaunch
  renders in ~60 ms on the API 36 emulator
  (`PamActivityRelaunchInstrumentedTest`, budget 1 s).
- Root cause of the 1.7.0 Scudo abort ("corrupted chunk header" on the PHP
  worker after the Activity stopped): the bridge stored request-allocated
  (`emalloc`) strings in PHP's persistent ini configuration hash and
  `php_embed_shutdown` freed them with `free()`
  (`zend_hash_destroy` ← `php_shutdown_config`). The ini defaults are now
  persistent strings, and shutting the runtime down never blocks the UI
  thread.
- `CloseApp` (Back at the root of a stack) follows React Native's
  `invokeDefaultOnBackPressed()`: Android 12+ moves the root task to the
  background instead of finishing it; older releases finish and re-attach.
- Native module results above the bridge limit (raised from 1 MiB to
  32 MiB) reach PHP as a `Failure` with a clear message instead of being
  dropped, on Android and iOS.
- Android mount: no full-tree passes per commit. Hosted insertion indexes
  walk the host's children instead of every node, sibling lists use binary
  insertion (prepends were quadratic), only changed virtual lists are
  re-synced, StatusBar merging scans status-bar nodes only and touches the
  window/insets controller only when the merged state changed, host
  background is written on change, local modal triggers skip plain
  pressables, unmaterialized list rows skip layout work. Debug builds on the
  emulator: inline-keyboard tap 2.4 → 1.7 ms, timestamp refresh 1.9 → 1.6 ms
  (p50); release builds were already ~0.6 ms / ~0.8 ms. Batch decode stays on
  the PHP worker thread; prepending a 30-row page mounts no off-screen views
  and keeps the scroll anchor.
- Regression tests: virtual lists never draw a blank viewport when every
  visible cell changes props, is promoted to a host view, resized at the end
  or re-keyed in one commit (`PamVirtualListRebindInstrumentedTest`).
- iOS (written on Linux, bridge syntax-checked, **Swift needs Mac
  validation**): same remount bridge (`pam_native_runtime_remount`),
  `PamRuntime.attach(hostView:)`/`detach()`, `PamNativeViewController`
  re-attaches to the live runtime; renderer uses binary sibling insertion,
  dirty-list syncing and re-binds modal triggers only on structural or marker
  changes.

## 1.9.1 - 2026-10-05

- iOS SQLite reuses compiled statements per database (32-entry LRU keyed by
  SQL, oversized one-off statements excluded, finalized on close), matching
  Android's `SQLiteDatabase` statement cache; `pam-native-nitro` upserts,
  batches and paged reads no longer re-prepare SQL on every call. Statements
  are reset right after use so cached reads never hold a WAL snapshot.
- iOS: `onTextLayout` and `ellipsizeMode="marquee"` on `Text` are owned by
  `PamTextView` (1.9.0); the 1.8.0 UILabel fallback no longer duplicates
  text-layout events or reconfigures `PamTextView`.
- Uncompiled on the release machine (no Xcode); needs Mac validation
  (`SQLiteStatementCacheTests`).

## 1.9.0 - 2026-10-05

iOS rendering parity with the Android 1.2.0–1.7.0 releases (CSS paint and
effects, typography, layout, components and the error overlay). See
[docs/ios-parity.md](docs/ios-parity.md). Swift and the iOS bridge were
written on Linux: the bridge was syntax-checked with the PHP headers, the
Swift sources and the new XCTest suites are **uncompiled and need Mac
validation**.

- Text: `Text` draws with a CoreText layout (`PamTextLayout`) that the Rust
  engine also uses to measure every text box (`pam_native_ios_set_text_measurer`,
  installed before the first frame): React Native iOS line-height rule
  (scaled by `fontScale`), pixel-ceiled sizes, first baselines,
  `numberOfLines` ellipsis modes, `adjustsFontSizeToFit`, nested spans with
  per-span press (`SpanPress`), `text-shadow`, `font-feature-settings` /
  `font-variant-numeric`, `text-align: justify`, `on:textLayout` and marquee.
  A bare `fontFamily` resolves bundled files by React Native conventions;
  `<Icon>` fonts load from `asset://`.
- CSS paint/effects: per-side border colors, per-corner radii,
  `transform-origin` and `%` translations, linear/radial/repeating gradients
  with premultiplied stops clipped to the radius, `border-image` gradient
  rings, multiple/inset/spread `box-shadow` as cached Core Animation shadow
  paths (outer shadows of `overflow: hidden` views live in a sibling layer),
  image `filter`/`blurRadius` (blur with opaque edges + color matrix),
  `backdrop-filter: blur()`, `<Shimmer>`. Filters on non-image views and
  backdrop color functions log a one-time debug diagnostic.
- Layout: the engine owns SafeAreaView insets from boot (window insets set
  before the worker starts, `PAM_BOOT_METRICS` exported, native insets never
  applied twice, geometry changes relayout), `on:layout`, `ScrollView`
  content size (previously never set on iOS), sticky headers in ScrollView
  and VirtualizedList, `<ScrollView keyboardInset>`, lists resting at their
  end stay there, scroll targets use the real viewport, and scrolled-out cell
  views are pooled by kind and property set.
- Components: `MediaReadyEvent` natural size/duration, `on:mediaLoadStart`,
  `on:buffering`; pressed-state `translate` in points; `Toast::message()`
  card; Modal `backdropColor`/`animationType` (incl. `slide-fade`)/
  `transparent`; bottom sheets slide while the backdrop fades and resolve
  `%` snap points against the container minus the top inset.
- Runtime: LogBox-style error overlay (toast, scrollable inspector,
  Dismiss/Copy/Reload, queue and repeat counter, release fallback with retry,
  `devErrorOverlay` → `PamDevErrorOverlay`), coalesced events of removed
  nodes are dropped, media cache identities are hashed once with a fast hex
  encoder.
- CLI/host: `appearance.splash` builds the iOS launch screen
  (`App/PamLaunch.xcassets`, light/dark background, PNG logo) and the host
  keeps the logo until the first frame.
- PHP SDK, Rust workspace, CLI and protocol parity tests passed locally.

## 1.8.0 - 2026-10-05

iOS parity for the 1.5.0 gestures/animations and the 1.1.x native modules.
See [docs/gestures.md](docs/gestures.md), [docs/animations.md](docs/animations.md)
and [docs/native-capabilities.md](docs/native-capabilities.md).

- iOS motion runs on the main thread with no PHP round-trips while a finger
  moves: `PressEvent` coordinates for press/long press, `delayLongPress`,
  press-in/out delays (hold-to-record), `on:doubleTap` with deferred single
  press and `TapEffect` (heart burst), `Drag` (rubber band, drivers on
  `nativeRef` views, snaps with spring/timing settle, threshold/velocity
  release, haptic tick, groups, `dragSnap`), `Swipeable`, animation programs
  and presets, `replayKey`, per-property CSS transitions with `spring()`,
  per-keyframe `easing`, scroll begin/end drag and momentum end with page
  index, list `pagingEnabled`, `on:textLayout`, `ellipsizeMode="marquee"`
  and the `slide-fade` modal. The spring solver matches Android/PHP
  durations exactly.
- iOS native modules: `Http::multipart()` and upload transfers with progress
  and cancellation, `Image::prefetch()`, conversation notifications
  (communication notifications with avatar, inline reply, mark as read),
  durable `Notifications::onAction()` queue with native `ActionEndpoint`
  delivery, `PushRendering` for background (`content-available`) pushes with
  route suppression and `PushMessage::$rendered`.
- `Screen::secure()` on iOS: screenshots cannot be blocked, so secure mode
  shields every window while the screen is recorded/mirrored
  (`UIScreen.isCaptured`) and while the app is inactive (app switcher).
- iOS host: forwards APNs registration, background pushes and notification
  responses; declares `remote-notification` and `INSendMessageIntent`.
- Views with a transform are laid out through bounds/center on iOS.
- Tests: XCTest mirrors of the Android motion, drag, transfer and
  notification suites. Swift sources were parse-checked with Swift 6.1 and
  the pure motion and HTTP logic type-checked and exercised on Linux; the
  UIKit code was not compiled (no Xcode) and needs Mac validation.

## 1.7.0 - 2026-10-05

LogBox-style runtime error overlay. See [docs/error-overlay.md](docs/error-overlay.md).

- **Dismiss fixed**: the old overlay grew with the stack until its Dismiss
  button was pushed under the navigation bar, and any committed frame
  silently cleared it. Dismiss now always closes the error, Back closes the
  inspector, a dismissed error is not re-shown until the runtime reloads (no
  re-show loop), and the overlay never swallows touches outside its toast.
- **Debug builds**: non-fatal errors show a compact bottom toast that
  expands into a full-screen, scrollable inspector: exception class and
  message in large text, app frame first with app-relative paths
  (`/data/user/0/<pkg>/files/pam/releases/<hash>/` stripped), source
  snippet, collapsible framework/vendor frames, wrapped monospace stack,
  Dismiss / Copy / Reload, and `‹ 1 / N ›` for queued errors (repeats count
  as `×N`). Fatal render/boot errors open the inspector directly. Respects
  safe areas and the light/dark appearance.
- **Release builds never show stacks**: non-fatal errors are logged only;
  fatal errors retry with a fresh runtime and then show a localized
  "Something went wrong" / "Algo deu errado" screen with **Try again**
  (event-handler errors no longer reload the whole app). `pam-native.json`
  `devErrorOverlay`: `true` (default, debug only), `false`, or `"always"`.
- `App::onError(Closure(Throwable, RuntimeError))` / `offError()`: crash
  reporting hook (Sentry, observability) for every uncaught error, with
  `phase` (boot, render, event, module), `fatal()`, classified frames and a
  fingerprint.
- **Module failures reach the caller**: `SQLite::execute/query/executeMany/
  transaction` and `Storage::get/set` accept `onError`, and
  `NativeModules::call/callRaw` accept `onFailure`, so failures such as
  "Native module value is too large" go to that callback instead of the
  overlay. Without one they surface as a non-fatal
  `Pam\Native\Modules\NativeModuleException` (a `RuntimeException`).
- SQLite results over the 1 MiB bridge limit fail with an explicit size and
  paging hint; a native module that throws synchronously now completes with
  a failure instead of crashing.
- Diagnostics payload version 2 (`phase`, `fatal`, `frames`, `appFrame`,
  `snippet`, `fingerprint`); version 1 and plain-text errors still render.
- Android strings are translated to Portuguese (`values-pt`).

## 1.6.1 - 2026-10-05

Fixes for the React Native parity releases (1.4.0+), found on a Galaxy S10.

- **Release builds measured text with the estimator**: R8 stripped the JNI
  text-measurement callback (`PamRuntime.onMeasureText`), so minified builds
  silently fell back to the portable estimator (wrong text box heights, e.g.
  line pitch not following `fontScale`). The callback is now kept, with a unit
  test that every JNI callback looked up by the bridge has a keep rule.
- **SafeAreaView inset race**: the runtime now starts with the window safe
  area already set in the engine (and the renderer in engine-managed mode)
  before the first PHP frame, reconciles insets right after the
  off-UI-thread start, and drops any native SafeAreaView padding applied
  earlier, so a root SafeAreaView is never inset twice or not at all and its
  descendants are never shifted.
- `Text::rich()`/`<Span>` and `Icon` no longer require `ext-mbstring`
  (absent from the Android PHP runtime).
- Regression tests: engine layout of the Zé chat header under a top-edge
  root SafeAreaView, renderer ordering (legacy frame → engine insets),
  `lineHeight` × `fontScale` 1.1 parity, ProGuard JNI contract.
- `examples/layout_dump.rs` prints engine frames for a captured render frame
  and window safe area.

## 1.6.0 - 2026-10-05

React Native component parity on Android. See
[docs/react-native-components-parity.md](docs/react-native-components-parity.md).

- `<Icon font name size color>` / `Pam\Native\UI\Icon`: react-native-vector-icons
  glyph maps (`assets/fonts/{Font}.ttf` + `{Font}.json`, or `Icon::register()`)
  rendered as icon-font text, measured like React Native.
- Sticky headers (`stickyHeader="true"`, `Element::stickyHeader()`) in
  `ScrollView` and `VirtualizedList`.
- `VirtualizedList`: multi-column rows and horizontal cells are content-sized
  too (tallest cell per row); `fullSpan="true" (`Element::fullSpan()`, protocol `ListFullSpan` 523) for
  header/footer rows in multi-column lists.
- `:active` now applies like `:pressed`; pressed-state `transform: translate…`
  is in points and applies to `Pressable` (React Native `translateY: 1.1`).
- `MediaPlayer`: `on:mediaLoadStart`, `on:buffering`, `on:ready` with
  `MediaReadyEvent` (natural size, duration); existing handlers keep working.
- `Toast::message()` in-app toast with title and message
  (react-native-toast-message styling, position, duration, colors, font).
  iOS shows the message with the system presentation.
- `<ScrollView keyboardInset>`: keyboard-aware bottom content inset.
- `overflow: hidden` clips to the padding box with inner radii, so border
  rings stay visible around clipped content (avatars), like React Native.
- Bottom sheets: backdrop fade and sheet slide animate independently;
  percentage snap points resolve against the container minus the top inset.
- A bare `fontFamily` resolves bundled files by React Native naming
  conventions (`{Family}-{Weight}.ttf`, `_bold`, …) without synthetic bold
  when an exact file exists.

## 1.5.2 - 2026-10-05

Virtual list layout and scroll fixes (chat timelines).

- **Behaviour change:** single-column `VirtualizedList` cells without an
  authored `height` are now content-sized, like React Native `FlatList`
  cells. `rowHeight`/`estimatedRowHeight` remains the prefetch estimate and
  the extent of empty cells or cells whose content has no definite intrinsic
  height (e.g. percentage-sized media). Previously every such cell was
  clamped to the estimate, so variable rows (chat bubbles, comments) were
  clipped and overlapped. Multi-column grids keep the previous behaviour.
- Intrinsic and flex measurement wrap a column child's text at the child's
  own cross size (its horizontal margins and `min-width`/`max-width`), so
  capped content-sized boxes no longer clip their wrapped lines.
- Android: explicit scroll targets inside rich virtual cells are aligned with
  the adapter's per-cell pixel extents and the list's real native viewport
  (no accumulated rounding drift, no hidden end rows when host insets shrink
  the list). An `initialScrollIndex` applied in the same commit no longer
  overrides an explicit scroll request, and a list resting at its end stays
  there when its cells are re-measured or its viewport shrinks.
- CLI: a linked `git worktree` (whose `.git` is a file) is treated as a
  source checkout, so its locally built engine is no longer replaced by the
  release archive.

## 1.5.1 - 2026-10-05

Render pipeline performance, part 3 (from a Galaxy S10 cold-start trace and
the chat benchmark fixture). Chat fixture on an API 36 emulator, 3 runs
(1.2.2 → this release): scroll jank 6.0% → 2.9%, scroll frame p90/p99 26/61
→ 16/32 ms, typing frame p90 19 → 16 ms; app threads have no frame over
16 ms during scroll in a Perfetto trace (remaining jank is emulator
composition).

- Images: cache identities are hashed once per source and hex-encoded
  without `String.format`, which serialised the three image threads on ICU
  locale locks and accounted for most of their CPU at cold start on device.
- Lists: image and pressable cell views are pooled too; recycled image views
  are cleared so a reused row never shows the previous row's pixels, and
  pressables drop pressed state and local trigger actions.
- OPcache: 128 MB shared memory, 16 MB interned strings and 16000 scripts so
  large apps fit, and freshly installed bundles are cached on first launch
  (bundles are activated atomically by rename).
- iOS: the PHP worker drains events and module results and renders once per
  batch through `Runtime::deferRendering()`/`flush()` (12 ms budget), and
  collects cycles only when idle.
- Android API 36 instrumented suite (268/268), Android unit, PHP SDK and Rust
  tests passed locally. The iOS bridge was syntax-checked only (no Xcode).

## 1.5.0 - 2026-10-05

Gestures and animations on the UI thread (React Native Reanimated +
gesture-handler parity for the Zé Chat rewrite). During a drag or an
animation PHP receives no events; it gets the final semantic event only.

- Press: `on:press`/`on:longPress` handlers typed `PressEvent` receive
  `x`/`y` (RN locationX/Y) and `pageX`/`pageY`; untyped handlers keep the
  empty payload. Hold-to-record works with `delayLongPress` + `on:longPress`
  + `on:pressOut`.
- Double tap: `on:doubleTap` (+ `doubleTapDelay`) defers and cancels the
  single press natively; `tapEffect` (`TapEffect`) plays an animation
  centred on the tap (Reels heart burst) before PHP is notified.
- Drag (`Pam\Native\Animation\Drag`, `:drag` on a pan `GestureDetector`):
  bounded one-axis drag with rubber band, per-frame drivers on `nativeRef`
  views (opacity, translate, scale, rotate, borderRadius), snap points with
  spring or timing settle, distance/velocity thresholds, haptic tick, groups
  and programmatic `dragSnap="index@request"`. `GestureEvent` gains
  `snapIndex`/`thresholdReached`; new `on:gestureSettle`
  (`GestureSettleEvent`).
- `Swipeable` (PHP and `<Swipeable>` tag): left/right action panels, spring
  open/close, one open row per group, `closeRequest`.
- Animation programs (`Pam\Native\Animation\Animation`): timing, spring,
  set, wait, sequence, with, then, delay, repeat and replay `key()`, attached
  with `:animation` on any element or `<Animated :animation>`; presets
  `heartBurst`, `likeBounce`, `backToTop`, `shimmer`, `pulse`, `marquee`,
  `fadeInUp`; `replayKey`; per-keyframe `easing`.
- CSS transitions keep per-property durations, delays and timing functions
  (delays no longer rejected) and accept `spring(mass stiffness damping)`;
  `Element::transition()`.
- Scroll: `on:scrollBeginDrag`, `on:scrollEndDrag` (velocity) and
  `on:momentumScrollEnd` with the page index (`ScrollPhaseEvent`); lists
  accept `pagingEnabled` (one item per page).
- Text: `on:textLayout` (`TextLayoutEvent`: wrapped and visible lines,
  truncation, line widths); `ellipsizeMode="marquee"`.
- Modal: `animationType="slide-fade"` fades the backdrop while the content
  slides independently.
- Protocol: property IDs 509-522 and event kinds 70-75 (append-only).
- Tests: PHP SDK (new gesture/animation suite), Rust, Android unit (spring
  solver parity with PHP, release rules, bezier, programs) and Android API 26
  instrumented suite 134/134, including drag/swipe/double-tap/paging/text
  layout/modal/program tests that assert no PHP event while the finger moves
  and UI-thread frame work within a 60 Hz budget (story drag p95 ~2 ms).
  iOS decodes the protocol only; its native implementation is pending.

## 1.4.0 - 2026-10-05

React Native typography and layout parity (Android). See
[docs/typography.md](docs/typography.md).

- Exact text measurement: the engine measures every `Text` box with the
  Android text stack that draws it (`pam_native_engine_set_text_measurer`,
  `PamTextLayout`), following React Native: integer (ceil) font pixel sizes,
  hinted glyph advances, kerning, emoji/fallback fonts, `CustomLineHeightSpan`
  line heights, ceil widths, last-line heights and real first baselines.
  `numberOfLines` implies a tail ellipsis. iOS keeps the estimator.
- **Behaviour change:** `includeFontPadding` now defaults to `true` like React
  Native Android (single-line Roboto 14 sp is 49 px instead of 43 px at
  2.625×). Opt out with `include-font-padding: false`
  (`-pam-include-font-padding`), `includeFontPadding="false"` or
  `Text::includeFontPadding(false)`. Text nodes no longer force linear
  (subpixel) advances.
- Nested text: `Text` may contain text and nested `Text`/`Span` runs with
  their own size, weight, style, family, color, background, decoration,
  letter spacing and transform, rendered as one paragraph. `on:press` on a run
  makes it a link/mention (`EventKind::SpanPress`). PHP: `Text::rich()`.
  Whitespace inside `Text` follows JSX.
- `hairline` (= `StyleSheet.hairlineWidth`) and `Ndpx` device-pixel units in
  CSS; `Pam\Native\PixelRatio` (`get`, `getFontScale`, `roundToNearestPixel`,
  `getPixelSizeForLayoutSize`, `hairlineWidth`).
- Yoga layout semantics: border widths inset children and leaf content and
  grow content-sized boxes; `align-items/align-self: baseline` uses the first
  text baseline of container children recursively.
- `on:layout` / `Element::onLayout()` (`LayoutEvent` x/y/width/height relative
  to the parent), on mount and on frame changes, coalesced per frame.
- Safe areas: `SafeAreaView` insets are laid out by the engine for the window
  edges each view touches, at any nesting level (fixes a bottom-only nested
  `SafeAreaView` under a root without the bottom edge drawing under the
  navigation bar). `WindowMetrics` safe areas are correct from the first
  render (`PAM_BOOT_METRICS`) and `Dimensions` is re-sent when insets change.
  New `android.safeAreaBottomFallback` (dp) for devices reporting no bottom
  inset.
- `appearance.splash` (`logo`, `darkLogo`, `size`) for the Android 12+
  SplashScreen icon and the legacy starting window.
- `HttpResponse::date()` no longer uses the PHP 8.5-deprecated
  `DateTimeInterface::RFC7231` constant.
- Protocol (append-only): properties `TextSpans` (501) … `ScrollKeyboardInset`
  (508), events `SpanPress` (66), `Layout` (67), `MediaBuffering` (68),
  `MediaLoadStart` (69).

## 1.3.0 - 2026-10-05

Native CSS visual effects on top of the 1.2.0 complete-CSS compiler and the
1.2.x render-pipeline work:

- CSS gradients: `linear-gradient()`, `radial-gradient()` and their
  `repeating-*` variants (angles, `to <side>`, magic corners, circle/ellipse,
  size keywords, explicit radii, `at <position>`, multiple and double-position
  color stops, hard stops) in `background`/`background-image`, layered over
  `background-color` and clipped anti-aliased to `border-radius`. Stops
  interpolate in premultiplied space like browsers (`transparent` fades never
  darken).
- `<LinearGradient colors start end locations>` with `expo-linear-gradient`
  semantics for React Native ports.
- `border-image: <gradient> 1` paints a gradient stroke that follows
  `border-radius` (story rings).
- `box-shadow`: comma-separated lists, `inset` shadows and spread; CSS blur
  semantics (σ = blur / 2). Outer shadows use cached, downscaled ALPHA_8
  nine-slice masks shared across views and now follow the child's full
  transform (scale/rotation).
- `filter`: `blur()` plus `brightness()`, `contrast()`, `saturate()`,
  `grayscale()`, `sepia()`, `invert()`, `opacity()` and `hue-rotate()`,
  composed at compile time into one color matrix (RenderEffect on API 31+,
  hardware layer paint before). `blur()` below API 31 is now a logged no-op
  instead of a fake elevation.
- `backdrop-filter` on containers (Android 12+): GPU blur/color matrix of
  the content behind the element, clipped to its radius.
- `<Shimmer baseColor gradientColor duration enabled>` skeleton primitive
  (Zé Chat shimmer port, shared frame clock, pauses off screen).
- `<Image blurRadius>` / `filter: blur()` on images now blur the bitmap once
  with opaque edges on every API level (React Native semantics) instead of
  a GPU blur that needed API 31.
- Protocol: append-only property IDs 492–500 (`BackgroundGradient`,
  `BoxShadows`, `FilterColorMatrix`, `BackdropBlurRadius`,
  `BackdropColorMatrix`, `BorderGradient`, `ShimmerGradientColor`,
  `ShimmerDurationMs`, `ShimmerEnabled`).
- Still diagnosed at compile time: `conic-gradient()`, gradient color hints,
  `url()` backgrounds, `drop-shadow()`/`url()` filters, non-neutral
  `background-size/position/clip`. iOS paints colors and the first outer
  shadow only; the new effects log a one-time debug diagnostic there.
- Behaviour change: `background: <color>` now also clears gradients and
  `filter: <function>` resets the other filter functions, as in CSS.
- PHP SDK, Rust workspace (152), Android unit/lint, Android instrumented
  API 36 full suite (123/123) and API 26 effects/paint/renderer suites
  (60, 2 API-31-only skips) passed locally. iOS/Xcode gates were not run.
## 1.2.3 - 2026-10-05

- Fix frames rejected with `DuplicateSiblingIndex` ("Pam Native rejected an
  invalid render frame") after 1.2.1: a reused keyed or memoized subtree that
  moved to another sibling index under the same path kept its cached index,
  for example the previous screen of a navigation stack when pushing a third
  screen, or keyed list rows of a memoized component when items are inserted
  before them. The encoder now re-places the reused subtree root.

## 1.2.2 - 2026-10-05

Render pipeline performance, part 2. Chat benchmark fixture on an API 36
emulator (3 runs, 1.2.0 → 1.2.2): scroll jank 12.1% → 6.0%, scroll frame
p50 28 → 10 ms, send-to-frame 125 → 56 ms, open p50 150 → 85 ms, process
start to first committed frame ~230-330 → ~195-245 ms.

- Navigation: screens below the top of the stack (and outgoing screens
  during a transition) are frozen: their last element is reused while they
  are inactive, their components stay mounted, and they render their latest
  state as soon as they become the top again. Theme, metrics and other
  global invalidations still refresh them.
- Android decoder: `NodeKind`/`PropKey` resolve through dense lookup tables
  and the batch reader uses absolute little-endian reads instead of a buffer
  slice per scalar.
- Android lists: views of scrolled-out cells are pooled by node kind and
  authored property set and reused for cells of the same shape (every
  property is re-applied, so no stale value survives); the holder bound to a
  cell is found through its list instead of scanning every view; created
  nodes are appended to their siblings without an O(n log n) sort per insert.
- Coalesced UI events for nodes removed before the next frame are dropped
  instead of waking PHP.
- Native child visibility changes are applied by the PHP worker instead of
  committing and decoding on the UI thread.
- Startup: bundle installation/verification and OTA resolution run off the
  main thread (no ANR on first launch), and the manifest is verified in the
  CLI's path-component order first, avoiding a second full hash. OPcache is
  enabled (JIT off) with a file cache under the app state directory, so cold
  starts reuse the opcodes compiled by the previous launch.
- Android API 36 and API 26 instrumented suites (224/224 each), Android unit,
  PHP SDK and Rust tests passed locally. iOS sources unchanged.

## 1.2.1 - 2026-10-05

Render pipeline performance. On the host, a Zé-sized chat screen (32
messages, real templates) renders a state change in ~10 ms instead of
~170 ms, and an event or module result that changes nothing costs ~0.02 ms
instead of a full ~180 ms re-render.

- Template expressions are compiled once per source string into closure trees
  (same grammar, eager evaluation and `??` semantics); reflection lookups for
  properties, methods and enum imports are cached.
- Scoped stylesheets are decoded and validated once per compiled template.
  Cascade and state rules are indexed by their subject compound (id, class,
  tag, universal) while preserving source order, element descriptors are
  computed once per element and only evaluate attributes a selector
  (including `:not()`) can observe, and static attribute values, utility
  classes and font faces are cached.
- Component render memoization: a component is reused when its own
  properties (public, protected and private; arrays by value, plain objects
  two levels deep, components/closures by identity), props, slots, tracked
  state/stores/signals and injected providers are unchanged and no template
  event handler ran on it. Writes from module-result or timer callbacks are
  detected before each render and re-render only the changed component and
  its ancestors. Element closures that are not template handlers, back,
  app-state and memory-pressure handlers still re-render the whole tree.
  Subtrees of reused components stay mounted. Use `#[AlwaysRender]` for
  components whose templates read mutable external services,
  `$this->markForRender()` to invalidate explicitly, or
  `App::memoization(false)` to restore whole-tree re-renders.
- `Runtime::requestRender()` keeps its meaning (untracked change, everything
  re-renders); tracked changes use the new `Runtime::scheduleRender()`.
- No encode or commit happens when the rendered tree is identical; theme
  defaults are applied once per element instead of cloning the whole tree on
  every render.
- Android bridge: events and module results are drained and rendered once
  (`Runtime::deferRendering()`/`flush()`) within a 12 ms budget instead of one
  full render per item; interactive input (press, change, back, submit,
  toggle, key press, menu, modal close, navigation gesture) has its own
  priority lane ahead of module results and scroll/progress traffic; cycle
  collection runs when idle instead of after every event; the PHP worker
  attaches to the JVM once; `stats()` no longer blocks the UI thread behind a
  PHP commit. Older PHP SDKs keep the per-event behaviour; iOS keeps
  per-event rendering in this release.

## 1.2.0 - 2026-10-05

- Complete the CSS compiler: `flex-basis` (length, %, `auto`, `content`) and
  the full `flex` shorthand (`flex: 1 1 0`, `auto`, `none`, `<basis>`) now
  compile and drive the Rust layout engine, which implements CSS Flexbox
  §9.7 flexible lengths, `order`, `align-content`, `wrap-reverse`,
  `margin: auto` on every side, relative offsets, `position: fixed` and
  percentage minimum sizes.
- Add per-side border colors, any-order border shorthands, `text-shadow`,
  `font-variant-numeric`/`font-feature-settings`, `transform-origin`,
  percentage translations, `matrix()`, `text-align: justify`, unitless
  `line-height`, `em`/dynamic viewport units, `env()` fallbacks, `hwb()`,
  `lab()`, `lch()`, `oklab()`, `oklch()`, `color()`, `color-mix()`, `:not()`,
  `:is()`, `:where()`, `::placeholder`, `@supports`, `@media` and/or/not/range
  syntax and CSS `transition`.
- Report unsupported CSS at compile time as `<file>:<line>: <message>`;
  structural pseudo-classes, sibling combinators, `position: sticky`,
  gradients and skew transforms fail closed instead of silently rendering
  wrong. Repeated declarations act as CSS fallbacks.
- See `docs/css.md` for the full support matrix.
- Behaviour change: `flex: <number>` now compiles with a zero `flex-basis`
  (`flex: 1` = `flex: 1 1 0`), so sibling items share the main axis equally
  as in browsers. Use `flex: 1 1 auto` to keep content-sized starting points.
- PHP, Rust and Android unit/lint gates and the Android API 26 and API 36
  instrumented suites passed locally. iOS/Xcode gates were not run.

## 1.1.1 - 2026-10-05

- Remove release artifacts accidentally committed under `dist/` in 1.1.0 and
  ignore that directory, keeping Composer downloads small. No API or runtime
  change from 1.1.0.

## 1.1.0 - 2026-10-05

- Add `Http::multipart()` with fluent fields, private-file parts, headers,
  bearer auth, `onProgress(TransferProgress)` and a cancellable `HttpTransfer`;
  add byte progress and cancellation to `Http::upload(progress: ...)` /
  `Http::uploadWithProgress()`. `HttpResponse` now exposes response headers,
  `header()` and the server `date()`.
- Add `Share::files()`, `Files::move()`, `Files::copy()`,
  `Files::makeDirectory()` and `MediaLibrary::save()` (MediaStore on Android
  10+, `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion=28` on Android 8–9,
  add-only Photos on iOS).
- Add `Screen::secure()` and route-scoped `->secure()` / `ScreenOptions::$secure`
  (Android `FLAG_SECURE`, cleared when the route is popped).
- Add conversation notifications (`Notifications::conversation()`, `Person`,
  MessagingStyle, inline `RemoteInput` replies, mark-as-read,
  `Notifications::cancelGroup()`), a persistent `Notifications::onAction()`
  stream, native `ActionEndpoint` delivery while PHP is suspended, and
  declarative `PushRendering` rules with `suppressWhenRoute()` for data-only
  Firebase pushes. `PushMessage::$rendered` marks pushes already displayed.
- Add `Accessibility::announce()`/`screenReaderEnabled()`, `Image::prefetch()`
  into the renderer disk cache, `DeviceInfo::$memoryClassMb`, `$lowRamDevice`,
  `$powerSaveMode`, cancellable `Timers::timeout()`/`every()`/`cancel()`,
  `App::onStateChange()` and `ScrollView` `on:endReached`.
- Add `PermissionKind::BluetoothConnect`, `FullScreenIntent`, `PhoneState` and
  `PermissionStatus::Unavailable`; mark `READ_PHONE_STATE` as sensitive in
  audits.
- Add `android.debugFirebase: false` to build debug variants without Firebase
  (for debug application-id suffixes missing from `google-services.json`) and
  accept `pam-native mobile <command>` as an alias.
- Android instrumented suites passed locally on API 36 and API 26 (109/109;
  load-induced renderer flakes re-run green), plus Android unit, PHP SDK and
  Rust tests. iOS sources were updated without an Xcode build; multipart
  transfers, image prefetch, notification actions and push rendering report
  "not available on iOS yet".

## 1.0.37 - 2026-10-05

- Add `Appearance` and the int-backed `AppearanceMode` enum (`System = 1`,
  `Light = 2`, `Dark = 3`): `Appearance::set()`, synchronous `mode()`,
  `current()`, `isDark()` and `system()`, and `onChange()`/`unsubscribe()`.
  The preference is persisted natively (Android SharedPreferences, iOS
  UserDefaults) and restyles the tree in place without remounting.
- Deliver the effective appearance to PHP before the first render, so
  `WindowMetrics::$appearance`, `App::appearance()` and CSS
  `@media (prefers-color-scheme)` match the native window from the first frame
  instead of defaulting to light until the first dimensions event.
- Make the Android host theme DayNight. Window background, status and
  navigation bar colours, bar icon contrast and the Android 12+ splash
  background come from the new `pam-native.json` `appearance` section
  (`defaultMode`, `light`, `dark`), generated into `values` and `values-night`
  resources by the mobile build. The persisted preference is applied before
  `setContentView()` (override configuration below Android 12,
  `UiModeManager.setApplicationNightMode()` on 12+) and runtime changes are
  handled as `uiMode` configuration changes without activity recreation.
- iOS applies the persisted preference as the window's
  `overrideUserInterfaceStyle` before it becomes visible and reports trait
  changes from the host root controller.
- Add `scripts/android-appearance-first-frame.py`, a cold-start screen
  recording gate proving a dark app never shows a light frame and that the
  override survives a process restart.
- Avoid global Android renderer work on idle commits.
- Android API 26 and API 36 instrumented appearance suites and the emulator
  cold-start gates passed locally.
  iOS Xcode, simulator and device gates were not run.

## 1.0.36 - 2026-10-05

- Add MIME filtering, an optional failure callback and an explicit import
  limit up to 8 GiB to `Files::pick()`, retaining the 64 MiB default and
  positional source compatibility.
- Stream Android document imports with bounded memory and copy iOS
  security-scoped documents without loading the whole file into `Data`.
- Android API 26 and API 36 instrumented suites passed locally. iOS device,
  Xcode and APNs gates were not run for this Android-focused patch release.

## 1.0.35 - 2026-10-05

- Add composable `Route::guard()` groups so protected nested routes and
  destinations share guards without repeating route definitions.
- Add `android.debugApplicationIdSuffix` for separate QA installations even
  when a Firebase application ID is present.
- Rearm rich `VirtualizedList` end-reached events when the item IDs change,
  allowing another page request after new rows arrive.
- Android API 26 and API 36 instrumented suites passed locally. iOS device,
  Xcode and APNs gates were not run for this Android-focused patch release.

## 1.0.34 - 2026-10-04

- Decode iOS mutation numbers from a single borrowed payload buffer instead of
  allocating a `Data` slice for every field. Two independent Release simulator
  microbenchmarks measured 9.1× and 13.1× faster scalar decoding across 20,000
  layout records; this does not measure application frame time.
- Preserve the wire format and validation rules. No app migration is required.

## 1.0.33 - 2026-10-04

- Add native PhotoKit gallery pages and albums on iOS, lazy `phasset://` image
  and video sources, and 64 MiB bounded streaming import of selected assets
  into the application sandbox.
- Bring iOS file downloads with headers, bounded progress and cancellation,
  plus private-file preview, into line with the public PHP Files API.
- Receive iOS App Group shares through the core `IncomingShares` API, preserving
  source titles and filenames when the share extension supplies them.
- Bound PhotoKit image requests to rendered size and release completed download
  observations after their terminal event is delivered.

## 1.0.32 - 2026-10-04

- Generate complete Language 2 features with a screen, component, PHP service,
  route module and service test. Existing generators now emit typed Language 2
  contracts and the formatter migrates event and model aliases.
- Preserve generated Android and iOS source files when their contents are
  unchanged so Gradle and Xcode can reuse incremental build outputs.

## 1.0.31 - 2026-10-03

- Stop hot reload from scanning Composer dependencies or reacting to app log
  writes. Source edits still refresh immediately, while development logs can
  no longer trigger an endless reload loop during a clean first run.

## 1.0.30 - 2026-10-03

- Resolve the SDK from the installed Composer package and install the Android
  renderer for the exact PAM Native version, preventing host and engine ABI
  mismatches during clean first runs.
- Complete iOS input editing contracts for keyboard modes, secure and readonly
  fields, length limits, selection, submit, blur and autofocus. Preserve native
  child visibility and responsive layout behavior across Android and iOS.
- Reuse Gradle dependencies across projects while removing project build outputs
  after each command. Certify both Core and official UI starters with rendered
  screen evidence and the exact candidate Android renderer.

## 1.0.29 - 2026-09-22

- Restore intrinsic row and text measurement: rows of flexible controls keep
  their full cross size, fixed-size controls beside text columns are laid out
  again, and the Android host preserves flattened row controls and stable
  text baselines. Fixes the collapsed quick-action rows seen since 1.0.14.

## 1.0.28 - 2026-09-22

- Pause components only when the application leaves the foreground
  (`AppState::Background`). Transient system UI such as permission prompts,
  document and photo pickers, share sheets and biometric dialogs now reports
  `inactive()`/`activated()` while the component stays resumed, so requests,
  pickers and prompts it started survive the interruption.

## 1.0.27 - 2026-09-12

- Preserve the active Android `Editable` while applying mask and currency
  formatting so rapid hardware/IME input cannot lose queued key events.
- Clamp the formatted cursor to the committed editable length, preventing
  backspace sequences from producing an invalid selection or crashing.

## 1.0.26 - 2026-09-08

- Add reusable native masked, currency and locale-aware input formatting.
- Add bounded native gesture translation and deterministic end-state reset.
- Preserve pull-to-refresh gestures when refresh surfaces are nested in scrolling pages.
- Install optimized local Android runs from the minified, locally signed Benchmark variant.

## 1.0.25 - 2026-09-07

- Isolate Android macrobenchmarks from the target process so the Kotlin instrumentation runtime loads reliably.
- Exercise the complete PAM Native showcase benchmark on Android API 36 in CI and preserve its reports and metrics.

## 1.0.24 - 2026-09-07

- Retry Android installs when a booting PackageInstaller leaves an inaccessible transient session.

## 1.0.23 - 2026-09-07

- Add `PushNotifications::unregister()` to invalidate FCM tokens and unregister from APNs without a broad provider plugin.
- Install the Android Rust cross-compilation target before certifying clean community starter projects.

## 1.0.22 - 2026-09-07

- Generate Android manifests with only the optional permissions selected by the app and its plugins.
- Preserve the core network permissions required by every PAM Native application.
- Remove broad camera, microphone, location, contacts and media access inherited from the SDK template when unused.

## 1.0.21 - 2026-09-06

- Add `Clipboard::setSensitiveText()` for expiring sensitive clipboard writes.
- Mark sensitive Android clips so supported keyboards and system surfaces avoid previews.
- Clear unchanged sensitive Android clips after a bounded lifetime and use native expiration on iOS.

## 1.0.20 - 2026-09-06

- Add `Http::upload()` for native streaming PUT uploads from private files, with bounded snapshots, network deadlines and cleanup on completion or failure.
- Refuse HTTP redirects on iOS, matching Android, including file uploads.
- Add `Files::sha256()` to hash private files in native workers without passing their bytes through PHP.
- Document upload limits and uncertain outcomes; foreground uploads do not provide progress, individual cancellation or restart recovery.
- Add reproducible VS Code extension packaging with metadata, license and checksum verification.

## 1.0.19 - 2026-09-06

- Allow applications to recover from native notification permission failures through an optional failure callback.
- Preserve the existing permission granted/denied callbacks and global error reporting when no failure callback is supplied.

## 1.0.18 - 2026-09-05

- Measure variable fonts using their requested weight and update automatic layout when weight or accessibility scale changes.
- Preserve exact Android font weights and fractional glyph advances to prevent text clipping.
- Load packaged iOS font assets with their requested OpenType weight through CoreText.
- Measure wrapped row children against their allocated width so text height grows with its lines.

## 1.0.17 - 2026-09-04

- Add exact project-relative `.pamignore` exclusions for mobile bundles.
- Restore PHP language tooling and embedded template/CSS highlighting for PAM components in VS Code.
- Publish editor package 0.1.1 with PHP definition/hover regression coverage.


## 1.0.16 - 2026-08-26

- Passes the short immutable release tag to every reusable certification
  workflow, preventing `refs/tags/*` from being interpreted as a Composer
  semantic version during ecosystem candidate resolution.
- Adds a release-workflow regression contract that rejects full Git refs at
  reusable workflow boundaries.

## 1.0.15 - 2026-08-26

- Adds the typed Visual DOM with indexed selectors, stable element handles,
  structural mutations, atomic rollback, observations, native motion, focus,
  asynchronous measurement and diagnostic snapshots.
- Connects declarative `id`, `class`, and `data-*` metadata to the same retained
  document while preserving the existing CSS compiler and test identifiers.
- Uses Visual DOM identities in incremental binary patches so sibling insertion
  preserves mounted Android and iOS views.
- Adds a mandatory 5,001-node performance gate for document indexing, 4,000
  indexed queries and 5,000 batched mutations.

## 1.0.14 - 2026-08-26

- Makes post-build cleanup mandatory for Android and iOS development, build,
  run and packaging flows while preserving only declared final deliverables in
  `dist`.
- Cleans the actual project-local Gradle home, Android build outputs, iOS
  DerivedData and temporary export workspace after successful and failed
  operations.

## 1.0.13 - 2026-08-26

- Recognizes Android's explicit `Too early to start activity` response as cold-boot readiness and continues the existing bounded launch retry.

## 1.0.12 - 2026-08-26

- Covers Android 36's late `PackageManagerInternal.isSameApp` initialization during cold-boot activity launch.
- Extends the bounded activity-registration recovery window to four minutes for slow emulators and first-run devices.

## 1.0.11 - 2026-08-26

- Waits for Android to register a successfully installed activity before launching it, covering the final package-manager cold-boot race with bounded retries.
- Keeps permission, manifest and other permanent launch failures fail-closed instead of retrying them.

## 1.0.10 - 2026-08-26

- Makes Android boot-cold installs resilient to partially initialized package/storage services with a bounded recovery window and automatic non-streaming ADB fallback.
- Recognizes the Android 36 `PackageManagerInternal.freeStorage` startup race without hiding permanent installation failures.

## 1.0.9 - 2026-08-25

- Require Android's storage manager to return mounted-volume state before APK
  installation, in addition to the package and settings readiness probes.
- Recover only from the exact Package Installer startup signature involving
  `InstallLocationUtils` and a null `StorageManager`, while unrelated null
  pointer failures remain fail-fast.

## 1.0.8 - 2026-08-25

- Require both Android's package service and global settings provider to be
  operational before APK installation, closing the gap between a superficial
  emulator boot and a usable Package Installer.
- Recover from the bounded Android startup state where system providers are
  still being installed without retrying permanent application failures.

## 1.0.7 - 2026-08-25

- Wait for Android's package manager service, not only `sys.boot_completed`,
  before installing an APK and after a transient package-service restart.
- Keep the recovery bounded and preserve fail-fast behavior for permanent APK
  installation errors.

## 1.0.6 - 2026-08-25

- Recover automatically from bounded transient Android package-service and ADB
  transport failures while preserving immediate failure for real APK errors.
- Keep clean first-run builds deterministic even on slow software-emulated CI
  devices without hiding invalid signatures, storage failures or bad packages.

## 1.0.5 - 2026-08-25

- Resolve the versioned PAM install root from the public launcher before clean
  mobile builds, keeping runtime discovery independent from install versions.
- Build iOS ZIP artifacts with normalized timestamps, modes and ordering for
  byte-identical cross-run release evidence.

## 1.0.4 - 2026-08-25

- Carry the installed PAM runtime catalog into clean Native starter execution,
  so the candidate CLI can resolve Android PHP runtimes without host setup.
- Keep Laravel/backend sync adapters independent from the Native frontend while
  still certifying their own PHP dependency graphs.

## 1.0.3 - 2026-08-25

- Certify Native 1.x dependents against this exact canonical release candidate
  before either side is published to Packagist.
- Preserve independent frontend, backend and plugin release ordering without a
  circular dependency on an already-public Native 1.x package.

## 1.0.2 - 2026-08-25

- Keep the checked-out PAM candidate outside the Native Cargo workspace during
  the mandatory clean-starter release gate.
- Restore the end-to-end `pam init --template mobile` plus `pam dev` journey in
  release certification without weakening workspace isolation.

## 1.0.1 - 2026-08-25

- Refresh the immutable Android ecosystem lock to the independently versioned
  plugin releases certified for the Native 1.x capability contract.
- Remove the final public Firebase-to-feature-flags dependency from aggregate
  release certification.

## 1.0.0 - 2026-08-25

- Freeze the ABI, protocol and capability-negotiation contract for the 1.x line.
- Add deterministic Freeze optimization, retained Fiber lanes, native worklets,
  signed atomic OTA boot, stable brownfield hosts and independent plugin gates.
- Certify clean `pam init --template mobile` plus `pam dev` launch as a hard
  release prerequisite on a real Android emulator.
- Require PHP 8.5 by default and retain frontend/backend independence.

## 0.10.0 - 2026-08-24

- Make reactive updates dependency-granular with indexed worklet bindings and a
  reusable evaluation stack, eliminating full binding scans and hot-path stack
  allocations.
- Add first-class 144 Hz scheduling alongside 60, 90, and 120 Hz budgets, and
  drive iOS commits at the display's real adaptive maximum frame rate.
- Add reproducible p50/p95/p99 engine budgets that fail CI on full-commit or
  patch regressions.
- Ship fat-LTO performance builds plus locally reproducible PGO training, while
  preserving symbols in the dedicated profiling artifact.
- Make the canonical monorepo directly Composer-installable so Packagist points
  to push-in/pam-native, while retaining the legacy split as a release mirror.

## 0.9.1 - 2026-08-24

- Propagate real safe-area, font-scale, refresh-rate, motion, pointer, input,
  device, memory, display, dynamic-range, and performance style environment
  values from Android and iOS.
- Add dependency-scoped reactive CSS variables and route optional utility
  classes through the same deterministic PAMS compiler and IR.
- Resolve application `R.color` resources and iOS Asset Catalog named colors
  through typed, append-only protocol properties with portable fallbacks.
- Lower pressed, focus/focus-visible, and hover declarations to native state
  maps while reconciling typed disabled, checked, selected, loading, error,
  active, and empty states.
- Add an executable CSS acceptance/rejection matrix and cross-platform protocol
  parity coverage.

## 0.9.0 - 2026-08-24

- Introduces PAM Style Language 1.0 with deterministic PAMS IR, bytecode, fingerprints, source maps, compatibility metadata, and granular invalidation dependencies.
- Adds compiled compound, ID, attribute, child, and descendant selectors with specificity, source order, cascade layers, and `!important`.
- Adds native Grid lowering, modern media/container queries, CSS math and relative units, native environment values, and scoped/module/global ownership.
- Adds typed design-token generation for PHP, Kotlin, and Swift plus `pam style inspect`, `manifest`, and `tokens` tooling.
- Certifies the new style pipeline on PHP 8.5, Rust, Android API 26–36, Swift/UIKit, and an Android API 36 visual consumer.

## 0.8.8 - 2026-08-24

- Align the public PHP SDK contract with the runtime release so the immutable
  Composer split, Packagist publication, and `pam production certify` can be
  validated from the same versioned source.

## 0.8.7 - 2026-08-24

- Add granular typed signals, computed values, effects, and batched reactive
  updates without replacing the existing component API.
- Add adaptive device/window metrics and stable device-class selection.
- Add the delegated `production:certify` release-readiness contract.
- Certify `pushinbr/pam-native-image` from public Composer distribution across
  the aggregate Android and iOS plugin hosts.

## 0.8.5 - 2026-08-23

- Add Vue-like `p-model` and `p-model:checked` two-way bindings to Language 2,
  while keeping the existing binding spellings compatible.
- Apply accessible semantic theme defaults to imperative element trees without
  replacing authored styles, preventing invisible dark-on-dark first frames.
- Expose the live native appearance to plugins so PAM Native UI follows system
  light and dark mode without environment configuration.
- Move Android application-bundle staging out of purgeable cache storage and
  certify a non-uniform visible first frame on API 36.1 and physical hardware.
- Add an Android pixel-level first-frame regression and a bounded theme-default
  performance budget for retained trees.

## 0.8.2 - 2026-08-23

- Resolve Android runtime bundles from the latest PAM runtime release instead
  of incorrectly treating the independently versioned Native SDK as a PAM tag.
- Preserve explicit `PAM_RELEASE_BASE_URL` mirrors while normalizing trailing
  slashes before resolving verified runtime assets.

## 0.8.1 - 2026-08-23

- Added production-grade camera, media editing, retained canvas, GPU shader and
  3D scene plugins to the independently installable official ecosystem.
- Certified every public plugin together in aggregate Android and iOS hosts.
- Build the Linux Native CLI on the Ubuntu 22.04 glibc baseline so published
  binaries run on supported LTS and Pop!_OS-era distributions.

## 0.8.0 - 2026-08-23

- Added opt-in PAM Native UI Language 2 with a stable, integer-capability UI IR.
- Added attribute-backed typed contracts for props, actions, events, slots and
  Composer-provided tags while retaining Language 1 and `#[Expose]` compatibility.
- Added compiled interaction states, design tokens, recipes, viewport/container
  queries and compositor-only keyframes without a runtime CSS engine.
- Added `Show`, strict `Match` and typed `Await` flow primitives.
- Added compile-time stable-key and accessibility diagnostics for virtual lists
  and meaningful images.
- Expanded the Composer-installed language server with references, rename,
  symbols, signatures, semantic tokens and Language 2 quick fixes.

## 0.7.0 - 2026-08-23

- Added schema-2 reflection contracts with generated PHP, Kotlin and Swift bindings.
- Added structured task groups, deadlines, cancellation and bounded async streams.
- Added transactional Rust render generations for atomic 60/90/120 Hz scheduling.
- Added encrypted local-first records, outbox batching and deterministic conflict policies.
- Added stateful hot reload snapshots and deterministic DevTools replay timelines.
- Added capability-governed plugin registry entries with trust tiers and quality scores.
- Added in-process HTTP routing and middleware through a socket-free local transport.
- Added `pam release` and four-framework physical-device comparison evidence.
- Added the Singularity showcase and architecture documentation.

## 0.6.78 - 2026-08-22

- Make PHP 8.5 the SDK, showcase, certification and release-pipeline baseline,
  and consume the verified PAM v2.0.7 mobile runtime by default.
- Replace the incomplete Native CLI help summary with the complete contextual,
  plugin, runtime, generator, Android and iOS command surface.
- Collapse the duplicated onboarding section into one canonical Start Here flow
  built around PAM, Composer and contextual project commands.
- Remove the PHP SDK's implicit ctype dependency so templates and scoped styles
  run on PAM's minimal verified PHP 8.5 extension profile.
- Avoid PHP 8.5's null array-offset deprecation when reactive state is read
  outside an active component render.
- Resolve PAM 2 runtime catalogs during Android source builds, require their
  declared PHP 8.5 default and expose the selected immutable runtime to Gradle.
- Accept Composer's canonical `vMAJOR.MINOR.PATCH` installed-package version
  while retaining strict SemVer validation for Native plugin compatibility.
- Certify public Android plugins with the candidate Native CLI and the verified
  public PAM runtime, removing the pre-release dependency on an older SDK CLI.
- Build the candidate Rust engine for both supported Android ABIs inside the
  aggregate plugin job instead of relying on artifacts from another workflow.

- Make all four iOS, Android renderer, Android plugin API and PHP SDK release
  artifacts reproducible from the tagged commit. The release gate constructs
  each artifact twice, including an isolated clean Gradle rebuild of the AAR,
  requires byte-for-byte equality, and only then emits checksums,
  package-budget evidence and provenance attestations. A strict, bounded
  reproducibility report records integer artifact/result codes, size and digest;
  the final release job re-hashes every downloaded package against it.
- Replace iOS development's rebuild/reinstall loop with bounded loopback hot
  reload. Debug hosts now poll the CLI, stream-validate and transactionally
  activate `PNA1` bundles from cache, reload the embedded PHP runtime, and
  measure accepted-version-to-first-frame latency with the same integer kind
  `6`, 64-sample p95/failure evidence contract used by Android.
- Add the bounded iOS `PNA1` development-bundle parser and transactional
  activator. It matches Android's file/count/size/path contract, rejects
  malformed or duplicate input before activation, preserves the live PHP app
  on failure and recovers an interrupted previous-directory swap.
- Measure Android development hot reload from accepted version through the
  first committed native frame or runtime failure. DevTools now retains a
  bounded 64-sample window, exports success/failure counts and nearest-rank
  p95, and evaluates it against a configurable device budget. An offline,
  size-bounded verifier turns exported physical-device snapshots into a CI
  gate for sample count, failures and p95.
- Add bounded custom TalkBack and VoiceOver actions across the PHP fluent API,
  `.pam` templates, binary protocol, Android renderer, and iOS renderer. Complex
  gesture controls can now expose localized screen-reader alternatives and
  receive one stable action-name event without sending UI data across bridges.

- Make Native source CI and both aggregate plugin certifications reusable and
  mandatory for every version tag. Android/iOS source changes now trigger their
  ecosystem workflows automatically instead of relying on a manual dispatch.
- Add bounded Android and iOS HTTP diagnostics to the redacted Native DevTools
  timeline. Exports include only integer method/status codes, byte counts,
  duration and failure state; URLs, headers and bodies remain excluded.
- Add strict, origin-scoped W3C `traceparent` propagation to the Native HTTP
  client, with generic-header spoofing blocked in PHP, Android and iOS.
- Add bounded, redacted Android and iOS Simulator DevTools snapshot transports
  for contextual `pam diagnostics`, backed by one-use private-cache files
  available only through debug hosts. Android is protected by the privileged
  `DUMP` permission; iOS uses an application-scoped URL and simulator container.
- Wire the generated iOS host to the UIKit DevTools overlay and runtime metrics
  by default in debug builds.

## 0.6.73 - 2026-08-10

- Added per-action transition and duration overrides to imperative stack navigation.
- Added documented support for instant peer navigation without changing route animations.

## 0.6.70 - 2026-08-10

- Completed typed imperative navigation: `Navigator`, `NavigationAction`,
  `NavigationRef`, legacy `Router`, bottom/top tabs and drawers now accept the
  same string-backed route enums as `Route::screen()` and `Route::to()`. Route
  names are normalized before lookup, persistence, events and native dispatch.

## 0.6.69 - 2026-08-10

- Added typed `shared-transition` and `shared-transition-style` attributes to
  PAM `Image` and `MediaPlayer` templates. Feed, gallery and other declarative
  media screens can now participate in Shared Elements 2 without dropping down
  to programmatic element construction.

## 0.6.68 - 2026-08-10

- Added the Laravel-inspired `Route::` navigation facade for composable native
  stacks, adaptive bottom tabs, swipeable top tabs and responsive drawers.
  Navigator declarations may be nested without manually attaching hosts, and
  actions, system Back, state restoration and deep links recurse through the
  focused child graph.
- Added composable stack defaults, nested option groups and route-local fluent
  presentation, sheet, gesture and transition overrides. Deep-link aliases now
  accumulate per destination instead of replacing earlier patterns. Reusable
  immutable presets and `RouteModule` composition let feature packages own
  their route declarations without global registration order.
- Added string-backed enum destinations, typed `Route::to()` targets and the
  `pam-native-routes` generator for enum cases and statically typed route helper
  methods from a validated JSON contract.
- Added Shared Elements 2 with route-element timing or spring motion, resize
  strategy, snapshot cross-fade, corner interpolation, Android predictive Back
  progress and UIKit controller-host support. Cancellation restores original
  views and temporary native snapshots are bounded and released.
- Fixed iOS application delegate compatibility by exposing the window contract
  required by UIKit embedding hosts.
- Fixed generated Xcode product-name quoting and compiled the iOS bridge
  cleanly against both PHP 8.4 and PHP 8.5 headers.

## 0.6.67 - 2026-08-10

- Fixed Android decorative children with ripple styling retaining clickable or
  long-clickable state without an event handler. Images and icons nested in a
  `Pressable` no longer intercept the parent's touch target, so tapping any
  point inside an icon button dispatches its action consistently.

## 0.6.66 - 2026-08-10

- Fixed Android `KeyboardAvoidingView` padding behavior to reduce the flex
  viewport above the IME. Bottom composer controls now reflow above the
  keyboard even when the layout has no scroll descendant.

## 0.6.65 - 2026-08-10

- Fixed Android `BottomSheet` interactive keyboard behavior to move its
  configured detent above the IME. Bottom-anchored composers no longer remain
  clipped below the keyboard with inverted accessibility bounds.

## 0.6.64 - 2026-08-10

- Bound Android rich-list empty-holder recovery to one attempt per holder and
  item identity. Legitimately empty conditional rows no longer trigger an
  endless RecyclerView rebind/layout loop at 60 FPS, while native subtrees
  released from a previously populated holder still remount on route resume.

## 0.6.63 - 2026-08-10

- Fix Android `KeyboardAvoidingView` instances mounted while the IME is closing
  retaining an intermediate resize inset after the keyboard is hidden. PAM now
  reconciles newly mounted views with the authoritative IME visibility after
  traversal and animation settling.

## 0.6.62 - 2026-08-10

- Fix Android `KeyboardAvoidingView` resize behavior to reduce its native
  viewport and reflow flex descendants. Fixed composers and footer actions now
  remain above the IME instead of staying at their pre-keyboard screen position.

## 0.6.61 - 2026-08-10

- Fixed Android intrinsic text measurement for packaged fonts by reserving a
  small relative shaping/hinting allowance. Multi-word labels no longer wrap
  their final word into a clipped second line when `numberOfLines="1"` and the
  native `TextView` rounds slightly wider than the TTF advance metrics.

## 0.6.60 - 2026-08-10

- Preserve native module results and runtime reload signals when sustained UI
  input fills the Android or iOS event queue. The bridge now evicts an older,
  disposable UI event instead of silently dropping HTTP, SQLite, timer, or
  other native completion callbacks, and prioritizes critical FIFO delivery
  ahead of the UI backlog so promises and durable outboxes cannot starve.

## 0.6.59 - 2026-08-09

- Decode Android inline `data:image/*` assets on an isolated image lane. Small
  bundled UI assets such as icon masks now reach the first interactive frame
  without waiting behind remote photos, disk reads, or animated media decoding.

## 0.6.58 - 2026-08-09

- Snapshot Android virtualized lists before critical memory trimming. Releasing
  recycled child trees may mutate the renderer view registry, so iterating the
  live `LongSparseArray` could crash a backgrounded application with
  `ArrayIndexOutOfBoundsException`. Critical trim now visits the stable snapshot
  exactly once while preserving the existing memory-release behavior.

## 0.6.57 - 2026-08-07

- Add Android `KeyboardAvoidingView` behavior `interactive` for bottom sheets.
  The sheet follows the IME but clamps below safe top chrome, keeping headers,
  search fields, and actions visible instead of panning the entire surface off
  screen.

## 0.6.56 - 2026-08-07

- Keep translated Android composer actions tappable above a geometrically
  overlapping message scroller while still honoring interactive camera and
  media overlays in the visual stacking order.

## 0.6.55 - 2026-08-07

- Publish the translated composer touch-target refresh with synchronized
  Android, Rust protocol, and PHP SDK version metadata.

## 0.6.54 - 2026-08-07

- Refresh Android translated keyboard-avoidance touch targets at pointer-down.
  Interactive composer descendants that change while the IME remains open,
  such as a microphone button becoming a send button, now receive taps at
  their visible position immediately.

## 0.6.53 - 2026-08-07

- Respect Android visual stacking when dispatching translated keyboard-avoidance
  touch targets. Absolute camera, media, and composer overlays now receive taps
  instead of leaking them to a visually covered input underneath.

## 0.6.52 - 2026-08-07

- Invalidate dependency-skipped component trees before invoking template event
  handlers. Direct mutations of component properties from press, native-view,
  gesture, input, and other template events now reach the next encoded patch
  instead of reusing stale elements and native host properties.

## 0.6.51 - 2026-08-07

- Remount empty Android rich-list holders when a retained navigation route
  becomes visible again. `VirtualizedList` and `VirtualGrid` now recover their
  cells after route transitions and reactive appearance changes even when the
  containing window itself never changed visibility.

## 0.6.50 - 2026-08-07

- Use the React Native-compatible `46.5 × 27` logical-pixel footprint in the
  layout engine's intrinsic `Switch` geometry. This removes the legacy
  `52 × 48` frame before Android receives its exact native measure specs.

## 0.6.49 - 2026-08-07

- Match the intrinsic Android `Switch` footprint used by React Native
  (`46.5 × 27` logical pixels) while preserving authored exact dimensions and
  respecting bounded measure specs. Switches no longer expand compact rows to
  the platform widget's legacy 40dp minimum height.

## 0.6.48 - 2026-08-07

- Remount empty Android rich-list holders when a retained window becomes
  visible again. `VirtualizedList` and `VirtualGrid` now recover cells whose
  native subtree was released while the app was backgrounded, without waiting
  for scrolling or a data change.

## 0.6.47 - 2026-08-07

- Fully bind empty Android rich-list holders when a layout payload arrives
  before their initial materialization. `VirtualizedList` and `VirtualGrid`
  cells no longer remain blank after row-height, prefetch, or clipping setup.

## 0.6.46 - 2026-08-07

- Respect `min-width` and `min-height` while intrinsically measuring the cross
  axis of flex children, including wrapped rows. Auto-sized controls now reserve
  their authored logical minimum before line sizing and placement instead of
  collapsing to their label's intrinsic extent.

## 0.6.45 - 2026-08-07

- Keep Android pinch and rotation gestures inside their detector when nested
  in a `ScrollView` or pager. The detector now claims the touch stream when the
  second pointer lands and releases it when the gesture finishes, preventing a
  gallery pinch from being misinterpreted as horizontal page navigation.

## 0.6.44 - 2026-08-06

- Recover Android `autoFocus` when an already-retained conditional ancestor
  becomes visible. Inputs inside `p-if` subtrees now focus and request the IME
  at the visibility transition, even after the initial bounded retry elapsed.

## 0.6.43 - 2026-08-06

- Keep reactive Android `autoFocus` pending while its screen is detached or a
  navigation transition does not yet own window focus. A bounded retry now
  focuses the input and opens the IME as soon as that screen becomes active.

## 0.6.42 - 2026-08-06

- Make Android `autoFocus` open the software keyboard for inputs introduced by
  reactive renders. The renderer waits for attachment/window focus with a
  bounded retry and respects `showSoftInputOnFocus="false"`.

## 0.6.41 - 2026-08-06

- Preserve independent CSS `column-gap` and `row-gap` values in flex and
  `flex-wrap` layouts. The retained Rust engine now applies the horizontal and
  vertical axes independently during line breaking, placement and intrinsic
  measurement, while `gap` remains the fallback for either omitted axis.

## 0.6.40 - 2026-08-06

- Add an optional failure callback to `PushNotifications::register()` so FCM
  and APNs provider/configuration failures can recover application UI without
  throwing from an asynchronous native-module result. Existing positional API
  and exception behavior remain compatible when no callback is supplied.

## 0.6.39 - 2026-08-06

- Add native baseline cross-axis alignment throughout the typed API, template
  renderer, scoped CSS utility compiler and Rust layout engine. Horizontal
  flex rows now align text and controls by their typographic baseline while
  wrapped lines calculate an independent baseline for each line.

## 0.6.38 - 2026-08-06

- Match React Native text-input baselines on Android by disabling the platform
  font padding, and retry imperative scroll requests after the next rendered
  frame so end/target jumps survive concurrent content layout.

## 0.6.37 - 2026-08-06

- Add bounded `pressedScale` feedback to `Pressable` on Android and iOS,
  compositing it with authored `scaleX`/`scaleY` transforms and the existing
  `pressedOpacity` animation. The declarative `pressedScale` attribute and
  fluent `Pressable::pressedScale()` API preserve a scale of `1` by default.

## 0.6.36 - 2026-08-06

- Add typed `scrollTargetAlignment` (`start`, `center`, or `end`) to tokenized
  descendant scroll requests on Android and iOS scroll views and virtualized
  lists. Alignment accounts for target and viewport extent and clamps edge
  targets without changing the existing start-aligned default.

## 0.6.35 - 2026-08-06

- Add an optional failure callback to `Location::current()` so permission,
  provider and timeout failures can recover application UI without throwing
  from an asynchronous native-module result. Existing positional parameters
  and exception behavior remain compatible when no failure callback is given.

## 0.6.34 - 2026-08-06

- Add authenticated, observable sandbox downloads through
  `Files::downloadWithProgress()`, including validated request headers, typed
  byte progress, explicit failure callbacks and cancellation. The Android
  implementation streams off the UI thread, enforces the existing 256 MiB
  ceiling and atomically publishes only completed files.
- Add `Files::open()` and an app-private Android `FileProvider`, allowing a
  downloaded sandbox document to open in a compatible platform application
  without exposing `file://` URIs or granting broad storage access.
- Extend `Files::download()` with optional validated request headers and a
  non-throwing failure callback while preserving its existing positional API.

## 0.6.33 - 2026-08-06

- Add optional failure callbacks to `Linking::open()`, `canOpen()`, and
  `initial()` so applications can recover from asynchronous platform link
  failures without an uncaught exception.

## 0.6.32 - 2026-08-06

- Add optional failure callbacks to `AudioRecorder::start()`, `stop()`,
  `cancel()`, `discard()`, and `watch()` so applications can recover their UI
  from asynchronous native recorder failures without an uncaught exception.

## 0.6.31 - 2026-08-05

- Support tokenized `scrollRequest`, `scrollTargetOffset`, and
  `scrollTargetTestId` on Android and iOS virtualized lists, grids, and section
  lists, including variable-height cells and offscreen targets.

## 0.6.30 - 2026-08-05

- Route Android `MediaPlayer` HTTP and HTTPS sources through the network URL
  overload instead of `ContentResolver`, fixing remote audio/video failures
  reported as `No content provider` while preserving `content://`, `file://`
  and `android.resource://` playback.

## 0.6.29 - 2026-08-05

- Preserve explicit reactive `scrollRequest` commands on Android instead of
  restoring the pre-commit viewport afterward, fixing jump-to-target and
  jump-to-end controls that appeared to run but left the list in place.

## 0.6.28 - 2026-08-05

- Treat Android and iOS file/media picker cancellation as normal UI control
  flow: `Files::pick()` now resolves `null` and `Files::pickMany()` resolves an
  empty list instead of raising a native-module failure when users press back.
- Resolve camera cancellation with `null` through `MediaCapture::capture()` as
  well, while preserving native failures for actual capture/import errors.

## 0.6.27 - 2026-08-05

- Render Android `MediaPlayer` thumbnails as native posters and keep them
  visible until the first video frame reaches the `TextureView`, preventing
  blank media surfaces while remote playback prepares or buffers.

## 0.6.26 - 2026-08-05

- Make declarative `MediaPlayer autoPlay` fully reactive on Android and iOS:
  switching the bound value to `false` now pauses a prepared player at its
  current position instead of leaving native playback running.

## 0.6.25 - 2026-08-05

- Match Android CameraRoll recent-media ordering by sorting equal added-time
  assets by modified time instead of media ID.
- Keep custom PAM galleries aligned with native React Native gallery order and
  initial selection when several captures enter MediaStore in the same second.

## 0.6.24 - 2026-08-05

- Make the Android renderer release artifact self-contained for SDK assembly
  by shipping its matching Cargo workspace and Rust engine/protocol sources.
- Prevent a copied older SDK workspace from rebuilding the bundled native
  libraries with a stale protocol after an upgrade.

## 0.6.23 - 2026-08-05

- Add the typed `BorderStyle`/`borderStyle` protocol contract with `solid`,
  `dashed`, and `dotted` values.
- Compile scoped CSS `border-style` and render uniform patterned borders
  natively on Android and iOS, including rounded corners and image hosts.

## 0.6.22 - 2026-08-05

- Fix `Image`/`ImageBackground` `cachePolicy="none"` so templates map it to a
  typed native policy instead of throwing `Unknown template option none`.
- Make the no-cache policy bypass decoded-memory, HTTP and PAM media-disk
  reads/writes on Android and iOS, enabling safe retry after corrupt/stale
  remote image cache entries.

## 0.6.21 - 2026-08-05

- Paint the active declarative `StatusBar` color over the Android 15+
  edge-to-edge status-bar inset, so a light root view cannot show through a
  dark authored system-bar surface.
- Cover the Android 15+ rendered status-bar pixels in instrumentation tests,
  in addition to checking the window color and icon appearance contracts.

## 0.6.20 - 2026-08-05

- Reapply the active declarative `StatusBar` configuration after host-root
  background synchronization on Android 15 and newer, so a status-bar color
  different from the screen root is not overwritten at the end of a render
  commit.

## 0.6.19 - 2026-08-04

- Decode declarative `MediaPlayer` progress payloads and invoke template
  handlers with the documented `(float $currentTime, float $duration)` pair,
  matching the programmatic component API instead of passing one wire string.
- Preserve the actual incoming Android stack route when reconciliation moves a
  retained route after inserting a new route, so media viewers open directly
  and one system Back returns to the originating screen.
- Reapply absolute descendant layout after the native safe-area viewport is
  measured, including layout-only parent chains and native padding, so floating
  controls remain fully visible above persistent app and system navigation bars.
- Leave Android system-navigation visibility unchanged when a declarative
  `StatusBar` omits `navigationBarHidden`, preventing retained routes from
  overriding the active global navigation-bar contract.

## 0.6.18 - 2026-08-04

- Add Android immersive navigation control through
  `StatusBar::navigationBarHidden()` and the declarative
  `navigationBarHidden` property, including transient swipe recovery and
  safe-area inset updates when system navigation is hidden.
- Apply declarative `StatusBar` color, icon appearance, visibility and
  translucency to active Android modal windows, so full-screen modal routes no
  longer retain the dialog theme's light system bar.

## 0.6.17 - 2026-08-04

- Add bounded, atomic `Files::download()` support on Android and iOS for
  materializing HTTPS media into the PAM sandbox as a typed `FileReference`.

## 0.6.16 - 2026-08-04

- Canonicalize the Android file-sandbox root before deriving returned relative
  paths, preventing `/data/user/0` and `/data/data` aliases from producing a
  traversal-shaped `FileReference` after `Files::copyAsset()`.

## 0.6.15 - 2026-08-04

- Add `Files::copyAsset()` for atomically materializing packaged project assets
  in the application file sandbox without transporting base64 bytes through
  PHP, with traversal protection and Android/iOS implementations.

## 0.6.14 - 2026-08-04

- Add bounded, normalized positioned text-layer compositing to
  `ImageEditor::render()`, including scale, radian rotation, color and integer
  presentation style for editable social-media compositions.

## 0.6.13 - 2026-08-04

- Decode explicitly passed `$event` expression arguments into `GestureEvent`
  when the target method uses that type, enabling contextual handlers such as
  `moveLayer($id, $event)` without exposing the binary wire payload.

## 0.6.12 - 2026-08-04

- Reject malformed `p-for` directives during template compilation instead of
  deferring the deterministic failure until the component renders on-device.
- Validate that declarative `GestureDetector` trees resolve to exactly one
  child across conditional branches, while accepting complete mutually
  exclusive `p-if`/`p-else-if`/`p-else` chains.

## 0.6.11 - 2026-08-04

- Measure dialog-modal cards at their authored percentage or fixed width and
  intrinsic content height in the shared layout engine, then center the result
  in the viewport instead of assigning an implicit full-screen height.
- Preserve explicit dialog dimensions, min/max constraints, edge-pinned portal
  content and the existing full-screen/sheet presentation contracts.

## 0.6.10 - 2026-08-04

- Reveal the focused input automatically when padding-mode Android keyboard
  avoidance owns a contained vertical scroll view.
- Preserve a 16 dp focus clearance and honor `keyboardVerticalOffset` as extra
  space for adjacent form actions, with API 26–36 instrumentation coverage.

## 0.6.9 - 2026-08-04

- Normalize declarative `ScrollView` deceleration aliases so `normal` and
  `fast` encode as numeric protocol values accepted by both native hosts.
- Clamp declarative numeric deceleration rates to the supported `0...1` range
  and cover alias and numeric behavior with PHP SDK regressions.

## 0.6.8 - 2026-08-04

- Add `Sms::isAvailable()` and `Sms::compose()` for querying platform support
  and opening a pre-addressed native SMS draft without sending automatically.
- Restrict Android drafts to `ACTION_SENDTO` with the `smsto:` scheme so only
  messaging applications can handle the request, declare the corresponding
  Android package-visibility query, and use MessageUI's native composer on iOS.
- Validate recipient count, recipient length and draft size in the PHP SDK and
  both native hosts, with PHP and Android regression coverage.

## 0.6.7 - 2026-08-04

- Preserve an Android dialog modal's intrinsic card width and height and center
  it inside the native backdrop instead of stretching every dialog child to a
  full-screen page.
- Keep full-screen and sheet modal sizing unchanged and cover all three Android
  presentation contracts with instrumentation tests.
- Start Android contact pagination at the requested row, including offset zero,
  instead of discarding the entire first page when the cursor is initially
  positioned before its first result.

## 0.6.6 - 2026-08-03

- Promote the remaining replacement route when a renderer update removes the
  currently active Android route after a completed pop. This prevents a valid
  navigation stack from becoming an entirely blank native surface.
- Return the PHP navigator operation to `Idle` after native transition
  completion instead of leaving a settled pop encoded in later renders.
- Cover active-route replacement and settled-pop finalization with Android and
  PHP regression tests.

## 0.6.5 - 2026-08-03

- Preserve a subpixel safety guard for intrinsic text measured from packaged
  TTF/OTF advances. This prevents Android `TextView` from wrapping a final word
  into a clipped second line when platform hinting rounds the run fractionally
  wider than the font's ideal advance sum.
- Cover packaged-font intrinsic measurement with a regression fixture based on
  a compact chat bubble whose full final word must remain on one line.

## 0.6.4 - 2026-08-03

- Expose the device's IANA time-zone identifier through `DeviceInfo::timeZone`
  on Android and iOS so applications can format API timestamps in local time.
- Preserve source compatibility for manually constructed `DeviceInfo` values
  with a documented `UTC` fallback when older native hosts omit the field.

## 0.6.3 - 2026-08-03

- Honor dark status-bar icons on Android 15 and newer instead of silently
  forcing light icons after the platform's edge-to-edge transition.
- Cover active retained-route status-bar appearance changes on both modern and
  legacy Android window APIs.
- Compile static and bound `disabled` template attributes to the inverse native
  enabled state so controls stop interaction and report their state correctly
  to accessibility services.

## 0.6.1 - 2026-08-01

- Add production plugin manifests for typed IDL fingerprints, Swift Package
  dependencies, Apple frameworks, purpose strings, app entitlements, Info.plist
  fragments, and signed iOS extension targets.
- Generate deterministic Android and iOS plugin registries so third-party
  modules and views autolink without editing the PAM Native host.
- Add an injectable native-module transport for deterministic ecosystem tests
  while retaining the production bridge as the default.
- Accept official namespaced Android permissions and Apple's
  `NFCReaderUsageDescription` key in validated plugin metadata.
- Publish the official 24-package ecosystem architecture and compatibility
  contract.

## 0.6.0 - 2026-07-31

- Add Laravel-inspired named route stacks, tabs, screens and modals. Components
  navigate through their nearest application scope without receiving a
  `Navigator`, and class-based screens hydrate typed constructor parameters.
- Add a bounded typed bridge IDL compiler with sequential module/method/field
  IDs, SHA-256 fingerprints and generated PHP, Kotlin, Swift and Rust
  contracts.
- Promote the priority scheduler into cancellable `AsyncResource` and
  `Suspense` APIs; add deterministic bounded numeric `PNW1` worklet bytecode.
- Formalize advanced retained list/grid/section virtualization and introduce
  recoverable background jobs plus an idempotent offline mutation queue with
  typed lifecycle states, persistence, conflict handling and capped backoff.
- Add append-only Canvas node/property contracts and hardware-accelerated
  Android/UIKit renderers for bounded retained vector commands.
- Add bounded server-driven UI documents with integer node kinds, allowlisted
  components/styles and locally resolved actions; remote documents cannot name
  or execute PHP functions or classes.
- Make Android paging move at most one page per gesture, protect programmatic
  flings from stale gesture origins, and add matching paging, snap interval and
  deceleration behavior plus tests on iOS.
- Replace the repository landing page with an adoption-focused English README,
  publish the platform-runtime guide and migrate the showcase to named routes.

## 0.5.94 - 2026-07-31

- Clear process-local linking, incoming-share, and push subscriptions during runtime shutdown so hot reload can rebuild an app without duplicate-listener exceptions.
- Let a focused native-synced input accept an authoritative PHP value after its latest native value has been acknowledged, allowing composers to clear after send without dismissing the keyboard.

## 0.5.93 - 2026-07-31

- Make Android activity and transparent full-screen modal windows share one
  explicit edge-to-edge contract, including stable system-bar/display-cutout
  insets on Samsung and other OEM window implementations.
- Correct geometric IME overlap in full-screen windows and keep translated
  composer inputs and actions touch-aligned throughout keyboard animations.
- Restrict the closed-IME touch fallback to text inputs so modal taps cannot
  leak through to retained activity buttons, tabs or routes underneath.
- Add an Android API 26–36, navigation-mode, cutout, orientation, multi-window
  and OEM safe-area compatibility contract with a repeatable release matrix.
- Update AndroidX Core to 1.17.0 and cover translated touch registration with
  regression tests.

## 0.5.92 - 2026-07-31

- Complete the 17-block native navigation parity program: route identity,
  layered options, typed actions and bubbling, recursive state, auth guards,
  linking ownership, native controllers, headers/search, sheets, retained
  bottom/top tabs, drawer coverage, gestures, transitions and shared elements.
- Run shared-element geometry, tab selection, controller transitions and
  interactive Back entirely on Kotlin/UIKit UI threads without per-frame PHP
  work; add full-screen and vertical gestures, flip and simple-push variants.
- Bound speculative routes and navigation traces, release subscriptions and
  option layers deterministically, and forward complete Android/iOS lifecycle
  and critical-memory events.
- Add recursive navigation inspection, transition average/p95 metrics,
  versioned JSON exports, deterministic performance gates and a public parity
  matrix covering all delivered behavior.

## 0.5.91 - 2026-07-31

- Introduce Navigation Core 2 with typed actions, interceptable lifecycle
  events, recursive navigation state, exact-route preloading, dynamic options,
  canonical deep links and checksummed state restoration.
- Retain stack, bottom-tab, top-tab and drawer scenes by route key, bubble
  nested state without polling, and recurse Back through the focused child
  before changing its parent.
- Add native top tabs, tab/drawer history policies, reselect-to-pop behavior,
  badges, drawer groups, native headers, search, modal and form-sheet screen
  presentations, orientation policies and light/dark navigation themes.
- Run all stack transitions and interactive gestures on UIKit/Android's UI
  thread; implement every public transition on iOS and Android 14 predictive
  Back without sending progress frames to PHP or replaying the committed pop.
- Extend the append-only protocol with navigation orientation and home-indicator
  intent, including matching PHP, Rust, Kotlin and Swift definitions.
- Add PHP, Rust, Android and iOS regression coverage plus a complete Navigation
  Core 2 guide and compatibility notes.

## 0.5.90 - 2026-07-31

- Establish one explicit Android edge-to-edge window contract before mounting
  the PAM host, avoiding OEM-dependent decor fitting on Samsung devices.
- Capture stable system-bar and display-cutout insets at the root host and use
  them as the lower bound for both `SafeAreaView` and early `DeviceInfo`
  queries, preventing transient zero bottom insets during cold start.
- Stop using Android's legacy visible display frame to compensate edge-to-edge
  flex layouts, which could subtract status/navigation bars a second time from
  fixed bottom siblings.
- Add `Router::restoreState(false)` for applications that require a
  deterministic initial route without deleting persisted domain state or
  briefly mounting a historical navigation stack.

## 0.5.89 - 2026-07-30

- Resolve Android safe areas from stable system-bar and display-cutout insets,
  including transient gesture/button navigation bars and early runtime device
  queries.
- Replace the decor-size heuristic with per-view geometric intersection
  against the window safe rectangle, preventing both missing and duplicated
  insets in edge-to-edge, decor-fitted, nested and bottom-bar layouts.
- Include display cutouts on Android API 28–29 and keep the same stable
  behavior through API 36, with regression coverage for portrait, landscape,
  fullscreen, fitted and bottom-overlap cases.
- Resolve iOS device information from the active window instead of the main
  screen, preserving correct dimensions and safe-area values for notches,
  home indicators, rotation and iPad multiwindow layouts.
- Add UI-thread-native transforms to `GestureDetector` for pan, pinch and
  rotation. High-refresh-rate media viewers can now follow the display cadence
  without dispatching a PHP event or committing a render tree on every frame.
- Add bounded native scale and revision-based transform reset properties with
  matching Android, iOS, PHP and Rust protocol contracts.
- Compose explicit `on:change` handlers with `bind:value` updates so input
  masks and validators observe the freshly bound value instead of being
  silently replaced by the model callback.

## 0.5.88 - 2026-07-30

- Added a public, package-restricted Android background-push broadcast contract
  to the plugin API. Plugins can now handle FCM data-only messages while the
  PHP runtime is suspended by declaring a non-exported receiver for
  `dev.pam.nativeapp.action.PUSH_RECEIVED`.
- Firebase delivery continues to persist the normal PAM push event before
  dispatching the native plugin broadcast, preserving foreground, opened and
  cold-start PHP routing.

## 0.5.87 - 2026-07-30

- Add an explicit pure-function allowlist to restricted template expressions
  for `trim`, `ltrim`, `rtrim`, byte/multibyte length, substring, and casing
  helpers.
- Compose those helpers in interpolations and conditional attributes without
  `eval`, while preserving public component-method calls.
- Reject filesystem, process, network, dynamic, and every other unlisted PHP
  function, with regression coverage for the exact nested expressions used by
  native search, comments, chat, and group-call screens.

## 0.5.86 - 2026-07-30

- Resolve safe-area padding against the system-bar space already consumed by
  Android's decor-fitted content frame. `SafeAreaView` no longer applies the
  status or navigation inset twice on Android versions and OEM windows that do
  not run edge-to-edge.
- Preserve full safe-area padding for edge-to-edge windows and preserve only
  the unconsumed edge in mixed layouts such as a translucent status bar with a
  decor-fitted navigation bar.
- Add unit coverage for decor-fitted, edge-to-edge, and mixed window modes.

## 0.5.85 - 2026-07-30

- Snap Android layout edges in the absolute physical-pixel grid, deriving each
  rendered extent from its rounded start and end edges instead of rounding
  position and size independently.
- Preserve the exact shared center of text-and-icon controls at fractional
  display densities, removing the remaining one-pixel vertical or horizontal
  drift in buttons, headers, tabs, badges, and nested flex layouts.
- Make adjacent siblings share the same rounded edge and round negative
  offsets symmetrically, with unit coverage for all three contracts.

## 0.5.84 - 2026-07-30

- Measure intrinsic text with the actual packaged TTF/OTF face selected by
  `@font-face`, eliminating clipped labels and font-specific centering drift.
- Cache font bytes and normalized glyph advances by asset face, sharing each
  immutable metric snapshot across every text node that uses it.
- Resolve asset fonts before the first native mount on Android and iOS, with no
  UI-thread measurement, post-render correction, or extra frame.
- Keep safe path resolution and the allocation-free generic estimator as the
  fallback for installed, missing, or unsupported font families.

## 0.5.83 - 2026-07-30

- Calibrate intrinsic text widths against Android sans-serif glyph advances
  instead of reserving broad per-run safety space.
- Keep centered text-and-icon rows optically aligned with their container while
  preserving the sub-pixel tolerance that prevents phantom wrapping.
- Refine narrow, punctuation, lowercase, uppercase, digit, and wide-glyph
  classes with regression coverage for text scale and letter spacing.

## 0.5.82 - 2026-07-30

- Preserve inherited CSS typography across compiled component boundaries
  through an internal style context, while keeping those values out of typed
  constructor props and declarative component variants.
- Fix nested icons and other prop-strict components failing when an ancestor
  authors `color`, font, line-height, alignment, letter spacing, or text case.
- Cover both the public prop boundary and inheritance into a nested
  `.pam.php` component with regression tests.

## 0.5.81 - 2026-07-30

- Add PHP-compatible, right-associative `??` null coalescing to restricted PAM
  template expressions, including safe missing and nested array/property paths.
- Keep missing values strict outside a coalescing expression and cover null,
  present, chained and nested fallback behavior without introducing `eval`.

## 0.5.80 - 2026-07-30

- Accept Expo Image's familiar `cachePolicy="memory-disk"` and camel-case
  `memoryDisk` spellings as aliases for PAM's native memory-plus-disk
  `force-cache` policy on `Image` and `ImageBackground`.
- Publish the aliases through the PAM template completion metadata and cover
  the mapping with a renderer regression test.

## 0.5.79 - 2026-07-30

- Accept `keyboardType="default"` and the familiar React Native keyboard names
  (`email-address`, `number-pad`, `numeric`, `phone-pad`, `decimal-pad`, and
  text-keyboard variants) as aliases for PAM's typed native keyboard modes.

## 0.5.78 - 2026-07-30

- Accept the standard CSS `text-align: left` and `text-align: right` values as
  aliases for PAM's direction-aware `start` and `end` alignment. Existing
  templates keep their behavior, while web-style component CSS no longer
  fails during mount.

## 0.5.77 - 2026-07-30

- Position absolute flex children with automatic insets from their CSS static
  position. `align-items`/`align-self` and `justify-content` now center or
  trail badges, tab indicators, logo layers, and other absolute adornments
  without app-specific offsets.

## 0.5.76 - 2026-07-30

- Add safe PHP `.` string concatenation to template expressions with PHP 8
  arithmetic precedence, while preserving PAM's `$object.property` shorthand.
  Only scalar, null, and `Stringable` operands are accepted; arrays and other
  unsafe coercions fail during rendering.

## 0.5.75 - 2026-07-30

- Resolve Android `StatusBar` from the active retained navigation route instead
  of letting a newer hidden route override the visible screen. Push, pop,
  replace, restored routes, and cancelled back gestures now update system UI
  from the actual route target.

## 0.5.74 - 2026-07-30

- Align conservative intrinsic text frames from the relevant flex axis:
  `align-items`/`align-self` in columns and `justify-content` in rows. Centered
  text-and-icon controls now stay optically centered without changing the
  authored alignment of explicitly sized or growing text.

## 0.5.73 - 2026-07-30

- Add typed, zero-selector-runtime CSS `box-shadow` with color, x/y offset,
  blur, spread, and `none` across Android and iOS.
- Center auto-width native text inside its deliberately conservative intrinsic
  frame whenever the parent cross-axis alignment is centered or trailing,
  eliminating visible drift without sacrificing glyph-clipping protection.
- Apply CSS `text-align` on iOS with the same start/center/end behavior as
  Android.
- Accept `backgroundColor`, `barStyle`, `animated`, and `translucent` on
  template `StatusBar`, matching familiar mobile authoring without silently
  leaving the platform defaults active.

## 0.5.72 - 2026-07-30

- Resolve every `asset://…` image and packaged font relative to the PAM
  project bundle on Android and iOS, without exposing the internal `pam/`
  namespace to application code.
- Reject empty, traversal, query, fragment, and malformed packaged-asset paths
  before they reach platform file APIs.

## 0.5.71 - 2026-07-30

- Compile CSS colors with web semantics: `transparent`, the complete named
  color set, `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`, modern/legacy
  `rgb()`/`rgba()`, and `hsl()`/`hsla()`.
- Make scoped cascade independent from class order in markup: tag rules form
  the base, matching classes use stylesheet source order, and authored PAM
  attributes win last.
- Inherit native text color, family, size, weight, style, spacing, line height,
  alignment, and case through layout containers, while resolving each
  descendant against the best packaged `@font-face`.
- Add nested custom-property references and `var(--token, fallback)` with
  circular-reference and expansion-depth protection.
- Add `rem`, logical inline/block box shorthands, CSS `transform`,
  `object-fit`, `visibility`, `box-sizing: border-box`, percentage opacity,
  ratio syntax, font stacks, `border: none`, `background: none`, and common
  text-decoration syntax to the zero-runtime scoped CSS compiler.

## 0.5.70 - 2026-07-30

- Make the Android renderer artifact self-contained for Firebase-enabled apps
  by shipping the root Google Services plugin declaration, Firebase source
  set, and ProGuard configuration with the app renderer.

## 0.5.69 - 2026-07-30

- Retry transient Android image-load failures while their native view remains
  attached, preventing the first visible `VirtualizedList` cell from staying
  blank until it is recycled.
- Reuse a completed same-source image request only while it still owns visible
  pixels; a completed request without a drawable now starts fresh immediately.

## 0.5.68 - 2026-07-30

- Include the versioned native engine C header in the Android renderer archive,
  keeping bridge sources, static engines and their ABI contract self-contained.

## 0.5.67 - 2026-07-30

- Recover automatically from a rejected incremental render patch by preserving
  PHP component identity and immediately resynchronizing the native renderer
  with one complete tree.
- Make the property-only Rust fast path transactional with a proportional
  rollback journal, preserving its no-tree-clone performance on success.
- Expose the retained engine's last commit error through its stable C ABI and
  include the precise diagnostic in Android and iOS logs.
- Prevent recoverable patch desynchronization from opening a native runtime
  error overlay.
- Force release jobs to rebuild Android engine archives from source so cached
  cross-target artifacts cannot leak into a newer protocol release.

## 0.5.66 - 2026-07-30

- Allow a false conditional component root to render an inert invisible native
  placeholder that consumes no parent flow space, while continuing to reject
  ambiguous multiple-root templates.

## 0.5.65 - 2026-07-30

- Add safe numeric `+`, `-`, `*`, `/`, and integer `%` operators to template
  expressions with conventional precedence, grouping, numeric type checks, and
  division-by-zero protection.

## 0.5.64 - 2026-07-30

- Compile the complete one-to-four-value CSS `border-radius` shorthand into
  native per-corner radii while preserving the compact uniform-radius path.
- Add percentage `left`, `top`, `right`, and `bottom` offsets to the protocol
  and resolve them against the retained inner containing block in Rust.

## 0.5.63 - 2026-07-30

- Add native `flex-wrap` row and column layout to the retained Rust engine,
  including intrinsic cross-axis measurement for content-sized containers.
- Compile CSS `inset`, `translation-x`, and `translation-y` into native layout
  and compositor properties.
- Accept directional border color declarations while documenting that the
  current native border renderer uses one shared color for all four edges.

## 0.5.62 - 2026-07-30

- Compile native-safe `border-top`, `border-right`, `border-bottom`, and
  `border-left` CSS shorthands into directional widths and the shared native
  border color, matching the existing `border` shorthand.

## 0.5.61 - 2026-07-30

- Add a native `DrawingCanvas` on Android and iOS with coalesced freehand
  input, brush/eraser modes and tokenized undo/clear commands. Completed
  strokes cross the PHP boundary only once and stay normalized to the displayed
  image content.
- Let the off-render-thread image editor flatten bounded drawing documents
  before transforms and export.
- Enforce `maxWidth`, `maxHeight` and `outputQuality` in the iOS image editor,
  matching the Android contract introduced in 0.5.59.

## 0.5.60 - 2026-07-30

- Add `scrollTargetOffset` to tokenized `scrollRequest` operations so an
  application can restore an observed logical scroll offset without binding a
  continuously controlled `contentOffset`.
- Preserve existing descendant `scrollTargetTestId` priority and end-scroll
  behavior while applying explicit offset requests on Android and iOS.

## 0.5.59 - 2026-07-30

- Add bounded `maxWidth`, `maxHeight`, and `outputQuality` controls to the
  native image editor without breaking existing calls.
- Decode large Android images with a source-size-aware sample before crop,
  effects, and composition, then perform one final filtered resize off the UI
  thread.
- Document the optimized avatar and attachment pipeline in the package and
  public PAM Native media guides.

## 0.5.58 - 2026-07-30

- Use the context-aware `MediaRecorder` constructor on Android 12 and newer
  while retaining the API 26–30 fallback.
- Publish native tri-state accessibility check semantics on Android 16 and
  preserve boolean compatibility on earlier platform versions.
- Replace pooled accessibility range metadata on Android 11 and newer while
  retaining the legacy API 26–29 path.
- Keep Android media-path tests null-safe under the current Kotlin compiler.

## 0.5.57 - 2026-07-30

- Add a direct Android `MediaLibrary` API for paginated image/video metadata,
  album summaries and Android 14 selected-photo access, with all MediaStore
  work isolated from the UI thread.
- Add `Files::importUri()` so custom galleries copy only the asset the user
  actually selects into PAM's bounded private sandbox.
- Return typed `MediaAsset`, `MediaAssetPage` and `MediaAlbum` values without
  moving thumbnail bytes through the PHP bridge.
- Upgrade `PermissionKind::Photos` on Android to report granted, limited,
  denied and blocked access across the platform's versioned media permission
  model.

## 0.5.56 - 2026-07-30

- Synchronize Android `google-services.json` through an incremental Gradle
  task with exact file inputs and outputs, keeping Firebase builds correct
  after `mobile prepare` even when Gradle reuses its configuration cache.

## 0.5.55 - 2026-07-30

- Compile `text-decoration` from PAM styles into the existing typed native
  text-decoration contract, including underline and line-through variants.
- Remove empty scoped-style blocks during formatting.

## 0.5.54 - 2026-07-30

- Apply the conventional `src/app.css` stylesheet to every PAM component at
  compile time, with local scoped rules winning the cascade and no runtime CSS
  parser or selector pass.
- Resolve imports relative to the global or local stylesheet that declares
  them and include the complete global graph in component cache invalidation.
- Add optional zero-glue Android Firebase Cloud Messaging reception when
  `.pam/google-services.json` or `google-services.json` is present, without
  adding Firebase bytecode to applications that do not configure it.
- Persist received native push payloads across process/runtime startup so PHP
  listeners can reconcile background events reliably.

## 0.5.53 - 2026-07-30

- Expose top, right, bottom, and left safe-area insets through `DeviceInfo` in
  logical points on Android and iOS so custom native chrome can match each
  device without fixed status-bar or navigation-bar guesses.

## 0.5.52 - 2026-07-30

- Add compile-time relative `@import` support to scoped PAM styles so shared
  fonts, tokens, tag defaults, and semantic classes can live in ordinary CSS
  files without adding a runtime CSS engine.
- Resolve nested imports inside the nearest Composer project, reject traversal,
  remote sources, cycles, oversized graphs, and invalid syntax, and include
  imported contents in component cache invalidation.
- Preserve and normalize `@import` statements in `pam-native-format`.

## 0.5.51 - 2026-07-30

- Render Android video through a retained `TextureView` and native
  `MediaPlayer`, keeping playback inside ordinary view clipping, transforms,
  transitions, and z-order instead of a detached `SurfaceView` layer.
- Preserve native transport controls, seeking, looping, playback rate,
  lifecycle pause/resume, streaming cache, and media events on the new
  texture-backed player.
- Clip the complete Android virtual-list draw pass, including overlays and
  foregrounds, at the authored viewport.
- Install the release-like Android application target before Macrobenchmark
  instrumentation instead of treating the benchmark module as a
  self-instrumenting application.

## 0.5.50 - 2026-07-30

- Clip Android virtualized-list drawing directly at the native canvas
  viewport, including translated or animated rich descendants that platform
  child clipping alone cannot contain.
- Add compile-time scoped `@font-face` aliases for packaged TTF and OTF assets,
  with numeric weight and italic variant selection through familiar CSS
  `font-family`, `font-weight`, and `font-style` declarations.
- Resolve font aliases to cached native asset families before the component is
  rendered, preserving PAM's zero-CSS-runtime contract.

## 0.5.49 - 2026-07-30

- Keep Android virtual-list viewports and recycled holders as hard native paint
  boundaries so media cannot draw over headers, adjacent rows, or tab bars
  while scrolling.
- Preserve component-level `overflow` behavior inside each recycled cell.

## 0.5.48 - 2026-07-30

- Let rich `VirtualizedList` and `VirtualGrid` cells keep their authored
  heights, or widths for horizontal lists, while `rowHeight` remains the
  fallback and prefetch estimate.
- Size each Android `RecyclerView` holder from the Rust-computed cell frame and
  patch changed extents without remounting stable cells.
- Apply extent-only updates synchronously on already-bound Android holders and
  preserve their internal `RecyclerView.LayoutParams` ownership, avoiding a
  stale frame on older API levels.
- Add the clearer `estimatedRowHeight` PHP and template alias while preserving
  existing `rowHeight` call sites.

## 0.5.47 - 2026-07-29

- Clip Android container descendants to the authored rounded border path when
  `overflow: hidden` is active, including `View`, `Row`, `Column`,
  `Pressable`, and `ImageBackground`.
- Recompute the native clip path only when bounds or corner radii change and
  restore visible overflow immediately when the property is removed.
- Connect the same public overflow contract to UIKit `masksToBounds` on iOS.

## 0.5.46 - 2026-07-29

- Give declarative `ScrollView` a native `Row` or `Column` content container,
  allowing one or many direct children without stretching a compact item to
  the viewport or failing when a conditional loop renders multiple items.
- Keep low-level `Scroll` source-compatible with its explicit single-content
  contract.

## 0.5.45 - 2026-07-29

- Add compiled `<style scoped>` blocks to `.pam.php` components with native
  tag/class selectors, component-local custom properties, deterministic
  cascade, dynamic classes, percentages, and box/border shorthands.
- Reject unsupported selectors, nested rules, unresolved variables, and CSS
  properties without a native protocol contract during component compilation.
- Make `p-if`, `p-else-if`, `p-else`, and `p-for` the canonical PAM template
  directives while retaining deprecated `v-*` aliases for migration.
- Ship the idempotent `pam-native-format` Composer binary to indent templates,
  normalize scoped styles, and migrate legacy directives across files or
  directories.
- Redistribute flex growth after min/max constraints instead of leaving unused
  space, and reject non-finite flex bounds before they reach native frames.
- Match Rust text measurement to the logical-point letter-spacing contract and
  apply authored `lineHeight` exactly on retained Android text views.
- Convert non-animated translation values from logical points to Android pixels
  consistently with animated transforms.
- Fix `w-full` to resolve to 100% of the containing block and add `h-full`.
- Map `contain` images to Android `FIT_CENTER`, matching PAM's authored-frame
  contract by scaling small and large bitmaps proportionally to fit.
- Remove the need for layout-breaking scale transforms when rendering compact
  packaged icons and other low-resolution assets.

## 0.5.44 - 2026-07-29

- Cancel stale native long-poll waiters before a hot reload and let the new PHP
  runtime immediately re-arm deep-link, incoming-share, and push listeners.
- Drop asynchronous native completions from older runtime generations so a
  reload cannot deliver obsolete callbacks into the new application instance.

## 0.5.43 - 2026-07-29

- Convert logical-point text letter spacing to Android `em` units using the
  authored font size, matching the shared PAM and React Native-style contract.
- Reapply letter spacing when font size changes so retained text stays
  metrically stable.

## 0.5.42 - 2026-07-29

- Load project-packaged TTF and OTF families through `fontFamily="asset://…"`
  on Android, with guarded asset paths and a per-renderer native typeface cache.
- Preserve Android installed-family behavior for ordinary font family names and
  fall back safely when a packaged font cannot be decoded.

## 0.5.41 - 2026-07-29

- Prune obsolete content-addressed Android application releases outside the
  startup path while retaining the active bundle and one previous release for
  rollback or diagnostics.
- Preserve application state, Nitro databases, cached media, and the active
  executable bundle during release cleanup.

## 0.5.38 - 2026-07-29

- Keep the iOS image-editor wire decoding contract local to its module so the
  release renderer compiles independently from other module extensions.

## 0.5.37 - 2026-07-29

- Add a typed, asynchronous native image editor with crop, rotation, horizontal
  flip, filters, tonal adjustments, text overlays, and compact stickers.
- Keep edited images inside the guarded PAM file sandbox as upload-ready JPEGs.

## 0.5.36 - 2026-07-29

- Apply the shared `ImageFit` contract to native video players on Android and
  iOS, including aspect-fill `cover`, aspect-fit `contain`, and `stretch`.
- Expose `MediaPlayer::fit()` in the PHP SDK and keep its reset behavior
  consistent with native images.

## 0.5.35 - 2026-07-29

- Keep PAM layout frames authoritative for Android images so intrinsic drawable
  proportions cannot collapse full-width media inside virtualized list cells.

## 0.5.34 - 2026-07-29

- Add cross-platform cache usage and cleanup through `Caches`, reporting image,
  media and temporary bytes while preserving explicitly pinned offline media by
  default.

## 0.5.33 - 2026-07-29

- Add configurable Android share targets and a typed `IncomingShares` API for
  cold/warm `ACTION_SEND` and `ACTION_SEND_MULTIPLE`, importing shared files
  into the app sandbox before dispatch.

## 0.5.32 - 2026-07-29

- Parse template tags with a quote-aware scanner so comparison operators such
  as `<` and `>` remain valid inside bound and conditional attributes.

## 0.5.31 - 2026-07-29

- Added bounded incoming deep-link delivery for Android cold starts and
  `singleTask` warm starts through `Linking::initial()` and
  `Linking::listen()`.
- Added `Linking::listenAndRoute()` to connect native URL delivery directly to
  the existing typed navigator matcher.
- Added the public `PamLinking` bridge for iOS application and scene delegates.
- Custom schemes can match both URI path-only patterns and host-plus-path
  patterns such as `pushin://profile/david`.

## 0.5.30 - 2026-07-29

- Added cross-platform `MediaPickerType::Media` filtering for image-or-video
  social galleries without exposing unrelated documents.
- Sync the current development bundle when Android reconnects after process
  restart, eliminating stale embedded screens on the first render.

## 0.5.29 - 2026-07-29

- Added tokenized native `ScrollView` requests for instant jumps to the end or
  to a descendant identified by `testId`, without measuring content in PHP.
- Added PHP builder and template APIs through `scrollRequest` and
  `scrollTargetTestId`.
- Added Android instrumentation coverage for deterministic target and end
  scrolling.

## 0.5.28 - 2026-07-29

- Flush focused `sync="native"` input values before press actions so handlers
  always receive the exact text visible on screen without per-keystroke bridge
  traffic.
- Let Android's IME consume Back inside PAM modals before dispatching the
  modal's route-close callback.

## 0.5.27 - 2026-07-29

- Let media capture callers handle camera unavailability and user cancellation
  through an optional failure callback instead of crashing the PHP runtime.

## 0.5.26 - 2026-07-29

- Add cross-platform `AudioRecorder::watch()` telemetry with coalesced native
  duration and normalized amplitude samples on Android and iOS.
- Stop recorder observations automatically when a recording is stopped,
  cancelled or the runtime closes.

## 0.5.25 - 2026-07-29

- Add guarded `count()` and strict-capable `in_array()` collection helpers to
  declarative template expressions, enabling efficient derived list state
  without duplicating membership flags into every rendered item.

## 0.5.24 - 2026-07-29

- Keep the resolved Android sandbox file URI after the media-cache pass instead
  of allowing its no-op result to restore the original `pam-file:///` source.

## 0.5.23 - 2026-07-29

- Preserve the provider's original display name when Android and iOS import a
  picked file, while keeping the collision-resistant UUID exclusively in its
  opaque sandbox path.

## 0.5.22 - 2026-07-29

- Resolve `pam-file:///` video and audio sources into the application sandbox
  before Android playback, with the same authority, traversal, existence and
  percent-decoding guarantees already used by native images.

## 0.5.21 - 2026-07-29

- Cancel competing `Pressable` tap and long-press semantics as soon as a
  composed native gesture recognizes movement, preventing pan/swipe actions
  from also opening contextual menus.

## 0.5.20 - 2026-07-29

- Keep fixed header and footer dimensions intact inside Android
  `SafeAreaView`, while flex children consume the real visible window
  viewport exactly once across edge-to-edge and consumed-system-bar modes.
- Hydrate declarative gesture payloads as typed `GestureEvent` objects.
- Give template `GestureDetector` tags the same pointer and distance defaults
  as the imperative API, including two-pointer pinch and rotation gestures.

## 0.5.19 - 2026-07-29

- Persist completed audio recordings inside `pam-files/recordings` on Android
  and iOS so voice-message outboxes survive process death and device restarts.
- Expose the upload-ready sandbox `relativePath` while preserving the recording
  URI for playback and guarded deletion.

## 0.5.18 - 2026-07-29

- Deliver HTTP transport failures as status-zero `HttpResponse` values with a
  typed error instead of throwing across the component runtime.

## 0.5.15 - 2026-07-29

- Add typed `Files::pickMany()` on Android and iOS with native multi-selection,
  ordered background imports, bounded batches, unique sandbox paths, and
  transactional cleanup when an import fails.

## 0.5.14 - 2026-07-29

- Accept React Native/CSS-compatible `flex-start` and `flex-end` aliases for
  template `alignItems`, `alignSelf`, and `justifyContent` properties.

## 0.5.13 - 2026-07-29

### Added

- Add `SQLite::transaction()` for up to 10,000 heterogeneous prepared
  statements in one bridge call and one native Android/iOS transaction.
- Roll back the complete statement batch on the first preparation, binding, or
  execution failure, enabling atomic offline-first snapshot replacement.

## 0.5.12 - 2026-07-29

### Fixed

- Template `&&` and `||` expressions now always consume their right operand,
  preventing valid compound conditions from failing with an unexpected-token
  error when PHP short-circuits the evaluated boolean value.
- Empty successful storage reads are treated as cache misses instead of invalid
  wire maps, and iOS now returns the same encoded empty-map contract as Android.

## 0.5.11 - 2026-07-29

- Add a typed, asynchronous audio recorder for Android and iOS with AAC/M4A
  capture, real duration and file-size metadata.
- Support cancellation and guarded deletion of temporary recorder files.
- Release recorder resources and audio sessions deterministically during
  shutdown and failure paths.

## 0.5.10 - 2026-07-29

- Make template `bind:value` and `bind:checked` changes participate in the
  component lifecycle by invoking `updating`, `updated`, and `propsChanged`.
- Avoid lifecycle work when a native binding reports an unchanged value.
- Add PHP SDK contracts covering lifecycle-aware text and toggle bindings.

## 0.5.9 - 2026-07-28

- Give Android dialogs an overlay-priority predictive-back callback so one
  hardware Back gesture closes only the top modal instead of also popping the
  underlying PAM Native route.
- Decode cached GIF and animated WebP sources as native animated drawables on
  Android 9+, without re-downloading or flashing the image between renders.

## 0.5.8 - 2026-07-28

- Add typed, asynchronous current-location access on Android and iOS with
  configurable accuracy, timeout and cached-position age.
- Return coordinates, accuracy, altitude, speed, bearing and capture timestamp
  through the public PHP SDK without blocking rendering.

## 0.5.7 - 2026-07-28

- Coerce dynamically bound hexadecimal colors to native integer values across
  all color properties, matching static template color behavior.
- Include the received wire value type in Android integer-property errors.

## 0.5.6 - 2026-07-28

- Add a first-class system Back interceptor to stack navigation so screens can
  dismiss transient editing, selection, search, sheet, and viewer states before
  the route is popped.

## 0.5.4 - 2026-07-28

- Add a native SQLite bulk-write fast path that crosses the bridge once,
  reuses one prepared statement and commits one transaction.
- Enable WAL, normal synchronous durability, foreign keys, bounded lock waits,
  and memory-backed temporary storage for private application databases.

## 0.5.3 - 2026-07-28

- Keep flex layouts consistent after safe-area insets reduce their native
  viewport, so fixed headers and composers remain visible around flexible
  scroll content.
- Clip Android scroll content to its viewport and preserve end-following
  during renderer reconciliation.
- Ship the Android renderer sources and matching Rust engines together in the
  GitHub release to prevent mixed protocol versions.

## 0.5.2 - 2026-07-28

- Add typed, paginated Android and iOS contacts access with an explicit
  contacts permission.
- Add native chat timeline anchoring, near-end auto-follow and visible-position
  preservation to `ScrollView` across Android and iOS.

## 0.5.1 - 2026-07-28

- Add generic native HTTP requests across Android and iOS with GET, POST, PUT,
  PATCH and DELETE methods, bounded request bodies and timeouts, custom headers,
  Bearer authentication and JSON helpers while preserving the existing GET API.

## 0.5.0

- Consume independently versioned PHP 8.4 and 8.5 Android runtimes owned and
  verified by PAM, with side-by-side layouts and project-level selection
  compatible with reproducible CLI lock files.

## 0.4.8

- Reapply explicit Android scroll offsets after retained content completes its
  next layout pass, keeping newly appended chat bubbles visible above the
  composer and software keyboard.
- Tighten the showcase chat composer to the Android keyboard and add
  comfortable native input padding while keeping its header fixed above the
  keyboard-adjusted conversation.
- Keep showcase headers in a stable foreground layer and adapt Android 15+
  status-bar icons to the platform-enforced dark system-bar background.

## 0.4.7

- Keep the chat composer focused and the software keyboard visible after
  sending while clearing the retained native input authoritatively.
- Animate only newly sent bubbles with a short native spring using opacity,
  translation and scale, then keep the conversation pinned to its end.

## 0.4.6

- Give every Gallery detail screen a consistent, safe-area-aware header frame
  with centered 48 dp controls and deliberate spacing below system UI.
- Polish the chat with correctly aligned incoming and outgoing bubbles,
  append-only messages, automatic end positioning and a keyboard-safe composer.
- Translate the complete Gallery and engineering lab experience to English.

## 0.4.5

- Keep `KeyboardAvoidingView` composers and submit actions visible on
  edge-to-edge Android hosts by combining animated IME insets with the actual
  window-resize delta, including reliable listener cleanup on unmount.
- Add the presentation-ready PAM Native Gallery with commerce, offline
  finance, chat and field-operation experiences while preserving the original
  runtime laboratory and 10,000-row benchmark route.
- Replace default showcase header buttons with accessible 48 dp native
  pressables, consistent state feedback and product-specific visual treatment.
- Align the starter documentation, showcase and reference plugin with the
  current `0.4.x` SDK and add `pam mobile doctor` to the first-run path.

## 0.4.4

- Add first-class native image/video/audio caching with memory and disk
  policies, TTL, stable keys, offline pinning, checksums, deduplicated
  downloads, bounded eviction, cache lifecycle events, and declarative tag
  attributes on `Image` and `MediaPlayer`.
- Add production runtime fast paths: bounded priority scheduler, render
  coalescing, property-level dependency tracking, cached component factories,
  strict compiler checks, correlated profiling, confirmed-frame checkpoints,
  deterministic fuzzing and enforceable encoder performance budgets.
- Expand `Component` with typed immutable props, setup/render hooks, reactive
  local state, computed/memo values, update guards, effects/watchers, guaranteed
  cleanup, render error boundaries, provide/inject context, typed slots/events,
  exposed component refs and lifecycle-safe native refs.
- Add Pam Store global reactive state with atomic actions, computed values,
  selectors, transactions, subscriptions, versioned persistence, migrations,
  middleware, action policies, optimistic rollback, undo/redo, time travel,
  encrypted persistence and SQLite/API replica adapters.
- Add typed permission decisions, push receive/open streams with deep-link
  routing, continuous sensor/device observation and lifecycle-aware media.
- Expand DevTools with capability latency/failure timelines and native
  integration-test fixtures.
- Harden WebView navigation, file streaming, SQLite result sizes and bounded
  push payload queues.
- Add semantic gestures, interactive stack navigation and native Bottom Sheets.
- Add declarative keyframe animation, advanced image loading, WebView and native
  video/audio playback.
- Add typed files, document picking, camera capture, SQLite, background tasks,
  local/push notification registration, clipboard, drag/drop, native menus,
  sensors and device-state APIs across Android and iOS.

## 0.3.0

- Add integer-backed async, form, motion, haptic and adaptive-tab contracts.
- Add attribute-driven typed forms with drafts, server errors and explicit
  submission state.
- Add native Android and iOS motion with accessibility preferences respected.
- Add lazy adaptive tab navigation with persistence, branded appearance,
  accessibility semantics and selection haptics.
- Add Android system haptics, a live UIKit DevTools overlay and expanded
  product-level tests.

## 0.2.1

- Add Android protocol, renderer, event routing, view identity and navigation
  instrumentation coverage.
- Validate Android API 26 and 36 at the supported platform boundaries.
- Make runtime preparation reproducible from a pinned, checksummed PAM release.
- Publish the Android plugin API and PHP SDK alongside the iOS renderer.
- Align repository metadata, SDK constraints and protocol documentation.

## 0.2.0

- Add the UIKit renderer and the expanded protocol-compatible component surface.
