# Migrating to Pam Native 1.24.0

1.24.0 is a performance release of the PHP SDK. Encoded frames are
byte-identical to 1.23.1, so the Android and iOS hosts, the protocol and the
CLI need no change; update the Composer package and rebuild the app bundle.

## Cycle collection between frames

While the runtime is booted it disables PHP's automatic cycle collector and
collects between frames once 40,000 possible roots are buffered, and again
at `Runtime::shutdown()` (which restores the automatic collector). Set
`PAM_NATIVE_FRAME_GC=0` to keep PHP's own scheduling, or a number (at least
10,001) to change the budget. Applications that create long-lived reference
cycles outside rendering behave as before: they are collected at the next
frame boundary.

## Component caches

The compiled component cache format is now 6. Development caches and
prebuilt bundles from older versions are recompiled once on first use;
release bundles built with 1.24.0 already carry the new format.

## Internal APIs

- `Pam\Native\Element` exposes `kind`, `properties`, `events`, `children`,
  `elementKey`, `domIdentity`, `identitySlot` and `reusable` as read-only
  public properties (the existing getters remain).
- Tests that read the private `TreeEncoder::$nodes` property should call
  `TreeEncoder::frameNodes()`, which returns the last frame's nodes in frame
  order.
- The first `runtime.checkpoint` state entry is written once the runtime has
  committed frames for five seconds, not during the first frame.
