import UIKit

/// Persisted light/dark preference for the iOS host, mirroring Android's
/// `PamAppearance`. The preference is applied as the window's
/// `overrideUserInterfaceStyle` before it becomes visible and exported to the
/// embedded PHP process before its first frame.
public enum PamAppearance {
    public static let modeSystem = 1
    public static let modeLight = 2
    public static let modeDark = 3
    static let defaultsKey = "pam.appearance.mode"

    public static func isValid(mode: Int) -> Bool {
        (modeSystem...modeDark).contains(mode)
    }

    /// The persisted preference, or `PamAppearanceDefaultMode` from Info.plist.
    public static func storedMode(defaults: UserDefaults = .standard, bundle: Bundle = .main) -> Int {
        let stored = defaults.integer(forKey: defaultsKey)
        if isValid(mode: stored) { return stored }
        let configured = (bundle.object(forInfoDictionaryKey: "PamAppearanceDefaultMode") as? NSNumber)?.intValue
        return configured.map { isValid(mode: $0) ? $0 : modeSystem } ?? modeSystem
    }

    @discardableResult
    public static func persist(_ mode: Int, defaults: UserDefaults = .standard) -> Bool {
        guard isValid(mode: mode) else { return false }
        defaults.set(mode, forKey: defaultsKey)
        return true
    }

    public static func interfaceStyle(for mode: Int) -> UIUserInterfaceStyle {
        switch mode {
        case modeLight: return .light
        case modeDark: return .dark
        default: return .unspecified
        }
    }

    /// The operating-system scheme; the screen is never affected by window overrides.
    public static var systemDark: Bool {
        UIScreen.main.traitCollection.userInterfaceStyle == .dark
    }

    public static func isDark(mode: Int = storedMode()) -> Bool {
        switch mode {
        case modeLight: return false
        case modeDark: return true
        default: return systemDark
        }
    }

    /// Window background for each scheme, from `PamAppearanceLightBackground`
    /// and `PamAppearanceDarkBackground` (generated from pam-native.json).
    public static var backgroundColor: UIColor {
        let light = color(infoKey: "PamAppearanceLightBackground") ?? .white
        let dark = color(infoKey: "PamAppearanceDarkBackground")
            ?? UIColor(red: 18 / 255, green: 18 / 255, blue: 18 / 255, alpha: 1)
        return UIColor { traits in traits.userInterfaceStyle == .dark ? dark : light }
    }

    /// Applies the preference to a window. Call before `makeKeyAndVisible()`.
    public static func apply(to window: UIWindow, mode: Int = storedMode()) {
        window.overrideUserInterfaceStyle = interfaceStyle(for: mode)
        window.backgroundColor = backgroundColor
    }

    /// Applies a runtime change to every connected window without recreating them.
    public static func applyToConnectedWindows(mode: Int) {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .forEach { apply(to: $0, mode: mode) }
    }

    /// Exports the boot appearance so PHP reads it synchronously.
    public static func exportEnvironment(mode: Int = storedMode()) {
        setenv("PAM_APPEARANCE_MODE", String(mode), 1)
        setenv("PAM_SYSTEM_APPEARANCE", systemDark ? "2" : "1", 1)
        setenv("PAM_SYSTEM_DARK", isDark(mode: mode) ? "1" : "0", 1)
    }

    static func color(infoKey: String, bundle: Bundle = .main) -> UIColor? {
        guard let value = bundle.object(forInfoDictionaryKey: infoKey) as? String,
              value.count == 7, value.hasPrefix("#"),
              let rgb = UInt32(value.dropFirst(), radix: 16) else { return nil }
        return UIColor(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }
}
