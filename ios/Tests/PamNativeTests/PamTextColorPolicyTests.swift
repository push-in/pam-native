import XCTest
import UIKit
@testable import PamNative

final class PamTextColorPolicyTests: XCTestCase {
    func testSemanticTextColorsReachLabelsButtonsAndInputsWithoutLoss() throws {
        let argb: Int64 = 0xFF4ADE80
        let views: [UIView] = [UILabel(), UIButton(type: .system), UITextField(), UITextView()]

        for view in views {
            XCTAssertTrue(PamTextColorPolicy.apply(argb: argb, to: view))
            let color: UIColor? = switch view {
            case let label as UILabel: label.textColor
            case let button as UIButton: button.titleColor(for: .normal)
            case let field as UITextField: field.textColor
            case let textView as UITextView: textView.textColor
            default: nil
            }
            let resolved = try XCTUnwrap(color)
            var red: CGFloat = 0
            var green: CGFloat = 0
            var blue: CGFloat = 0
            var alpha: CGFloat = 0
            XCTAssertTrue(resolved.getRed(&red, green: &green, blue: &blue, alpha: &alpha))
            XCTAssertEqual(red, 74.0 / 255.0, accuracy: 0.000_1)
            XCTAssertEqual(green, 222.0 / 255.0, accuracy: 0.000_1)
            XCTAssertEqual(blue, 128.0 / 255.0, accuracy: 0.000_1)
            XCTAssertEqual(alpha, 1, accuracy: 0.000_1)
        }

        XCTAssertFalse(PamTextColorPolicy.apply(argb: argb, to: UIView()))
    }
}
