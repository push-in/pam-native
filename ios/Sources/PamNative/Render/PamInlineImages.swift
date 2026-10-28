import UIKit

/// `data:image/*` sources (bundled icon masks such as an app's AppIcon
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
        guard let data = decode(source),
              let image = PamAnimatedImage.image(data: data, targetSize: .zero) ?? UIImage(data: data) else {
            return nil
        }
        if source.utf16.count <= cachedMaxCharacters {
            let cost = Int(image.size.width * image.size.height * image.scale * image.scale * 4)
            cache.setObject(image, forKey: key, cost: cost)
        }
        return image
    }

    /// Packaged `asset://` files up to this size (icon masks, an app's
    /// `assets/icons`) decode in the frame and share the inline cache, like
    /// Android's `INLINE_SYNC_MAX_CHARS` assets: cells re-mounting the same
    /// icon never read and decode the file again, nor wait behind photos.
    static let assetSyncMaxBytes = 16 * 1_024
    private static let assetLock = NSLock()
    /// Remembers which asset paths are larger than `assetSyncMaxBytes`.
    private static var largeAssets: Set<String> = []
    private static let largeAssetsMax = 1_024

    static func smallAsset(_ source: String, url: URL) -> UIImage? {
        let key = source as NSString
        if let cached = cache.object(forKey: key) { return cached }
        assetLock.lock()
        let known = largeAssets.contains(source)
        assetLock.unlock()
        if known { return nil }
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? Int.max
        guard size <= assetSyncMaxBytes, let data = try? Data(contentsOf: url) else {
            assetLock.lock()
            if largeAssets.count >= largeAssetsMax { largeAssets.removeAll() }
            largeAssets.insert(source)
            assetLock.unlock()
            return nil
        }
        guard let image = PamAnimatedImage.image(data: data, targetSize: .zero) ?? UIImage(data: data) else {
            return nil
        }
        let decoded = image.pamAnimation == nil ? (image.preparingForDisplay() ?? image) : image
        decoded.pamAnimation = image.pamAnimation
        let cost = Int(decoded.size.width * decoded.size.height * decoded.scale * decoded.scale * 4)
        cache.setObject(decoded, forKey: key, cost: max(cost, 1))
        return decoded
    }

    /// A small asset already decoded (no bundle lookup, no file read).
    static func cachedAsset(_ source: String) -> UIImage? {
        cache.object(forKey: source as NSString)
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
