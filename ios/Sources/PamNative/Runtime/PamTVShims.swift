import UIKit

/// The default text size (UIFont.systemFontSize is iOS-only).
enum PamSystemFont {
    static var size: CGFloat {
        #if os(tvOS)
        17
        #else
        UIFont.systemFontSize
        #endif
    }
}

extension UIColor {
    /// `systemBackground` (iOS-only): tvOS apps draw on black.
    static var pamSystemBackground: UIColor {
        #if os(tvOS)
        .black
        #else
        .systemBackground
        #endif
    }

    static var pamSecondarySystemBackground: UIColor {
        #if os(tvOS)
        UIColor(white: 0.11, alpha: 1)
        #else
        .secondarySystemBackground
        #endif
    }
}

extension UITextView {
    /// `isEditable` (iOS-only): tvOS text views only display text; text
    /// entry there goes through UITextField and the system keyboard.
    var pamIsEditable: Bool {
        #if os(tvOS)
        false
        #else
        isEditable
        #endif
    }
}

extension UIScrollView {
    /// `refreshControl`, which tvOS lacks: there the refresh control is kept
    /// but never shown (no pull to refresh on a TV).
    var pamRefreshControl: UIRefreshControl? {
        get {
            #if os(tvOS)
            objc_getAssociatedObject(self, &pamRefreshControlKey) as? UIRefreshControl
            #else
            refreshControl
            #endif
        }
        set {
            #if os(tvOS)
            objc_setAssociatedObject(self, &pamRefreshControlKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
            #else
            refreshControl = newValue
            #endif
        }
    }
}

#if os(tvOS)
private var pamRefreshControlKey: UInt8 = 0

/// Pull to refresh does not exist on tvOS: `refreshing` is still tracked so
/// the controlled value round-trips, and nothing is drawn.
final class UIRefreshControl: UIControl {
    private(set) var isRefreshing = false
    func beginRefreshing() { isRefreshing = true }
    func endRefreshing() { isRefreshing = false }
}
#endif

#if os(tvOS)

// tvOS has no haptics and no system clipboard. These stand in for the iOS-only
// UIKit types under the same names, so the shared renderer and modules compile
// unchanged: feedback does nothing, and the clipboard lives in the app.

final class UIImpactFeedbackGenerator {
    enum FeedbackStyle: Int {
        case light, medium, heavy, soft, rigid
    }

    init(style: FeedbackStyle = .medium) {}
    func prepare() {}
    func impactOccurred() {}
    func impactOccurred(intensity: CGFloat) {}
}

final class UISelectionFeedbackGenerator {
    func prepare() {}
    func selectionChanged() {}
}

final class UINotificationFeedbackGenerator {
    enum FeedbackType: Int {
        case success, warning, error
    }

    func prepare() {}
    func notificationOccurred(_ notificationType: FeedbackType) {}
}

/// A focusable on/off switch: the remote's select button toggles it.
final class UISwitch: UIControl {
    private let track = CALayer()
    private let thumb = CALayer()

    var onTintColor: UIColor? { didSet { refresh(animated: false) } }
    var thumbTintColor: UIColor? { didSet { refresh(animated: false) } }
    override var tintColor: UIColor! { didSet { refresh(animated: false) } }

    private(set) var isOn = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        layer.addSublayer(track)
        layer.addSublayer(thumb)
        refresh(animated: false)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var canBecomeFocused: Bool { isEnabled }

    override func sizeThatFits(_ size: CGSize) -> CGSize { CGSize(width: 51, height: 31) }

    override var intrinsicContentSize: CGSize { sizeThatFits(.zero) }

    func setOn(_ on: Bool, animated: Bool) {
        isOn = on
        refresh(animated: animated)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        refresh(animated: false)
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        guard isEnabled, presses.contains(where: { $0.type == .select }) else {
            super.pressesEnded(presses, with: event)
            return
        }
        setOn(!isOn, animated: true)
        sendActions(for: .valueChanged)
    }

    override func didUpdateFocus(in context: UIFocusUpdateContext, with coordinator: UIFocusAnimationCoordinator) {
        super.didUpdateFocus(in: context, with: coordinator)
        coordinator.addCoordinatedAnimations({
            self.transform = self.isFocused ? CGAffineTransform(scaleX: 1.15, y: 1.15) : .identity
        })
    }

    private func refresh(animated: Bool) {
        CATransaction.begin()
        CATransaction.setDisableActions(!animated)
        let height = bounds.height > 0 ? min(bounds.height, 31) : 31
        let width = bounds.width > 0 ? min(bounds.width, 51) : 51
        track.frame = CGRect(x: (bounds.width - width) / 2, y: (bounds.height - height) / 2, width: width, height: height)
        track.cornerRadius = height / 2
        track.backgroundColor = (isOn ? (onTintColor ?? .systemGreen) : (tintColor ?? .systemGray)).cgColor
        let inset: CGFloat = 2
        let side = height - inset * 2
        thumb.frame = CGRect(
            x: track.frame.minX + (isOn ? width - side - inset : inset),
            y: track.frame.minY + inset,
            width: side,
            height: side
        )
        thumb.cornerRadius = side / 2
        thumb.backgroundColor = (thumbTintColor ?? .white).cgColor
        alpha = isEnabled ? 1 : 0.5
        CATransaction.commit()
    }
}

/// The app's own clipboard: copy and paste work inside the app.
final class UIPasteboard {
    struct OptionsKey: Hashable {
        static let localOnly = OptionsKey()
        static let expirationDate = OptionsKey()
        private let id = UUID()
    }

    static let general = UIPasteboard()

    var string: String?
    var hasStrings: Bool { string != nil }

    func setItems(_ items: [[String: Any]], options: [OptionsKey: Any] = [:]) {
        string = items.first?.values.first as? String
    }
}
#endif
