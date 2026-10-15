import UIKit

/// React Native `keyboardShouldPersistTaps` (property 538). Wire values match
/// `Pam\Native\KeyboardPersistTaps`; `none` is the absent property, which keeps
/// PAM's historical behaviour (a tap never dismisses the keyboard).
enum PamKeyboardPersistTaps {
    static let none = 0
    static let never = 1
    static let always = 2
    static let handled = 3

    static func normalize(_ value: Int) -> Int {
        (never...handled).contains(value) ? value : none
    }

    /// Only `never` and `handled` install a recognizer.
    static func tracks(_ mode: Int) -> Bool { mode == never || mode == handled }

    /// Whether a completed tap dismisses the keyboard. Taps on a text input
    /// always pass (RN lets them move the focus).
    static func shouldDismiss(
        mode: Int,
        keyboardVisible: Bool,
        onTextInput: Bool,
        handledByChild: Bool
    ) -> Bool {
        guard keyboardVisible, !onTextInput else { return false }
        if mode == never { return true }
        return mode == handled && !handledByChild
    }
}

/// Keyboard visibility from the keyboard notifications. Observed lazily: only
/// once a scroll view uses `never` or `handled`.
final class PamKeyboardTapVisibility {
    static let shared = PamKeyboardTapVisibility()
    private(set) var visible = false
    private var tokens: [NSObjectProtocol] = []

    private init() {
        let center = NotificationCenter.default
        tokens.append(center.addObserver(
            forName: UIResponder.keyboardWillShowNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in self?.visible = true })
        tokens.append(center.addObserver(
            forName: UIResponder.keyboardWillHideNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in self?.visible = false })
    }
}

/// Tap recognizer for `never` / `handled` on a scroll view or list.
/// `never`: while the keyboard is up, the scroll view takes every touch that
/// is not on a text input (see `capture`), so the tap only dismisses the
/// keyboard and children receive nothing; dragging still scrolls.
/// `handled`: the tap reaches the children; it dismisses the keyboard only
/// when no control, text input or tap/long-press recognizer under the touch
/// handles it.
final class PamKeyboardTapController: NSObject, UIGestureRecognizerDelegate {
    let mode: Int
    private weak var scrollView: UIScrollView?
    private var tap: UITapGestureRecognizer?

    private init(mode: Int, scrollView: UIScrollView) {
        self.mode = mode
        self.scrollView = scrollView
        super.init()
        _ = PamKeyboardTapVisibility.shared
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        tap.cancelsTouchesInView = mode == PamKeyboardPersistTaps.never
        tap.delegate = self
        scrollView.addGestureRecognizer(tap)
        self.tap = tap
    }

    /// Returns the controller for `value`, reusing `current` when the mode is
    /// unchanged; nil (and nothing installed) for absent or `always`.
    static func install(
        _ value: Int,
        on scrollView: UIScrollView,
        current: PamKeyboardTapController?
    ) -> PamKeyboardTapController? {
        let mode = PamKeyboardPersistTaps.normalize(value)
        if let current = current, current.mode == mode { return current }
        current?.detach()
        guard PamKeyboardPersistTaps.tracks(mode) else { return nil }
        return PamKeyboardTapController(mode: mode, scrollView: scrollView)
    }

    func detach() {
        if let tap = tap { scrollView?.removeGestureRecognizer(tap) }
        tap = nil
    }

    /// `never`: while the keyboard is visible the scroll view itself is the
    /// hit view for everything but text inputs.
    func capture(_ hit: UIView?, in scrollView: UIScrollView) -> UIView? {
        guard mode == PamKeyboardPersistTaps.never,
              PamKeyboardTapVisibility.shared.visible,
              let hit,
              !Self.isInTextInput(hit, below: scrollView) else { return hit }
        return scrollView
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard PamKeyboardTapVisibility.shared.visible, let scrollView, let view = touch.view else { return false }
        let onTextInput = Self.isInTextInput(view, below: scrollView)
        let handled = mode == PamKeyboardPersistTaps.handled
            && handlesTap(view, below: scrollView)
        return PamKeyboardPersistTaps.shouldDismiss(
            mode: mode,
            keyboardVisible: true,
            onTextInput: onTextInput,
            handledByChild: handled
        )
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        true
    }

    @objc private func tapped(_ recognizer: UITapGestureRecognizer) {
        guard recognizer.state == .ended else { return }
        scrollView?.window?.endEditing(true)
    }

    private static func isInTextInput(_ view: UIView, below root: UIView) -> Bool {
        var current: UIView? = view
        while let candidate = current, candidate !== root {
            if candidate is UITextField || candidate is UITextView { return true }
            current = candidate.superview
        }
        return false
    }

    private func handlesTap(_ view: UIView, below root: UIView) -> Bool {
        var current: UIView? = view
        while let candidate = current, candidate !== root {
            if let control = candidate as? UIControl, control.isEnabled { return true }
            if candidate is UITextField || candidate is UITextView { return true }
            if let recognizers = candidate.gestureRecognizers,
               recognizers.contains(where: {
                   $0 !== tap && $0.isEnabled
                       && ($0 is UITapGestureRecognizer || $0 is UILongPressGestureRecognizer)
               }) {
                return true
            }
            current = candidate.superview
        }
        return false
    }
}
