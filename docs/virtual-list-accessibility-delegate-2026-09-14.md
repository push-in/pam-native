# Preserve RecyclerView accessibility behavior

Applying PAM accessibility roles, hints or custom actions replaced the
RecyclerView delegate with a plain View delegate. Touch scrolling still worked,
but list metadata and native accessibility scroll actions were lost. Removing
those properties also cleared the delegate instead of restoring the native one.

`configureAccessibilityDelegate` now initializes node information through the
list's existing compat delegate before applying PAM semantics, and forwards
unhandled actions to it. The no-customization path restores that delegate.
An unspecified role no longer overwrites the native class name.

This follows the responsibility of AndroidX's
[RecyclerViewAccessibilityDelegate](https://developer.android.com/reference/androidx/recyclerview/widget/RecyclerViewAccessibilityDelegate):
the platform list owns native collection information and accessibility actions;
PAM augments rather than reimplements that behavior. UI composition is unchanged.

The instrumented test
`virtualListRolesPreserveNativeAccessibilityScrollActions` passed on
emulator-5554/Android 16/API 36. A rendered 20-row native list with an explicit
list role reports scrollability and ACTION_SCROLL_FORWARD. Performing that
accessibility action advances the first visible item without a touch gesture.

Showcase candidate `ae46a7202efdad26432b8897967ec395f68cc5932ff34d48528c6adf99166ce2`
also passes the three-route integrated check in
`/tmp/pam-scroll-semantics-20260914.json`: Virtual List, Section List and virtual
Data Table expose `scrollable=true` and retain internal touch scrolling in both
directions. The generic audit now rejects missing native scroll semantics.

This focused regression does not establish full TalkBack navigation, custom
action combinations, horizontal/reversed lists, disabled scrolling, event
announcement behavior or iOS accessibility parity.
