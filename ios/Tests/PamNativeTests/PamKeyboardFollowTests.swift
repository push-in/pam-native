import UIKit
import XCTest
@testable import PamNative

/// A panning KeyboardAvoidingView that rests on the screen bottom starts
/// moving with the keyboard natively (residual translation) and gives that
/// translation back when the engine frame for the same inset lands
/// (Android 1.46.18: the composer never hides under the rising keyboard).
@MainActor
final class PamKeyboardFollowTests: XCTestCase {
    private func render(behavior: Int64) -> (UIWindow, PamRenderer, UIView) {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.isHidden = false
        let host = UIView(frame: window.bounds)
        window.addSubview(host)
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        let full = Frame(x: 0, y: 0, width: 390, height: 844)
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .view, properties: [:])),
            .create(NodeSpec(id: 3, parent: 1, index: 1, kind: .keyboardAvoidingView,
                properties: [PamConstants.keyboardBehavior: .integer(behavior)])),
            .layout(id: 1, frame: full),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 390, height: 784)),
            .layout(id: 3, frame: Frame(x: 0, y: 784, width: 390, height: 60)),
            .setRoot(1),
        ]])
        return (window, renderer, host.viewWithTag(3)!)
    }

    func testComposerRidesTheKeyboardBeforeAndAfterTheEngineFrame() {
        let (window, renderer, composer) = render(behavior: 2)
        defer { renderer.close(); window.isHidden = true }

        renderer.followKeyboard(overlap: 300, duration: 0, curve: 7)
        XCTAssertEqual(composer.transform.ty, -300, "moves with the keyboard before the engine relayout")

        // The engine frame for the same inset: the list shrinks, the composer
        // is laid out above the keyboard; the translation is given back.
        renderer.commit([[
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 390, height: 484)),
            .layout(id: 3, frame: Frame(x: 0, y: 484, width: 390, height: 60)),
        ]])
        XCTAssertEqual(composer.transform, .identity)
        XCTAssertEqual(composer.frame.maxY, 544, accuracy: 0.5)
        XCTAssertTrue(renderer.keyboardFollowTranslations.isEmpty)

        // Growing upwards (a second line) keeps the bottom: nothing to undo.
        renderer.followKeyboard(overlap: 0, duration: 0, curve: 7)
        XCTAssertEqual(composer.transform.ty, 300, "hides with the keyboard")
        renderer.commit([[.layout(id: 3, frame: Frame(x: 0, y: 464, width: 390, height: 80))]])
        XCTAssertEqual(composer.transform.ty, 300)
        renderer.commit([[.layout(id: 3, frame: Frame(x: 0, y: 764, width: 390, height: 80))]])
        XCTAssertEqual(composer.transform, .identity)
        XCTAssertEqual(composer.frame.maxY, 844, accuracy: 0.5)
    }

    func testOnlyAPanningViewOnTheKeyboardEdgeFollows() {
        let (window, renderer, view) = render(behavior: 1)
        defer { renderer.close(); window.isHidden = true }
        renderer.followKeyboard(overlap: 300, duration: 0, curve: 7)
        XCTAssertEqual(view.transform, .identity, "padding/height views are laid out by the engine alone")
    }

    func testResidualMath() {
        XCTAssertEqual(PamKeyboardFollow.residual(offset: 0, bottom: 844, edge: 844, target: 544), -300)
        XCTAssertEqual(PamKeyboardFollow.residual(offset: -300, bottom: 544, edge: 544, target: 844), 0)
        XCTAssertNil(PamKeyboardFollow.residual(offset: 0, bottom: 760, edge: 844, target: 544),
                     "above a tab bar: not resting on the keyboard edge")
    }
}
