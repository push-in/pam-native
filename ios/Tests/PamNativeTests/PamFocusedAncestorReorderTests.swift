import UIKit
import XCTest
@testable import PamNative

@MainActor
final class PamFocusedAncestorReorderTests: XCTestCase {
    func testMovingFocusedAncestorKeepsResponderSelectionAndDraft() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let controller = UIViewController()
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        var blurCount = 0
        let renderer = PamRenderer(hostView: controller.view) { id, event, _ in
            if id == 5 && event == EventKind.blur.rawValue { blurCount += 1 }
        }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .view, properties: [PamConstants.backgroundColor: .integer(0xFFFFFFFF)])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view, properties: [PamConstants.backgroundColor: .integer(0xFFEEEEEE)])),
            .create(NodeSpec(id: 4, parent: 2, index: 1, kind: .view, properties: [PamConstants.backgroundColor: .integer(0xFFDDDDDD)])),
            .create(NodeSpec(id: 5, parent: 3, index: 0, kind: .input, properties: [PamConstants.onBlur: .flag(true)])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 390, height: 844)),
            .layout(id: 2, frame: Frame(x: 0, y: 100, width: 390, height: 300)),
            .layout(id: 3, frame: Frame(x: 0, y: 0, width: 390, height: 80)),
            .layout(id: 4, frame: Frame(x: 0, y: 100, width: 390, height: 80)),
            .layout(id: 5, frame: Frame(x: 0, y: 0, width: 300, height: 50)),
            .setRoot(1),
        ]])
        let input = controller.view.viewWithTag(5) as! PamInputField
        input.text = "draft before reorder"
        XCTAssertTrue(input.becomeFirstResponder())
        let start = input.position(from: input.beginningOfDocument, offset: 2)!
        let end = input.position(from: input.beginningOfDocument, offset: 7)!
        input.selectedTextRange = input.textRange(from: start, to: end)
        for index in [1, 0, 1, 0] {
            renderer.commit([[.move(id: 3, parent: 2, index: index)]])
            XCTAssertTrue(input.isFirstResponder)
            XCTAssertEqual(input.text, "draft before reorder")
            XCTAssertEqual(input.offset(from: input.beginningOfDocument, to: input.selectedTextRange!.start), 2)
            XCTAssertEqual(input.offset(from: input.beginningOfDocument, to: input.selectedTextRange!.end), 7)
            XCTAssertEqual(blurCount, 0)
        }
    }
}
