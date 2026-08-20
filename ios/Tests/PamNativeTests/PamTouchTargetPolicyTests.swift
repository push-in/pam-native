import XCTest
import UIKit
@testable import PamNative

final class PamTouchTargetPolicyTests: XCTestCase {
    func testCompactInteractiveControlsKeepPlatformMinimumTouchTargets() {
        let button = PamPressButton(frame: CGRect(x: 0, y: 0, width: 20, height: 30))

        XCTAssertTrue(button.point(inside: CGPoint(x: -12, y: 15), with: nil))
        XCTAssertTrue(button.point(inside: CGPoint(x: 10, y: -7), with: nil))
        XCTAssertFalse(button.point(inside: CGPoint(x: -13, y: 15), with: nil))
        XCTAssertFalse(button.point(inside: CGPoint(x: 10, y: -8), with: nil))

        button.isUserInteractionEnabled = false
        XCTAssertFalse(button.point(inside: CGPoint(x: 10, y: 15), with: nil))
    }
}
