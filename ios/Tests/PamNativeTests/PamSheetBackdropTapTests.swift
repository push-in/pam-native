import UIKit
import XCTest
@testable import PamNative

/// A tap on the dimmed area above a bottom sheet closes it (Android scrim,
/// gorhom `pressBehavior="close"`); the full-window content container must not
/// swallow it. Taps inside the sheet still reach its content.
@MainActor
final class PamSheetBackdropTapTests: XCTestCase {
    func testTapAboveTheSheetReachesTheBackdropAndRequestsClose() {
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
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view,
                properties: [PamConstants.flexGrow: .decimal(1)])),
            .layout(id: 1, frame: full), .layout(id: 2, frame: full), .layout(id: 3, frame: full),
            .setRoot(1),
        ]])
        let modal = host.viewWithTag(2) as! PamModalHost
        var closes = 0
        modal.setCallbacks(onRequestClose: { closes += 1 }, onShow: nil, onDismiss: nil, onOrientationChange: nil)
        modal.setBottomSheetSnapPoints([0.5])
        modal.setVisible(true)
        defer { window.isHidden = true }
        let deadline = Date().addingTimeInterval(0.15)
        repeat {
            window.layoutIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.01))
        } while Date() < deadline

        let inside = modal.hitTest(CGPoint(x: 195, y: 780), with: nil)
        XCTAssertNotNil(inside)
        XCTAssertTrue(inside!.isDescendant(of: host.viewWithTag(3)!), "a tap inside the sheet reaches its content")

        let above = modal.hitTest(CGPoint(x: 195, y: 150), with: nil)
        let backdrop = try? XCTUnwrap(above as? UIControl, "a tap above the sheet lands on the backdrop, not the content container")
        // SwiftPM runs without UIApplicationMain: invoke the targets like UIKit.
        for target in backdrop?.allTargets ?? [] {
            guard let object = target.base as? NSObject else { continue }
            for action in backdrop?.actions(forTarget: object, forControlEvent: .touchUpInside) ?? [] {
                _ = object.perform(NSSelectorFromString(action))
            }
        }
        XCTAssertEqual(closes, 1)

        modal.setPresentation(1)
        modal.setVisible(true)
        window.layoutIfNeeded()
        XCTAssertFalse(modal.hitTest(CGPoint(x: 195, y: 150), with: nil) is UIControl,
                       "a full-screen modal's content still covers the window")
    }
}
