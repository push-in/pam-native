import Foundation

/// CSS effects iOS cannot paint with public APIs (filters on arbitrary views,
/// backdrop color matrices). Debug builds report each property once instead of
/// dropping it silently; release builds ignore it.
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
        case PamConstants.filterColorMatrix: name = "filter color functions on non-image views (images are filtered)"
        case PamConstants.blurRadius: name = "filter: blur() on non-image views (images are blurred)"
        case PamConstants.backdropColorMatrix: name = "backdrop-filter color functions (blur is painted)"
        default: name = "property \(key)"
        }
        NSLog("[PamNative] CSS %@ is not painted on iOS.", name)
        #endif
    }
}
