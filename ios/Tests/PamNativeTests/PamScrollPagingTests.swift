import XCTest
@testable import PamNative

final class PamScrollPagingTests: XCTestCase {
    func testPagingMovesAtMostOnePageFromGestureOrigin() {
        XCTAssertEqual(
            PamAnchoredScrollView.onePageTarget(
                start: 400,
                position: 1_480,
                velocity: 5_000,
                extent: 400,
                maximum: 2_000
            ),
            800
        )
    }

    func testPagingUsesDisplacementWhenVelocityIsLow() {
        XCTAssertEqual(
            PamAnchoredScrollView.onePageTarget(
                start: 800,
                position: 900,
                velocity: 0,
                extent: 400,
                maximum: 2_000
            ),
            1_200
        )
        XCTAssertEqual(
            PamAnchoredScrollView.onePageTarget(
                start: 800,
                position: 840,
                velocity: 0,
                extent: 400,
                maximum: 2_000
            ),
            800
        )
    }

    func testPagingClampsToPartialFinalPage() {
        XCTAssertEqual(
            PamAnchoredScrollView.onePageTarget(
                start: 1_600,
                position: 1_850,
                velocity: 1_000,
                extent: 400,
                maximum: 1_850
            ),
            1_850
        )
    }
}
