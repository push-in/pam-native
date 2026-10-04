# Reusable Android overlay collision resolution

The Android plugin API now exposes OverlayBounds, OverlayPlacement and
OverlayCollisionResolver. Hosts provide ordered candidate origins, measured
content dimensions, viewport bounds and anchor bounds. The resolver compares
anchor intersection after viewport clamping, then original viewport overflow;
equal candidates preserve caller preference. It returns the chosen candidate
index, leaving rendering and placement policy to the host.

This addresses the case where neither lateral placement fits: simply clamping
the less-overflowing side can cover the trigger. A host may now supply vertical
alternatives and select one that does not cover it. PAM Native UI integrates the
resolver only when flipping is enabled, trigger overlap is not requested, and
placement is not Center. The UI still owns visual tokens and candidate ordering.

Four targeted JVM regressions pass: lateral fallback, preferred fitting origin,
bottom-edge fallback and rejection of an empty candidate list. The JUnit report
records four tests, zero skipped/failures/errors (0.018 seconds test time):
`android/app/build/test-results/testDebugUnitTest/TEST-dev.pam.nativeapp.render.OverlayCollisionResolverTest.xml`.
The Gradle invocation took 18 seconds. This is not a full SDK/platform approval.

The UI integration requires this plugin API source/version; publish the native
capability before any UI release that depends on it, and only after release
gates. No iOS implementation or platform parity is claimed by this Android API.
Device evidence for the UI consumer is tracked in the sibling UI repository's
`docs/anchored-overlays-batch-2026-09-14.md`.
