import UIKit

/// The sensor housing alone (notch, Dynamic Island), in points, without the
/// status bar or home indicator: what immersive content still has to avoid
/// (`useSafeAreaInsets()->cutoutTop`...). iOS has no cutout API; a phone whose
/// top safe area exceeds the classic 20 pt status bar has a sensor housing
/// there (portrait), and the side insets in landscape are the housing alone.
enum PamCutoutInsets {
    static func from(safeArea: UIEdgeInsets, idiom: UIUserInterfaceIdiom) -> UIEdgeInsets {
        guard idiom == .phone else { return .zero }
        return UIEdgeInsets(
            top: safeArea.top > 24 ? safeArea.top : 0,
            left: safeArea.left,
            bottom: 0,
            right: safeArea.right
        )
    }
}
