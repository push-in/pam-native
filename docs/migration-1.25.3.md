# Migrating to Pam Native 1.25.3

No application changes are required. Rebuild the Android and iOS hosts after
updating; the PHP SDK API and the protocol are unchanged.

## Gesture settle order

`onGestureSettle` of a draggable now always follows its `onGestureEnd`. With
reduced motion (or Android system animations turned off) the settle used to
be reported first, because it completed inside the release. Handlers that
reset state on end and read it on settle no longer need to guard against the
reverse order.

## Backdrop filter on Android 12+

A screen with a `backdrop-filter` stops rendering once nothing changes; the
glass still follows any change behind it (scrolling, animations) in the same
frame. Apps that relied on the continuous redraw to pick up content changed
outside the view system (for example a `SurfaceView`) were never captured and
are unaffected.

## Formatted inputs

Inputs with a mask or currency format no longer show keyboard suggestions or
autocorrection (Android IME suggestions, iOS autocorrection and spell
checking), whatever `autoCorrect` says: the formatter owns the text.

## Navigation mounts on Android

A route added to an attached navigation host enters the window once. Code
that counted attach/detach callbacks of a freshly mounted screen sees a single
attach.

## Instrumented tests

Tests for host plugins that extend the renderer can reuse the helpers from the
renderer suite: `awaitFrames(instrumentation, n)` waits for `n` rendered
frames (layout and draw included) and `PamAnimationsEnabledRule` runs a test
with system animations at 1x on emulators that disable them.
