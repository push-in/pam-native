# Responsive grid template contract — integration in progress

`grid_template::GridTemplate` validates a bounded container-relative plan in the
shared Rust engine. It is independent of PAM Native UI. Each level contains
minimum width, maximum columns, column gap and row gap. The compact transport
representation is `minimum,columns,columnGap,rowGap;...`.

Rules: one to six levels, initial threshold zero, strictly increasing thresholds,
1–64 columns, finite nonnegative dimensions and at most 1024 bytes. Resolution
uses the highest threshold not exceeding the container width and returns both
the plan and level index. This index can drive spans/offsets/orders consistently.
Negative/nonfinite container widths are rejected. No dependency was added.

Tests prove exact boundaries and values immediately below each of the UI's
640/768/1024/1280/1536 thresholds, independent gutters, compatibility with native
600/840/1200/1600 plans, and rejection of invalid/oversized plans. All 82 engine
tests passed. This is a validated contract, not yet a wired rendering feature.

Remaining integration must be completed before public release:

1. Append a protocol property for the template without renumbering existing IDs;
   keep PHP/Rust/Android/UIKit parity and add sixth-tier span/offset/order keys.
2. Provide typed SDK construction/serialization and template attribute mapping.
3. Resolve one plan for both grid intrinsic measurement and final layout, including
   auto-fit maximum columns, gutters, and child breakpoint properties. Existing
   grids without a template retain their current native thresholds.
4. Map UI responsive columns/gutters/spans to the shared plan, preserving all six
   existing UI levels. Remove host fallback only where equivalent behavior is
   implemented; reversed/column-direction behavior still requires explicit work.
5. Exercise resize, wrapped text, spans, offsets/order, large fonts and RTL in the
   engine and Android showcase. Do not mark this parser as responsive UI approval.
