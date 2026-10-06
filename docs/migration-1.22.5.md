# Migrating to Pam Native 1.22.5

## Android virtual-list continuity

Update the SDK and rebuild the Android host. No application workaround is needed
for stable keyed rows: loading headers, page prepends and same-list reordering
retain the mounted native subtree and already-decoded images. RecyclerView still
owns row positioning and recycling; moving a row to a different list keeps its
normal removal and materialization lifecycle.

Keep stable keys for logical rows. Replacing a key still requests a new row and
can reset its native state by design.

The regression includes a delayed native image download without a PHP load
callback. Image completion alone did not reproduce the reported list teardown;
the confirmed defect was same-list topology updates destroying mounted rows.

## Native gesture animation handoff

A native transform gesture begins from its target's current visible
transform and stops competing transform animations on that target. This keeps a
pinch or pan from jumping back to an unfinished double-tap animation's target.
Keep the same image element mounted and animate its container.

`GestureEvent` appends three optional nullable constructor parameters:
`nativeScale`, `nativeTranslationX` and `nativeTranslationY`. They describe the
applied child transform after native bounds; translations use dp. Existing
`scale` and `translationX/Y` remain relative gesture deltas. Prefer the applied
value when committing an interrupted animation:

```php
$this->scale = $event->nativeScale ?? $this->scaleAtBegin * $event->scale;
```

The fields remain null on older runtimes and when native transforms are disabled.
Android reports dp and iOS reports equivalent view points. Existing callers and
positional arguments remain valid. Keep
`gestureNativeResetKey` stable when committing the result of a gesture; change
it when replacing the photo or explicitly resetting its transform.

No numeric protocol identifiers or dependency requirements change. See
[gesture documentation](gestures.md) for the native transform contract.

Adjacent pan/pinch detectors with native transforms enabled share their first
content surface. Neither detector may use `Drag`; an intermediate View preserves
the previous separate-target behavior. On a shared surface, commit all three
applied values at gesture end, including scale after a pan that interrupted zoom.

Android continuity and native zoom regressions were executed on APIs 26 and 36.
The iOS implementation and three new UIKit regressions received static review;
UIKit/XCTest execution was not available on the Linux release host. This release
does not claim executed iOS gesture validation. A TapEffect owned by an ancestor
and targeting a child remains outside the iOS handoff coverage; the shared photo
viewport does not use that configuration.
