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
final class PamContainerView: UIView, PamPointerEventsHost {
    var pamPointerEvents = PamPointerEvents.auto
    /// Row/Column/View/Spacer content containers; screens and keyboard hosts
    /// keep claiming touches so empty screen areas behave as before.
    var pamTouchTransparentWhenIdle = false

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        PamPointerEvents.hitTest(self, mode: pamPointerEvents) {
            let hit = super.hitTest(point, with: event)
            // A plain Row/Column/View with no handlers of its own is
            // touch-transparent, like an Android View without listeners: the
            // touch reaches the Pressable above it. UIControl only tracks a
            // touch whose hit view is the control itself, so a container that
            // claimed the touch would swallow every press on its content.
            if hit === self, pamTouchTransparentWhenIdle, (gestureRecognizers ?? []).isEmpty {
                return nil
            }
            return hit
        }
    }
}
