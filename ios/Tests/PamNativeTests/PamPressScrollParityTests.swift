import UIKit
import XCTest
@testable import PamNative

/// UIKit regressions; requires an iOS test destination (not executable on Linux).
@MainActor
final class PamPressScrollParityTests: XCTestCase {
    func testADoubleTapSurfaceNeverDimsOnPressWhileAPlainPressableDoes() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        let renderer = PamRenderer(hostView: host, dispatchEvent: { _, _, _ in })
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .pressable, properties: [
                PamConstants.onPress: .flag(true),
                PamConstants.onDoubleTap: .flag(true),
            ])),
            .create(NodeSpec(id: 3, parent: 1, index: 1, kind: .pressable, properties: [
                PamConstants.onPress: .flag(true),
            ])),
            .setRoot(1),
        ]])
        defer { renderer.close() }
        let doubleTap = try XCTUnwrap(host.viewWithTag(2) as? PamPressButton)
        let plain = try XCTUnwrap(host.viewWithTag(3) as? PamPressButton)
        XCTAssertTrue(doubleTap.pamTakesDoubleTap)
        XCTAssertFalse(plain.pamTakesDoubleTap)

        UIView.performWithoutAnimation {
            doubleTap.isHighlighted = true
            plain.isHighlighted = true
        }
        XCTAssertEqual(doubleTap.alpha, 1, accuracy: 0.001)
        XCTAssertEqual(doubleTap.transform, .identity)
        XCTAssertEqual(plain.alpha, plain.pamPressedOpacity, accuracy: 0.001)

        doubleTap.isHighlighted = false
        XCTAssertEqual(doubleTap.alpha, 1, accuracy: 0.001)
    }

    func testATransitionTurnedOnWithItsFirstChangeIsAppliedFirst() {
        let batch: [Mutation] = [
            .update(id: 7, key: PamConstants.scaleX, value: .decimal(2)),
            .create(NodeSpec(id: 9, parent: 1, index: 0, kind: .view, properties: [:])),
            .update(id: 9, key: PamConstants.transitionSpec, value: .text("transform 200ms")),
            .update(id: 7, key: PamConstants.transitionSpec, value: .text("transform 200ms")),
            .update(id: 7, key: PamConstants.animateChanges, value: .flag(true)),
        ]
        let ordered = PamRenderer.transitionSpecsFirst(batch)
        func describe(_ m: Mutation) -> String {
            switch m {
            case let .update(id, key, _): return "u\(id):\(key)"
            case let .create(node): return "c\(node.id)"
            default: return "?"
            }
        }
        XCTAssertEqual(ordered.map(describe), [
            "u7:\(PamConstants.transitionSpec)", "u7:\(PamConstants.animateChanges)",
            "u7:\(PamConstants.scaleX)", "c9", "u9:\(PamConstants.transitionSpec)",
        ])
        let plain: [Mutation] = [.update(id: 7, key: PamConstants.scaleX, value: .decimal(2))]
        XCTAssertEqual(PamRenderer.transitionSpecsFirst(plain).map(describe), plain.map(describe))
    }

    func testALongAnimatedScrollJumpsToOneViewportBeforeItsTarget() {
        XCTAssertEqual(PamContentScrollView.longScrollJumpStart(current: 9_000, target: 0, viewport: 800), 800)
        XCTAssertEqual(PamContentScrollView.longScrollJumpStart(current: 0, target: 5_000, viewport: 800), 4_200)
        XCTAssertNil(PamContentScrollView.longScrollJumpStart(current: 1_500, target: 0, viewport: 800),
                     "within two viewports the whole distance animates")
        XCTAssertNil(PamContentScrollView.longScrollJumpStart(current: 1_500, target: 0, viewport: 0))

        let scroll = PamContentScrollView(frame: CGRect(x: 0, y: 0, width: 320, height: 600))
        scroll.contentSize = CGSize(width: 320, height: 20_000)
        scroll.contentOffset = CGPoint(x: 0, y: 12_000)
        scroll.jumpCloseBeforeAnimatedScroll(to: .zero, horizontal: false)
        XCTAssertEqual(scroll.contentOffset.y, 600, accuracy: 0.5)
        scroll.jumpCloseBeforeAnimatedScroll(to: .zero, horizontal: false)
        XCTAssertEqual(scroll.contentOffset.y, 600, accuracy: 0.5, "the last stretch is left to the animation")
    }
}
