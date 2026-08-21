import XCTest
import UIKit
@testable import PamNative

final class PamFocusPolicyTests: XCTestCase {
    func testKeyboardFocusUsesVisibleSystemHaloAndSkipsDisabledControls() {
        let button = PamPressButton(frame: CGRect(x: 0, y: 0, width: 100, height: 44))

        XCTAssertTrue(button.canBecomeFocused)
        XCTAssertTrue(button.focusEffect is UIFocusHaloEffect)

        button.isEnabled = false
        XCTAssertFalse(button.canBecomeFocused)
    }
}
