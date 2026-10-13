# Retained route input focus

A QA Android Settings search field kept IME focus after navigating to a retained
but invisible route. A three-second observation counted183 frames; hiding the
keyboard before navigation produced zero frames in the comparison window. These
are observations of that reproduction, not an application-wide FPS claim.

Android now retires focus/input connection and queued autofocus when a PamEditText
becomes invisible. Rendered autofocus checks visibility at dispatch. Showing a
modal again requests its visible descendant input independently of whether a PHP
show event is registered. Destination editor focus remains independent.

iOS navigation resigns editors only within the subtree being hidden, including
completed transitions and cancelled interactive destinations. It preserves focus
in the visible destination. Three XCTest regressions are authored; neither Xcode
nor iOS device execution was available on this Linux host.

The final Android source passed26 navigation/keyboard instrumented tests on API36
(70.469s), including retained hidden editors, queued hidden autofocus and remounted
modal autofocus. The app must still be measured after installing the official
release to confirm the original idle redraw scenario no longer repeats. API26
was not rerun for this focused patch. Source commit13d96d2 includes the native fix;
release1.39.2 adds matching metadata and distribution artifacts.
