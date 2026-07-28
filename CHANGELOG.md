# Changelog

## Unreleased

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
