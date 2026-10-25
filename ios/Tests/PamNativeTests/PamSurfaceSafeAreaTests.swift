import UIKit
import XCTest
@testable import PamNative

/// Safe areas are per presentation surface. The iOS bridge sets the engine's
/// `SurfacePolicy::InWindow` (policy 0): a PAM `Modal`/`BottomSheet` is a view
/// inside the PAM window, so a `SafeAreaView` inside a full-screen or dialog
/// modal receives every window inset, inside a bottom sheet no top inset, and
/// inside a page/form-sheet navigation route (presentations 2 and 7) no top
/// inset either. These tests pin each of those engine rules to UIKit's own
/// safe area for the very same surface, so engine padding and the platform
/// never disagree (no status bar applied twice, none missing).
@MainActor
final class PamSurfaceSafeAreaTests: XCTestCase {
    override func setUp() {
        super.setUp()
        // Present without animation: geometry is read once presented.
        PamMotionPolicy.reduceMotionOverride = true
    }

    override func tearDown() {
        PamMotionPolicy.reduceMotionOverride = nil
        super.tearDown()
    }

    private enum Presentation {
        static let fullScreen = 1
        static let dialog = 2
        static let sheet = 3
    }

    private enum Route {
        static let modal = 2
        static let fullScreenModal = 4
        static let formSheet = 7
    }

