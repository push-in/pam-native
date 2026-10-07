import UIKit
import XCTest
@testable import PamNative

/// A `KeyboardAvoidingView` inside a full-screen or dialog `Modal` avoids the
/// keyboard measured in the modal's own coordinate space: PamModalHost turns
/// the keyboard frame into the overlap of its bottom edge and the renderer
/// re-expresses it from the PAM root view's bottom for the engine, which lays
/// the modal's resize/padding/position views out above the keyboard (see the
/// engine's surface keyboard inset). Sheets ride on the keyboard themselves.
final class PamModalKeyboardAvoidingTests: XCTestCase {
    override func setUp() {
        super.setUp()
        PamMotionPolicy.reduceMotionOverride = true
    }

    override func tearDown() {
        PamMotionPolicy.reduceMotionOverride = nil
        super.tearDown()
    }

    private func makeWindow() -> (UIWindow, UIViewController) {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let root = UIViewController()
        window.rootViewController = root
        window.makeKeyAndVisible()
        window.layoutIfNeeded()
        return (window, root)
    }

    private func spin(until condition: () -> Bool, timeout: TimeInterval = 3) {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() && Date() < deadline {
            RunLoop.main.run(until: Date().addingTimeInterval(0.02))
        }
    }

    private func present(_ presentation: Int, in root: UIViewController) -> PamModalHost {
        let modal = PamModalHost(frame: root.view.bounds)
        modal.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        root.view.addSubview(modal)
        modal.insert(UIView(), index: 0)
        modal.setPresentation(presentation)
        modal.setVisible(true)
        spin(until: { !modal.isHidden })
        spin(until: { false }, timeout: 0.3)
        return modal
    }

    private func postKeyboard(top: CGFloat?, in window: UIWindow) {
        let screenHeight = window.bounds.height
        let frame = top.map { CGRect(x: 0, y: $0, width: window.bounds.width, height: screenHeight - $0) }
            ?? CGRect(x: 0, y: screenHeight, width: window.bounds.width, height: 0)
        let end = window.convert(frame, to: window.screen.coordinateSpace)
        NotificationCenter.default.post(
            name: top == nil
                ? UIResponder.keyboardWillHideNotification
                : UIResponder.keyboardWillChangeFrameNotification,
            object: nil,
            userInfo: [
                UIResponder.keyboardFrameEndUserInfoKey: NSValue(cgRect: end),
                UIResponder.keyboardAnimationDurationUserInfoKey: NSNumber(value: 0.25),
                UIResponder.keyboardAnimationCurveUserInfoKey: NSNumber(value: 7),
            ]
        )
    }

    func testOverlapIsMeasuredFromTheModalBottomEdge() {
        let bounds = CGRect(x: 0, y: 0, width: 390, height: 844)
        XCTAssertEqual(
            PamModalHost.surfaceKeyboardInset(
                keyboardInModal: CGRect(x: 0, y: 544, width: 390, height: 300),
                modalBounds: bounds,
                hiding: false
            ),
            300
        )
        XCTAssertEqual(
            PamModalHost.surfaceKeyboardInset(
                keyboardInModal: CGRect(x: 0, y: 844, width: 390, height: 0),
                modalBounds: bounds,
                hiding: false
            ),
            0,
            "an empty or off-screen keyboard covers nothing"
        )
        XCTAssertEqual(
            PamModalHost.surfaceKeyboardInset(
                keyboardInModal: CGRect(x: 0, y: 544, width: 390, height: 300),
                modalBounds: bounds,
                hiding: true
            ),
            0
        )
    }

    func testFullScreenModalPublishesTheKeyboardWithItsAnimation() {
        let (window, root) = makeWindow()
        defer { window.isHidden = true }
        let modal = present(1, in: root)
        var published: [(CGFloat, TimeInterval, UInt)] = []
        modal.onSurfaceKeyboardInset = { published.append(($0, $1, $2)) }

        postKeyboard(top: 544, in: window)
        XCTAssertEqual(published.last?.0 ?? -1, 300, accuracy: 0.5)
        XCTAssertEqual(published.last?.1 ?? 0, 0.25, accuracy: 0.001)
        XCTAssertEqual(published.last?.2, 7)

        postKeyboard(top: nil, in: window)
        XCTAssertEqual(published.last?.0 ?? -1, 0, accuracy: 0.5)
        modal.removeFromSuperview()
    }

    func testDialogModalUsesItsOwnCoordinateSpace() {
        let (window, root) = makeWindow()
        defer { window.isHidden = true }
        // A modal host that ends 100 pt above the window bottom.
        let modal = PamModalHost(frame: CGRect(x: 0, y: 0, width: 390, height: 744))
        root.view.addSubview(modal)
        modal.insert(UIView(), index: 0)
        modal.setPresentation(2)
        modal.setVisible(true)
        spin(until: { !modal.isHidden })
        var inset: CGFloat = -1
        modal.onSurfaceKeyboardInset = { value, _, _ in inset = value }

        postKeyboard(top: 544, in: window)
        XCTAssertEqual(inset, 200, accuracy: 0.5, "keyboard overlap of the modal's own bottom edge")
        XCTAssertEqual(
            PamRenderer.hostKeyboardInset(modalInset: inset, modal: modal, host: root.view),
            300,
            accuracy: 0.5,
            "re-expressed from the PAM root view's bottom for the engine"
        )
        postKeyboard(top: nil, in: window)
        modal.removeFromSuperview()
    }

    func testSheetsRideOnTheKeyboardInsteadOfAvoidingIt() {
        let (window, root) = makeWindow()
        defer { window.isHidden = true }
        let modal = present(3, in: root)
        var published: [CGFloat] = []
        modal.onSurfaceKeyboardInset = { value, _, _ in published.append(value) }
        postKeyboard(top: 544, in: window)
        XCTAssertTrue(published.allSatisfy { $0 == 0 }, "sheet content is lifted, not inset: \(published)")
        postKeyboard(top: nil, in: window)
        modal.removeFromSuperview()
    }
}
