import XCTest
@testable import PamNative

final class PamPanHoldTests: XCTestCase {
    func testExplicitHoldDefersMovementAndDefaultDoesNot() {
        var hold = PamPanHold()
        hold.begin(at: 1)
        XCTAssertTrue(hold.permits(at: 1.01, duration: 0))
        XCTAssertFalse(hold.permits(at: 1.179, duration: 0.180))
        XCTAssertTrue(hold.permits(at: 1.181, duration: 0.180))
    }

    func testResetDiscardsCancelledTouchAndStartsANewDeadline() {
        var hold = PamPanHold()
        hold.begin(at: 1)
        hold.reset()
        XCTAssertFalse(hold.permits(at: 5, duration: 0.180))
        hold.begin(at: 10)
        hold.begin(at: 10.1) // Another finger must not restart this gesture.
        XCTAssertFalse(hold.permits(at: 10.1, duration: 0.180))
        XCTAssertTrue(hold.permits(at: 10.181, duration: 0.180))
    }
}
