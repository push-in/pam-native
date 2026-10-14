import XCTest
@testable import PamNative

final class PamPageTrackerTests: XCTestCase {
    func testReportsOnlyActualPageChanges() {
        var tracker = PamPageTracker()
        tracker.reset(0)
        for _ in 0..<30 { XCTAssertNil(tracker.advance(0)) }
        XCTAssertEqual(tracker.advance(1), 0)
        for _ in 0..<30 { XCTAssertNil(tracker.advance(1)) }
        XCTAssertEqual(tracker.advance(0), 1)
    }

    func testFirstObservationWithoutResetIsSilent() {
        var tracker = PamPageTracker()
        XCTAssertNil(tracker.advance(2))
        XCTAssertEqual(tracker.advance(3), 2)
    }

    func testOverscrollClampsToFirstPage() {
        var tracker = PamPageTracker()
        tracker.reset(0)
        XCTAssertNil(tracker.advance(-1))
    }
}
