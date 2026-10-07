import UIKit

/// `data:image/*` sources (bundled icon masks such as Zé Chat's AppIcon
/// glyphs). They decode synchronously on the main thread, in the frame that
/// assigns them, like a font glyph (Android decodes small ones on the UI
/// thread too), and stay in their own cache so photos never evict them.
enum PamInlineImages {
    /// Larger inline payloads are not icons: they still decode here (there
    /// is no other path for them) but are not cached.
    static let cachedMaxCharacters = 16 * 1024

    private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.totalCostLimit = PamImageDownsampling.inlineCacheBytes()
        return cache
    }()

    static func isInline(_ source: String) -> Bool {
        source.range(of: "data:image/", options: [.anchored, .caseInsensitive]) != nil
    }

    static func image(_ source: String) -> UIImage? {
        guard isInline(source) else { return nil }
        let key = source as NSString
        if let cached = cache.object(forKey: key) {
            return cached
        }
        guard let data = decode(source), let image = UIImage(data: data) else { return nil }
        if source.utf16.count <= cachedMaxCharacters {
            let cost = Int(image.size.width * image.size.height * image.scale * image.scale * 4)
            cache.setObject(image, forKey: key, cost: cost)
        }
        return image
    }

    static func decode(_ source: String) -> Data? {
        guard isInline(source), let comma = source.firstIndex(of: ",") else { return nil }
        let metadata = source[source.index(source.startIndex, offsetBy: 5)..<comma].lowercased()
        guard metadata.hasPrefix("image/") else { return nil }
        let payload = String(source[source.index(after: comma)...])
        if metadata.hasSuffix(";base64") {
            return Data(base64Encoded: payload, options: .ignoreUnknownCharacters)
        }
        return payload.removingPercentEncoding.map { Data($0.utf8) }
    }
}
