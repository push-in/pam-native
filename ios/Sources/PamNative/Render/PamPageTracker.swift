import Foundation

/// Current page of a pager/carousel. `advance` reports the previous page only
/// when the page actually changed, so scroll steps inside one page are free.
struct PamPageTracker {
    private var current = -1

    mutating func reset(_ page: Int) {
        current = max(0, page)
    }

    /// Returns the previous page when `page` differs from the current one.
    mutating func advance(_ page: Int) -> Int? {
        let next = max(0, page)
        if current < 0 {
            current = next
            return nil
        }
        guard next != current else { return nil }
        let previous = current
        current = next
        return previous
    }
}
