import XCTest
@testable import PamNative

final class PamLeafPaddingTests: XCTestCase {
    func testLeafInsetsCombineAuthoredPaddingAndBorderWidths() {
        let insets = PamRenderer.leafContentInsets([
            PamConstants.padding: .decimal(4),
            PamConstants.paddingHorizontal: .decimal(12),
            PamConstants.paddingLeft: .decimal(14),
            PamConstants.borderWidth: .decimal(1.5),
            PamConstants.borderBottomWidth: .decimal(0),
        ])
        XCTAssertEqual(insets.left, 15.5)
        XCTAssertEqual(insets.right, 13.5)
        XCTAssertEqual(insets.top, 5.5)
        XCTAssertEqual(insets.bottom, 4)
    }

    func testTextDrawsInsideItsContentInsets() {
        let label = PamTextView(frame: CGRect(x: 0, y: 0, width: 100, height: 40))
        label.contentInsets = UIEdgeInsets(top: 2, left: 14, bottom: 2, right: 2)
        XCTAssertEqual(label.contentBounds, CGRect(x: 14, y: 2, width: 84, height: 36))
    }

    func testSingleLineInputKeepsTextInsideItsPadding() {
        let field = PamInputField(frame: CGRect(x: 0, y: 0, width: 200, height: 54))
        field.contentInsets = UIEdgeInsets(top: 0, left: 12, bottom: 0, right: 12)
        let rect = field.textRect(forBounds: field.bounds)
        XCTAssertGreaterThanOrEqual(rect.minX, 12)
        XCTAssertLessThanOrEqual(rect.maxX, 188)
    }
}
