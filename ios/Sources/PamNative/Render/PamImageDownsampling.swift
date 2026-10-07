import Foundation
import ImageIO
import UIKit

/// Decode-at-display-size helpers shared by the image paths (network, media
/// cache, bundled assets and sandbox files), matching Android's
/// `nativeImageDecodePlan`: ImageIO creates a thumbnail whose longest edge
/// still covers the view (center-crop never upscales; contain draws the same
/// pixels smaller) instead of decoding the full source.
enum PamImageDownsampling {
    /// Longest-edge pixel size for `kCGImageSourceThumbnailMaxPixelSize` so a
    /// `sourceSize` image still covers `targetSize` points at `scale`. Returns
    /// nil when the source is already at most the covering size (decode as is).
    static func coverMaxPixelSize(
        sourceSize: CGSize,
        targetSize: CGSize,
        scale: CGFloat,
        multiplier: CGFloat
    ) -> CGFloat? {
        guard sourceSize.width > 0, sourceSize.height > 0,
              targetSize.width > 0, targetSize.height > 0 else { return nil }
        let factor = scale * min(max(multiplier, 0.1), 8)
        let desiredWidth = min(targetSize.width * factor, 4_096)
        let desiredHeight = min(targetSize.height * factor, 4_096)
        let cover = max(desiredWidth / sourceSize.width, desiredHeight / sourceSize.height)
        // Within ~10% of the view: not worth a resampling pass.
        guard cover < 0.9 else { return nil }
        return ceil(max(sourceSize.width, sourceSize.height) * cover - 0.000_001)
    }

    /// Downsampled image from encoded bytes; nil when ImageIO cannot read it
    /// or the source needs no downsampling (the caller decodes it as is).
    static func image(
        data: Data,
        targetSize: CGSize,
        scale: CGFloat = UIScreen.main.scale,
        multiplier: CGFloat = 1
    ) -> UIImage? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options) else { return nil }
        return image(source: source, targetSize: targetSize, scale: scale, multiplier: multiplier)
    }

    /// Downsampled image from a file URL without reading it into memory first.
    static func image(
        contentsOf url: URL,
        targetSize: CGSize,
        scale: CGFloat = UIScreen.main.scale,
        multiplier: CGFloat = 1
    ) -> UIImage? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithURL(url as CFURL, options) else { return nil }
        return image(source: source, targetSize: targetSize, scale: scale, multiplier: multiplier)
    }

    static func image(
        source: CGImageSource,
        targetSize: CGSize,
        scale: CGFloat,
        multiplier: CGFloat
    ) -> UIImage? {
        // Animated sources keep UIImage's own decoding (all frames).
        guard CGImageSourceGetCount(source) <= 1 else { return nil }
        guard let maxPixels = coverMaxPixelSize(
            sourceSize: pixelSize(source),
            targetSize: targetSize,
            scale: scale,
            multiplier: multiplier
        ) else {
            // Already at most the covering size: callers decode it as is.
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(maxPixels, 1),
            // Decode now, on the calling (background) queue, not in the
            // first Core Animation commit that draws the image.
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return nil
        }
        // Scale 1 like the previous ImageIO and UIImage(data:) paths: image
        // views size and scale the image by its pixels.
        return UIImage(cgImage: image)
    }

    /// Oriented pixel size of the first image (EXIF rotation applied).
    static func pixelSize(_ source: CGImageSource) -> CGSize {
        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue,
              let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue else {
            return .zero
        }
        let orientation = (properties[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
        // Orientations 5-8 rotate by 90 degrees.
        return orientation >= 5 && orientation <= 8
            ? CGSize(width: height, height: width)
            : CGSize(width: width, height: height)
    }

    /// Byte budget for in-memory image caches from the device's physical
    /// memory, like Android's `nativeImageMemoryCacheBytes`: 1/32 of RAM for
    /// decoded/encoded photos (a 4 GB iPhone gets 128 MB clamped to 96 MB,
    /// a 2 GB one 64 MB), between 16 MB and 96 MB.
    static func memoryCacheBytes(physicalMemory: UInt64 = ProcessInfo.processInfo.physicalMemory) -> Int {
        let budget = physicalMemory / 32
        return Int(min(max(budget, 16 * 1_024 * 1_024), 96 * 1_024 * 1_024))
    }

    /// Small inline glyph cache budget: 1/512 of RAM, between 2 MB and 8 MB.
    static func inlineCacheBytes(physicalMemory: UInt64 = ProcessInfo.processInfo.physicalMemory) -> Int {
        let budget = physicalMemory / 512
        return Int(min(max(budget, 2 * 1_024 * 1_024), 8 * 1_024 * 1_024))
    }
}
