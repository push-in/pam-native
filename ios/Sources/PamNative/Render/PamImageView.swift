import CoreImage
import UIKit

/// CSS `filter` for images: `blur()`/`blurRadius` (σ in points) blurs the
/// bitmap once with clamped, opaque edges (React Native `blurRadius`), and the
/// compiled 4×5 color matrix (brightness, contrast, saturate, grayscale, sepia,
/// invert, opacity, hue-rotate) is applied to the decoded pixels.
struct PamImageFilter: Equatable {
    var blurSigma: CGFloat = 0
    /// Row-major 4×5 matrix, offsets in 0…255 (Android `ColorMatrix` layout).
    var matrix: [CGFloat]?

    var isIdentity: Bool { blurSigma <= 0 && matrix == nil }

    static func colorMatrix(_ wire: String?) -> [CGFloat]? {
        guard let wire, !wire.isEmpty else { return nil }
        let values = wire.split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        guard values.count == 20 else { return nil }
        return values.map { CGFloat($0) }
    }

    private static let context = CIContext(options: [.cacheIntermediates: false])

    func apply(to image: UIImage, displayScale: CGFloat) -> UIImage {
        guard !isIdentity, let source = CIImage(image: image) else { return image }
        var output = source
        if let matrix {
            let filter = CIFilter(name: "CIColorMatrix")
            filter?.setValue(output, forKey: kCIInputImageKey)
            filter?.setValue(CIVector(x: matrix[0], y: matrix[1], z: matrix[2], w: matrix[3]), forKey: "inputRVector")
            filter?.setValue(CIVector(x: matrix[5], y: matrix[6], z: matrix[7], w: matrix[8]), forKey: "inputGVector")
            filter?.setValue(CIVector(x: matrix[10], y: matrix[11], z: matrix[12], w: matrix[13]), forKey: "inputBVector")
            filter?.setValue(CIVector(x: matrix[15], y: matrix[16], z: matrix[17], w: matrix[18]), forKey: "inputAVector")
            filter?.setValue(
                CIVector(x: matrix[4] / 255, y: matrix[9] / 255, z: matrix[14] / 255, w: matrix[19] / 255),
                forKey: "inputBiasVector"
            )
            output = filter?.outputImage ?? output
        }
        if blurSigma > 0 {
            // σ is authored in points; the bitmap is in pixels of its own scale.
            let pixelsPerPoint = max(1, image.scale)
            let filter = CIFilter(name: "CIGaussianBlur")
            filter?.setValue(output.clampedToExtent(), forKey: kCIInputImageKey)
            filter?.setValue(blurSigma * pixelsPerPoint, forKey: kCIInputRadiusKey)
            output = filter?.outputImage?.cropped(to: source.extent) ?? output
        }
        guard let rendered = Self.context.createCGImage(output, from: source.extent) else { return image }
        return UIImage(cgImage: rendered, scale: image.scale, orientation: image.imageOrientation)
    }
}

/// Image host that keeps the original bitmap and displays it filtered.
final class PamImageView: UIImageView {
    private var original: UIImage?

    var pamFilter = PamImageFilter() {
        didSet {
            guard pamFilter != oldValue else { return }
            refilter()
        }
    }

    override var image: UIImage? {
        get { super.image }
        set {
            original = newValue
            guard let newValue, !pamFilter.isIdentity else {
                super.image = newValue
                return
            }
            super.image = pamFilter.apply(to: newValue, displayScale: window?.screen.scale ?? 2)
        }
    }

    /// The unfiltered bitmap last assigned by the renderer.
    var originalImage: UIImage? { original }

    private func refilter() {
        guard let original else { return }
        super.image = pamFilter.isIdentity
            ? original
            : pamFilter.apply(to: original, displayScale: window?.screen.scale ?? 2)
    }
}
