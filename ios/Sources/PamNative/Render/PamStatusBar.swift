import UIKit

/// The status bar a screen declares with `StatusBar` nodes (RN `StatusBar`
/// on iOS): `barStyle`, `hidden` and `animated`. `backgroundColor` and
/// `translucent` are Android-only, as in React Native.
public struct PamStatusBarConfig: Equatable {
    public var style: UIStatusBarStyle
    public var hidden: Bool
    public var animated: Bool

    public init(style: UIStatusBarStyle = .default, hidden: Bool = false, animated: Bool = false) {
        self.style = style
        self.hidden = hidden
        self.animated = animated
    }

    /// No `StatusBar` mounted: the system default (RN's initial stack entry).
    public static let initial = PamStatusBarConfig()

    /// `preferredStatusBarUpdateAnimation` for this config (RN
    /// `showHideTransition` defaults to `fade`).
    public var updateAnimation: UIStatusBarAnimation {
        animated ? .fade : .none
    }
}

/// One mounted `StatusBar` node, as the renderer reads it. A `nil` field
/// was not declared and leaves the value below it in the stack untouched.
struct PamStatusBarDeclaration: Equatable {
    var appearance: Int?
    var hidden: Bool?
    var animated: Bool?
}

/// Resolves the `StatusBar` stack and drives the iOS status bar.
///
/// Like RN's `StatusBar._updatePropsStack`, every active (shown, in an active
/// route or visible modal) declaration is merged in mount order, the latest
/// mounted winning property by property, so a modal's or pushed screen's bar
/// overrides the screen below it and the screen's comes back when it goes.
///
/// With `UIViewControllerBasedStatusBarAppearance` YES (the default) the PAM
/// controllers report the config through `preferredStatusBarStyle`,
/// `prefersStatusBarHidden` and `preferredStatusBarUpdateAnimation`, and a
/// change calls `setNeedsStatusBarAppearanceUpdate()` on every controller of
/// the app's windows (inside an animation when `animated`). With NO, RN's
/// `RCTStatusBarManager` path is used: the application-level status bar APIs.
public final class PamStatusBarCoordinator {
    public static let shared = PamStatusBarCoordinator()

    /// Posted when something that decides which `StatusBar` nodes are active
    /// changed outside a commit (a modal shown or hidden, a route transition
    /// finished); renderers re-resolve the stack.
    static let invalidated = Notification.Name("PamNativeStatusBarInvalidated")

    public private(set) var config = PamStatusBarConfig.initial
    let viewControllerBased: Bool
    private let windows: () -> [UIWindow]
    private let animate: (TimeInterval, @escaping () -> Void) -> Void
    private let legacy: (PamStatusBarConfig) -> Void

    init(
        viewControllerBased: Bool = PamStatusBarCoordinator.viewControllerBased(in: .main),
        windows: @escaping () -> [UIWindow] = PamStatusBarCoordinator.applicationWindows,
        animate: @escaping (TimeInterval, @escaping () -> Void) -> Void = { duration, changes in
            UIView.animate(withDuration: duration, animations: changes)
        },
        legacy: @escaping (PamStatusBarConfig) -> Void = PamStatusBarCoordinator.applyApplicationStatusBar
    ) {
        self.viewControllerBased = viewControllerBased
        self.windows = windows
        self.animate = animate
        self.legacy = legacy
    }

    /// `UIViewControllerBasedStatusBarAppearance` from Info.plist; UIKit
    /// treats a missing key as YES.
    static func viewControllerBased(in bundle: Bundle) -> Bool {
        (bundle.object(forInfoDictionaryKey: "UIViewControllerBasedStatusBarAppearance") as? Bool) ?? true
    }

    /// PAM `StatusBarAppearance`: 1 dark icons (`dark-content`), 2 light
    /// icons (`light-content`); anything else follows the system.
    static func style(forAppearance appearance: Int?) -> UIStatusBarStyle {
        switch appearance {
        case 1: return .darkContent
        case 2: return .lightContent
        default: return .default
        }
    }

    /// Merges the active declarations, ordered by mount (oldest first).
    static func merge(_ declarations: [PamStatusBarDeclaration]) -> PamStatusBarConfig {
        var appearance: Int?
        var hidden = false
        var animated = false
        for declaration in declarations {
            if let value = declaration.appearance { appearance = value }
            if let value = declaration.hidden { hidden = value }
            if let value = declaration.animated { animated = value }
        }
        return PamStatusBarConfig(
            style: style(forAppearance: appearance),
            hidden: hidden,
            animated: animated
        )
    }

    /// Applies `next`; unchanged configs cost nothing. Main thread only.
    func apply(_ next: PamStatusBarConfig) {
        guard next != config else { return }
        config = next
        guard viewControllerBased else {
            legacy(next)
            return
        }
        let windows = windows()
        let update = {
            for window in windows {
                Self.setNeedsStatusBarAppearanceUpdate(from: window.rootViewController)
            }
        }
        if next.animated && !PamMotionPolicy.isReduced {
            animate(0.25, update)
        } else {
            update()
        }
    }

    /// Asks `controller`, its children and the controllers it presented to
    /// re-read the status bar (UIKit asks whichever one owns it).
    static func setNeedsStatusBarAppearanceUpdate(from controller: UIViewController?) {
        guard let controller else { return }
        controller.setNeedsStatusBarAppearanceUpdate()
        for child in controller.children {
            setNeedsStatusBarAppearanceUpdate(from: child)
        }
        // `presentedViewController` also answers for an ancestor's
        // presentation; follow it only from the controller that presented.
        if let presented = controller.presentedViewController,
           presented.presentingViewController === controller {
            setNeedsStatusBarAppearanceUpdate(from: presented)
        }
    }

    static func invalidate() {
        NotificationCenter.default.post(name: invalidated, object: nil)
    }

    static func applicationWindows() -> [UIWindow] {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
    }

    /// RN `RCTStatusBarManager` (`UIViewControllerBasedStatusBarAppearance`
    /// NO): the application-level, pre-iOS 9 API is the only one honoured.
    @available(iOS, deprecated: 9.0)
    static func applyApplicationStatusBar(_ config: PamStatusBarConfig) {
        UIApplication.shared.setStatusBarStyle(config.style, animated: config.animated)
        UIApplication.shared.setStatusBarHidden(config.hidden, with: config.updateAnimation)
    }
}
