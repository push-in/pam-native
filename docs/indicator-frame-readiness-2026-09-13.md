# Persistent horizontal indicator: frame readiness

CI run 34790637153 at 69ee5cc failed the initial child-color assertion on
Android APIs 26 and 36. Inspection of the API 26 artifact confirms that the
initial window is blank white, whereas the subsequent shown frame contains
the gray child and dark indicator. UI-thread idle alone did not prove the
newly revealed scene had been presented.

The test now captures for at most two seconds until the gray **content** pixel
appears, without calling draw, toggling the indicator, or waiting for indicator
pixels. If content never appears, the last capture is retained and the original
assertion still fails. All initial-indicator presence and contrast assertions
remain unchanged. Discarded bitmaps are recycled.

Validation: offline compileDebugAndroidTestKotlin passed; the single
rendererKeepsPersistentHorizontalIndicatorVisibleBelowContent instrumentation
case passed on local emulator API 36 (one test, nine-second Gradle invocation).
This is a test synchronization change, not a production renderer correction.
Remote API 26/36 verification remains required; no CI success is claimed yet.
