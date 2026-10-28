import UIKit

public enum MountedViews {
    /// The view tagged with `nativeRef` in any window of the app.
    public static func mountedView(_ ref: String) -> UIView? {
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for window in scene.windows {
                if let match = window.pamFindNativeRef(ref) { return match }
            }
        }
        return nil
    }
}
