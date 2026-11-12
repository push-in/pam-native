import UIKit

/// Remote control focus for one node (mirrors Android's `PamFocusInfo`): who
/// hears `onKeyDown`, which keys it takes before the focus engine, whether a
/// plain view takes the focus, focus groups and the default focus ring.
final class PamFocusInfo {
    let nodeId: Int64
    var keyDown = false
    var captureKeys: Set<String> = []
    var focusable = false
    var group = false
    weak var remembered: UIView?

    /// 1 draws the default ring on focus; 0 never.
    var ring = 1

    var focusEvents = false
    var send: ((Int64, Int, Data) -> Void)?

    init(nodeId: Int64) {
        self.nodeId = nodeId
    }

    func captures(_ key: String) -> Bool {
        captureKeys.contains(key) || key == " " && captureKeys.contains("Space")
    }
}

private var pamFocusInfoKey: UInt8 = 0
private var pamFocusRingKey: UInt8 = 0

extension UIView {
    var pamFocus: PamFocusInfo? {
        get { objc_getAssociatedObject(self, &pamFocusInfoKey) as? PamFocusInfo }
        set { objc_setAssociatedObject(self, &pamFocusInfoKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC) }
    }
}

/// The tvOS focus engine driven like PAM on Android TV: `autoFocus`,
/// `captureKeys`, `onKeyDown`, `focusGroup`, focus events and the ring. The
/// app's root view controller forwards its focus and press callbacks here.
public enum PamTVFocus {
    /// The view an `autoFocus` or a focus group asked for, until it has it.
    private static weak var preferred: UIView?

    /// Press types whose began was captured: their end is swallowed too.
    private static var capturedPresses = Set<Int>()

    /// Moves the focus to [view] once it is on screen.
    static func request(_ view: UIView) {
        #if os(tvOS)
        preferred = view
        DispatchQueue.main.async { applyPreferred() }
        #endif
    }

    /// Called when a view joins a window: a pending request can now land.
    static func didMoveToWindow(_ view: UIView) {
        #if os(tvOS)
        if view.window != nil, preferred === view {
            DispatchQueue.main.async { applyPreferred() }
        }
        #endif
    }

    private static func applyPreferred() {
        guard let view = preferred, let root = view.window?.rootViewController else { return }
        if view.isFocused {
            preferred = nil
            return
        }
        root.setNeedsFocusUpdate()
        root.updateFocusIfNeeded()
    }

    /// The root view controller's `preferredFocusEnvironments` while a
    /// request is pending.
    public static func preferredEnvironments() -> [UIFocusEnvironment]? {
        guard let view = preferred, view.window != nil else { return nil }
        return [view]
    }

    #if os(tvOS)
    /// Refuses moves out of a view that captures that arrow, and sends a move
    /// into a focus group from outside to the child it remembers.
    public static func shouldUpdateFocus(in context: UIFocusUpdateContext) -> Bool {
        guard let previous = context.previouslyFocusedView else { return true }
        if let key = key(for: context.focusHeading),
           ancestors(of: previous).contains(where: { $0.pamFocus?.captures(key) == true }) {
            return false
        }
        guard let next = context.nextFocusedView, context.focusHeading != [] else { return true }
        for ancestor in ancestors(of: next).dropFirst() {
            guard let info = ancestor.pamFocus, info.group else { continue }
            if previous.isDescendant(of: ancestor) { return true }
            guard let remembered = info.remembered, remembered !== next,
                  remembered.window != nil, !remembered.isHidden,
                  remembered.canBecomeFocused, remembered.isDescendant(of: ancestor) else { return true }
            request(remembered)
            return false
        }
        return true
    }

    public static func didUpdateFocus(in context: UIFocusUpdateContext) {
        if let previous = context.previouslyFocusedView {
            updateRing(previous, focused: false)
            if let info = previous.pamFocus, info.focusEvents {
                info.send?(info.nodeId, EventKind.blur.rawValue, Data())
            }
        }
        guard let next = context.nextFocusedView else { return }
        if preferred === next { preferred = nil }
        for ancestor in ancestors(of: next).dropFirst() {
            if let info = ancestor.pamFocus, info.group { info.remembered = next }
        }
        updateRing(next, focused: true)
        if let info = next.pamFocus, info.focusEvents {
            info.send?(info.nodeId, EventKind.focus.rawValue, Data())
        }
    }

    /// `onKeyDown` and `captureKeys` for a press that reached the root view
    /// controller. True when it must not reach the system (Menu would leave
    /// the app).
    public static func pressesBegan(_ presses: Set<UIPress>) -> Bool {
        guard let press = presses.first, let key = key(for: press.type) else { return false }
        let focused = UIScreen.main.focusedView
        let chain = focused.map(ancestors(of:)) ?? []
        if focused == nil {
            // Nothing has the focus: every listener on screen hears the key.
            for window in UIApplication.shared.connectedScenes
                .compactMap({ ($0 as? UIWindowScene)?.windows }).joined() where window.isKeyWindow {
                forEachListener(window) { send(key, to: $0) }
            }
        } else if let listener = chain.lazy.compactMap(\.pamFocus).first(where: \.keyDown) {
            send(key, to: listener)
        }
        guard chain.contains(where: { $0.pamFocus?.captures(key) == true }) else { return false }
        capturedPresses.insert(press.type.rawValue)
        return true
    }

