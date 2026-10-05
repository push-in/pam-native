import CoreText
import UIKit

/// Resolves `fontFamily`/weight/italic/features to a font exactly once for
/// both drawing (main thread) and engine measurement (PHP worker thread).
///
/// Order, like React Native: packaged `asset://…ttf|otf` files, then the
/// React Native file conventions for a bare family name inside the bundle
/// (`{Family}-{Weight}.ttf`, `{Family}_{weight}`, `_bold`/`_italic`,
/// `{Family}.ttf`), then installed/registered families, then the system font.
/// A file that exactly matches the requested weight/style is used as is (no
/// synthetic bold).
final class PamFontResolver: @unchecked Sendable {
    static let shared = PamFontResolver()

    private let lock = NSLock()
    private let resourceRoot: URL?
    private var faces: [String: CGFont] = [:]
    private var existing: [String: Bool] = [:]
    private var installedFamilies: [String: Bool] = [:]
    private var fonts: [String: UIFont] = [:]

    private static let systemFamilies: Set<String> = [
        "system", "system-ui", "sans-serif", "-apple-system", "roboto", "san francisco", "sf pro",
    ]
    private static let fontDirectories = ["pam/assets/fonts", "pam/fonts"]
    private static let weightNames: [Int: String] = [
        100: "Thin",
        200: "ExtraLight",
        300: "Light",
        400: "Regular",
        500: "Medium",
        600: "SemiBold",
        700: "Bold",
        800: "ExtraBold",
        900: "Black",
    ]

    init(resourceRoot: URL? = Bundle.main.resourceURL) {
        self.resourceRoot = resourceRoot
    }

    func font(
        family: String?,
        size: CGFloat,
        weight: Int,
        italic: Bool,
        features: String? = nil
    ) -> UIFont {
        let resolvedSize = max(1, size.isFinite ? size : 14)
        let resolvedWeight = min(1000, max(1, weight))
        let key = "\(family ?? "")|\(resolvedSize)|\(resolvedWeight)|\(italic)|\(features ?? "")"
        lock.lock()
        if let cached = fonts[key] {
            lock.unlock()
            return cached
        }
        lock.unlock()
        var font = resolve(family: family, size: resolvedSize, weight: resolvedWeight, italic: italic)
        if let features, let featured = PamFontFeatures.apply(features, to: font) {
            font = featured
        }
        lock.lock()
        if fonts.count > 512 { fonts.removeAll(keepingCapacity: true) }
        fonts[key] = font
        lock.unlock()
        return font
    }

    /// Packaged asset font (`asset://fonts/Inter.ttf`) with its OpenType
    /// `wght` axis set to the requested weight.
    func assetFont(family: String, size: CGFloat, weight: Int) -> UIFont? {
        guard let path = try? normalizedPamAssetPath(family) else { return nil }
        return fileFont(path: path, size: size, weight: weight)
    }

    private func resolve(family: String?, size: CGFloat, weight: Int, italic: Bool) -> UIFont {
        guard let family = family?.trimmingCharacters(in: .whitespaces), !family.isEmpty else {
            return Self.systemFont(size: size, weight: weight, italic: italic, design: .default)
        }
        if family.range(of: "asset://", options: [.anchored, .caseInsensitive]) != nil {
            if let font = assetFont(family: family, size: size, weight: weight) {
                return italic ? (Self.italicized(font) ?? font) : font
            }
            return Self.systemFont(size: size, weight: weight, italic: italic, design: .default)
        }
        let lowered = family.lowercased()
        if Self.systemFamilies.contains(lowered) {
            return Self.systemFont(size: size, weight: weight, italic: italic, design: .default)
        }
        if lowered == "monospace" {
            let base = UIFont.monospacedSystemFont(ofSize: size, weight: Self.uiWeight(weight))
            return italic ? (Self.italicized(base) ?? base) : base
        }
        if lowered == "serif" {
            return Self.systemFont(size: size, weight: weight, italic: italic, design: .serif)
        }
        if let (path, exact) = conventionalFontAsset(family: family, weight: weight, italic: italic),
           let font = fileFont(path: path, size: size, weight: exact ? nil : weight) {
            return font
        }
        if installed(family) {
            var traits: [UIFontDescriptor.TraitKey: Any] = [.weight: Self.uiWeight(weight)]
            if italic {
                traits[.symbolic] = UIFontDescriptor.SymbolicTraits.traitItalic.rawValue
            }
            let descriptor = UIFontDescriptor(fontAttributes: [
                .family: family,
                .traits: traits,
            ])
            return UIFont(descriptor: descriptor, size: size)
        }
        if let named = UIFont(name: family, size: size) {
            return italic ? (Self.italicized(named) ?? named) : named
        }
        return Self.systemFont(size: size, weight: weight, italic: italic, design: .default)
    }

    private func installed(_ family: String) -> Bool {
        lock.lock()
        if let known = installedFamilies[family] {
            lock.unlock()
            return known
        }
        lock.unlock()
        let probe = CTFontDescriptorCreateWithAttributes([
            kCTFontFamilyNameAttribute: family,
        ] as CFDictionary)
        let matches = CTFontDescriptorCreateMatchingFontDescriptors(
            probe,
            NSSet(object: kCTFontFamilyNameAttribute) as CFSet
        )
        let found = matches.map { CFArrayGetCount($0) > 0 } ?? false
        lock.lock()
        installedFamilies[family] = found
        lock.unlock()
        return found
    }

