import UIKit
import XCTest
@testable import PamNative

@MainActor
final class PamSheetViewportTests: XCTestCase {
    func testEngineRelayoutCannotReplaceSheetDetentAndDescendantsFollowSnapChanges() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let root = UIViewController()
        window.rootViewController = root
        window.makeKeyAndVisible()
        let host = root.view!
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        renderer.engineManagedSafeArea = true
        let full = Frame(x: 0, y: 0, width: 390, height: 844)
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .modal,
                properties: [PamConstants.modalPresentation: .integer(3)])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .safeAreaView, properties: [:])),
            .create(NodeSpec(id: 4, parent: 3, index: 0, kind: .keyboardAvoidingView,
                properties: [PamConstants.flexGrow: .decimal(1)])),
            .create(NodeSpec(id: 5, parent: 4, index: 0, kind: .column,
                properties: [PamConstants.flexGrow: .decimal(1)])),
            .create(NodeSpec(id: 6, parent: 5, index: 0, kind: .view,
                properties: [PamConstants.flexGrow: .decimal(1)])),
            .create(NodeSpec(id: 7, parent: 5, index: 1, kind: .view, properties: [:])),
            .layout(id: 1, frame: full), .layout(id: 2, frame: full),
            .layout(id: 3, frame: full), .layout(id: 4, frame: full),
            .layout(id: 5, frame: full),
            .layout(id: 6, frame: Frame(x: 0, y: 0, width: 390, height: 794)),
            .layout(id: 7, frame: Frame(x: 0, y: 794, width: 390, height: 50)),
            .setRoot(1),
        ]])
        let modal = host.viewWithTag(2) as! PamModalHost
        let child = host.viewWithTag(3)!
        let footer = host.viewWithTag(7)!
        modal.setBottomSheetSnapPoints([0.5, 0.9])
        modal.setVisible(true)
        func settle() {
            let deadline = Date().addingTimeInterval(0.15)
            repeat {
                window.layoutIfNeeded()
                modal.layoutIfNeeded()
                RunLoop.main.run(until: Date().addingTimeInterval(0.01))
            } while Date() < deadline
        }
        defer { modal.setVisible(false); window.isHidden = true }
        settle()
        for snap in [0, 1, 0] {
            modal.setBottomSheetIndex(snap)
            settle()
            let viewport = modal.sheetContentBounds!
            XCTAssertGreaterThan(viewport.height, 0)
            XCTAssertLessThan(viewport.height, 844)
            XCTAssertEqual(child.frame, viewport, "native snap changes resize the child without an engine commit")
            XCTAssertEqual(footer.convert(footer.bounds, to: child).maxY, viewport.height, accuracy: 0.5)
            XCTAssertGreaterThanOrEqual(child.convert(child.bounds, to: modal).minY, modal.safeAreaInsets.top - 0.5)
            // This is the production sequence absent from the earlier modal-only fixture.
            renderer.commit([[
                .layout(id: 3, frame: full), .layout(id: 4, frame: full),
                .layout(id: 5, frame: full),
                .layout(id: 6, frame: Frame(x: 0, y: 0, width: 390, height: 794)),
                .layout(id: 7, frame: Frame(x: 0, y: 794, width: 390, height: 50)),
            ]])
            XCTAssertEqual(child.frame, viewport)
            XCTAssertEqual(footer.convert(footer.bounds, to: child).maxY, viewport.height, accuracy: 0.5)
            XCTAssertEqual(footer.bounds.height, 50, accuracy: 0.5)
        }
        for presentation in [1, 2] {
            modal.setPresentation(presentation)
            settle()
            let authored = Frame(x: 12, y: 20, width: 300, height: 200)
            renderer.commit([[.layout(id: 3, frame: authored)]])
            XCTAssertNil(modal.sheetContentBounds)
            XCTAssertEqual(child.frame, CGRect(x: 12, y: 20, width: 300, height: 200))
        }
    }
}
