import XCTest
@testable import PamNative

final class PamKeyboardVisibilityTests: XCTestCase {
    func testResumeSnapshotDoesNotConfuseHomeIndicatorWithVisibleKeyboard() {
        let bounds = CGRect(x: 0, y: 0, width: 390, height: 844)
        XCTAssertFalse(PamKeyboardVisibility.guideIsVisible(CGRect(x: 0, y: 810, width: 390, height: 34), in: bounds, safeBottom: 34))
        XCTAssertTrue(PamKeyboardVisibility.guideIsVisible(CGRect(x: 0, y: 500, width: 390, height: 344), in: bounds, safeBottom: 34))
        XCTAssertTrue(PamKeyboardVisibility.guideIsVisible(CGRect(x: 40, y: 300, width: 240, height: 200), in: bounds, safeBottom: 34))
        XCTAssertFalse(PamKeyboardVisibility.guideIsVisible(CGRect(x: 0, y: 844, width: 390, height: 344), in: bounds, safeBottom: 34))
    }

    func testFramesAndModalHandoffEmitOnlyBinaryTransitions() {
        let visibility = PamKeyboardVisibility()
        var events: [Bool] = []
        visibility.onChange = { events.append($0) }
        for inset in 1...300 { visibility.update(surface: 0, bottom: Float(inset)) }
        visibility.update(surface: 7, bottom: 300)
        visibility.update(surface: 0, bottom: 0)
        XCTAssertEqual(events, [true])
        visibility.update(surface: 7, bottom: 0)
        XCTAssertEqual(events, [true, false])
    }

    func testBackgroundIgnoresLateFramesUntilResume() {
        let visibility = PamKeyboardVisibility()
        var events: [Bool] = []
        visibility.onChange = { events.append($0) }
        visibility.update(surface: 9, bottom: 200)
        visibility.setActive(false)
        visibility.update(surface: 9, bottom: 250)
        visibility.setActive(true)
        visibility.update(surface: 0, bottom: 200)
        visibility.update(surface: 0, bottom: 0)
        XCTAssertEqual(events, [true, false, true, false])
    }
}
