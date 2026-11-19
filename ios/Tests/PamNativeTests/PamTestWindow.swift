import UIKit

/// Creates a test window on the Simulator's active scene. Since iOS 26 a
/// frame-only UIWindow can be visible enough for view tests while still being
/// unable to present controllers or own first responder state.
@MainActor
func pamTestWindow(frame: CGRect) -> UIWindow {
    let window: UIWindow
    if let scene = UIApplication.shared.connectedScenes
        .compactMap({ $0 as? UIWindowScene })
        .first(where: { $0.activationState == .foregroundActive })
        ?? UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
        window = UIWindow(windowScene: scene)
        window.frame = frame
    } else {
        window = UIWindow(frame: frame)
    }
    return window
}

/// Runs the main run loop until `condition` holds or `timeout` passes and
/// returns the condition's last value. Tests wait for the state they assert
/// (UIKit installing a controller's view, a timer or decode landing) instead
/// of a fixed sleep: a loaded CI simulator can take far longer than a frame.
@MainActor
@discardableResult
func pamSpin(until condition: () -> Bool, timeout: TimeInterval = 5, step: TimeInterval = 0.01) -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while !condition() {
        if Date() >= deadline {
            // A stalled CI simulator can pass the deadline without the run loop
            // turning: give timers that came due meanwhile one last pass.
            RunLoop.main.run(until: Date().addingTimeInterval(0.05))
            return condition()
        }
        RunLoop.main.run(until: Date().addingTimeInterval(step))
    }
    return true
}

/// Scene state for failure messages: a host scene in the background stops
/// display links and timers that only run on screen.
@MainActor
func pamSceneState(_ window: UIWindow?) -> String {
    guard let state = window?.windowScene?.activationState else { return "no scene" }
    switch state {
    case .foregroundActive: return "foregroundActive"
    case .foregroundInactive: return "foregroundInactive"
    case .background: return "background"
    case .unattached: return "unattached"
    @unknown default: return "unknown"
    }
}
