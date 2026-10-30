import UIKit

/// Leading rows (headers, loaders) a head insert may land after (Android
/// `HEAD_INSERT_LEADING_ROWS`).
let pamHeadInsertLeadingRows = 2

/// The rows a virtual list showed after its last sync, so the next sync keeps
/// one of them at its pixel offset when rows are inserted, removed or
/// re-measured above it (Android: RecyclerView's own anchoring plus the
/// head-insert, rebind and resize-at-rest anchors).
struct PamListKeyAnchor {
    struct Row: Equatable {
        let id: Int64
        /// Content offset of the row's start along the list axis.
        let start: CGFloat
        /// Entirely inside the viewport.
        let full: Bool
    }

    /// Rows intersecting the viewport, in list order.
    let visible: [Row]
    /// The list's row ids in order, and as a set (shared while unchanged).
    let ids: [Int64]
    let idSet: Set<Int64>
    /// The list rested at its end.
    let atEnd: Bool

    static func capture(
        cellIds: [Int64],
        frames: [(Int64, CGRect)],
        viewport: CGRect,
        horizontal: Bool,
        atEnd: Bool,
        previous: PamListKeyAnchor?
    ) -> PamListKeyAnchor? {
        var visible: [Row] = []
        for (id, frame) in frames where frame.intersects(viewport) && (horizontal ? frame.width : frame.height) > 0 {
            let start = horizontal ? frame.minX : frame.minY
            let end = horizontal ? frame.maxX : frame.maxY
            let full = horizontal
                ? start >= viewport.minX - 0.5 && end <= viewport.maxX + 0.5
                : start >= viewport.minY - 0.5 && end <= viewport.maxY + 0.5
            visible.append(Row(id: id, start: start, full: full))
        }
        guard !visible.isEmpty else { return nil }
        visible.sort { $0.start < $1.start }
        if let previous, previous.ids == cellIds {
            return PamListKeyAnchor(visible: visible, ids: previous.ids, idSet: previous.idSet, atEnd: atEnd)
        }
        return PamListKeyAnchor(visible: visible, ids: cellIds, idSet: Set(cellIds), atEnd: atEnd)
    }

    /// The row to keep in place in the next layout and its previous start:
    /// - rows inserted right after a leading row on screen (a header, a
    ///   "loading older" row among the list's first rows): the first kept
    ///   row after them, like any prepend above the viewport;
    /// - otherwise the first fully visible row that is still there (a row
    ///   partly hidden at the top that grew must not push the rows shown),
    ///   else the first visible one.
    func anchor(next: [Int64], positions: [Int64: CGRect], horizontal: Bool) -> (Int64, CGFloat)? {
        if next != ids, let head = headInsertAnchor(next: next) {
            return head
        }
        if let row = visible.first(where: { $0.full && positions[$0.id] != nil }) {
            return (row.id, row.start)
        }
        if let row = visible.first(where: { positions[$0.id] != nil }) {
            return (row.id, row.start)
        }
        return nil
    }

    /// Android `headInsertAnchor`: scanning from the first visible row
    /// through the list's first `pamHeadInsertLeadingRows + 1` rows, the
    /// first kept row with inserted rows directly before it. Nil when that is
    /// the first visible row itself (the default anchor keeps it) or nothing
    /// was inserted near the head.
    func headInsertAnchor(next: [Int64]) -> (Int64, CGFloat)? {
        guard let first = visible.first,
              let firstPosition = ids.prefix(pamHeadInsertLeadingRows + 1).firstIndex(of: first.id) else { return nil }
        let last = min(ids.count - 1, pamHeadInsertLeadingRows + 1)
        guard firstPosition <= last else { return nil }
        let window = next.prefix(64)
        for position in firstPosition...last {
            let id = ids[position]
            guard let index = window.firstIndex(of: id) else { continue }
            if index > 0, !idSet.contains(next[index - 1]) {
                guard position != firstPosition, let row = visible.first(where: { $0.id == id }) else { return nil }
                return (row.id, row.start)
            }
        }
        return nil
    }
}

