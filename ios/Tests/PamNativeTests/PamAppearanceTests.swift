import UIKit
import XCTest
@testable import PamNative

/// A runtime light/dark switch must not paint UIKit's transparent overlay
/// windows (the keyboard's `UITextEffectsWindow`): an opaque background there
/// hid the whole app behind a blank screen.
final class PamAppearanceTests: XCTestCase {
    @MainActor
    func testRuntimeSwitchRepaintsTheAppWindowButLeavesTransparentWindowsClear() {
        let app = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        PamAppearance.apply(to: app, mode: PamAppearance.modeLight)
        let overlay = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        overlay.backgroundColor = nil
        let tinted = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        tinted.backgroundColor = UIColor.black.withAlphaComponent(0.2)

        for window in [app, overlay, tinted] {
            PamAppearance.apply(runtimeChange: window, mode: PamAppearance.modeDark)
            XCTAssertEqual(window.overrideUserInterfaceStyle, .dark)
        }
        XCTAssertNil(overlay.backgroundColor, "a transparent window stays transparent")
        XCTAssertEqual(tinted.backgroundColor, UIColor.black.withAlphaComponent(0.2))
        let dark = app.backgroundColor?.resolvedColor(with: UITraitCollection(userInterfaceStyle: .dark))
        XCTAssertEqual(dark?.cgColor.alpha, 1)
    }
}
