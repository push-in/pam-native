import Foundation

/// CSS visual effects compiled for Android that the iOS renderer does not
/// paint yet. Debug builds report each property once instead of dropping it
/// silently; release builds ignore it.
enum PamUnsupportedEffects {
    private static var reported = Set<Int>()

    static func report(key: Int, value: PropValue) {
        #if DEBUG
        let active: Bool
        switch value {
        case let .text(text): active = !text.isEmpty
        case let .decimal(number): active = number > 0
        case let .integer(number): active = number > 0
        case let .flag(flag): active = flag
        default: active = false
        }
        guard active, !reported.contains(key) else { return }
        reported.insert(key)
        let name: String
        switch key {
        case PamConstants.backgroundGradient: name = "background gradients"
        case PamConstants.boxShadows: name = "multiple/inset box-shadow (first outer shadow is painted)"
        case PamConstants.filterColorMatrix: name = "filter color functions"
        case PamConstants.backdropBlurRadius, PamConstants.backdropColorMatrix: name = "backdrop-filter"
        case PamConstants.borderGradient: name = "gradient border-image"
        case PamConstants.shimmerEnabled: name = "<Shimmer> sweep (base color is painted)"
        default: name = "property \(key)"
        }
        NSLog("[PamNative] CSS %@ is not painted on iOS yet (Android only).", name)
        #endif
    }
}
