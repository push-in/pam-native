import XCTest
@testable import PamNative

/// Mirrors Android `PamAlertButtonsTest`. Uncompiled — needs Mac validation.
final class PamAlertButtonsTests: XCTestCase {
    private func payload(_ buttons: [(String, Int)]) -> [String: WireValue] {
        var values: [String: WireValue] = [
            "title": .text("t"),
            "message": .text("m"),
            "buttonCount": .integer(Int64(buttons.count)),
        ]
        for (index, button) in buttons.enumerated() {
            values["button\(index)Label"] = .text(button.0)
            values["button\(index)Style"] = .integer(Int64(button.1))
        }
        return values
    }

    func testLegacyAlertHasNoButtons() throws {
        XCTAssertEqual(try PamAlertButtons.decode(["title": .text("t")]), [])
    }

    func testDecodesButtonsInOrder() throws {
        XCTAssertEqual(
            try PamAlertButtons.decode(payload([("Agora não", 2), ("Continuar", 1)])),
            [PamAlertButton(label: "Agora não", style: 2), PamAlertButton(label: "Continuar", style: 1)]
        )
    }

    func testRejectsInvalidButtonLists() {
        let invalid: [[(String, Int)]] = [
            [],
            [("A", 1), ("B", 1), ("C", 1), ("D", 1)],
            [("A", 2), ("B", 2)],
            [(" ", 1)],
            [("A", 4)],
        ]
        for buttons in invalid {
            XCTAssertThrowsError(try PamAlertButtons.decode(payload(buttons)))
        }
    }
}
