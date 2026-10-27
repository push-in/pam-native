import UIKit
import XCTest
@testable import PamNative

@MainActor
final class PamLiftedModalTests: XCTestCase {
    /// A modal declared inside a tab scene is drawn above the scene's later
    /// siblings (a tab bar), at its absolute engine frame, like Android's
    /// dialog window.
    func testModalInsideAContainerCoversLaterSiblings() throws {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let root = UIViewController()
        window.rootViewController = root
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        let host = root.view!
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.engineManagedSafeArea = true
        let full = Frame(x: 0, y: 0, width: 390, height: 844)
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .view, properties: [:])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .modal,
                properties: [PamConstants.modalPresentation: .integer(3)])),
            .create(NodeSpec(id: 4, parent: 1, index: 1, kind: .view, properties: [:])),
            .layout(id: 1, frame: full),
            .layout(id: 2, frame: Frame(x: 0, y: 100, width: 390, height: 644)),
            .layout(id: 3, frame: full),
            .layout(id: 4, frame: Frame(x: 0, y: 744, width: 390, height: 100)),
            .setRoot(1),
        ]])
        let modal = try XCTUnwrap(host.viewWithTag(3) as? PamModalHost)
        let tabBar = try XCTUnwrap(host.viewWithTag(4))
        XCTAssertTrue(modal.superview === host, "mounted on the root host")
        XCTAssertEqual(modal.frame, CGRect(x: 0, y: 0, width: 390, height: 844), "absolute engine frame")
        let order = host.subviews
        XCTAssertGreaterThan(order.firstIndex(of: modal) ?? -1, order.firstIndex(where: { tabBar.isDescendant(of: $0) }) ?? Int.max)

        // Its logical parent hidden (inactive tab): the modal hides with it.
        host.viewWithTag(2)?.isHidden = true
        NotificationCenter.default.post(name: PamRouteCoverage.didChange, object: nil)
        XCTAssertEqual(modal.alpha, 0)
        XCTAssertFalse(modal.isUserInteractionEnabled)
    }

    func testClosingAModalEndsEditingInside() throws {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.isHidden = false
        defer { window.isHidden = true }
        let modal = PamModalHost(frame: window.bounds)
        window.addSubview(modal)
        modal.setVisible(true)
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        let field = UITextField(frame: CGRect(x: 0, y: 0, width: 200, height: 40))
        modal.insert(field, index: 0)
        field.becomeFirstResponder()
        modal.setVisible(false)
        RunLoop.main.run(until: Date().addingTimeInterval(0.6))
        XCTAssertFalse(field.isFirstResponder)
    }
}
