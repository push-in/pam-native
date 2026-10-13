import XCTest
@testable import PamNative

final class PamKeyboardVisibilityTests: XCTestCase {
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
