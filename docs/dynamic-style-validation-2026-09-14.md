# Dynamic style validation — 2026-09-14

This change belongs to PAM Native: UI consumers share the same compiled style
environment and must not each implement their own dimension validation.

Container width/height used by CSS expressions now require finite nonnegative
numbers. Numeric strings remain accepted for compatibility. Arrays, objects,
booleans, negative values, infinity and NaN fail with InvalidArgumentException
instead of being cast into misleading geometry. Numeric font-scale/root-font-size
overrides receive the same bound check. Nonfinite numeric environment overrides
are rejected before expression evaluation.

The media-cache attribute map and recursively checked style metadata now have
explicit iterable annotations. An unreachable array-key type check was removed:
PHP array keys already can only be integers or strings. No suppression or baseline
was added to hide diagnostics.

The SDK suite passed, including new public template-rendering regressions for
horizontal/vertical percentage math, numeric-string compatibility, seven invalid
dimension values on both axes, and nonfinite font/inset overrides.

PHPStan level 9 completed and still fails on the wider TemplateRenderer file:
83 diagnostics remain, down from the previously recorded 89. None are in the
changed dimension-validation/media-cache/metadata functions. Full diagnostic JSON
is `/tmp/pam-template-static-20260914.json`. Remaining groups include typed cascade
selectors, reactive style metadata, responsive merge inputs, keyframes and font
metadata. These are open work, not a passing release gate.

No Android rebuild was made for this internal validation batch, and no device,
performance, iOS or publication approval is claimed. The last installed showcase
does not yet contain this change.
