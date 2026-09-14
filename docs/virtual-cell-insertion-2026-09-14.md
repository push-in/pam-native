# Conditional descendants in mounted virtual cells

Android deferred child view creation under VirtualList until holder binding.
A conditional child insertion leaves the row ID and extent unchanged, so the
adapter correctly avoids rebinding, but the new child previously stayed unmounted.

The renderer now collects created IDs per commit, resolves unique virtual cell
roots after node/frame mutations, and materializes missing descendants only for
affected cells with existing holders. Offscreen cells remain deferred. Existing
views are retained rather than rebuilding each visible row.

`richVirtualCellMountsInsertedChildrenWithoutChangingRowExtent` passed on Android
16/API 36 (`emulator-5554`) via `:app:connectedDebugAndroidTest`, filtered to this
method. Build/test completed in 17 seconds. It inserts/removes a text child twice,
asserting native parent, content, height, retained holder and no duplicate children.

The UI showcase select/deselect report `/tmp/pam-grid-insertion-20260914.json`
also passed; visual inspection confirms the formerly missing check is visible.
This does not establish all virtualization, accessibility, performance or iOS
requirements. No release approval follows from this scoped regression.
