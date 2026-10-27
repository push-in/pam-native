import UIKit
import XCTest
@testable import PamNative

@MainActor
final class PamModalBackdropTapTests: XCTestCase {
    /// A tap above a bottom sheet reaches the backdrop and requests close; a
    /// tap on the sheet content stays with the content.
    func testTapAboveBottomSheetReachesBackdropAndRequestsClose() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let root = UIViewController()
        window.rootViewController = root
        window.makeKeyAndVisible()
        let host = root.view!
        var closeRequests = 0
        let renderer = PamRenderer(hostView: host) { node, kind, _ in
            if node == 2, kind == EventKind.modalRequestClose.rawValue { closeRequests += 1 }
        }
        renderer.engineManagedSafeArea = true
        let full = Frame(x: 0, y: 0, width: 390, height: 844)
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .modal,
                properties: [
                    PamConstants.modalPresentation: .integer(3),
                    PamConstants.onModalRequestClose: .flag(true),
                ])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .column,
                properties: [PamConstants.flexGrow: .decimal(1)])),
            .layout(id: 1, frame: full), .layout(id: 2, frame: full), .layout(id: 3, frame: full),
            .setRoot(1),
        ]])
        let modal = host.viewWithTag(2) as! PamModalHost
        let content = host.viewWithTag(3)!
        // SwiftPM runs without UIApplicationMain: call the control's
        // registered touch-up targets instead of asking UIApplication to send.
        func tap(_ control: UIControl?) {
            guard let control else { return }
            for target in control.allTargets {
                guard let object = target.base as? NSObject else { continue }
                for action in control.actions(forTarget: object, forControlEvent: .touchUpInside) ?? [] {
                    _ = object.perform(NSSelectorFromString(action))
                }
            }
        }
        modal.setBottomSheetSnapPoints([0.5])
        modal.setVisible(true)
        let deadline = Date().addingTimeInterval(0.5)
        repeat {
            window.layoutIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.01))
        } while Date() < deadline
        defer { modal.setVisible(false); window.isHidden = true; renderer.close() }

        let sheetTop = content.convert(content.bounds, to: modal).minY
        XCTAssertGreaterThan(sheetTop, 200, "the half-height sheet leaves the top of the window uncovered")

        let above = modal.hitTest(CGPoint(x: 195, y: sheetTop / 2), with: nil)
        let backdrop = try? XCTUnwrap(above as? UIControl)
        XCTAssertNotNil(backdrop, "a tap above the sheet lands on the backdrop, not the content container")
        tap(backdrop)
        XCTAssertEqual(closeRequests, 1)

        modal.setVisible(true)
        let deadline2 = Date().addingTimeInterval(0.5)
        repeat {
            window.layoutIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.01))
        } while Date() < deadline2
        let inside = modal.hitTest(CGPoint(x: 195, y: sheetTop + 100), with: nil)
        XCTAssertTrue(inside?.isDescendant(of: content) ?? false, "a tap on the sheet stays with its content")

        // The app answers the close request with `visible = false` while the
        // native dismissal is running: the sheet stays closed.
        tap(modal.hitTest(CGPoint(x: 195, y: sheetTop / 2), with: nil) as? UIControl)
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        modal.setVisible(false)
        let deadline3 = Date().addingTimeInterval(0.8)
        repeat {
            window.layoutIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.01))
        } while Date() < deadline3
        XCTAssertEqual(closeRequests, 2)
        XCTAssertTrue(modal.isHidden, "the repeated hide does not bring the sheet back")
    }
}
