import Foundation

/// Insertion-ordered keys and costs of an in-memory cache. `trimToHalf`
/// returns the oldest keys whose removal brings the tracked cost to at most
/// half. Tracking stays bounded: entries past twice the cache limit are the
/// ones NSCache has already evicted, so they are forgotten.
struct PamMemoryCostOrder {
    private(set) var keys: [String] = []
    private var costs: [String: Int] = [:]
    private(set) var totalCost = 0

    mutating func insert(_ key: String, cost: Int, limit: Int) {
        if let previous = costs[key] {
            totalCost -= previous
            keys.removeAll { $0 == key }
        }
        keys.append(key)
        costs[key] = cost
        totalCost += cost
        guard limit > 0 else { return }
        while totalCost > limit * 2, keys.count > 1 {
            forgetOldest()
        }
    }

    mutating func trimToHalf() -> [String] {
        let target = totalCost / 2
        var removed: [String] = []
        while totalCost > target, !keys.isEmpty {
            removed.append(keys[0])
            forgetOldest()
        }
        return removed
    }

    mutating func removeAll() {
        keys.removeAll()
        costs.removeAll()
        totalCost = 0
    }

    private mutating func forgetOldest() {
        let key = keys.removeFirst()
        totalCost -= costs.removeValue(forKey: key) ?? 0
    }
}
