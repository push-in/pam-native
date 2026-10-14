import UIKit

/// Covered-route policy (Android parity, `PamRouteCoverage.kt`). A view is
/// covered when it left the window (a UINavigationController removes the
/// covered controller's view) or a route ancestor is hidden by its host: a
/// navigation route below a full-screen route, or an inactive tab scene.
/// Contained modals and form sheets keep the route below visible.
enum PamRouteCoverage {
    static let didChange = Notification.Name("PamRouteCoverageDidChange")
    private static var notifyPending = false

    static func isCovered(_ view: UIView) -> Bool {
        guard view.window != nil else { return true }
        var child: UIView = view
        while let parent = child.superview {
            if child.isHidden, isRouteContainer(parent) { return true }
            child = parent
        }
        return false
    }

    private static func isRouteContainer(_ parent: UIView) -> Bool {
        parent is PamNavigationHost || parent.superview is PamTabHost
    }

    /// Coalesced: hosts call this for every route visibility change; observers
    /// re-evaluate once on the next main-queue turn.
    static func notifyChanged() {
        guard !notifyPending else { return }
        notifyPending = true
        DispatchQueue.main.async {
            notifyPending = false
            NotificationCenter.default.post(name: didChange, object: nil)
        }
    }
}

/// Edge-triggered `on:routeCovered` / `on:routeFocused` state: returns the
/// new coverage only when it changed. Starts focused.
struct PamRouteFocusTracker {
    private var covered = false

    mutating func update(_ nowCovered: Bool) -> Bool? {
        guard nowCovered != covered else { return nil }
        covered = nowCovered
        return nowCovered
    }
}

/// `MediaClockFormat` (PHP authority) and the `mediaProgress` fill fraction.
enum PamMediaClock {
    static let elapsed = 1
    static let remaining = 2
    static let elapsedAndDuration = 3
    static let duration = 4
    static let countdownSeconds = 5

    static func format(_ format: Int, position rawPosition: Double, duration rawDuration: Double) -> String {
        let position = rawPosition.isFinite ? max(0, rawPosition) : 0
        let duration = rawDuration.isFinite ? max(0, rawDuration) : 0
        let left = max(0, duration - position)
        switch format {
        case remaining: return clock((left - 1e-6).rounded(.up))
        case elapsedAndDuration: return clock(position.rounded(.down)) + " / " + clock(duration.rounded(.down))
        case PamMediaClock.duration: return clock(duration.rounded(.down))
        case countdownSeconds: return String(max(0, Int64((left - 1e-6).rounded(.up))))
        default: return clock(position.rounded(.down))
        }
    }

    static func fraction(position: Double, duration: Double) -> CGFloat {
        guard duration.isFinite, duration > 0, position.isFinite else { return 0 }
        return CGFloat(min(max(position / duration, 0), 1))
    }

    private static func clock(_ seconds: Double) -> String {
        let total = max(0, Int64(seconds))
        let hours = total / 3_600
        let minutes = (total % 3_600) / 60
        let secs = total % 60
        let two = { (value: Int64) in value < 10 ? "0\(value)" : "\(value)" }
        return hours > 0 ? "\(hours):\(two(minutes)):\(two(secs))" : "\(minutes):\(two(secs))"
    }
}

private var pamMediaClockRefKey: UInt8 = 0
private var pamMediaClockFormatKey: UInt8 = 0
private var pamMediaProgressRefKey: UInt8 = 0

extension UIView {
    /// `mediaClock`: the player `nativeRef` whose position this label shows.
    var pamMediaClockRef: String? {
        get { objc_getAssociatedObject(self, &pamMediaClockRefKey) as? String }
        set { objc_setAssociatedObject(self, &pamMediaClockRefKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC) }
    }

    var pamMediaClockFormat: Int {
        get { (objc_getAssociatedObject(self, &pamMediaClockFormatKey) as? Int) ?? PamMediaClock.elapsed }
        set { objc_setAssociatedObject(self, &pamMediaClockFormatKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC) }
    }

    /// `mediaProgress`: the player `nativeRef` whose position fills this view.
    var pamMediaProgressRef: String? {
        get { objc_getAssociatedObject(self, &pamMediaProgressRefKey) as? String }
        set { objc_setAssociatedObject(self, &pamMediaProgressRefKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC) }
    }
}
