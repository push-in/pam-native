import UIKit
import XCTest
@testable import PamNative

/// Layout/component parity (mirrors Android `PamLayoutParityInstrumentedTest`
/// and the 1.5.2/1.6.x scroll fixes). Uncompiled on Linux — needs Mac validation.
@MainActor
final class PamLayoutParityTests: XCTestCase {
    func testAvatarBorderRingStaysVisibleAroundClippedContent() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 200, height: 200))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .view, properties: [
                PamConstants.overflow: .integer(2),
                PamConstants.borderRadius: .decimal(40),
                PamConstants.borderWidth: .decimal(4),
                PamConstants.borderColor: .integer(0xFF00_FF00),
            ])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view, properties: [
                PamConstants.backgroundColor: .integer(0xFFFF_0000),
            ])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 200, height: 200)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 80, height: 80)),
            .layout(id: 3, frame: Frame(x: 0, y: 0, width: 80, height: 80)),
            .setRoot(1),
        ]])
        let avatar = try XCTUnwrap(host.viewWithTag(2))
        XCTAssertTrue(avatar.layer.masksToBounds)
        let image = PamRenderTestSupport.snapshot(avatar)
        // The ring (top edge, centre) is drawn above the clipped child.
        PamRenderTestSupport.assertColor(PamRenderTestSupport.pixel(image, 40, 1), 0xFF00_FF00, tolerance: 24)
        PamRenderTestSupport.assertColor(PamRenderTestSupport.pixel(image, 40, 40), 0xFFFF_0000)
        XCTAssertEqual(PamRenderTestSupport.pixel(image, 1, 1) >> 24, 0, "child clipped to the radius")
    }

    func testStickyHeaderPinsToTheTopUntilTheNextHeaderPushesIt() {
        let frames = [
            CGRect(x: 0, y: 0, width: 100, height: 40),
            CGRect(x: 0, y: 300, width: 100, height: 40),
        ]
        XCTAssertEqual(PamStickyHeaders.shifts(frames: frames, offset: 0), [0, 0])
        XCTAssertEqual(PamStickyHeaders.shifts(frames: frames, offset: 120), [120, 0])
        // The next header (at 300) pushes the first one up.
        XCTAssertEqual(PamStickyHeaders.shifts(frames: frames, offset: 280), [260, 0])
        XCTAssertEqual(PamStickyHeaders.shifts(frames: frames, offset: 320), [260, 20])
    }

    func testStickyScrollChildFollowsTheViewportTop() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 200, height: 300))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .scroll, properties: [:])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view, properties: [PamConstants.stickyHeader: .flag(true)])),
            .create(NodeSpec(id: 4, parent: 2, index: 1, kind: .view, properties: [:])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 200, height: 300)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 200, height: 300)),
            .layout(id: 3, frame: Frame(x: 0, y: 50, width: 200, height: 40)),
            .layout(id: 4, frame: Frame(x: 0, y: 90, width: 200, height: 1_000)),
            .setRoot(1),
        ]])
        let scroll = try XCTUnwrap(host.viewWithTag(2) as? PamAnchoredScrollView)
        XCTAssertEqual(scroll.contentSize, CGSize(width: 200, height: 1_090), "ScrollView content extent")
        scroll.contentOffset = CGPoint(x: 0, y: 200)
        scroll.layoutIfNeeded()
        let header = try XCTUnwrap(host.viewWithTag(3))
        XCTAssertEqual(header.frame.minY, 200 + scroll.adjustedContentInset.top, accuracy: 0.5)
        XCTAssertEqual(header.layer.zPosition, 1)
    }

    func testPressedStateTranslationIsAuthoredInPoints() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.nativeStateStyles: .text(#"{"1":{"73":1.1}}"#),
            PamConstants.pressScale: .decimal(1),
        ], kind: .pressable)
        defer { renderer.close() }
        let button = try XCTUnwrap(view as? PamPressButton)
        button.isHighlighted = true
        button.layer.removeAllAnimations()
        XCTAssertEqual(button.transform.ty, 1.1, accuracy: 0.001)
        button.isHighlighted = false
        button.layer.removeAllAnimations()
        XCTAssertEqual(button.transform, .identity)
    }

    func testEngineManagedSafeAreaIsNeverInsetTwice() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 300, height: 600))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.engineManagedSafeArea = true
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .safeAreaView, properties: [:])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view, properties: [:])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 300, height: 600)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 300, height: 600)),
            // The engine already inset the child by the window safe area.
            .layout(id: 3, frame: Frame(x: 0, y: 47, width: 300, height: 519)),
            .setRoot(1),
        ]])
        let child = try XCTUnwrap(host.viewWithTag(3))
        XCTAssertEqual(child.frame, CGRect(x: 0, y: 47, width: 300, height: 519))
    }

    func testVirtualListRestingAtItsEndStaysThereWhenCellsGrow() {
        let list = PamVirtualListView(frame: CGRect(x: 0, y: 0, width: 100, height: 200))
        list.contentSize = CGSize(width: 100, height: 1_000)
        list.maintainEndAnchor()
        list.contentOffset = CGPoint(x: 0, y: 800)
        list.maintainEndAnchor()
        list.contentSize = CGSize(width: 100, height: 1_100)
        list.maintainEndAnchor()
        XCTAssertEqual(list.contentOffset.y, 900, accuracy: 0.5)
        list.contentOffset = CGPoint(x: 0, y: 100)
        list.maintainEndAnchor()
        list.contentSize = CGSize(width: 100, height: 1_200)
        list.maintainEndAnchor()
        XCTAssertEqual(list.contentOffset.y, 100, accuracy: 0.5, "a list away from the end keeps its offset")
    }

    func testKeyboardOverlapOnlyCountsTheCoveredPart() {
        let view = CGRect(x: 0, y: 100, width: 390, height: 600)
        XCTAssertEqual(PamKeyboardInsetObserver.overlap(keyboard: CGRect(x: 0, y: 500, width: 390, height: 344), viewInWindow: view), 200)
        XCTAssertEqual(PamKeyboardInsetObserver.overlap(keyboard: CGRect(x: 0, y: 844, width: 390, height: 0), viewInWindow: view), 0)
    }

    func testMixedCornerRadiiClipWithAPathMask() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.backgroundColor: .integer(0xFF00_00FF),
            PamConstants.borderTopLeftRadius: .decimal(20),
            PamConstants.borderBottomRightRadius: .decimal(4),
        ], kind: .view)
        defer { renderer.close() }
        let mask = try XCTUnwrap(view.layer.mask as? CAShapeLayer)
        XCTAssertNotNil(mask.path)
        XCTAssertEqual(view.layer.cornerRadius, 0)
        let (_, other, uniform) = PamRenderTestSupport.render([
            PamConstants.borderTopLeftRadius: .decimal(12),
            PamConstants.borderTopRightRadius: .decimal(12),
        ], kind: .view)
        defer { other.close() }
        XCTAssertEqual(uniform.layer.cornerRadius, 12)
        XCTAssertEqual(uniform.layer.maskedCorners, [.layerMinXMinYCorner, .layerMaxXMinYCorner])
    }

    func testRecycledCellViewsComeBackClean() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 200, height: 100))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        var mutations: [Mutation] = [
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .virtualList, properties: [:])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 200, height: 100)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 200, height: 100)),
            .setRoot(1),
        ]
        for row in 0..<40 {
            let id = Int64(10 + row)
            mutations.append(.create(NodeSpec(id: id, parent: 2, index: row, kind: .view, properties: [
                PamConstants.backgroundColor: .integer(row == 0 ? 0xFFFF_0000 : 0xFF00_FF00),
            ])))
            mutations.append(.layout(id: id, frame: Frame(x: 0, y: Float(row * 50), width: 200, height: 50)))
        }
        renderer.commit([mutations])
        let list = try XCTUnwrap(host.viewWithTag(2) as? PamVirtualListView)
        XCTAssertNotNil(host.viewWithTag(10))
        list.contentOffset = CGPoint(x: 0, y: 1_500)
        list.layoutIfNeeded()
        XCTAssertNil(host.viewWithTag(10), "scrolled-out cells are dematerialized")
        let reused = try XCTUnwrap(host.viewWithTag(40))
        XCTAssertEqual(reused.backgroundColor, UIColor(argb: 0xFF00_FF00))
        XCTAssertEqual(reused.transform, .identity)
        XCTAssertEqual(reused.alpha, 1)
    }
}