    /// Loads a bundle-relative font file. `weight` sets the `wght` variation
    /// axis (ignored by static fonts); `nil` keeps the file's own weight.
    private func fileFont(path: String, size: CGFloat, weight: Int?) -> UIFont? {
        guard let resourceRoot else { return nil }
        lock.lock()
        var face = faces[path]
        lock.unlock()
        if face == nil {
            let url = resourceRoot.appendingPathComponent(path)
            guard let provider = CGDataProvider(url: url as CFURL),
                  let loaded = CGFont(provider) else { return nil }
            lock.lock()
            faces[path] = loaded
            lock.unlock()
            face = loaded
        }
        guard let face else { return nil }
        guard let weight else {
            return CTFontCreateWithGraphicsFont(face, size, nil, nil) as UIFont
        }
        let variations = [NSNumber(value: 0x7767_6874): NSNumber(value: min(1000, max(1, weight)))]
        let descriptor = CTFontDescriptorCreateWithAttributes([
            kCTFontVariationAttribute: variations,
        ] as CFDictionary)
        return CTFontCreateWithGraphicsFont(face, size, nil, descriptor) as UIFont
    }

    private func fileExists(_ path: String) -> Bool {
        guard let resourceRoot else { return false }
        lock.lock()
        if let known = existing[path] {
            lock.unlock()
            return known
        }
        lock.unlock()
        let exists = FileManager.default.fileExists(atPath: resourceRoot.appendingPathComponent(path).path)
        lock.lock()
        existing[path] = exists
        lock.unlock()
        return exists
    }

    /// React Native font-file conventions for a bare `fontFamily`. Returns the
    /// bundle path and whether it exactly matches the requested weight/style.
    func conventionalFontAsset(family: String, weight: Int, italic: Bool) -> (String, Bool)? {
        let allowed = family.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.contains($0) || $0 == " " || $0 == "_" || $0 == "-"
        }
        guard allowed, !family.isEmpty else { return nil }
        let compact = family.replacingOccurrences(of: " ", with: "")
        let rounded = min(900, max(100, (weight + 50) / 100 * 100))
        let weightName = Self.weightNames[rounded] ?? "Regular"
        var candidates: [(String, Bool)] = []
        let italicSuffix = italic ? "Italic" : ""
        candidates.append(("\(compact)-\(italic && rounded == 400 ? "Italic" : weightName + italicSuffix)", true))
        candidates.append(("\(compact)_\(rounded)\(italic ? "_italic" : "")", true))
        if rounded >= 600 { candidates.append(("\(compact)_bold\(italic ? "_italic" : "")", true)) }
        if italic && rounded < 600 { candidates.append(("\(compact)_italic", true)) }
        if rounded == 400 && !italic { candidates.append(("\(compact)-Regular", true)) }
        candidates.append((compact, rounded == 400 && !italic))
        for (name, exact) in candidates {
            for directory in Self.fontDirectories {
                for fileExtension in ["ttf", "otf"] {
                    let path = "\(directory)/\(name).\(fileExtension)"
                    if fileExists(path) { return (path, exact) }
                }
            }
        }
        return nil
    }

    static func uiWeight(_ weight: Int) -> UIFont.Weight {
        switch weight {
        case ..<150: return .ultraLight
        case ..<250: return .thin
        case ..<350: return .light
        case ..<450: return .regular
        case ..<550: return .medium
        case ..<650: return .semibold
        case ..<750: return .bold
        case ..<850: return .heavy
        default: return .black
        }
    }

    static func systemFont(size: CGFloat, weight: Int, italic: Bool, design: UIFontDescriptor.SystemDesign) -> UIFont {
        var font = UIFont.systemFont(ofSize: size, weight: uiWeight(weight))
        if design != .default, let designed = font.fontDescriptor.withDesign(design) {
            font = UIFont(descriptor: designed, size: size)
        }
        if italic, let slanted = italicized(font) {
            font = slanted
        }
        return font
    }

    static func italicized(_ font: UIFont) -> UIFont? {
        guard let descriptor = font.fontDescriptor.withSymbolicTraits(
            font.fontDescriptor.symbolicTraits.union(.traitItalic)
        ) else { return nil }
        return UIFont(descriptor: descriptor, size: font.pointSize)
    }
}

/// CSS `font-feature-settings` / compiled `font-variant-numeric`
/// (`"tnum" 1, 'liga' off, "smcp"`) as OpenType feature settings.
enum PamFontFeatures {
    static func parse(_ source: String) -> [(tag: String, value: Int)] {
        var result: [(String, Int)] = []
        for entry in source.split(separator: ",") {
            let trimmed = entry.trimmingCharacters(in: .whitespaces)
            guard let quote = trimmed.first, quote == "\"" || quote == "'" else { continue }
            let rest = trimmed.dropFirst()
            guard let close = rest.firstIndex(of: quote) else { continue }
            let tag = String(rest[rest.startIndex..<close])
            guard tag.count == 4, tag.unicodeScalars.allSatisfy({ $0.isASCII }) else { continue }
            let valueText = rest[rest.index(after: close)...].trimmingCharacters(in: .whitespaces).lowercased()
            let value: Int
            switch valueText {
            case "", "on": value = 1
            case "off": value = 0
            default: value = Int(valueText) ?? 1
            }
            result.append((tag, max(0, value)))
        }
        return result
    }

    static func apply(_ source: String, to font: UIFont) -> UIFont? {
        let settings = parse(source)
        guard !settings.isEmpty else { return nil }
        let features: [NSDictionary] = settings.map {
            [
                kCTFontOpenTypeFeatureTag as String: $0.tag,
                kCTFontOpenTypeFeatureValue as String: NSNumber(value: $0.value),
            ] as NSDictionary
        }
        let descriptor = CTFontDescriptorCreateWithAttributes([
            kCTFontFeatureSettingsAttribute: features as NSArray,
        ] as CFDictionary)
        return CTFontCreateCopyWithAttributes(font as CTFont, font.pointSize, nil, descriptor) as UIFont
    }
}
