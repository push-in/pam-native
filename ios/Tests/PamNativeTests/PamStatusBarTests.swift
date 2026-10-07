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

    func testMissingInfoPlistKeyMeansViewControllerBased() {
        XCTAssertTrue(PamStatusBarCoordinator.viewControllerBased(in: Bundle(for: Self.self)))
    }

    func testViewControllerBasedChangeAsksEveryControllerToUpdate() {
        let root = CountingController()
        let child = CountingController()
        root.addChild(child)
        root.view.addSubview(child.view)
        child.didMove(toParent: root)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 640))
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
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 640))
        let route = UIView()
        let bar = UIView()
        route.addSubview(bar)
        XCTAssertFalse(PamRenderer.isStatusBarActive(bar), "not in a window")
        window.addSubview(route)
        XCTAssertTrue(PamRenderer.isStatusBarActive(bar))
        route.isHidden = true
        XCTAssertFalse(PamRenderer.isStatusBarActive(bar), "an inactive route")

        let modal = PamModalHost(frame: window.bounds)
        let modalBar = UIView()
        modal.addSubview(modalBar)
        window.addSubview(modal)
        modal.setVisible(true)
        XCTAssertTrue(PamRenderer.isStatusBarActive(modalBar), "an opening modal")
        modal.setVisible(false)
        XCTAssertFalse(PamRenderer.isStatusBarActive(modalBar), "a closing modal")
    }

    func testRendererStacksScreenAndModalStatusBars() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 360, height: 720))
        let host = UIView(frame: window.bounds)
        window.addSubview(host)
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
}
