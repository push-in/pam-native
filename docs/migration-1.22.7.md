# Migrating to Pam Native 1.22.7

## Completed content-offset requests

Rebuild the Android host after updating. `ScrollView`'s `contentOffsetX` and
`contentOffsetY` keep their existing PHP properties and units. Each requested
axis is now consumed once the native scroll view actually reaches its value.
Subsequent layout or content insertion preserves the current position instead
of replaying a completed initial request.

This corrects a gallery that reached its last page and then returned to the
initial page when a child laid out again. Applications do not need to remove
their initial offset binding or mirror native scrolling into PHP every frame.
A later explicit property update can request a new offset as before.

An offset still clamped by insufficient content is not consumed: it remains
pending so delayed content can satisfy it. The fix does not cancel requests
on a tap or redefine end anchoring. `anchorToEnd`,
`maintainVisibleContentPosition`, keyboard clearance, indicator changes and
tokenized `scrollRequest` retain their existing behavior.

## Validation scope

The three new instrumented regressions fail on published 1.22.6 and pass with
the fix on Android APIs 26 and 36. They cover completed horizontal and vertical
requests followed by relayout, plus a request issued before enough content
exists that must survive growth and stop replaying after it is reached.
The twelve existing scroll-container cases passed on both APIs, including
growth, end anchoring, maintained position, keyboard geometry, indicators,
targeted requests and paging over pressable children.

These native results do not replace the final application gallery swipe/zoom
and creation acceptance. They do not measure FPS or claim complete UI parity.
No iOS source changes are included; UIKit execution was unavailable on Linux.

PHP remains `^8.5`. Third-party dependencies and the numeric protocol/ABI
versions are unchanged. The 1.22.6 image-event, reload, cache-recovery and
initial-property improvements remain included.
