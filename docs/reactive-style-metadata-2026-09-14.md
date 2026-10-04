# Reactive style metadata — 2026-09-14

PAM Native now validates reactive variable maps, cascade containers and declaration
containers before using them. Updates preserve declaration metadata while changing
only the compiled value. Scoped font data reuses the existing font-face validator,
so malformed families/faces do not reach font selection as unchecked arrays.

The fallback reactive cache identity previously depended only on variable rules,
and an empty fingerprint was accepted as an identity. Two sheets with the same
rules but different base variables could therefore share a cached result. Empty
or missing fingerprints now use the complete sheet content as the fallback key.
Normal nonempty compiler fingerprints keep their existing fast path.

SDK tests passed, including existing public reactive-style rendering plus new
internal regressions for valid font faces, malformed font metadata, malformed
variable/rule maps, and separation of sheets with different base variables and
empty fingerprints. StyleVariables is restored after the test cases.

The first level-9 analysis completed with 74 remaining TemplateRenderer
diagnostics (previous batch: 83), none inside reactiveStyleSheet or styleSheetFonts.
The wider file is not statically approved. The final font implementation reuses
validatedFontFaces instead of duplicating its checks; SDK tests passed again.
Diagnostic artifact: `/tmp/pam-template-metadata-static-20260914.json`.

This is native SDK work, not another UI host implementation. No Android build,
iOS execution, performance claim or publication is included in this batch.
