# Full-window modal viewport correction

PAM Native's renderer now retains MATCH_PARENT dimensions and zero engine margins
for direct content hosted by a full-window modal. Previously a subsequent engine
frame could replace the dialog-owned layout with the activity's fixed height.
When Android resized the dialog for the IME, the child stayed taller than its
window, placing bottom-aligned content below the visible area.

Ownership is restricted by PamModalHost's current presentation. Centered dialogs
and native sheets retain their existing intrinsic/snap sizing. No UI-specific
command or selection behavior has been added to PAM Native.

Evidence:

- Samsung SM-G973F, showcase APK
  `e9583f894901d0432135d2141e4b81e143b5c77adeebf377de6df897f4248b06`:
  keyboard focus, filtering, command selection and dismissal pass in
  `/tmp/pam-ui-command-native-viewport-20260914/report.json`.
- API 36 emulator: all five PamModalHostInstrumentedTest cases pass, including
  presentation changes 1 → 2 → 3 → 1, centered dialog dimensions, native sheet
  dimensions, dismissal policy and backdrop bounds.
- Diagnostic geometry before the fix: child height 2280px, parent height 1177px,
  content top 1513px. Temporary logging was removed after diagnosis.

The renderer regression
`fullWindowModalChildIgnoresStaleActivityFrameAfterViewportResize` also passes on
API 36: a real materialized child retains MATCH_PARENT and zero top offset through
1930 → 1177 → 1930px viewport changes while the old activity frame is reapplied.
It measures and lays out the actual dialog content parent, rather than merely
testing a sizing helper. The first fixture used a layout-only View that was
eliminated by optimization; adding a background materialized the test child.

These are scoped checks, not complete cross-platform modal approval. Rotation
and other IME configurations still need coverage before release. No iOS change
or validation is claimed.
