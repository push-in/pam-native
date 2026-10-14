import XCTest
@testable import PamNative

final class PamMemoryCostOrderTests: XCTestCase {
    func testModerateTrimDropsTheOldestHalf() {
        var order = PamMemoryCostOrder()
        for key in ["a", "b", "c", "d"] {
            order.insert(key, cost: 10, limit: 1_000)
        }
        XCTAssertEqual(order.trimToHalf(), ["a", "b"])
        XCTAssertEqual(order.keys, ["c", "d"])
        XCTAssertEqual(order.totalCost, 20)
    }

    func testReinsertMovesKeyToNewest() {
        var order = PamMemoryCostOrder()
        order.insert("a", cost: 10, limit: 1_000)
        order.insert("b", cost: 10, limit: 1_000)
        order.insert("a", cost: 10, limit: 1_000)
        XCTAssertEqual(order.trimToHalf(), ["b"])
    }

    func testTrackingStaysBoundedByTwiceTheLimit() {
        var order = PamMemoryCostOrder()
        for index in 0..<100 {
            order.insert("k\(index)", cost: 10, limit: 100)
        }
        XCTAssertLessThanOrEqual(order.totalCost, 200)
    }
}
