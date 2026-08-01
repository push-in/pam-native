import XCTest
@testable import PamNative

final class PamPerformanceContractTests: XCTestCase {
    func testHundredThousandItemVirtualWindowStaysBounded() {
        let frames = (0..<100_000).map { index in
            (Int64(index + 1), CGRect(x: 0, y: index * 48, width: 390, height: 48))
        }
        let viewport = CGRect(x: 0, y: 2_400_000, width: 390, height: 844)
        let started = CFAbsoluteTimeGetCurrent()
        var visible = Set<Int64>()

        for velocity in stride(from: -12_000, through: 12_000, by: 240) {
            visible = PamVirtualWindow.visibleIds(
                frames: frames,
                viewport: viewport,
                horizontal: false,
                overscan: 844 * 2,
                velocity: CGFloat(velocity)
            )
        }

        let elapsed = CFAbsoluteTimeGetCurrent() - started
        XCTAssertLessThan(elapsed, 2)
        XCTAssertLessThanOrEqual(visible.count, 128)
        XCTAssertFalse(visible.isEmpty)
    }
}
