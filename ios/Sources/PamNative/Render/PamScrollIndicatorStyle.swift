import UIKit

enum PamScrollIndicatorStyle: Int {
    case auto = 1
    case dark = 2
    case light = 3

    var native: UIScrollView.IndicatorStyle {
        switch self {
        case .auto: return .default
        case .dark: return .black
        case .light: return .white
        }
    }
}
