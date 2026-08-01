import XCTest
@testable import PamNative

final class PamVirtualWindowTests: XCTestCase {
    private let verticalFrames = (0..<10).map { index in
        (Int64(index + 1), CGRect(x: 0, y: index * 100, width: 320, height: 100))
    }

    func testKeepsOnlyViewportAndBoundedOverscan() {
        let visible = PamVirtualWindow.visibleIds(
            frames: verticalFrames,
            viewport: CGRect(x: 0, y: 400, width: 320, height: 200),
            horizontal: false,
            overscan: 101,
            velocity: 0
        )

        XCTAssertEqual(visible, Set([4, 5, 6, 7, 8]))
        XCTAssertFalse(visible.contains(1))
        XCTAssertFalse(visible.contains(10))
    }

    func testVelocityBiasesOverscanInScrollDirection() {
        let forward = PamVirtualWindow.visibleIds(
            frames: verticalFrames,
            viewport: CGRect(x: 0, y: 400, width: 320, height: 100),
            horizontal: false,
            overscan: 100,
            velocity: 2_000
        )
        let backward = PamVirtualWindow.visibleIds(
            frames: verticalFrames,
            viewport: CGRect(x: 0, y: 400, width: 320, height: 100),
            horizontal: false,
            overscan: 100,
            velocity: -2_000
        )

        XCTAssertTrue(forward.contains(7))
        XCTAssertFalse(forward.contains(3))
        XCTAssertTrue(backward.contains(3))
        XCTAssertFalse(backward.contains(7))
    }

    func testSupportsHorizontalLists() {
        let frames = (0..<8).map { index in
            (Int64(index + 1), CGRect(x: index * 80, y: 0, width: 80, height: 200))
        }
        let visible = PamVirtualWindow.visibleIds(
            frames: frames,
            viewport: CGRect(x: 240, y: 0, width: 160, height: 200),
            horizontal: true,
            overscan: 81,
            velocity: 0
        )

        XCTAssertEqual(visible, Set([3, 4, 5, 6, 7]))
    }
}
