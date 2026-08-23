# Navigation parity matrix

This matrix tracks the 17 native-mobile capability blocks selected from React
Navigation's common, native-stack, bottom-tabs, material-top-tabs and drawer
surfaces. “Implemented” means the public PHP contract reaches the Rust retained
tree and is executed by Kotlin/UIKit without a per-frame PHP callback.

| # | Capability | PAM Native implementation and optimization |
| ---: | --- | --- |
| 1 | Shared elements | Stable `sharedTransitionTag`/style contracts; native snapshots, clipping, corner interpolation and cleanup on the UI thread. |
| 2 | Native controllers | Retained Android fragments and UIKit view controllers own route containment and system integration. |
| 3 | Headers | Native title, large-title, back, colors, shadows and custom left/right/title slots with safe-area ownership. |
| 4 | Search | Controller-owned native search, focus/blur/cancel/change/submit events and hide-on-scroll behavior. |
| 5 | Form sheets and modals | Full-screen, modal, transparent modal, contained modal, form sheet and page sheet; detents, grabber, corner radius and content sizing. |
| 6 | Gestures and transitions | Platform default plus slide, fade, scale, shared-axis, flip and simple-push variants; RTL, reduced motion, vertical and full-screen gestures. |
| 7 | Bottom tabs | Native bottom bar, lazy scene creation, retained visited scenes, badges, selection events and adaptive rail. |
| 8 | Top tabs | Native top strip/segmented presentation, lazy retained scenes, scrollable labels, indicator styling and UI-thread swipe selection. |
| 9 | Drawer | Front/back/slide/permanent modes, RTL/position/width, overlay, edge and distance thresholds, keyboard/status-bar behavior, groups and adaptive permanent layout. |
| 10 | Option resolution | Navigator defaults → groups → route resolver → dynamic route options, using sparse typed patches. |
| 11 | Route identity | Stable entry keys plus typed `getId` resolvers prevent duplicate semantic routes while preserving independent instances. |
| 12 | Actions and bubbling | Typed common/stack actions, source/target addressing, focused-child bubbling and unhandled-action reporting. |
| 13 | State and restoration | Recursive integer-typed state, child state, tab/drawer history, guarded restore, checksums and legacy migration. |
| 14 | Deep linking | Prefix/filter enforcement, cold and warm URLs, custom schemes, query values, percent encoding, optional/wildcard segments and canonical reverse URLs. |
| 15 | Auth and conditional flows | Route guards, public fallback, atomic condition refresh and protected-history removal on logout/restoration. |
| 16 | Lifecycle and memory | Focus/blur/before-remove/removed contracts, automatic subscription teardown, bounded preload LRU, memory trimming and Android/iOS lifecycle delivery. |
| 17 | DevTools and observability | Bounded action/state/gesture timelines, recursive state inspection, dropped-event counters, transition average/p95, JSON export and CI microbenchmarks. |

## Performance invariants

- Transform and opacity frames execute on the native UI thread.
- Shared-element geometry is captured once per transition; no frame crosses the
  PHP/runtime boundary.
- Tabs retain visited scenes instead of reconstructing them on every switch.
- Stack rendering retains only the active neighbor required for an interactive
  transition and prunes removed route instances after completion.
- Route parameters, restored trees, link queues, traces and speculative
  preloads are bounded before they can grow retained memory.
- Accessibility traversal exposes the active route; disabled animations and
  iOS Reduced Motion bypass decorative movement.

The CI contract runs PHP 8.5, PHPStan level 9 for new Singularity boundaries,
Rust formatting/tests/clippy, protocol tests,
Android unit/lint/build checks, API 26/API 36 instrumented navigation tests,
UIKit simulator tests, deterministic fuzzing and navigation performance gates.
