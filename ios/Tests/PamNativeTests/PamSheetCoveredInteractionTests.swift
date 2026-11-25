import UIKit
import XCTest
@testable import PamNative

@MainActor
final class PamSheetCoveredInteractionTests: XCTestCase {
    func testCoveredSheetDoesNotClaimOrHandleAnEarlierEscapeCommand() throws {
        let scene = try makeScene()
        defer { scene.renderer.close(); scene.window.isHidden = true }
        var requests = 0
        var sheetDismissals = 0
        scene.modal.setCallbacks(
            onRequestClose: { requests += 1 }, onShow: nil,
            onDismiss: nil, onOrientationChange: nil
        )
        scene.modal.setBottomSheetCallbacks(onChange: nil, onDismiss: { sheetDismissals += 1 })
        let command = try XCTUnwrap(scene.modal.keyCommands?.first)

        scene.parent.isHidden = true
        NotificationCenter.default.post(name: PamRouteCoverage.didChange, object: nil)
        XCTAssertEqual(scene.modal.alpha, 0)
        XCTAssertFalse(scene.modal.isUserInteractionEnabled)
        XCTAssertNil(scene.modal.keyCommands, "a retained hidden sheet does not claim Escape")
        _ = scene.modal.perform(command.action)
        XCTAssertEqual(requests, 0, "an Escape captured before navigation cannot close a covered sheet")
        XCTAssertEqual(sheetDismissals, 0)
        XCTAssertTrue(scene.modal.showsContent, "coverage alone preserves the app's open sheet state")

        scene.parent.isHidden = false
        NotificationCenter.default.post(name: PamRouteCoverage.didChange, object: nil)
        XCTAssertNotNil(scene.modal.keyCommands)
        _ = scene.modal.perform(command.action)
        XCTAssertEqual(requests, 1)
        XCTAssertEqual(sheetDismissals, 1)
        XCTAssertFalse(scene.modal.showsContent, "Escape still closes the revealed sheet")
    }

    func testCoveredSheetStillClosesProgrammatically() throws {
        let scene = try makeScene()
        defer { scene.renderer.close(); scene.window.isHidden = true }
        var requests = 0
        var dismissals = 0
        scene.modal.setCallbacks(
            onRequestClose: { requests += 1 }, onShow: nil,
            onDismiss: { dismissals += 1 }, onOrientationChange: nil
        )
        scene.parent.isHidden = true
        NotificationCenter.default.post(name: PamRouteCoverage.didChange, object: nil)
        scene.modal.setVisible(false)
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))

        XCTAssertTrue(scene.modal.isHidden)
        XCTAssertFalse(scene.modal.showsContent)
        XCTAssertEqual(dismissals, 1)
        XCTAssertEqual(requests, 0, "programmatic dismissal does not become a user close request")
        scene.parent.isHidden = false
        NotificationCenter.default.post(name: PamRouteCoverage.didChange, object: nil)
        XCTAssertTrue(scene.modal.isHidden, "revealing the route does not reopen its closed sheet")
        XCTAssertNil(scene.modal.keyCommands)
    }

    func testPendingSheetAutofocusDoesNotStealDestinationFocusAfterCoverage() throws {
        let scene = try makeScene()
        defer { scene.renderer.close(); scene.window.isHidden = true }
        let sheetInput = PamInputField(frame: CGRect(x: 0, y: 0, width: 200, height: 44))
        sheetInput.inputView = UIView()
        sheetInput.autoFocusRequested = true
        scene.modal.insert(sheetInput, index: 0)
        scene.parent.isHidden = true
        NotificationCenter.default.post(name: PamRouteCoverage.didChange, object: nil)

        let destination = UITextField(frame: CGRect(x: 0, y: 100, width: 200, height: 44))
        destination.inputView = UIView()
        scene.window.rootViewController?.view.addSubview(destination)
        XCTAssertTrue(destination.becomeFirstResponder())
        // Covers both the input's queued focus and the modal entrance's
        // delayed retry. Input hosts already reject invisible ancestors.
        RunLoop.main.run(until: Date().addingTimeInterval(0.4))
        XCTAssertFalse(sheetInput.isFirstResponder)
        XCTAssertTrue(destination.isFirstResponder)
    }

    private func makeScene() throws -> (
        window: UIWindow, renderer: PamRenderer, parent: UIView, modal: PamModalHost
    ) {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let root = UIViewController()
        window.rootViewController = root
        window.makeKeyAndVisible()
        let host = root.view!
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        renderer.engineManagedSafeArea = true
        let full = Frame(x: 0, y: 0, width: 390, height: 844)
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .view, properties: [:])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .modal,
                properties: [PamConstants.modalPresentation: .integer(3)])),
            .layout(id: 1, frame: full), .layout(id: 2, frame: full), .layout(id: 3, frame: full),
            .setRoot(1),
        ]])
        let modal = try XCTUnwrap(host.viewWithTag(3) as? PamModalHost)
        let parent = try XCTUnwrap(host.viewWithTag(2))
        modal.setVisible(true)
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        XCTAssertTrue(modal.showsContent)
        XCTAssertFalse(modal.isHidden)
        return (window, renderer, parent, modal)
    }
}
