# Android intrinsic text alignment

Android previously used CENTER_VERTICAL for every TextView. When Rust's
conservative fallback-font measurement reserved two lines but Android shaped
the word on one line, its first baseline shifted downward. This was visible on
the four-column showcase's `Create` label even after row heights were corrected.

Intrinsic flow text now uses TOP vertical gravity. Text with authored Height,
HeightPercent or MinHeight retains CENTER_VERTICAL, as do labels whose parent
explicitly centers them vertically (row AlignItems/AlignSelf or column
JustifyContent). Horizontal alignment is
unchanged, and PamEditText keeps its existing input alignment behavior.
The rule runs again when a layout frame is applied, so later height changes are
not tied to the initial view creation.

The existing `intrinsicTextFollowsTheParentsRelevantCenteringAxis` instrumented
regression now also checks both vertical contracts. It passed on API 36.
This fixes baseline placement, not the precision of fallback glyph estimates;
actual system-font shaping/metrics parity remains a separate open requirement.
No iOS behavior is changed and no cross-platform approval is implied.

Samsung final candidate SHA-256:
`186e4582e80da13fc343826cbf3d2974ecf78dee8273b473f01e0b7e4330f43a`.
Evidence: `/tmp/pam-text-baseline-final-20260914.json` and its matching directory.
Viewed grid and chip captures: first-line grid titles align, the header badge
remains centered, and the chip press changes the rendered state to `Release opened`.
Earlier candidate `579f738d...` also exercised a button press but exposed the
minimum-height badge regression; it is superseded, not publication evidence.
Android unit tests passed before the minimum-height/parent-centering refinement;
the instrumented alignment regression passed again after that refinement.
Narrow-grid word breaks, fallback font precision, complete component interaction
coverage, accessibility scaling/RTL and iOS remain open gates.
