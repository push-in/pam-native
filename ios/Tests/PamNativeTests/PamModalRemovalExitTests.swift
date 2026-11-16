import UIKit
import XCTest
@testable import PamNative

/// A presented BottomSheet unmounted by `p-if` (the engine removes its
/// subtree) plays the same exit as `visible = false` over a snapshot of its
/// content, takes no touches meanwhile and goes when the exit ends, without
/// notifying the app that already closed it (Android parity:
/// PamModalRemovalExitInstrumentedTest).
@MainActor
final class PamModalRemovalExitTests: XCTestCase {
    override func tearDown() {
        PamMotionPolicy.reduceMotionOverride = nil
        super.tearDown()
    }

    func testRemovingAPresentedSheetPlaysItsExitBeforeItGoes() throws {
        PamMotionPolicy.reduceMotionOverride = false
        let scene = openSheet()
        defer { scene.close() }

        scene.renderer.commit([removeSheet()])
        XCTAssertNil(scene.host.viewWithTag(2), "the app no longer has the sheet")
        let exit = try XCTUnwrap(PamModalHost.removalExitView(in: scene.host), "the unmounted sheet plays its exit")
        XCTAssertFalse(exit.isUserInteractionEnabled, "touches reach the screen below")
        spin(0.05)
        XCTAssertNotNil(PamModalHost.removalExitView(in: scene.host), "still on screen while the exit plays")
        spin(0.6)
        XCTAssertNil(PamModalHost.removalExitView(in: scene.host), "gone when the exit ends")
        XCTAssertEqual(scene.dismissals(), 0, "the app closed it: nothing is notified")
    }

    func testReopeningDuringTheExitPresentsANewSheetAboveIt() throws {
        PamMotionPolicy.reduceMotionOverride = false
        let scene = openSheet()
        defer { scene.close() }

        scene.renderer.commit([removeSheet()])
        let exit = try XCTUnwrap(PamModalHost.removalExitView(in: scene.host))
        spin(0.03)
        scene.renderer.commit([sheet()])
        let reopened = try XCTUnwrap(scene.host.viewWithTag(2) as? PamModalHost)
        reopened.setVisible(true)
        spin(0.05)
        let order = scene.host.subviews
        XCTAssertGreaterThan(
            order.firstIndex(of: reopened) ?? -1,
            order.firstIndex(of: exit) ?? Int.max,
            "the reopened sheet draws above the one leaving"
        )
        spin(0.6)
        XCTAssertNil(PamModalHost.removalExitView(in: scene.host))
        XCTAssertTrue(reopened.isPresentedOnScreen, "the reopened sheet stays")
        XCTAssertEqual(scene.dismissals(), 0)
    }

    func testReducedMotionRemovesTheSheetAtOnce() {
        PamMotionPolicy.reduceMotionOverride = true
        let scene = openSheet()
        defer { scene.close() }

        scene.renderer.commit([removeSheet()])
        XCTAssertNil(scene.host.viewWithTag(2))
        XCTAssertNil(PamModalHost.removalExitView(in: scene.host), "no exit with reduced motion")
        spin(0.3)
        XCTAssertEqual(scene.dismissals(), 0)
    }

    private struct Scene {
        let window: UIWindow
        let host: UIView
        let renderer: PamRenderer
        let dismissals: () -> Int

        func close() {
            renderer.close()
            window.isHidden = true
        }
    }

    private func openSheet() -> Scene {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let root = UIViewController()
        window.rootViewController = root
        window.makeKeyAndVisible()
        let host = root.view!
        var dismissals = 0
        let renderer = PamRenderer(hostView: host) { _, kind, _ in
            if kind == EventKind.modalDismiss.rawValue ||
                kind == EventKind.bottomSheetDismiss.rawValue ||
                kind == EventKind.modalRequestClose.rawValue {
                dismissals += 1
            }
        }
        renderer.engineManagedSafeArea = true
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 390, height: 844)),
        ] + sheet() + [.setRoot(1)]])
        (host.viewWithTag(2) as? PamModalHost)?.setVisible(true)
        spin(0.5)
        XCTAssertTrue((host.viewWithTag(2) as? PamModalHost)?.isPresentedOnScreen == true)
        return Scene(window: window, host: host, renderer: renderer, dismissals: { dismissals })
    }

    private func sheet() -> [Mutation] {
        [
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .modal, properties: [
                PamConstants.modalPresentation: .integer(3),
                PamConstants.modalAnimationType: .integer(2),
                PamConstants.onModalRequestClose: .flag(true),
                PamConstants.onModalDismiss: .flag(true),
                PamConstants.onBottomSheetDismiss: .flag(true),
            ])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .column, properties: [
                PamConstants.backgroundColor: .integer(0xFFFF_FFFF),
            ])),
            .create(NodeSpec(id: 4, parent: 3, index: 0, kind: .view, properties: [
                PamConstants.backgroundColor: .integer(0xFF2E_7D32),
            ])),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 390, height: 844)),
            .layout(id: 3, frame: Frame(x: 0, y: 0, width: 390, height: 422)),
            .layout(id: 4, frame: Frame(x: 16, y: 32, width: 358, height: 48)),
        ]
    }

    /// The engine removes a subtree children first, one Remove per node.
    private func removeSheet() -> [Mutation] {
        [.remove(4), .remove(3), .remove(2)]
    }

    private func spin(_ seconds: TimeInterval) {
        let deadline = Date().addingTimeInterval(seconds)
        repeat {
            RunLoop.main.run(until: Date().addingTimeInterval(0.01))
        } while Date() < deadline
    }
}
