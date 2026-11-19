import CoreGraphics

/// Where a ScrollView or virtual list rests, by the row under its leading
/// screen edge (top, or left when horizontal) and how far into that row the
/// edge is, as a fraction of the row's extent (Android
/// `ScrollRemountAnchor` / `PamRecyclerList.ConfigurationAnchor`).
///
/// A Dynamic Type change re-measures every row and a surface remount
/// rebuilds the views: putting the same row back under the edge, with its
/// new extent, keeps the reader's place where a pixel offset would not. A
/// container resting at either end stays there (an inverted chat at its
/// newest message, a feed at its top, a log at its end).
///
/// Offsets and row rects are in the scroll view's content coordinates
/// (`contentOffset` space). An inverted list is mirrored along its axis, so
/// its leading screen edge is the far end of the viewport in content
/// coordinates and rows are read from their end.
struct PamScrollRemountAnchor: Equatable, Sendable {
    /// Node id of the row (a cell, or the deepest wrapper under the edge); 0 at an end.
    let rowId: Int64
    let fraction: CGFloat
    let atStart: Bool
    let atEnd: Bool
    let horizontal: Bool
    let reversed: Bool

    /// The content coordinate under the leading screen edge.
    static func edge(offset: CGFloat, viewport: CGFloat, reversed: Bool) -> CGFloat {
        reversed ? offset + viewport : offset
    }

    /// The row `edge` falls in (`rows` along the axis: id, start, extent).
    static func hit(_ rows: [(id: Int64, start: CGFloat, extent: CGFloat)], edge: CGFloat, reversed: Bool) -> (id: Int64, start: CGFloat, extent: CGFloat)? {
        rows.first { row in
            guard row.extent > 0 else { return false }
            return reversed
                ? edge > row.start && edge <= row.start + row.extent
                : edge >= row.start && edge < row.start + row.extent
        }
    }

    /// The fraction of `row` between its screen-leading side and the edge.
    static func fraction(of row: (id: Int64, start: CGFloat, extent: CGFloat), edge: CGFloat, reversed: Bool) -> CGFloat {
        let into = reversed ? row.start + row.extent - edge : edge - row.start
        return min(1, max(0, into / max(row.extent, 1)))
    }

    /// Resting at an end (no row needed), or nil when a row must anchor it.
    static func end(offset: CGFloat, minimum: CGFloat, maximum: CGFloat, horizontal: Bool, reversed: Bool) -> PamScrollRemountAnchor? {
        if offset <= minimum + 0.5 {
            return PamScrollRemountAnchor(rowId: 0, fraction: 0, atStart: true, atEnd: false, horizontal: horizontal, reversed: reversed)
        }
        if maximum > minimum, offset >= maximum - 1 {
            return PamScrollRemountAnchor(rowId: 0, fraction: 0, atStart: false, atEnd: true, horizontal: horizontal, reversed: reversed)
        }
        return nil
    }

    /// The content offset that puts the anchor back, clamped to
    /// `minimum...maximum`; nil when its row is gone (`row` nil).
    /// A pager rests on the page more than half shown.
    func restoredOffset(
        row: (start: CGFloat, extent: CGFloat)?,
        viewport: CGFloat,
        minimum: CGFloat,
        maximum: CGFloat,
        paging: Bool = false
    ) -> CGFloat? {
        let upper = max(minimum, maximum)
        if atStart { return minimum }
        if atEnd { return upper }
        guard let row else { return nil }
        let share: CGFloat = paging ? (fraction >= 0.5 ? 1 : 0) : fraction
        let edge = reversed
            ? row.start + row.extent - share * row.extent
            : row.start + share * row.extent
        let offset = reversed ? edge - viewport : edge
        return min(upper, max(minimum, offset))
    }
}
