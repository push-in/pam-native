import UIKit
import XCTest
@testable import PamNative

/// RN `StatusBar` on iOS: the stack, the controller-based appearance and
/// the `UIViewControllerBasedStatusBarAppearance` NO fallback.
@MainActor
final class PamStatusBarTests: XCTestCase {
    private final class CountingController: UIViewController {
        var updates = 0

        override func setNeedsStatusBarAppearanceUpdate() {
            updates += 1
            super.setNeedsStatusBarAppearanceUpdate()
        }
    }

    func testBarStyleMapsLikeReactNative() {
        XCTAssertEqual(PamStatusBarCoordinator.style(forAppearance: 1), .darkContent)
        XCTAssertEqual(PamStatusBarCoordinator.style(forAppearance: 2), .lightContent)
        XCTAssertEqual(PamStatusBarCoordinator.style(forAppearance: nil), .default)
    }

    func testLaterMountedDeclarationsWinPropertyByProperty() {
        let screen = PamStatusBarDeclaration(appearance: 1, hidden: false, animated: true)
        let modal = PamStatusBarDeclaration(appearance: 2, hidden: nil, animated: nil)
        XCTAssertEqual(
            PamStatusBarCoordinator.merge([screen, modal]),
            PamStatusBarConfig(style: .lightContent, hidden: false, animated: true)
        )
        // The modal closes: the screen's bar comes back.
        XCTAssertEqual(
            PamStatusBarCoordinator.merge([screen]),
            PamStatusBarConfig(style: .darkContent, hidden: false, animated: true)
        )
        XCTAssertEqual(PamStatusBarCoordinator.merge([]), .initial)
    }

    func testBackgroundColorAndTranslucencyStackLikeTheOtherProperties() {
        let screen = PamStatusBarDeclaration(appearance: 2, backgroundColor: 0xFF0B1120)
        let modal = PamStatusBarDeclaration(translucent: true)
        let merged = PamStatusBarCoordinator.merge([screen, modal])
        XCTAssertEqual(merged.backgroundColor, 0xFF0B1120)
        XCTAssertTrue(merged.translucent)
        XCTAssertNil(merged.bandColor, "translucent: the content shows through")
        XCTAssertEqual(
            PamStatusBarCoordinator.merge([screen]).bandColor,
            UIColor(argb: 0xFF0B1120)
        )
        XCTAssertNil(PamStatusBarConfig.initial.bandColor, "no StatusBar: no band")
        XCTAssertNil(
            PamStatusBarConfig(backgroundColor: 0x000B1120).bandColor,
            "a fully transparent colour paints nothing, as on Android"
        )
    }

    func testMissingInfoPlistKeyMeansViewControllerBased() {
        XCTAssertTrue(PamStatusBarCoordinator.viewControllerBased(in: Bundle(for: Self.self)))
    }

