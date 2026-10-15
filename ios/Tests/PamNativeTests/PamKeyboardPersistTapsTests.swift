import UIKit
import XCTest
@testable import PamNative

final class PamKeyboardPersistTapsTests: XCTestCase {
    func testWireValuesMatchThePhpEnum() {
        XCTAssertEqual(PamKeyboardPersistTaps.never, 1)
        XCTAssertEqual(PamKeyboardPersistTaps.always, 2)
        XCTAssertEqual(PamKeyboardPersistTaps.handled, 3)
        XCTAssertEqual(PamKeyboardPersistTaps.normalize(0), PamKeyboardPersistTaps.none)
        XCTAssertEqual(PamKeyboardPersistTaps.normalize(7), PamKeyboardPersistTaps.none)
    }

    func testDecidesLikeReactNative() {
        let never = PamKeyboardPersistTaps.never
        let handled = PamKeyboardPersistTaps.handled
        XCTAssertTrue(PamKeyboardPersistTaps.shouldDismiss(mode: never, keyboardVisible: true, onTextInput: false, handledByChild: true))
        XCTAssertFalse(PamKeyboardPersistTaps.shouldDismiss(mode: never, keyboardVisible: true, onTextInput: true, handledByChild: true))
        XCTAssertFalse(PamKeyboardPersistTaps.shouldDismiss(mode: never, keyboardVisible: false, onTextInput: false, handledByChild: false))
        XCTAssertTrue(PamKeyboardPersistTaps.shouldDismiss(mode: handled, keyboardVisible: true, onTextInput: false, handledByChild: false))
        XCTAssertFalse(PamKeyboardPersistTaps.shouldDismiss(mode: handled, keyboardVisible: true, onTextInput: false, handledByChild: true))
        XCTAssertFalse(PamKeyboardPersistTaps.shouldDismiss(mode: PamKeyboardPersistTaps.always, keyboardVisible: true, onTextInput: false, handledByChild: false))
        XCTAssertFalse(PamKeyboardPersistTaps.shouldDismiss(mode: PamKeyboardPersistTaps.none, keyboardVisible: true, onTextInput: false, handledByChild: false))
    }

    func testRecognizerIsInstalledOnlyForNeverAndHandled() {
        let scroll = PamAnchoredScrollView()
        let baseline = scroll.gestureRecognizers?.count ?? 0
        XCTAssertNil(PamKeyboardTapController.install(PamKeyboardPersistTaps.always, on: scroll, current: nil))
        XCTAssertNil(PamKeyboardTapController.install(PamKeyboardPersistTaps.none, on: scroll, current: nil))
        XCTAssertEqual(scroll.gestureRecognizers?.count ?? 0, baseline)

        let handled = PamKeyboardTapController.install(PamKeyboardPersistTaps.handled, on: scroll, current: nil)
        XCTAssertNotNil(handled)
        XCTAssertEqual(scroll.gestureRecognizers?.count ?? 0, baseline + 1)
        XCTAssertTrue(PamKeyboardTapController.install(PamKeyboardPersistTaps.handled, on: scroll, current: handled) === handled)

        XCTAssertNil(PamKeyboardTapController.install(PamKeyboardPersistTaps.always, on: scroll, current: handled))
        XCTAssertEqual(scroll.gestureRecognizers?.count ?? 0, baseline)
    }
}
