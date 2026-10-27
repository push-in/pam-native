import UIKit
import XCTest
@testable import PamNative

/// A push whose route is moved to the top in the same batch as the revision
/// (a screen mounted ahead below the stack, then pushed) must show that route,
/// whatever order the batch carries the move and the revision in.
@MainActor
final class PamNavigationBatchOrderTests: XCTestCase {
    func testRevisionBeforeMoveInOneBatchShowsTheMovedRoute() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let controller = UIViewController()
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        let renderer = PamRenderer(hostView: controller.view) { _, _, _ in }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .navigationHost, properties: [
                PamConstants.navigationDurationMs: .integer(0),
            ])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view, properties: [:])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 390, height: 844)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 390, height: 844)),
            .layout(id: 3, frame: Frame(x: 0, y: 0, width: 390, height: 844)),
            .setRoot(1),
            .update(id: 2, key: PamConstants.navigationRevision, value: .integer(1)),
        ]])
        renderer.commit([[
            .create(NodeSpec(id: 4, parent: 2, index: 0, kind: .view, properties: [:])),
            .layout(id: 4, frame: Frame(x: 0, y: 0, width: 390, height: 844)),
        ]])
        let list = renderer.mountedView(3)!
        let pushed = renderer.mountedView(4)!
        XCTAssertTrue(pushed.isHidden)

        renderer.commit([[
            .update(id: 2, key: PamConstants.navigationOperation, value: .integer(2)),
            .update(id: 2, key: PamConstants.navigationRevision, value: .integer(2)),
            .move(id: 4, parent: 2, index: 1),
        ]])

        XCTAssertFalse(pushed.isHidden)
        XCTAssertTrue(list.isHidden)
        XCTAssertNotNil(pushed.window)
    }
}
