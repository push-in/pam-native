import Foundation

struct PamAlertButton: Equatable {
    let label: String
    /// 1 default, 2 cancel, 3 destructive (PHP `AlertButtonStyle`).
    let style: Int
}

/// Pure decoding of the optional multi-button payload of the `alert`
/// operation (`buttonCount`, `button<i>Label`, `button<i>Style`). A payload
/// without `buttonCount` is the legacy single-OK alert. Mirrors Android
/// `PamAlertButtons`.
enum PamAlertButtons {
    static let styleDefault = 1
    static let styleCancel = 2
    static let styleDestructive = 3
    static let maxButtons = 3
    static let dismissed = -1

    static func decode(_ values: [String: WireValue]) throws -> [PamAlertButton] {
        guard case let .integer(rawCount)? = values["buttonCount"] else { return [] }
        let count = Int(rawCount)
        guard (1...maxButtons).contains(count) else {
            throw PamAlertButtonsError("Alert supports between 1 and \(maxButtons) buttons")
        }
        var buttons: [PamAlertButton] = []
        for index in 0..<count {
            guard case let .text(label)? = values["button\(index)Label"] else {
                throw PamAlertButtonsError("Missing alert button label \(index)")
            }
            guard !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw PamAlertButtonsError("Alert button labels cannot be empty")
            }
            var style = styleDefault
            if case let .integer(value)? = values["button\(index)Style"] { style = Int(value) }
            guard (styleDefault...styleDestructive).contains(style) else {
                throw PamAlertButtonsError("Unknown alert button style \(style)")
            }
            buttons.append(PamAlertButton(label: label, style: style))
        }
        guard buttons.filter({ $0.style == styleCancel }).count <= 1 else {
            throw PamAlertButtonsError("Alert supports one cancel button")
        }
        return buttons
    }
}

struct PamAlertButtonsError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
