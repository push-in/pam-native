import Foundation

/// A short, non-renewing-by-background-work window for an action's response.
/// Progress, layout, scroll updates and lifecycle notifications keep their
/// normal frame budget. No timer/display link is created by this policy.
struct PamInteractionPriority {
    static let window: TimeInterval = 0.250
    private var lastAction: TimeInterval?

    mutating func record(kind: Int, now: TimeInterval) {
        guard let event = EventKind(rawValue: kind) else { return }
        switch event {
        case .press, .change, .back, .longPress, .submit, .refresh, .toggle,
             .inputKeyPress, .modalRequestClose, .drop, .menuAction,
             .navigationGesturePop, .accessibilityAction, .spanPress,
             .doubleTap, .gestureSettle:
            lastAction = now
        default:
            break
        }
    }

    func isActive(now: TimeInterval) -> Bool {
        guard let lastAction else { return false }
        let elapsed = now - lastAction
        return elapsed >= 0 && elapsed < Self.window
    }

    mutating func reset() { lastAction = nil }
}