#if DEBUG
/// Debug-only per-frame motion trace of a virtual list (Android
/// `PamListTrace`): how far the rows on screen moved (`dy`, median over the
/// rows present in both frames by stable id), rows in common and shown, the
/// scroll state, rows above the viewport and the row count. A frame where
/// rows join or leave logs even when nothing moved.
///
/// Enable with the launch environment `PAM_LIST_TRACE=1` or
/// `xcrun simctl spawn booted defaults write <bundle id> PamListTrace -bool YES`
/// and reopen the screen; read with `xcrun simctl spawn booted log stream
/// --predicate 'eventMessage CONTAINS "PamListTrace"'`.
final class PamListTrace {
    static let enabled: Bool = ProcessInfo.processInfo.environment["PAM_LIST_TRACE"] == "1"
        || UserDefaults.standard.bool(forKey: "PamListTrace")

    private var tops: [Int64: CGFloat] = [:]

    private weak var pendingList: UIScrollView?
    private var pendingAnimated = false
    private var pending: (listId: Int64, frames: [(Int64, CGRect)], horizontal: Bool, rowCount: Int)?

    /// Logs what the frame shows: several syncs inside one run loop turn
    /// (a resize, then the end follow of the same layout pass) are one
    /// drawn frame, so the state is read once, after the turn.
    func record(listId: Int64, list: UIScrollView, frames: [(Int64, CGRect)], horizontal: Bool, rowCount: Int) {
        let scheduled = pending != nil
        // Inside an animation block (keyboard follow, an animated scroll):
        // the frame moves there over the animation, not at once.
        pendingAnimated = (scheduled && pendingAnimated) || UIView.inheritedAnimationDuration > 0
        pendingList = list
        pending = (listId, frames, horizontal, rowCount)
        guard !scheduled else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self, let pending = self.pending else { return }
            self.pending = nil
            guard let list = self.pendingList else { return }
            self.flush(listId: pending.listId, list: list, frames: pending.frames,
                       horizontal: pending.horizontal, rowCount: pending.rowCount)
        }
    }

    private func flush(listId: Int64, list: UIScrollView, frames: [(Int64, CGRect)], horizontal: Bool, rowCount: Int) {
        let offset = horizontal ? list.contentOffset.x : list.contentOffset.y
        let viewport = CGRect(origin: list.contentOffset, size: list.bounds.size)
        var next: [Int64: CGFloat] = [:]
        var above = 0
        var deltas: [CGFloat] = []
        for (id, frame) in frames {
            let start = horizontal ? frame.minX : frame.minY
            let end = horizontal ? frame.maxX : frame.maxY
            if end <= offset { above += 1; continue }
            guard frame.intersects(viewport) else { continue }
            let top = start - offset
            next[id] = top
            if let previous = tops[id] { deltas.append(top - previous) }
        }
        defer { tops = next }
        let joined = next.count != deltas.count || tops.count != deltas.count
        let dy: String
        if deltas.isEmpty {
            guard !tops.isEmpty || !next.isEmpty else { return }
            dy = tops.isEmpty || next.isEmpty ? "0" : "swap"
        } else {
            let sorted = deltas.sorted()
            let median = sorted[sorted.count / 2]
            guard abs(median) >= 0.01 || joined else { return }
            dy = String(format: "%.2f", median)
        }
        // isDragging stays true through the deceleration a drag released:
        // decelerating is checked first. "anim": the offset or bounds changed
        // inside a Core Animation animation (keyboard follow, an animated
        // scroll); the trace sees the model values, not each drawn frame.
        let animating = pendingAnimated || list.layer.animationKeys()?.isEmpty == false
        let state = list.isTracking ? "drag"
            : (list.isDecelerating ? "fling" : (list.isDragging ? "drag" : (animating ? "anim" : "idle")))
        let first = frames.first { ($0.1.maxY > offset) && $0.1.intersects(viewport) }?.0 ?? 0
        NSLog(
            "PamListTrace list=%lld dy=%@ common=%ld shown=%ld state=%@ above=%ld count=%ld offset=%.1f height=%.1f first=%lld",
            listId, dy, deltas.count, next.count, state, above, rowCount, offset, list.bounds.height, first
        )
    }
}
#endif
