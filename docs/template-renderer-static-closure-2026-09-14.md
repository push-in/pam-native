# TemplateRenderer static-analysis closure — 2026-09-14

The targeted PHPStan level-9 run now reports zero errors for TemplateRenderer.
This closes the previously recorded 89-diagnostic file-level debt; it does not
prove the entire SDK, platforms or UI release gates are complete.

The final group validates inherited/scoped/state attribute maps, preserves finite
fractional style values, checks recipe/state metadata before indexing, and gives
explicit diagnostics for numeric event expressions. Type annotations now reflect
the integer/float attributes genuinely produced by style resolution.

Named keyframes validate frame objects, finite offsets in [0,1] and style maps.
Frame styles cannot overwrite the timeline offset. Media errors format already
validated strings rather than interpolating arbitrary values.

Evidence:

- PHPStan JSON: `/tmp/pam-template-final-static-20260914.json`, errors 0 and
  file_errors 0, no ignores or baseline added.
- Full SDK test runner passed, including Language 2 public rendering regressions.
- New tests cover scalar/fractional attributes, malformed maps, numeric event
  metadata, valid animation serialization, protected timeline offsets and invalid
  keyframes. The first keyframe fixture omitted required classes/tags containers;
  the fixture was corrected and assertions now require a relevant keyframe error.
- UI material matrix against this SDK passed: 114 components, 32,832 style cases,
  456 renders. These counts are not Android interaction approvals.

No APK was rebuilt for these internal batches. The installed showcase candidate
`393e1558d85b6b9f3a747b01a664249dabb5e81d057643f01908d487b708baff`
predates this SDK work. The next Android candidate must sync the current native
PHP TemplateRenderer into staging, which the native build does not do itself.
No publication, iOS approval or broad performance claim is made.
