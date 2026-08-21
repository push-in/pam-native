import XCTest
import UIKit
@testable import PamNative

final class PamTouchTargetPolicyTests: XCTestCase {
    @MainActor
    func testRendererAppliesAndResetsProtocolHitSlop() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 120, height: 100))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(
                id: 2,
                parent: 1,
                index: 0,
                kind: .pressable,
                properties: [
                    PamConstants.hitSlop: .decimal(20),
                    PamConstants.hitSlopLeft: .decimal(4),
                    PamConstants.testId: .text("hit-slop-target"),
                ]
            )),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 120, height: 100)),
            .layout(id: 2, frame: Frame(x: 40, y: 35, width: 20, height: 30)),
            .setRoot(1),
        ]])

        let button = try XCTUnwrap(
            host.subviews.first?.subviews.first as? PamPressButton
        )
        XCTAssertEqual(button.accessibilityIdentifier, "hit-slop-target")
        XCTAssertTrue(button.point(inside: CGPoint(x: -12, y: -19.9), with: nil))
        XCTAssertFalse(button.point(inside: CGPoint(x: -13, y: 15), with: nil))

        renderer.commit([[
            .update(id: 2, key: PamConstants.hitSlopLeft, value: .decimal(24)),
            .update(id: 2, key: PamConstants.hitSlopTop, value: .decimal(-8)),
        ]])
        XCTAssertTrue(button.point(inside: CGPoint(x: -24, y: 15), with: nil))
        XCTAssertFalse(button.point(inside: CGPoint(x: 10, y: -8), with: nil))

        renderer.commit([[
            .update(id: 2, key: PamConstants.hitSlop, value: nil),
            .update(id: 2, key: PamConstants.hitSlopLeft, value: nil),
            .update(id: 2, key: PamConstants.hitSlopTop, value: nil),
        ]])
        XCTAssertTrue(button.point(inside: CGPoint(x: -12, y: 15), with: nil))
        XCTAssertFalse(button.point(inside: CGPoint(x: -13, y: 15), with: nil))
        renderer.close()
    }

    func testCompactInteractiveControlsKeepPlatformMinimumTouchTargets() {
        let button = PamPressButton(frame: CGRect(x: 0, y: 0, width: 20, height: 30))

        XCTAssertTrue(button.point(inside: CGPoint(x: -12, y: 15), with: nil))
        XCTAssertTrue(button.point(inside: CGPoint(x: 10, y: -7), with: nil))
        XCTAssertFalse(button.point(inside: CGPoint(x: -13, y: 15), with: nil))
        XCTAssertFalse(button.point(inside: CGPoint(x: 10, y: -8), with: nil))

        button.pamHitSlop = UIEdgeInsets(top: 8, left: 16, bottom: 10, right: 18)
        XCTAssertTrue(button.point(inside: CGPoint(x: -16, y: 15), with: nil))
        XCTAssertTrue(button.point(inside: CGPoint(x: 37.9, y: 15), with: nil))
        XCTAssertTrue(button.point(inside: CGPoint(x: 10, y: 39.9), with: nil))
        XCTAssertFalse(button.point(inside: CGPoint(x: -17, y: 15), with: nil))
        XCTAssertFalse(button.point(inside: CGPoint(x: 39, y: 15), with: nil))
        XCTAssertFalse(button.point(inside: CGPoint(x: 10, y: 41), with: nil))

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
