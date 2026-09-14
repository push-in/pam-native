# Input runtime fixes: ownership and evidence

These are reusable PAM Native fixes, not UI-owned native implementations.
Numeric bounds/step/precision remain in PAM Native UI.

- `defacc0`: Android retains normalized authored text while focused and applies
  it on blur. Newer typing invalidates the deferred value. The targeted renderer
  test passed on API 36: cursor preservation, normalization, sibling isolation
  and newer-typing protection. Showcase checks passed for `731 -> 20` and
  `3.6 -> 4` with step 1.
- `91d515d`: Android Decimal keyboard/input mode accepts signed numbers; Number
  and Numeric stay digit-only. Targeted instrumentation passed for `-7.5` versus
  digit-only `-75 -> 75`. The rebuilt showcase passed `-7 -> 0` on blur with an
  unchanged sibling, APK `98844101c40ae6e0eba8f49439ce5b61f5a6348ec83df44b0ac8c87203d34ab9`.
- `4882875`: UIKit applies keyboardType and inputMode, including mode priority
  and resetting properties. CI 34851408115 compiled Swift but failed two
  assertions in keyboard suppression/reset: optional `.none` was interpreted
  as absence, not the explicit enum case. No iOS approval for this revision.
- `820b42d`: fixes that ambiguity with `PamInputModeKind.none`, applies secure
  text entry while preserving selection, and maps autocorrection/capitalization
  with reset behavior. Additional UIKit regressions are authored; awaiting CI.

Local evidence: `/tmp/pam-number-confirmation-fixed-20260914`,
`/tmp/pam-number-rounding-fixed-20260914`, and
`/tmp/pam-number-signed-minimum-fixed-20260914`. These are scoped interaction
results, not complete component approval, physical Samsung evidence or release
media. No public package release has been made for this batch.

Follow-up UIKit maxLength handling is authored locally: apply/reset property,
bounded paste without splitting grapheme clusters, rejection when no insertion
fits, and post-composition/end-editing length enforcement. Delegate checks defer
while marked text is active. Tests cover paste, full-capacity insertion,
deletion, Unicode boundaries, unrestricted reset and post-unmark normalization.
These tests have not run yet and do not simulate a real multilingual IME session.
