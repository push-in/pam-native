import XCTest
import UIKit
@testable import PamNative

final class PamMotionPolicyTests: XCTestCase {
    override func tearDown() {
        PamMotionPolicy.reduceMotionOverride = nil
        super.tearDown()
    }

    func testOverrideMakesMotionPolicyDeterministic() {
        PamMotionPolicy.reduceMotionOverride = true
        XCTAssertTrue(PamMotionPolicy.isReduced)
        PamMotionPolicy.reduceMotionOverride = false
        XCTAssertFalse(PamMotionPolicy.isReduced)
    }

    func testReducedMotionAppliesTerminalKeyframeWithoutLayerAnimation() {
        let view = UIView(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        view.layer.add(CABasicAnimation(keyPath: "opacity"), forKey: "running")

        PamMotionPolicy.applyTerminalKeyframe(
            [
                "opacity": 0.75,
                "translationX": 12,
                "translationY": -8,
                "scaleX": 0.9,
                "scaleY": 1.1,
                "rotation": 15,
            ],
            to: view
        )

        XCTAssertEqual(view.alpha, 0.75, accuracy: 0.000_1)
        XCTAssertTrue(view.layer.animationKeys()?.isEmpty ?? true)
        let point = CGPoint(x: 0, y: 0).applying(view.transform)
        XCTAssertEqual(point.x, 12, accuracy: 0.000_1)
        XCTAssertEqual(point.y, -8, accuracy: 0.000_1)
    }

    func testPressFeedbackChangesStateImmediatelyWhenMotionIsReduced() {
        PamMotionPolicy.reduceMotionOverride = true
        let button = PamPressButton(frame: CGRect(x: 0, y: 0, width: 100, height: 48))
        button.pamPressedOpacity = 0.6
        button.pamPressedScale = 0.95

        button.isHighlighted = true
        XCTAssertEqual(button.alpha, 0.6, accuracy: 0.000_1)
        XCTAssertEqual(button.transform.a, 0.95, accuracy: 0.000_1)
        XCTAssertTrue(button.layer.animationKeys()?.isEmpty ?? true)

        button.isHighlighted = false
        XCTAssertEqual(button.alpha, 1, accuracy: 0.000_1)
        XCTAssertEqual(button.transform, .identity)
        XCTAssertTrue(button.layer.animationKeys()?.isEmpty ?? true)
    }
}
