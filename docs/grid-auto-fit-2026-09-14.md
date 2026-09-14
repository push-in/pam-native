# Minimum-width native grids

`Style(gridColumns: 4, gridMinColumnWidth: 120.0, gridColumnGap: 12.0)`
requests at most four columns. The shared Rust engine resolves the number that
fits the grid's inner width, including gutters. Below the minimum, one column
uses the available width instead of forcing horizontal overflow. Zero or an
omitted minimum preserves the existing fixed column count. Values are logical
layout units; the minimum does not automatically scale with system font size.

Use `gridSpan: 1` for individual cells. Existing explicit spans are clamped to
the resolved column count. Text measurement, row placement and intrinsic grid
height all use the same resolved count; no Android-host post-layout resize is
needed. Changes to the property are classified as layout-affecting mutations.

PHP templates also accept `gridMinColumnWidth="120"` on Grid. Protocol property
466 is appended consistently in PHP, Rust, Kotlin and Swift. This capability
requires a runtime including that property; it is not yet a published release.

Validation:

- All 79 engine tests passed. Auto-fit regression covers widths 100, 247, 248,
  360, 600 and 2000, verifying the fitting threshold, maximum column count,
  cell width, last-cell placement and intrinsic grid height together.
- All 12 protocol tests and cross-language protocol parity passed.
- PHP SDK suite passed, including templates and typed Style mapping.
- Android unit suite passed; arm64 candidate built and installed on Samsung.
- UI's auto-fit audit confirms 2x2 layout on Samsung at font scale 1.1. Evidence
  and SHA are in the matching PAM Native UI collections batch.

Remaining gates include device resize/orientation, larger font scales, RTL and
iOS execution. Protocol parity is not an iOS runtime test. Explicit breakpoint
column/gutter migration is separate and is not completed by this capability.
