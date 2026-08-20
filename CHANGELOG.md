# Changelog

## Unreleased

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