    public static func pressesEnded(_ presses: Set<UIPress>) -> Bool {
        guard let press = presses.first else { return false }
        return capturedPresses.remove(press.type.rawValue) != nil
    }

    private static func send(_ key: String, to info: PamFocusInfo) {
        let payload = (try? WireMap.encode([
            "key": .text(key),
            "shift": .flag(false),
            "control": .flag(false),
            "alt": .flag(false),
            "meta": .flag(false),
            "repeat": .flag(false),
        ])) ?? Data()
        info.send?(info.nodeId, EventKind.keyDown.rawValue, payload)
    }

    private static func forEachListener(_ view: UIView, _ action: (PamFocusInfo) -> Void) {
        guard !view.isHidden else { return }
        if let info = view.pamFocus, info.keyDown { action(info) }
        view.subviews.forEach { forEachListener($0, action) }
    }

    /// The W3C UI Events `key` of a Siri Remote or game controller button.
    static func key(for type: UIPress.PressType) -> String? {
        switch type {
        case .upArrow: return "ArrowUp"
        case .downArrow: return "ArrowDown"
        case .leftArrow: return "ArrowLeft"
        case .rightArrow: return "ArrowRight"
        case .select: return "Enter"
        case .menu: return "GoBack"
        case .playPause: return "MediaPlayPause"
        default: return nil
        }
    }

    private static func key(for heading: UIFocusHeading) -> String? {
        if heading.contains(.up) { return "ArrowUp" }
        if heading.contains(.down) { return "ArrowDown" }
        if heading.contains(.left) { return "ArrowLeft" }
        if heading.contains(.right) { return "ArrowRight" }
        return nil
    }
    #endif

    private static func ancestors(of view: UIView) -> [UIView] {
        Array(sequence(first: view, next: \.superview))
    }

    /// The default focus ring: a white outline around a focused node that
    /// has no `:focus` style and did not opt out with `focusRing="none"`.
    static func updateRing(_ view: UIView, focused: Bool) {
        let existing = objc_getAssociatedObject(view, &pamFocusRingKey) as? CAShapeLayer
        let info = view.pamFocus
        // A `:focus` style already shows the focus.
        let focusStyle = (view as? PamPressButton)?.pamStateStyles[2] != nil
        let wanted = focused && (info?.ring ?? 1) != 0 && !focusStyle
            && (info != nil || view is PamPressButton)
        guard wanted else {
            existing?.removeFromSuperlayer()
            return
        }
        let ring = existing ?? CAShapeLayer()
        let outset: CGFloat = view.clipsToBounds ? -3 : 5
        let frame = view.bounds.insetBy(dx: -outset, dy: -outset)
        ring.path = UIBezierPath(
            roundedRect: frame,
            cornerRadius: view.layer.cornerRadius + max(outset, 0)
        ).cgPath
        ring.fillColor = nil
        ring.strokeColor = UIColor.white.cgColor
        ring.lineWidth = 3
        view.layer.addSublayer(ring)
        objc_setAssociatedObject(view, &pamFocusRingKey, ring, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
    }
}

/// The view PAM renders into on tvOS: a 960 dp wide canvas scaled to the
/// screen (1920 x 1080 points is 960 x 540 dp at 2x, like a 1080p Android TV
/// and the web TV targets), so one layout serves every TV.
public final class PamTVCanvas: UIView {
    public static let logicalWidth: CGFloat = 960

    /// Screen points per dp (2 on a 1080p Apple TV).
    public private(set) static var scale: CGFloat = 1

    /// Sizes the canvas to [bounds] of its parent at the logical width.
    public func fit(in bounds: CGRect) {
        let scale = max(1, bounds.width / Self.logicalWidth)
        Self.scale = scale
        transform = .identity
        frame = CGRect(x: 0, y: 0, width: bounds.width / scale, height: bounds.height / scale)
        transform = CGAffineTransform(scaleX: scale, y: scale)
        center = CGPoint(x: bounds.midX, y: bounds.midY)
    }

    /// Window safe-area insets (points) in canvas dp.
    static func dp(_ insets: UIEdgeInsets) -> UIEdgeInsets {
        guard scale > 1 else { return insets }
        return UIEdgeInsets(
            top: insets.top / scale,
            left: insets.left / scale,
            bottom: insets.bottom / scale,
            right: insets.right / scale
        )
    }

    /// Draws [view]'s text and shapes at the scaled resolution, not
    /// stretched from 1x.
    static func sharpen(_ view: UIView) {
        guard scale > 1 else { return }
        view.contentScaleFactor = (view.window?.screen.scale ?? UIScreen.main.scale) * scale
    }
}
