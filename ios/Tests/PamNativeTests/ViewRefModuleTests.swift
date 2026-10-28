import UIKit
import XCTest
@testable import PamNative

/// ViewRef imperative input operations and measurement; requires an iOS test destination.
@MainActor
final class ViewRefModuleTests: XCTestCase {
    func testFocusSelectionClearBlurAndMeasure() throws {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let root = UIViewController()
        window.rootViewController = root
        window.makeKeyAndVisible()
        let card = UIView(frame: CGRect(x: 10, y: 40, width: 200, height: 120))
        let field = UITextField(frame: CGRect(x: 8, y: 12, width: 180, height: 48))
        field.text = "olá mundo"
        var changes: [String] = []
        let target = ChangeTarget { changes.append(field.text ?? "") }
        field.addTarget(target, action: #selector(ChangeTarget.changed), for: .editingChanged)
        card.addSubview(field)
        root.view.addSubview(card)
        let module = ViewRefModule { ref in ref == "card" ? card : (ref == "composer" ? field : nil) }

        _ = try module.run(method: "focus", ref: "composer")
        XCTAssertTrue(field.isFirstResponder)
        let focused = try WireMap.decode(try module.run(method: "isFocused", ref: "composer"))
        XCTAssertEqual(focused["focused"], .flag(true))
        _ = try module.run(method: "setSelection", ref: "composer", start: 2, end: 99)
        let range = try XCTUnwrap(field.selectedTextRange)
        XCTAssertEqual(field.offset(from: field.beginningOfDocument, to: range.start), 2)
        XCTAssertEqual(field.offset(from: field.beginningOfDocument, to: range.end), 9)
        let frame = try WireMap.decode(try module.run(method: "measure", ref: "card"))
        XCTAssertEqual(frame["x"], .decimal(10))
        XCTAssertEqual(frame["width"], .decimal(200))
        let inputFrame = try WireMap.decode(try module.run(method: "measure", ref: "composer"))
        XCTAssertEqual(inputFrame["pageX"], .decimal(18))
        XCTAssertEqual(inputFrame["pageY"], .decimal(52))
        card.pamNativeRef = "card"
        field.pamNativeRef = "composer"
        let scoped = ViewRefModule { ref in ref == "card" ? card : nil }
        let scopedFrame = try WireMap.decode(try scoped.run(method: "measure", ref: "composer", within: "card"))
        XCTAssertEqual(scopedFrame["width"], .decimal(180))
        XCTAssertThrowsError(try scoped.run(method: "measure", ref: "card", within: "missing"))
        _ = try module.run(method: "clear", ref: "composer")
        XCTAssertEqual(field.text, "")
        XCTAssertEqual(changes, [""])
        _ = try module.run(method: "blur", ref: "composer")
        XCTAssertFalse(field.isFirstResponder)
        XCTAssertThrowsError(try module.run(method: "focus", ref: "card"))
        XCTAssertThrowsError(try module.run(method: "measure", ref: "missing"))
        XCTAssertFalse(ViewRefModule.isValidRef("bad ref"))
        window.isHidden = true
    }
}

private final class ChangeTarget: NSObject {
    let action: () -> Void
    init(_ action: @escaping () -> Void) { self.action = action }
    @objc func changed() { action() }
}
