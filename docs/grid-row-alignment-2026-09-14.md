# Shared grid row alignment

The UI integration of existing GridColumns exposed a native layout omission:
cells always retained intrinsic height, ignoring row alignment. Automatic-height
cells now stretch by default to their row height after subtracting vertical margins.
Explicit heights remain explicit; MaxHeight constrains stretching. AlignSelf
overrides AlignItems, including center and end positioning within the row.

Regression: `grid_rows_stretch_auto_height_and_respect_explicit_alignment_and_limits`
covers explicit 80px height, stretched height with asymmetric margins, centered
20px height, maximum 40px height and end alignment. `cargo test -p pam-native-engine
--offline`: 78 passed, including all four grid tests. This is shared Rust layout,
not platform-specific UI compensation. Android candidate validation is recorded in
PAM Native UI's collections batch; iOS/device-wide approval remains outstanding.
