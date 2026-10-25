import UIKit
import XCTest
@testable import PamNative

/// `pointerEvents`, ActivityIndicator props, `Element::animate()` and
/// `pressRetentionOffset` parity with the Android renderer. Uncompiled on
/// Linux — needs Mac validation.
@MainActor
final class PamTouchParityTests: XCTestCase {
    private func renderBox(pointerEvents: PropValue?) -> (host: UIView, renderer: PamRenderer) {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        var boxProperties: [Int: PropValue] = [:]
        if let pointerEvents { boxProperties[PamConstants.pointerEvents] = pointerEvents }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .view, properties: boxProperties)),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .pressable, properties: [:])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
            .layout(id: 2, frame: Frame(x: 20, y: 20, width: 200, height: 100)),
            .layout(id: 3, frame: Frame(x: 10, y: 10, width: 80, height: 40)),
            .setRoot(1),
        ]])
        return (host, renderer)
    }

    private let onChild = CGPoint(x: 50, y: 50)
    private let onBoxOnly = CGPoint(x: 180, y: 100)

    func testPointerEventsAutoHitsChildrenAndSelf() {
        let (host, renderer) = renderBox(pointerEvents: nil)
        defer { renderer.close() }
        XCTAssertTrue(host.viewWithTag(2) is PamContainerView)
        XCTAssertEqual(host.hitTest(onChild, with: nil)?.tag, 3)
        XCTAssertEqual(host.hitTest(onBoxOnly, with: nil)?.tag, 2)
    }

    func testPointerEventsNoneIgnoresSelfAndChildren() {
        let (host, renderer) = renderBox(pointerEvents: .integer(2))
        defer { renderer.close() }
        XCTAssertEqual(host.hitTest(onChild, with: nil)?.tag, 1)
        XCTAssertEqual(host.hitTest(onBoxOnly, with: nil)?.tag, 1)
        renderer.commit([[.update(id: 2, key: PamConstants.pointerEvents, value: nil)]])
        XCTAssertEqual(host.hitTest(onChild, with: nil)?.tag, 3)
        XCTAssertEqual(host.hitTest(onBoxOnly, with: nil)?.tag, 2)
    }

    func testPointerEventsBoxNonePassesThroughSelfButKeepsChildren() {
        let (host, renderer) = renderBox(pointerEvents: .integer(3))
        defer { renderer.close() }
        XCTAssertEqual(host.hitTest(onChild, with: nil)?.tag, 3)
        XCTAssertEqual(host.hitTest(onBoxOnly, with: nil)?.tag, 1)
    }

    func testPointerEventsBoxOnlyHitsSelfNeverChildren() {
        let (host, renderer) = renderBox(pointerEvents: .integer(4))
        defer { renderer.close() }
        XCTAssertEqual(host.hitTest(onChild, with: nil)?.tag, 2)
        XCTAssertEqual(host.hitTest(onBoxOnly, with: nil)?.tag, 2)
    }

    func testPointerEventsOnPressableImageAndText() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .pressable,
                properties: [PamConstants.pointerEvents: .integer(2)])),
            .create(NodeSpec(id: 3, parent: 1, index: 1, kind: .image,
                properties: [PamConstants.pointerEvents: .integer(2)])),
            .create(NodeSpec(id: 4, parent: 1, index: 2, kind: .text,
                properties: [PamConstants.text: .text("hello"), PamConstants.pointerEvents: .integer(2)])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 100, height: 60)),
            .layout(id: 3, frame: Frame(x: 0, y: 100, width: 100, height: 60)),
            .layout(id: 4, frame: Frame(x: 0, y: 200, width: 100, height: 60)),
            .setRoot(1),
        ]])
        for (tag, point) in [(2, CGPoint(x: 50, y: 30)), (3, CGPoint(x: 50, y: 130)), (4, CGPoint(x: 50, y: 230))] {
            let view = try XCTUnwrap(host.viewWithTag(tag) as? PamPointerEventsHost)
            XCTAssertEqual(view.pamPointerEvents, PamPointerEvents.none)
            XCTAssertEqual(host.hitTest(point, with: nil)?.tag, 1, "tag \(tag)")
            renderer.commit([[.update(id: Int64(tag), key: PamConstants.pointerEvents, value: .integer(1))]])
            XCTAssertEqual(view.pamPointerEvents, PamPointerEvents.auto, "tag \(tag)")
        }
        // Only the pressable is a touch target in `auto`: like Android, plain
        // images and text without press handlers let touches fall through to
        // their parent, so `pointerEvents` must not make them hittable.
        XCTAssertEqual(host.hitTest(CGPoint(x: 50, y: 30), with: nil)?.tag, 2)
        XCTAssertEqual(host.hitTest(CGPoint(x: 50, y: 130), with: nil)?.tag, 1)
        XCTAssertEqual(host.hitTest(CGPoint(x: 50, y: 230), with: nil)?.tag, 1)
    }

    // MARK: ActivityIndicator

    func testActivityIndicatorColorSizeAndStopHide() throws {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 360, height: 720))
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        let host = UIView(frame: window.bounds)
        window.addSubview(host)
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .activityIndicator, properties: [
                PamConstants.visible: .flag(true),
                PamConstants.activityAnimating: .flag(false),
                PamConstants.activityHidesWhenStopped: .flag(false),
                PamConstants.activitySize: .decimal(36),
                PamConstants.progressColor: .integer(0xFFFF_0000),
            ])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 360, height: 720)),
            .layout(id: 2, frame: Frame(x: 20, y: 20, width: 36, height: 36)),
            .setRoot(1),
        ]])
        let spinner = try XCTUnwrap(host.viewWithTag(2) as? PamVuetifySpinner)
        spinner.layoutIfNeeded()

        XCTAssertEqual(spinner.pamSize, 36)
        XCTAssertEqual(spinner.intrinsicContentSize, CGSize(width: 36, height: 36))
        let stroke = try XCTUnwrap(spinner.pamStrokeColor)
        PamRenderTestSupport.assertColor(Self.argb(UIColor(cgColor: stroke)), 0xFFFF_0000)

        // Stopped but `hidesWhenStopped=false`: visible and static.
        XCTAssertFalse(spinner.pamIsAnimating)
        XCTAssertFalse(spinner.pamIsRunning)
        XCTAssertFalse(spinner.isHidden)

        renderer.commit([[.update(id: 2, key: PamConstants.activityHidesWhenStopped, value: .flag(true))]])
        XCTAssertTrue(spinner.isHidden)

        renderer.commit([[.update(id: 2, key: PamConstants.activityAnimating, value: .flag(true))]])
        XCTAssertTrue(spinner.pamIsAnimating)
        XCTAssertTrue(spinner.pamIsRunning)
        XCTAssertFalse(spinner.isHidden)

        // `visible=false` still wins over `animating` (Android requestedVisible).
        renderer.commit([[.update(id: 2, key: PamConstants.visible, value: .flag(false))]])
        XCTAssertTrue(spinner.isHidden)
        XCTAssertFalse(spinner.pamIsRunning)
        renderer.commit([[.update(id: 2, key: PamConstants.visible, value: .flag(true))]])
        XCTAssertFalse(spinner.isHidden)
        XCTAssertTrue(spinner.pamIsRunning)

        // Removing the colour restores the tint; removing size restores 20pt.
        renderer.commit([[
            .update(id: 2, key: PamConstants.progressColor, value: nil),
            .update(id: 2, key: PamConstants.activitySize, value: nil),
        ]])
        let tinted = try XCTUnwrap(spinner.pamStrokeColor)
        XCTAssertEqual(UIColor(cgColor: tinted), UIColor(cgColor: spinner.tintColor.cgColor))
        XCTAssertEqual(spinner.pamSize, PamVuetifySpinner.defaultSize)
    }

    func testActivityIndicatorDefaultsSpinLikeBefore() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([:], kind: .activityIndicator)
        defer { renderer.close() }
        let spinner = try XCTUnwrap(view as? PamVuetifySpinner)
        XCTAssertTrue(spinner.pamIsAnimating)
        XCTAssertTrue(spinner.pamHidesWhenStopped)
        XCTAssertFalse(spinner.isHidden)
        XCTAssertEqual(spinner.pamSize, 20)
    }

    // MARK: Element::animate()

    private func coordinator() -> PamMotionCoordinator {
        PamMotionCoordinator(
            dispatch: { _, _, _ in },
            isMounted: { _ in true },
            wants: { _, _ in false },
            firstChild: { _ in nil }
        )
    }

    func testImplicitAnimateRuleCoversEveryPropertyWithoutATransitionSpec() {
        let motion = coordinator()
        let view = UIView(frame: CGRect(x: 0, y: 0, width: 40, height: 40))
        XCTAssertFalse(motion.animateTransition(nodeId: 1, view: view, property: .opacity, target: 0.5))

        motion.setImplicitTransition(nodeId: 1, enabled: true, durationMs: 250, easing: 3)
        let rule = motion.state(1).implicitRule
        XCTAssertEqual(rule?.durationMs, 250)
        XCTAssertEqual(rule?.delayMs, 0)
        XCTAssertEqual(rule?.easing, "ease-out")
        XCTAssertNil(rule?.spring)
        XCTAssertEqual(PamMotionCoordinator.implicitEasing(1), "linear")
        XCTAssertEqual(PamMotionCoordinator.implicitEasing(2), "ease-in")
        XCTAssertEqual(PamMotionCoordinator.implicitEasing(4), "ease-in-out")
        XCTAssertEqual(PamMotionCoordinator.implicitEasing(5), "ease-out-back")

        // Detached views take the value immediately, like a CSS rule.
        XCTAssertTrue(motion.animateTransition(nodeId: 1, view: view, property: .opacity, target: 0.5))
        XCTAssertEqual(view.alpha, 0.5, accuracy: 0.001)
        XCTAssertTrue(motion.animateTransition(nodeId: 1, view: view, property: .translateX, target: 12))
        XCTAssertEqual(PamMotionTarget.read(view, .translateX), 12, accuracy: 0.001)

        motion.setImplicitTransition(nodeId: 1, enabled: false, durationMs: 250, easing: 3)
        XCTAssertNil(motion.state(1).implicitRule)
        XCTAssertFalse(motion.animateTransition(nodeId: 1, view: view, property: .opacity, target: 1))
    }

    func testImplicitAnimateRuleRunsATransitionOnScreen() throws {
        guard !PamMotionPolicy.isReduced else { throw XCTSkip("Reduce Motion is on") }
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 360, height: 720))
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        let view = UIView(frame: CGRect(x: 0, y: 0, width: 40, height: 40))
        window.addSubview(view)
        let motion = coordinator()
        motion.setImplicitTransition(nodeId: 7, enabled: true, durationMs: 800, easing: 4)
        XCTAssertTrue(motion.animateTransition(nodeId: 7, view: view, property: .opacity, target: 0.2))
        let runner = try XCTUnwrap(motion.state(7).transitionRunners[.opacity])
        XCTAssertTrue(runner.isRunning)
        XCTAssertGreaterThan(view.alpha, 0.5)
        runner.cancel()
    }

    func testRendererWiresAnimateChangesIntoTheImplicitRule() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.animateChanges: .flag(true),
            PamConstants.animationDurationMs: .integer(300),
            PamConstants.animationEasing: .integer(1),
            PamConstants.opacity: .decimal(1),
        ], kind: .view)
        defer { renderer.close() }
        // Off-screen: the implicit rule applies the target immediately.
        renderer.commit([[.update(id: 2, key: PamConstants.opacity, value: .decimal(0.3))]])
        XCTAssertEqual(view.alpha, 0.3, accuracy: 0.001)
        renderer.commit([[.update(id: 2, key: PamConstants.animateChanges, value: nil)]])
        renderer.commit([[.update(id: 2, key: PamConstants.opacity, value: .decimal(0.9))]])
        XCTAssertEqual(view.alpha, 0.9, accuracy: 0.001)
    }

    // MARK: pressRetentionOffset

    func testPressRetentionGrowsTheCancelBoundsOnlyWhileTracking() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .pressable, properties: [
                PamMotionKeys.pressRetentionLeft: .decimal(60),
                PamMotionKeys.pressRetentionBottom: .decimal(0),
            ])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
            .layout(id: 2, frame: Frame(x: 100, y: 100, width: 100, height: 44)),
            .setRoot(1),
        ]])
        let button = try XCTUnwrap(host.viewWithTag(2) as? PamPressButton)
        XCTAssertEqual(button.pamPressRetention.left, 60)
        XCTAssertEqual(button.pamPressRetention.top, 20)
        XCTAssertEqual(button.pamPressRetention.right, 20)
        XCTAssertEqual(button.pamPressRetention.bottom, 0)

        // Hit testing (not tracking) ignores the retention offset.
        XCTAssertFalse(button.point(inside: CGPoint(x: -30, y: 20), with: nil))
        XCTAssertFalse(button.touchBounds(retaining: false).contains(CGPoint(x: -30, y: 20)))
        // A press in flight survives a drag inside the retention bounds.
        let retained = button.touchBounds(retaining: true)
        XCTAssertTrue(retained.contains(CGPoint(x: -59, y: 20)))
        XCTAssertFalse(retained.contains(CGPoint(x: -61, y: 20)))
        XCTAssertTrue(retained.contains(CGPoint(x: 50, y: -19)))
        XCTAssertFalse(retained.contains(CGPoint(x: 50, y: 45)))

        renderer.commit([[.update(id: 2, key: PamMotionKeys.pressRetentionLeft, value: nil)]])
        XCTAssertEqual(button.pamPressRetention.left, 20)
        XCTAssertEqual(button.pamPressRetention, PamPressButton.defaultPressRetention.withBottom(0))
    }

    private static func argb(_ color: UIColor) -> UInt32 {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        return UInt32(a * 255 + 0.5) << 24 | UInt32(r * 255 + 0.5) << 16 | UInt32(g * 255 + 0.5) << 8 | UInt32(b * 255 + 0.5)
    }
}

private extension UIEdgeInsets {
    func withBottom(_ value: CGFloat) -> UIEdgeInsets {
        UIEdgeInsets(top: top, left: left, bottom: value, right: right)
    }
}
