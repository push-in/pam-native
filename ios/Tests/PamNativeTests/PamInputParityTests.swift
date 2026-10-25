import UIKit
import XCTest
@testable import PamNative

/// TextInput parity with Android (`applyInputConfiguration`): multiline
/// growth, placeholder color, caret/context menu and change coalescing.
/// Uncompiled on Linux — needs Mac validation.
@MainActor
final class PamInputParityTests: XCTestCase {
    func testMultilineInputGrowsWithItsContent() throws {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 360, height: 720))
        let (host, renderer, view) = PamRenderTestSupport.render([
            PamConstants.multiline: .flag(true),
            PamConstants.fontSize: .decimal(16),
            PamConstants.text: .text("one\ntwo\nthree\nfour\nfive\nsix"),
        ], kind: .input, frame: Frame(x: 20, y: 20, width: 200, height: 24))
        defer { renderer.close() }
        window.addSubview(host)
        window.isHidden = false
        host.layoutIfNeeded()
        view.layoutIfNeeded()

        let field = try XCTUnwrap(view as? PamMultilineInputField)
        XCTAssertTrue(field.growsWithContent)
        XCTAssertEqual(field.submitBehavior, .newline)
        let lineHeight = try XCTUnwrap(field.font).lineHeight
        XCTAssertGreaterThanOrEqual(field.bounds.height, lineHeight * 6 - 1)
    }

    func testMultilineInputKeepsRoomForMinLines() throws {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 360, height: 720))
        let (host, renderer, view) = PamRenderTestSupport.render([
            PamConstants.multiline: .flag(true),
            PamConstants.inputMinLines: .integer(3),
            PamConstants.fontSize: .decimal(16),
        ], kind: .input, frame: Frame(x: 20, y: 20, width: 200, height: 10))
        defer { renderer.close() }
        window.addSubview(host)
        window.isHidden = false
        view.layoutIfNeeded()

        let field = try XCTUnwrap(view as? PamMultilineInputField)
        XCTAssertEqual(field.minimumLines, 3)
        let lineHeight = try XCTUnwrap(field.font).lineHeight
        XCTAssertGreaterThanOrEqual(field.bounds.height, lineHeight * 3 - 1)
    }

    func testAuthoredHeightStopsMultilineGrowth() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.multiline: .flag(true),
            PamConstants.height: .decimal(40),
            PamConstants.text: .text("one\ntwo\nthree\nfour\nfive\nsix"),
        ], kind: .input, frame: Frame(x: 20, y: 20, width: 200, height: 40))
        defer { renderer.close() }
        view.layoutIfNeeded()
        let field = try XCTUnwrap(view as? PamMultilineInputField)
        XCTAssertFalse(field.growsWithContent)
        XCTAssertEqual(field.bounds.height, 40, accuracy: 0.5)
    }

    func testPlaceholderColorReachesTheAttributedPlaceholder() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.placeholder: .text("Search"),
            PamConstants.placeholderColor: .integer(0xFF88_8899),
        ], kind: .input)
        defer { renderer.close() }
        let field = try XCTUnwrap(view as? PamInputField)
        let placeholder = try XCTUnwrap(field.attributedPlaceholder)
        XCTAssertEqual(placeholder.string, "Search")
        let color = try XCTUnwrap(placeholder.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? UIColor)
        XCTAssertEqual(color, UIColor(argb: 0xFF88_8899))
    }

    func testMultilinePlaceholderColorAndVisibility() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.multiline: .flag(true),
            PamConstants.placeholder: .text("Write a message"),
            PamConstants.placeholderColor: .integer(0xFF11_2233),
        ], kind: .input)
        defer { renderer.close() }
        let field = try XCTUnwrap(view as? PamMultilineInputField)
        XCTAssertEqual(field.placeholderLabel.text, "Write a message")
        XCTAssertEqual(field.placeholderLabel.textColor, UIColor(argb: 0xFF11_2233))
        XCTAssertFalse(field.placeholderLabel.isHidden)
        field.setFormattedTextFromRenderer("typed")
        XCTAssertTrue(field.placeholderLabel.isHidden)
    }

    func testCaretHiddenAndContextMenuHidden() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.text: .text("secret"),
            PamConstants.inputCaretHidden: .flag(true),
            PamConstants.inputContextMenuHidden: .flag(true),
            PamConstants.inputCursorColor: .integer(0xFFFF_0000),
        ], kind: .input)
        defer { renderer.close() }
        let field = try XCTUnwrap(view as? PamInputField)
        XCTAssertTrue(field.caretHidden)
        XCTAssertEqual(field.caretRect(for: field.beginningOfDocument), .zero)
        XCTAssertFalse(field.canPerformAction(#selector(UIResponderStandardEditActions.copy(_:)), withSender: nil))
        XCTAssertFalse(field.canPerformAction(#selector(UIResponderStandardEditActions.paste(_:)), withSender: nil))
        XCTAssertEqual(field.tintColor, UIColor(argb: 0xFFFF_0000))

        let (_, multilineRenderer, multilineView) = PamRenderTestSupport.render([
            PamConstants.multiline: .flag(true),
            PamConstants.text: .text("secret"),
            PamConstants.inputCaretHidden: .flag(true),
            PamConstants.inputContextMenuHidden: .flag(true),
        ], kind: .input)
        defer { multilineRenderer.close() }
        let multiline = try XCTUnwrap(multilineView as? PamMultilineInputField)
        XCTAssertEqual(multiline.caretRect(for: multiline.beginningOfDocument), .zero)
        XCTAssertFalse(multiline.canPerformAction(#selector(UIResponderStandardEditActions.copy(_:)), withSender: nil))
    }


    /// SwiftPM runs without UIApplicationMain, so `sendActions(for:)` never
    /// reaches the registered targets; invoke them like UIKit would.
    private func perform(_ event: UIControl.Event, on field: UITextField) {
        for target in field.allTargets {
            guard let object = target.base as? NSObject else { continue }
            for action in field.actions(forTarget: object, forControlEvent: event) ?? [] {
                _ = object.perform(NSSelectorFromString(action), with: field)
            }
        }
    }

    func testDebouncedChangesAreCoalesced() throws {
        var values: [String] = []
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.onChange: .integer(1),
            PamConstants.inputSyncMode: .integer(2),
            PamConstants.inputDebounceMs: .integer(120),
        ], kind: .input) { _, kind, payload in
            guard kind == EventKind.change.rawValue,
                  case let .text(value)? = try? WireMap.decode(payload)["value"] else { return }
            values.append(value)
        }
        defer { renderer.close() }
        let field = try XCTUnwrap(view as? PamInputField)
        field.text = "a"
        perform(.editingChanged, on: field)
        field.text = "ab"
        perform(.editingChanged, on: field)
        XCTAssertEqual(values, [])

        let settled = expectation(description: "debounce window elapsed")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { settled.fulfill() }
        wait(for: [settled], timeout: 2)
        XCTAssertEqual(values, ["ab"])
    }

    func testImmediateSyncModeSendsEveryChange() throws {
        var values: [String] = []
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.onChange: .integer(1),
            PamConstants.inputSyncMode: .integer(3),
        ], kind: .input) { _, kind, payload in
            guard kind == EventKind.change.rawValue,
                  case let .text(value)? = try? WireMap.decode(payload)["value"] else { return }
            values.append(value)
        }
        defer { renderer.close() }
        let field = try XCTUnwrap(view as? PamInputField)
        field.text = "a"
        perform(.editingChanged, on: field)
        field.text = "ab"
        perform(.editingChanged, on: field)
        XCTAssertEqual(values, ["a", "ab"])
    }

    func testBlurSyncModeFlushesOnEndEditing() throws {
        var values: [String] = []
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.onChange: .integer(1),
            PamConstants.inputSyncMode: .integer(4),
        ], kind: .input) { _, kind, payload in
            guard kind == EventKind.change.rawValue,
                  case let .text(value)? = try? WireMap.decode(payload)["value"] else { return }
            values.append(value)
        }
        defer { renderer.close() }
        let field = try XCTUnwrap(view as? PamInputField)
        field.text = "draft"
        perform(.editingChanged, on: field)
        XCTAssertEqual(values, [])
        perform(.editingDidEnd, on: field)
        XCTAssertEqual(values, ["draft"])
    }

    func testSelectableAndDataDetectorReachTheTextView() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.text: .text("Call +1 555 0100 or visit https://example.com"),
            PamConstants.textSelectable: .flag(true),
            PamConstants.textDataDetectorType: .integer(3),
        ], kind: .text)
        defer { renderer.close() }
        let text = try XCTUnwrap(view as? PamTextView)
        XCTAssertTrue(text.isSelectable)
        XCTAssertEqual(text.dataDetectorType, 3)
        XCTAssertTrue(text.isUserInteractionEnabled)
    }
}
