import Foundation
import UIKit

/// React Native `pointerEvents` (property 133). Same integer mapping as the
/// Android `PamPointerEventsHost` (`POINTER_EVENTS_*`).
enum PamPointerEvents {
    static let auto = 1
    static let none = 2
    static let boxNone = 3
    static let boxOnly = 4

    static func clamp(_ mode: Int) -> Int {
        min(max(mode, auto), boxOnly)
    }

    /// Resolves UIKit hit testing for [mode]; `superHit` runs the view's
    /// regular `super.hitTest`, which already honours hidden/alpha/enabled.
    static func hitTest(
        _ host: UIView,
        mode: Int,
        superHit: () -> UIView?
    ) -> UIView? {
        switch mode {
        case none:
            return nil
        case boxOnly:
            return superHit() == nil ? nil : host
        case boxNone:
            let hit = superHit()
            return hit === host ? nil : hit
        default:
            return superHit()
        }
    }
}

/// Views that honour `pointerEvents` in `hitTest`.
protocol PamPointerEventsHost: AnyObject {
    var pamPointerEvents: Int { get set }
}

/// Plain layout container (View/Row/Column/Screen/Spacer).
final class PamContainerView: UIView, PamPointerEventsHost, PamAccessibilityAdjustable {
    var pamPointerEvents = PamPointerEvents.auto
    var pamAccessibilityAdjust: ((Bool) -> Void)?

    override func accessibilityIncrement() {
        if let adjust = pamAccessibilityAdjust { adjust(true) } else { super.accessibilityIncrement() }
    }

    override func accessibilityDecrement() {
        if let adjust = pamAccessibilityAdjust { adjust(false) } else { super.accessibilityDecrement() }
    }

    /// An element without its own label (a View with an interactive role)
    /// reads its content, like RN's `RCTRecursiveAccessibilityLabel`.
    override var accessibilityLabel: String? {
        get {
            if let own = super.accessibilityLabel, !own.isEmpty { return own }
            return isAccessibilityElement ? PamPressButton.contentAccessibilityLabel(of: self) : nil
        }
        set { super.accessibilityLabel = newValue }
    }
    /// Row/Column/View/Spacer content containers; screens and keyboard hosts
    /// keep claiming touches so empty screen areas behave as before.
    var pamTouchTransparentWhenIdle = false

    /// Only content inside a Pressable/Button needs to let the touch through:
    /// everywhere else a container stays the hit view (z-order, sticky
    /// headers and overlays rely on that).
    #if os(tvOS)
    /// `focusable="true"`: the remote can land on this view (a player that
    /// takes the keys with `captureKeys`).
    override var canBecomeFocused: Bool { pamFocus?.focusable == true }
    #endif

    override func didMoveToWindow() {
        super.didMoveToWindow()
        #if os(tvOS)
        PamTVFocus.didMoveToWindow(self)
        #endif
        // A timed fill (`progress*`) resumes on screen, stops off it.
        pamTimeBinding?.refresh()
    }

    private var hasControlAncestor: Bool {
        var view = superview
        while let current = view {
            if current is UIControl { return true }
            view = current.superview
        }
        return false
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        PamPointerEvents.hitTest(self, mode: pamPointerEvents) {
            let hit = super.hitTest(point, with: event)
            // A plain Row/Column/View with no handlers of its own is
            // touch-transparent, like an Android View without listeners: the
            // touch reaches the Pressable above it. UIControl only tracks a
            // touch whose hit view is the control itself, so a container that
            // claimed the touch would swallow every press on its content.
            if hit === self, pamTouchTransparentWhenIdle, (gestureRecognizers ?? []).isEmpty,
               hasControlAncestor {
                return nil
            }
            return hit
        }
    }
}
