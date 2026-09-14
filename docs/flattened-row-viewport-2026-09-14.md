# Flattened Row viewport correction

Measured cross-axis compensation must use the axis of the materialized ancestor
whose frame and measured size are being compared. A virtual Row inside a tall
Column must not cause that Column's lost viewport height to be subtracted from
each Row button. This produced zero-height native buttons with text still drawn
outside their bounds, making visually present actions unavailable to accessibility.

The renderer correction is covered by
`PamRendererInstrumentedTest#flattenedRowButtonsKeepHeightWhenColumnViewportShrinks`
(API 36 pass). Samsung showcase fullscreen-dialog checks then exposed and clicked
both actions at font scales 1.0 and 1.1. Exact APK and artifacts are recorded in
PAM Native UI's `docs/dialog-batch-2026-09-14.md`.

No new UI-specific native API or dependency was introduced. Broader viewport,
accessibility and release validation remains required before publication.
