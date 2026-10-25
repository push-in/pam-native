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

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        PamPointerEvents.hitTest(self, mode: pamPointerEvents) {
            super.hitTest(point, with: event)
        }
    }
}
