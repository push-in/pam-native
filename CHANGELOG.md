# Changelog

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
