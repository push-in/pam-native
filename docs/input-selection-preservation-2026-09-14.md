# Preserve selection while reconfiguring Android inputs

`applyInputConfiguration` assigned inputType/transformation without retaining the
current selection unless explicit InputSelectionStart/End properties existed.
Showing/hiding a password therefore kept the value but moved its cursor to zero.
The consolidated Samsung form review exposed this in the screenshot after hiding.

The renderer now snapshots the native selection before configuration and restores
it afterwards, clamped to the current text. Explicit selection properties still
take priority. This belongs to the reusable native input, not a UI-only toggle fix.

`passwordVisibilityPreservesCursorAndSelectionUnlessExplicitlyControlled` passed
on API 36. It checks repeated secure transitions at a cursor position and over a
selected range, retained text, and an explicit controlled selection override.
The Android unit suite also passed.

Samsung's strengthened password audit adds a character after revealing and another
after hiding, without moving the cursor. A final reveal must expose the original
value plus both trailing characters. That scoped test passed; batch evidence and
APK SHA are recorded in PAM Native UI's forms audit. Broader IME composition,
reverse selections and iOS behavior are not approved by this regression.