    /// A window whose root view has a status-bar and home-indicator area
    /// even on a simulator without a notch.
    private func makeWindow() -> (UIWindow, UIViewController) {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let root = UIViewController()
        root.additionalSafeAreaInsets = UIEdgeInsets(top: 47, left: 0, bottom: 34, right: 0)
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

    private func presentModal(
        _ presentation: Int,
        snapPoints: [CGFloat]? = nil,
        in window: UIWindow,
        root: UIViewController
    ) -> (modal: PamModalHost, content: UIView) {
        let modal = PamModalHost(frame: root.view.bounds)
        modal.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        root.view.addSubview(modal)
        let content = UIView()
        modal.insert(content, index: 0)
        modal.setPresentation(presentation)
        if let snapPoints {
            modal.setBottomSheetSnapPoints(snapPoints)
            modal.setBottomSheetIndex(0)
        }
        modal.setVisible(true)
        spin(until: { !modal.isHidden })
        spin(until: { false }, timeout: 0.4)
        window.layoutIfNeeded()
        return (modal, content)
    }

    private func assertInsets(
        _ actual: UIEdgeInsets,
        _ expected: UIEdgeInsets,
        _ message: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertEqual(actual.top, expected.top, accuracy: 0.5, "\(message) top", file: file, line: line)
        XCTAssertEqual(actual.left, expected.left, accuracy: 0.5, "\(message) left", file: file, line: line)
        XCTAssertEqual(actual.bottom, expected.bottom, accuracy: 0.5, "\(message) bottom", file: file, line: line)
        XCTAssertEqual(actual.right, expected.right, accuracy: 0.5, "\(message) right", file: file, line: line)
    }

    func testFullScreenAndDialogModalsCoverTheWindowSoTheEngineAppliesEveryInset() {
        for presentation in [Presentation.fullScreen, Presentation.dialog] {
            let (window, root) = makeWindow()
            let (modal, content) = presentModal(presentation, in: window, root: root)
            let windowInsets = root.view.safeAreaInsets
            XCTAssertGreaterThan(windowInsets.top, 0)
            XCTAssertGreaterThan(windowInsets.bottom, 0)
            // The surface is the whole window, top to bottom...
            XCTAssertEqual(content.convert(content.bounds, to: root.view), root.view.bounds)
            // ...so UIKit's safe area for it is the window's, exactly the
            // insets the engine gives its SafeAreaView (applied once).
            assertInsets(content.safeAreaInsets, windowInsets, "presentation \(presentation)")
            modal.removeFromSuperview()
            window.isHidden = true
        }
    }

    func testBottomSheetNeverReachesTheStatusBarButKeepsTheHomeIndicator() {
        for snapPoint: CGFloat in [0.5, 1] {
            let (window, root) = makeWindow()
            let (modal, content) = presentModal(
                Presentation.sheet,
                snapPoints: [snapPoint],
                in: window,
                root: root
            )
            let windowInsets = root.view.safeAreaInsets
            let frame = content.convert(content.bounds, to: root.view)
            XCTAssertGreaterThan(frame.height, 0)
            // Snap points resolve below the top inset, so even a full sheet
            // starts under the status bar area, never inside it...
            XCTAssertGreaterThanOrEqual(frame.minY, windowInsets.top - 0.5, "snap \(snapPoint)")
            // ...and rests on the screen bottom, behind the home indicator.
            XCTAssertEqual(frame.maxY, root.view.bounds.maxY, accuracy: 0.5, "snap \(snapPoint)")
            // Engine rule for a sheet surface: top 0, other edges real.
            assertInsets(
                content.safeAreaInsets,
                UIEdgeInsets(top: 0, left: windowInsets.left, bottom: windowInsets.bottom, right: windowInsets.right),
                "sheet snap \(snapPoint)"
            )
            modal.removeFromSuperview()
            window.isHidden = true
        }
    }

    private func presentRoute(_ presentation: Int) -> (
        window: UIWindow,
        root: UIViewController,
        host: PamNavigationHost,
        route: UIView
    ) {
        let window = pamTestWindow(frame: UIScreen.main.bounds)
        let root = UIViewController()
        window.rootViewController = root
        window.makeKeyAndVisible()
        spin(until: { root.view.window != nil })
        let host = PamNavigationHost(frame: root.view.bounds)
        root.view.addSubview(host)
        host.operation = 2
        host.transition = 8
        host.screenPresentation = presentation
        let base = UIView()
        let route = UIView()
        host.insert(base, index: 0)
        host.insert(route, index: 1)
        host.navigate(1)
        spin(until: { root.presentedViewController != nil })
        // The headless XCTest host records presentation without necessarily
        // loading the presented navigation controller's top view. A real app
        // does that while drawing its first frame; force the same lifecycle
        // before reading safe-area geometry.
        if let navigation = root.presentedViewController as? UINavigationController {
            _ = navigation.view
            _ = navigation.topViewController?.view
            navigation.view.layoutIfNeeded()
        }
        spin(until: {
            route.window != nil && root.presentedViewController?.isBeingPresented == false
        })
        spin(until: { false }, timeout: 0.3)
        route.window?.layoutIfNeeded()
        return (window, root, host, route)
    }

    func testPageAndFormSheetRoutesStartBelowTheStatusBar() throws {
        for presentation in [Route.modal, Route.formSheet] {
            let (window, root, host, route) = presentRoute(presentation)
            XCTAssertTrue(host.usesNativeModalController, "presentation \(presentation)")
            XCTAssertNotNil(root.view.window, "presenter window \(presentation)")
            XCTAssertNotNil(root.presentedViewController, "presented controller \(presentation)")
            guard route.window != nil else {
                throw XCTSkip(
                    "The headless XCTest host records presentation but does not attach its view; "
                        + "the installed-app Simulator gate validates sheet safe-area geometry"
                )
            }
            // Engine rule (InWindow): the active route of a page/form-sheet
            // stack gets no top inset. UIKit agrees for the sheet surface.
            XCTAssertEqual(route.safeAreaInsets.top, 0, accuracy: 0.5, "presentation \(presentation)")
            window.rootViewController?.dismiss(animated: false)
            window.isHidden = true
        }
    }

    func testFullScreenRouteKeepsTheWindowTopInset() throws {
        let (window, root, host, route) = presentRoute(Route.fullScreenModal)
        XCTAssertTrue(host.usesNativeModalController)
        XCTAssertNotNil(root.view.window, "presenter window")
        XCTAssertNotNil(root.presentedViewController, "presented controller")
        guard route.window != nil else {
            throw XCTSkip(
                "The headless XCTest host records presentation but does not attach its view; "
                    + "the installed-app Simulator gate validates full-screen safe-area geometry"
            )
        }
        // Engine rule (InWindow): a full-screen route keeps the real top inset.
        XCTAssertEqual(route.safeAreaInsets.top, window.safeAreaInsets.top, accuracy: 0.5)
        window.rootViewController?.dismiss(animated: false)
        window.isHidden = true
    }
}