    func testViewControllerBasedChangeAsksEveryControllerToUpdate() {
        let root = CountingController()
        let child = CountingController()
        root.addChild(child)
        root.view.addSubview(child.view)
        child.didMove(toParent: root)
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 640))
        window.rootViewController = root
        var animations = 0
        var legacyCalls = 0
        let coordinator = PamStatusBarCoordinator(
            viewControllerBased: true,
            windows: { [window] },
            animate: { _, changes in
                animations += 1
                changes()
            },
            legacy: { _ in legacyCalls += 1 }
        )

        coordinator.apply(PamStatusBarConfig(style: .lightContent))
        XCTAssertEqual(coordinator.config.style, .lightContent)
        XCTAssertGreaterThanOrEqual(root.updates, 1)
        XCTAssertGreaterThanOrEqual(child.updates, 1)
        XCTAssertEqual(animations, 0)

        let rootUpdates = root.updates
        coordinator.apply(PamStatusBarConfig(style: .lightContent))
        XCTAssertEqual(root.updates, rootUpdates, "an unchanged config is not re-applied")

        coordinator.apply(PamStatusBarConfig(style: .darkContent, hidden: true, animated: true))
        XCTAssertEqual(animations, PamMotionPolicy.isReduced ? 0 : 1)
        XCTAssertGreaterThan(root.updates, rootUpdates)
        XCTAssertEqual(coordinator.config.updateAnimation, .fade)
        XCTAssertEqual(legacyCalls, 0)
    }

    func testApplicationStatusBarIsUsedWhenNotViewControllerBased() {
        var applied: [PamStatusBarConfig] = []
        let coordinator = PamStatusBarCoordinator(
            viewControllerBased: false,
            windows: { XCTFail("controllers are not asked"); return [] },
            animate: { _, changes in changes() },
            legacy: { applied.append($0) }
        )
        let config = PamStatusBarConfig(style: .lightContent, hidden: true, animated: false)
        coordinator.apply(config)
        coordinator.apply(config)
        XCTAssertEqual(applied, [config])
    }

    func testOnlyShownStatusBarsAreActive() {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 640))
        let root = UIViewController()
        window.rootViewController = root
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        let route = UIView()
        let bar = UIView()
        route.addSubview(bar)
        XCTAssertFalse(PamRenderer.isStatusBarActive(bar), "not in a window")
        root.view.addSubview(route)
        XCTAssertTrue(PamRenderer.isStatusBarActive(bar))
        route.isHidden = true
        XCTAssertFalse(PamRenderer.isStatusBarActive(bar), "an inactive route")

        let modal = PamModalHost(frame: window.bounds)
        let modalBar = UIView()
        modal.addSubview(modalBar)
        root.view.addSubview(modal)
        modal.setVisible(true)
        XCTAssertTrue(PamRenderer.isStatusBarActive(modalBar), "an opening modal")
        modal.setVisible(false)
        XCTAssertFalse(PamRenderer.isStatusBarActive(modalBar), "a closing modal")
    }

    func testRendererStacksScreenAndModalStatusBars() {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 360, height: 720))
        let root = UIViewController()
        window.rootViewController = root
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        let host = UIView(frame: window.bounds)
        root.view.addSubview(host)
        let renderer = PamRenderer(hostView: host, dispatchEvent: { _, _, _ in })
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .statusBar, properties: [
                PamConstants.statusBarStyle: .integer(1),
            ])),
            .create(NodeSpec(id: 3, parent: 1, index: 1, kind: .modal, properties: [
                PamConstants.visible: .flag(true),
            ])),
            .create(NodeSpec(id: 4, parent: 3, index: 0, kind: .statusBar, properties: [
                PamConstants.statusBarStyle: .integer(2),
                PamConstants.statusBarHidden: .flag(true),
            ])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 360, height: 720)),
            .setRoot(1),
        ]])
        XCTAssertEqual(
            PamStatusBarCoordinator.shared.config,
            PamStatusBarConfig(style: .lightContent, hidden: true, animated: false)
        )

        renderer.commit([[.update(id: 3, key: PamConstants.visible, value: .flag(false))]])
        XCTAssertEqual(
            PamStatusBarCoordinator.shared.config,
            PamStatusBarConfig(style: .darkContent, hidden: false, animated: false)
        )

        renderer.commit([[.remove(2), .remove(3)]])
        XCTAssertEqual(PamStatusBarCoordinator.shared.config, .initial)
        renderer.close()
    }

    func testRendererPaintsTheStatusBarBandLikeAndroid() {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 360, height: 720))
        let root = UIViewController()
        // The simulator's inset is device-dependent; grow it so the band has
        // a height whatever the device.
        root.additionalSafeAreaInsets = UIEdgeInsets(top: 44, left: 0, bottom: 0, right: 0)
        window.rootViewController = root
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        window.layoutIfNeeded()
        let host = UIView(frame: root.view.bounds)
        host.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        root.view.addSubview(host)
        let renderer = PamRenderer(hostView: host, dispatchEvent: { _, _, _ in })
        defer { renderer.close() }
        let color: Int64 = 0xFF0B1120

        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .statusBar, properties: [
                PamConstants.statusBarColor: .integer(color),
                PamConstants.statusBarStyle: .integer(2),
            ])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 360, height: 720)),
            .setRoot(1),
        ]])
        host.layoutIfNeeded()
        let inset = host.safeAreaInsets.top
        XCTAssertGreaterThanOrEqual(inset, 44)
        guard let band = renderer.statusBarBand else {
            return XCTFail("an opaque backgroundColor paints a band")
        }
        XCTAssertTrue(band.superview === host)
        XCTAssertTrue(host.subviews.last === band, "the band covers the content")
        XCTAssertEqual(band.backgroundColor, UIColor(argb: color))
        XCTAssertEqual(band.frame.minY, 0)
        XCTAssertEqual(band.frame.width, host.bounds.width)
        XCTAssertEqual(band.frame.height, inset, accuracy: 0.5)
        XCTAssertFalse(band.isUserInteractionEnabled, "touches reach the content")

        // Later root children must not cover it.
        renderer.commit([[
            .create(NodeSpec(id: 5, parent: 0, index: 1, kind: .screen, properties: [:])),
            .layout(id: 5, frame: Frame(x: 0, y: 0, width: 360, height: 720)),
        ]])
        XCTAssertTrue(host.subviews.last === band)

        // translucent: the content shows through, no band.
        renderer.commit([[.update(id: 2, key: PamConstants.statusBarTranslucent, value: .flag(true))]])
        XCTAssertNil(renderer.statusBarBand)
        XCTAssertNil(band.superview)
        XCTAssertTrue(PamStatusBarCoordinator.shared.config.translucent)

        renderer.commit([[.update(id: 2, key: PamConstants.statusBarTranslucent, value: .flag(false))]])
        XCTAssertNotNil(renderer.statusBarBand)

        // The node replaced by one with another colour recolours the band.
        let replacement: Int64 = 0xFFFFF8F2
        renderer.commit([[
            .remove(2),
            .create(NodeSpec(id: 3, parent: 1, index: 0, kind: .statusBar, properties: [
                PamConstants.statusBarColor: .integer(replacement),
            ])),
        ]])
        XCTAssertEqual(renderer.statusBarBand?.backgroundColor, UIColor(argb: replacement))

        // The node removed: no band, the config back to the default.
        renderer.commit([[.remove(3)]])
        XCTAssertNil(renderer.statusBarBand)
        XCTAssertFalse(host.subviews.contains { $0 is PamStatusBarBand })
        XCTAssertEqual(PamStatusBarCoordinator.shared.config, .initial)
    }
}
