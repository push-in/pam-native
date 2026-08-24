import UIKit

enum PamTextColorPolicy {
    @discardableResult
    static func apply(argb: Int64, to view: UIView) -> Bool {
        apply(color: UIColor(argb: argb), to: view)
    }

    @discardableResult
    static func apply(color: UIColor, to view: UIView) -> Bool {
        switch view {
        case let label as UILabel:
            label.textColor = color
        case let button as UIButton:
            button.setTitleColor(color, for: .normal)
        case let field as UITextField:
            field.textColor = color
        case let textView as UITextView:
            textView.textColor = color
        default:
            return false
        }
        return true
    }
}
