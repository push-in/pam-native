import Foundation

/// Immutable values parsed from wire strings, memoized by source (Android
/// `PamParseCache`): list rows repeat the same gesture programs, and binding a
/// row must not split and parse them again. Bounded: the oldest source is
/// dropped past `capacity`; failed parses are remembered too.
final class PamParseCache<Value> {
    private let capacity: Int
    private var values: [String: Value?] = [:]
    private var order: [String] = []
    private let lock = NSLock()

    init(capacity: Int = 32) {
        self.capacity = capacity
    }

    func value(for source: String, parse: (String) -> Value?) -> Value? {
        lock.lock()
        defer { lock.unlock() }
        if let hit = values[source] {
            if let index = order.firstIndex(of: source) {
                order.remove(at: index)
                order.append(source)
            }
            return hit
        }
        let parsed = parse(source)
        values[source] = .some(parsed)
        order.append(source)
        if order.count > capacity {
            values.removeValue(forKey: order.removeFirst())
        }
        return parsed
    }
}
