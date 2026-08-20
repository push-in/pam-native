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
        button.isUserInteractionEnabled = true
        button.isEnabled = false
        XCTAssertFalse(button.point(inside: CGPoint(x: 10, y: 15), with: nil))

        let parent = UIView(frame: CGRect(x: 0, y: 0, width: 120, height: 100))
        let lower = PamPressButton(
            frame: CGRect(x: 40, y: 40, width: 20, height: 20)
        )
        let upper = PamPressButton(
            frame: CGRect(x: 70, y: 40, width: 20, height: 20)
        )
        var lowerActivations = 0
        var upperActivations = 0
        lower.addAction(
            UIAction { _ in lowerActivations += 1 },
            for: .touchUpInside
        )
        upper.addAction(
            UIAction { _ in upperActivations += 1 },
            for: .touchUpInside
        )
        parent.addSubview(lower)
        parent.addSubview(upper)

        let target = parent.hitTest(CGPoint(x: 60, y: 50), with: nil)
        XCTAssertTrue(target === upper)
        (target as? UIControl)?.sendActions(for: .touchUpInside)
        XCTAssertEqual(lowerActivations, 0)
        XCTAssertEqual(upperActivations, 1)

        upper.isEnabled = false
        let enabledTarget = parent.hitTest(CGPoint(x: 60, y: 50), with: nil)
        XCTAssertTrue(enabledTarget === lower)
        (enabledTarget as? UIControl)?.sendActions(for: .touchUpInside)
        XCTAssertEqual(lowerActivations, 1)
        XCTAssertEqual(upperActivations, 1)
    }
}
