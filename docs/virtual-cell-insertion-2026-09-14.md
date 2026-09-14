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

## UIKit parity review

UIKit already invokes `materializeSubtree` for every visible cell during
`syncVirtualList`. `materialize` skips existing views, but recursion still visits
their children, so its source does not share Android's unchanged-holder bind gap.
No speculative UIKit runtime change was made.

`PamVirtualCellInsertionTests.testInsertedDescendantsMountWithoutReplacingVisibleCell`
adds the corresponding two-cycle insertion/removal regression, checking retained
cell identity, label content/size, parent and absence of duplicate descendants.
It has **not been compiled or executed**: this Linux environment exposes neither
Swift nor xcodebuild. Source review and the Android pass do not constitute an
iOS pass. Run this UIKit test on an Apple host before asserting platform parity.
