# Changelog

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
