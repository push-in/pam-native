import CoreText
import UIKit

final class PamFontLoader {
    private let resourceRoot: URL?
    private var faces: [String: CGFont] = [:]

    init(resourceRoot: URL? = Bundle.main.resourceURL) {
        self.resourceRoot = resourceRoot
    }

    func assetFont(family: String, size: CGFloat, weight: Int) -> UIFont? {
        guard let path = try? normalizedPamAssetPath(family),
              let resourceRoot else { return nil }
        let face: CGFont
        if let cached = faces[path] {
            face = cached
        } else {
            let url = resourceRoot.appendingPathComponent(path)
            guard let provider = CGDataProvider(url: url as CFURL),
                  let loaded = CGFont(provider) else { return nil }
            faces[path] = loaded
            face = loaded
        }
        // OpenType wght axis; the layout engine uses the same numeric weight.
        let variations = [NSNumber(value: 0x77676874): NSNumber(value: min(1000, max(1, weight)))]
        let descriptor = CTFontDescriptorCreateWithAttributes([
            kCTFontVariationAttribute: variations,
        ] as CFDictionary)
        return CTFontCreateWithGraphicsFont(face, size, nil, descriptor) as UIFont
    }
}
