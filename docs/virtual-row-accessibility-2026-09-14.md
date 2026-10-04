# Partially visible virtual rows

`PamRecyclerList` previously hid a holder and all its accessibility descendants
unless the entire holder fitted inside the viewport. This excluded visible
controls in clipped rows and made rows taller than the viewport unreachable.

The visibility filter now excludes only holders that do not intersect the
viewport. Detachment restores the original accessibility mode so a recycled
holder does not inherit `NO_HIDE_DESCENDANTS` as its permanent original mode.
Virtualization and offscreen prefetch remain enabled.

The Android instrumented regression
`PamRendererInstrumentedTest#partiallyVisibleVirtualRowsRemainAccessible`
passed on emulator-5554, Android 16/API 36: both a partially visible second row
and a first row taller than the viewport retain accessibility descendants.
The first staging invocation ran zero tests because a relative copy failed;
it is not validation. After copying the changed sources using absolute paths,
the invocation compiled the changes and ran one passing test.

This is native runtime behavior shared by virtualized UI compositions, not a
Data Grid-specific workaround. Full TalkBack traversal, horizontal scrolling,
recycling stress and iOS are not established by this focused test.
