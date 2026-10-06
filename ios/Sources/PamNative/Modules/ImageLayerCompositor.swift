import Foundation
import ImageIO
import UIKit

/// Decodes only the first frame, bounded to 2048 px, releasing each layer before the next.
enum ImageLayerCompositor {
    static func compose(_ image: UIImage, encoded: String, resolve: (String) throws -> URL) throws -> UIImage {
        guard !encoded.isEmpty else { return image }
        guard let data = encoded.data(using: .utf8),
              let layers = try JSONSerialization.jsonObject(with: data) as? [[String: Any]],
              layers.count <= 80 else { throw layerError("Invalid image layers") }
        guard !layers.isEmpty else { return image }
        // Validate sandbox paths before beginning a non-throwing renderer closure.
        let sources = try layers.map { layer -> URL in
            guard let path = layer["path"] as? String else { throw layerError("Invalid image layer path") }
            return try resolve(path)
        }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        var failure: Error?
        let output = UIGraphicsImageRenderer(size: image.size, format: format).image { renderer in
            image.draw(in: CGRect(origin: .zero, size: image.size))
            for (index, layer) in layers.enumerated() {
                autoreleasepool {
                    guard failure == nil else { return }
                    guard let source = CGImageSourceCreateWithURL(sources[index] as CFURL, nil),
                          let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
                          let pixelsWide = properties[kCGImagePropertyPixelWidth] as? NSNumber,
                          let pixelsHigh = properties[kCGImagePropertyPixelHeight] as? NSNumber,
                          pixelsWide.doubleValue > 0, pixelsHigh.doubleValue > 0 else {
                        failure = layerError("Unable to decode image layer"); return
                    }
                    let width = CGFloat(max(0.001, min(1, layer["width"] as? Double ?? 0.25))) * image.size.width
                    let fraction = CGFloat(max(0, min(1, layer["height"] as? Double ?? 0)))
                    let height = fraction > 0 ? fraction * image.size.height
                        : width * CGFloat(pixelsHigh.doubleValue / pixelsWide.doubleValue)
                    let fit = min(1, min(image.size.width / width, image.size.height / height))
                    let size = CGSize(width: width * fit, height: height * fit)
                    let options: [CFString: Any] = [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceCreateThumbnailWithTransform: true,
                        kCGImageSourceThumbnailMaxPixelSize: max(1, min(2048, ceil(max(size.width, size.height)))),
                    ]
                    guard let bitmap = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
                        failure = layerError("Unable to decode image layer"); return
                    }
                    let x = min(image.size.width - size.width, CGFloat(max(0, min(1, layer["x"] as? Double ?? 0))) * image.size.width)
                    let y = min(image.size.height - size.height, CGFloat(max(0, min(1, layer["y"] as? Double ?? 0))) * image.size.height)
                    let context = renderer.cgContext
                    context.saveGState()
                    context.translateBy(x: x + size.width / 2, y: y + size.height / 2)
                    context.rotate(by: CGFloat(max(-Double.pi * 2, min(Double.pi * 2, layer["rotation"] as? Double ?? 0))))
                    UIImage(cgImage: bitmap).draw(in: CGRect(x: -size.width / 2, y: -size.height / 2, width: size.width, height: size.height))
                    context.restoreGState()
                }
            }
        }
        if let failure { throw failure }
        return output
    }

    private static func layerError(_ message: String) -> NSError {
        NSError(domain: "PamImageLayer", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
}
