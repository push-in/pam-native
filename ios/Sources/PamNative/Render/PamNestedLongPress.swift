import UIKit

/// A nested Pressable without `onLongPress` lends the long press to its
/// nearest enclosing Pressable that has one, never across a scroll view or
/// list (React Native responders; Android `nestedLongPressTarget`). The
/// receiver's own recognizer fires with its own `delayLongPress`, and UIKit
/// cancels the nested press, so its `onPress` does not fire on release.
enum PamNestedLongPress {
    static let recognizerName = "pam.longPress"

    /// One ancestor seen by a nested Pressable looking for a long press handler.
    struct Node: Equatable {
        var hasLongPress: Bool
        var enabled: Bool
        var boundary: Bool
    }

    /// Index in [chain] (nearest ancestor first) of the Pressable that
    /// receives a long press its nested child does not handle, or -1.
    static func target(_ chain: [Node]) -> Int {
        for (index, node) in chain.enumerated() {
            if node.boundary { return -1 }
            if node.hasLongPress { return node.enabled ? index : -1 }
        }
        return -1
    }

    static func hasLongPress(_ view: UIView) -> Bool {
        view.gestureRecognizers?.contains { $0.name == recognizerName && $0.isEnabled } ?? false
    }

    /// Scroll views and lists stop the search (a text view scrolls only its text).
    static func isBoundary(_ view: UIView) -> Bool {
        view is UIScrollView && !(view is UITextView)
    }

    /// Whether [receiver]'s long press takes a touch that began on [touchView].
    static func receives(touchView: UIView, receiver: UIView) -> Bool {
        var view: UIView? = touchView
        var nested: UIView?
        while let current = view, current !== receiver {
            if isBoundary(current) { return false }
            if current is PamPressButton {
                nested = current
                break
            }
            view = current.superview
        }
        // The touch is the receiver's own.
        guard let nested else { return view === receiver }
        if hasLongPress(nested) { return false }
        var chain: [Node] = []
        var views: [UIView?] = []
        var ancestor = nested.superview
        while let current = ancestor {
            if isBoundary(current) {
                chain.append(Node(hasLongPress: false, enabled: false, boundary: true))
                views.append(nil)
                break
            }
            if let pressable = current as? PamPressButton {
                chain.append(Node(hasLongPress: hasLongPress(pressable), enabled: pressable.isEnabled, boundary: false))
                views.append(pressable)
            }
            ancestor = current.superview
        }
        let index = target(chain)
        return index >= 0 && views[index] === receiver
    }
}
