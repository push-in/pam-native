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
