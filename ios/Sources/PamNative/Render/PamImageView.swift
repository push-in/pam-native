import Accelerate
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

    /// Only the color matrix uses Core Image: the context (and its GPU
    /// textures and compiled kernels) is created on first use, never for a
    /// blur.
    private static let context = CIContext(options: [.cacheIntermediates: false])

    /// Filters run here, never on the main thread (Android blurs on the image
    /// loader's workers): a blur or a Core Image render of a photo costs
    /// several milliseconds, a dropped frame per image bound while scrolling.
    static let queue = DispatchQueue(label: "dev.pam.image-filter", qos: .userInitiated)

    /// `drawScale`: view points per image point at the view's content mode
    /// (σ is authored in view points; a 96 px icon drawn at 92 pt must not be
    /// blurred as if it were 92 px).
    func apply(to image: UIImage, displayScale: CGFloat, drawScale: CGFloat = 1) -> UIImage {
        guard !isIdentity else { return image }
        var output = image
        if let matrix {
            output = Self.applyMatrix(matrix, to: output)
        }
        if blurSigma > 0 {
            // σ is authored in points; the bitmap is in pixels of its own scale.
            let drawn = drawScale > 0 ? drawScale : 1
            output = PamBitmapBlur.blur(output, sigma: blurSigma * max(1, output.scale) / drawn) ?? output
        }
        return output
    }

    /// View points per image point for a content mode (1 when not laid out).
    static func drawScale(image: CGSize, bounds: CGSize, contentMode: UIView.ContentMode) -> CGFloat {
        guard image.width > 0, image.height > 0, bounds.width > 0, bounds.height > 0 else { return 1 }
        let sx = bounds.width / image.width
        let sy = bounds.height / image.height
        switch contentMode {
        case .scaleAspectFill: return max(sx, sy)
        case .scaleToFill: return (sx * sy).squareRoot()
        case .scaleAspectFit: return min(sx, sy)
        default: return 1
        }
    }

    private static func applyMatrix(_ matrix: [CGFloat], to image: UIImage) -> UIImage {
        guard let source = CIImage(image: image) else { return image }
        let filter = CIFilter(name: "CIColorMatrix")
        filter?.setValue(source, forKey: kCIInputImageKey)
        filter?.setValue(CIVector(x: matrix[0], y: matrix[1], z: matrix[2], w: matrix[3]), forKey: "inputRVector")
        filter?.setValue(CIVector(x: matrix[5], y: matrix[6], z: matrix[7], w: matrix[8]), forKey: "inputGVector")
        filter?.setValue(CIVector(x: matrix[10], y: matrix[11], z: matrix[12], w: matrix[13]), forKey: "inputBVector")
        filter?.setValue(CIVector(x: matrix[15], y: matrix[16], z: matrix[17], w: matrix[18]), forKey: "inputAVector")
        filter?.setValue(
            CIVector(x: matrix[4] / 255, y: matrix[9] / 255, z: matrix[14] / 255, w: matrix[19] / 255),
            forKey: "inputBiasVector"
        )
        guard let output = filter?.outputImage,
              let rendered = context.createCGImage(output, from: source.extent)
        else { return image }
        return UIImage(cgImage: rendered, scale: image.scale, orientation: image.imageOrientation)
    }
}

/// The last filtered copy of a decoded image, kept on that image (Android's
/// blurred memory-cache entry): every view showing the shared decode (a
/// recycled cell, the same photo twice) reuses it in the same pass, and it
/// goes with the image.
final class PamFilteredImage: @unchecked Sendable {
    let filter: PamImageFilter
    let drawScale: CGFloat
    let image: UIImage

    init(filter: PamImageFilter, drawScale: CGFloat, image: UIImage) {
        self.filter = filter
        self.drawScale = drawScale
        self.image = image
    }

    /// Same filter; a blur also at the same drawn scale within 5 % (its σ
    /// in pixels follows the scale; Android `PamBitmapBlur.sigmaMatches`).
    func matches(_ filter: PamImageFilter, drawScale: CGFloat) -> Bool {
        guard filter == self.filter else { return false }
        return filter.blurSigma <= 0 || Self.drawScaleMatches(self.drawScale, drawScale)
    }

    static func drawScaleMatches(_ applied: CGFloat, _ wanted: CGFloat) -> Bool {
        abs(applied - wanted) <= max(0.0005, wanted * 0.05)
    }
}

private var pamFilteredKey: UInt8 = 0

/// An image view handed to a background filter job.
private final class PamWeakImageView: @unchecked Sendable {
    weak var view: PamImageView?

    init(_ view: PamImageView) {
        self.view = view
    }
}

extension UIImage {
    /// Set off the main thread by `PamImageView`, read on it.
    var pamFiltered: PamFilteredImage? {
        get { objc_getAssociatedObject(self, &pamFilteredKey) as? PamFilteredImage }
        set { objc_setAssociatedObject(self, &pamFilteredKey, newValue, .OBJC_ASSOCIATION_RETAIN) }
    }
}

/// React Native `blurRadius` for images, like Android's `PamBitmapBlur`: three
/// box passes (≈ Gaussian, σ in pixels) on a copy
/// downscaled so σ stays near 4 px, on the CPU with vImage. No Core Image
/// context, GPU textures or kernel compilation per process, and the blurred
/// bitmap is a fraction of the source size (it is displayed scaled up).
/// Opaque images keep clamped edges (opaque to their bounds); an image with a
/// transparent border (an icon or a glyph used as a shadow) fades into
/// transparency, so its blur never fills the whole box.
enum PamBitmapBlur {
    /// True when any border pixel of a BGRA (alpha last) buffer is translucent.
    static func hasTranslucentBorder(_ pixels: UnsafeRawPointer, width: Int, height: Int, rowBytes: Int) -> Bool {
        func alpha(_ x: Int, _ y: Int) -> UInt8 { pixels.load(fromByteOffset: y * rowBytes + x * 4 + 3, as: UInt8.self) }
        for x in 0..<width where alpha(x, 0) < 255 || alpha(x, height - 1) < 255 { return true }
        for y in 0..<height where alpha(0, y) < 255 || alpha(width - 1, y) < 255 { return true }
        return false
    }

    static func blur(_ image: UIImage, sigma: CGFloat) -> UIImage? {
        guard sigma > 0, let source = image.cgImage ?? rasterized(image) else { return nil }
        let scale = min(1, max(1.0 / 64.0, 4 / sigma))
        let width = max(1, Int((CGFloat(source.width) * scale).rounded()))
        let height = max(1, Int((CGFloat(source.height) * scale).rounded()))
        let bitmapInfo = CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        guard let canvas = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: bitmapInfo
        ), let pixels = canvas.data else { return nil }
        canvas.interpolationQuality = .medium
        canvas.draw(source, in: CGRect(x: 0, y: 0, width: width, height: height))

        // Three box passes of width w approximate a Gaussian of σ² = 3(w² - 1)/12.
        let scaledSigma = sigma * CGFloat(width) / CGFloat(source.width)
        let boxWidth = (4 * scaledSigma * scaledSigma + 1).squareRoot()
        let radius = max(1, Int(((boxWidth - 1) / 2).rounded(.up)))
        let kernel = UInt32(radius * 2 + 1)
        var buffer = vImage_Buffer(
            data: pixels,
            height: vImagePixelCount(height),
            width: vImagePixelCount(width),
            rowBytes: canvas.bytesPerRow
        )
        guard let scratchData = malloc(canvas.bytesPerRow * height) else { return nil }
        defer { free(scratchData) }
        var scratch = vImage_Buffer(
            data: scratchData,
            height: buffer.height,
            width: buffer.width,
            rowBytes: canvas.bytesPerRow
        )
        let transparentEdges = hasTranslucentBorder(pixels, width: width, height: height, rowBytes: canvas.bytesPerRow)
        let flags = vImage_Flags(transparentEdges ? kvImageBackgroundColorFill : kvImageEdgeExtend)
        let clear: [UInt8] = [0, 0, 0, 0]
        // buffer -> scratch -> buffer -> scratch, then back into the canvas.
        for pass in 0..<3 {
            let status = pass % 2 == 0
                ? vImageBoxConvolve_ARGB8888(&buffer, &scratch, nil, 0, 0, kernel, kernel, clear, flags)
                : vImageBoxConvolve_ARGB8888(&scratch, &buffer, nil, 0, 0, kernel, kernel, clear, flags)
            guard status == kvImageNoError else { return nil }
        }
        memcpy(pixels, scratchData, canvas.bytesPerRow * height)
        guard let blurred = canvas.makeImage() else { return nil }
        let pixelsPerPoint = image.scale * CGFloat(width) / CGFloat(source.width)
        return UIImage(cgImage: blurred, scale: pixelsPerPoint, orientation: image.imageOrientation)
    }

    private static func rasterized(_ image: UIImage) -> CGImage? {
        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        return UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
            image.draw(at: .zero)
        }.cgImage
    }
}

/// Image host that keeps the original bitmap and displays it filtered and,
/// with `tintColor`, as a template mask in that color (Android
/// `ImageView.imageTintList`, SRC_IN): one decoded icon serves every color.
/// Filters run on `PamImageFilter.queue`; until a filtered copy is ready the
/// view keeps what it showed, or nothing, and never a sharp image under a
/// blur (a chat spoiler). A cached copy shows at once.
final class PamImageView: UIImageView, PamPointerEventsHost {
    private var original: UIImage?
    var pamPointerEvents = PamPointerEvents.auto

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        PamPointerEvents.hitTest(self, mode: pamPointerEvents) {
            super.hitTest(point, with: event)
        }
    }

    var pamFilter = PamImageFilter() {
        didSet {
            guard pamFilter != oldValue else { return }
            if (pamFilter.blurSigma > 0) != (oldValue.blurSigma > 0) {
                setAnimation(pamFilter.blurSigma > 0 ? nil : original?.pamAnimation)
            }
            refilter()
        }
    }

    var pamTintColor: UIColor? {
        didSet {
            guard pamTintColor != oldValue else { return }
            refilter()
        }
    }

    override var image: UIImage? {
        get { super.image }
        set {
            original = newValue
            // A blurred animated image shows its first frame, blurred once
            // (Android parity), never a blur per frame.
            setAnimation(pamFilter.blurSigma > 0 ? nil : newValue?.pamAnimation)
            showFiltered(newValue)
        }
    }

    // MARK: Animated GIF/WebP (Android `AnimatedImageDrawable` parity)

    private var animationPlayer: PamAnimatedImagePlayer?

    /// True while an animated image is attached to a window and ticking.
    var isAnimatingImage: Bool { animationPlayer != nil && window != nil }
    var animationFrameIndex: Int { animationPlayer?.frameIndex ?? 0 }
    var animationElapsed: TimeInterval { animationPlayer?.elapsed ?? 0 }

    private func setAnimation(_ animation: PamAnimatedImage?) {
        guard animation !== animationPlayer?.animation else { return }
        animationPlayer = animation.map { animation in
            PamAnimatedImagePlayer(animation: animation) { [weak self] frame in
                self?.showAnimationFrame(frame)
            }
        }
        syncAnimationClock()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        syncAnimationClock()
    }

    private func syncAnimationClock() {
        if animationPlayer != nil, window != nil {
            PamAnimatedImageClock.shared.register(self)
        } else {
            PamAnimatedImageClock.shared.unregister(self)
        }
    }

    /// On screen for real: in a window, no hidden or transparent ancestor
    /// (covered route, inactive tab) and inside the window bounds (cells
    /// scrolled away keep their place in the window but are not drawn).
    var isAnimationVisible: Bool {
        guard let window, !PamRouteCoverage.isCovered(self) else { return false }
        return convert(bounds, to: window).intersects(window.bounds)
    }

    func advanceAnimation(by delta: TimeInterval) {
        animationPlayer?.advance(by: delta)
    }

    func purgeAnimationFrames() {
        animationPlayer?.animation.purgeFrames()
    }

    private func showAnimationFrame(_ frame: CGImage) {
        // `original` stays the first frame (recycling, filters, tint). No
        // blur here: a blurred image does not animate.
        filterRequest &+= 1
        let image = UIImage(cgImage: frame)
        show(pamFilter.isIdentity ? image : pamFilter.apply(to: image, displayScale: window?.screen.scale ?? 2), blur: 0)
    }

    /// The unfiltered bitmap last assigned by the renderer.
    var originalImage: UIImage? { original }

    /// Blur σ of what is shown (0: sharp or nothing).
    private var shownBlurSigma: CGFloat = 0
    /// Bumped on every image or filter change: an older filter result is dropped.
    private var filterRequest = 0

    private func showFiltered(_ image: UIImage?) {
        filterRequest &+= 1
        guard let image else {
            show(nil, blur: 0)
            return
        }
        let filter = pamFilter
        guard !filter.isIdentity else {
            show(image, blur: 0)
            return
        }
        // Not laid out: the drawn scale (and so σ in pixels) is unknown;
        // layoutSubviews filters it once sized.
        if filter.blurSigma > 0, bounds.width <= 0 || bounds.height <= 0 {
            appliedBlurDrawScale = 0
            if shownBlurSigma <= 0 { show(nil, blur: 0) }
            return
        }
        let drawScale = blurDrawScale(for: image)
        if let cached = image.pamFiltered, cached.matches(filter, drawScale: drawScale) {
            show(cached.image, blur: filter.blurSigma)
            return
        }
        // Keep the pixels already shown unless they are sharp under a blur.
        if filter.blurSigma > 0, shownBlurSigma <= 0 { show(nil, blur: 0) }
        let request = filterRequest
        let displayScale = window?.screen.scale ?? 2
        // Weak: the job never keeps (nor releases off the main thread) the view.
        let target = PamWeakImageView(self)
        PamImageFilter.queue.async {
            let output = filter.apply(to: image, displayScale: displayScale, drawScale: drawScale)
            image.pamFiltered = PamFilteredImage(filter: filter, drawScale: drawScale, image: output)
            DispatchQueue.main.async {
                guard let view = target.view, view.filterRequest == request else { return }
                view.show(output, blur: filter.blurSigma)
            }
        }
    }

    private func show(_ image: UIImage?, blur: CGFloat) {
        shownBlurSigma = image == nil ? 0 : blur
        guard let image else {
            super.image = nil
            return
        }
        guard let pamTintColor else {
            super.image = image
            return
        }
        tintColor = pamTintColor
        super.image = image.withRenderingMode(.alwaysTemplate)
    }

    private func refilter() {
        guard let original else { return }
        showFiltered(original)
    }

    /// Draw scale the current blur was requested at (0 = no blur yet).
    private var appliedBlurDrawScale: CGFloat = 0

    private func blurDrawScale(for image: UIImage) -> CGFloat {
        guard pamFilter.blurSigma > 0 else { return 1 }
        let scale = PamImageFilter.drawScale(image: image.size, bounds: bounds.size, contentMode: contentMode)
        appliedBlurDrawScale = scale
        return scale
    }

    /// A blurred image laid out after it arrived (or resized) re-blurs at its drawn scale.
    override func layoutSubviews() {
        super.layoutSubviews()
        guard pamFilter.blurSigma > 0, let original, bounds.width > 0, bounds.height > 0 else { return }
        let scale = PamImageFilter.drawScale(image: original.size, bounds: bounds.size, contentMode: contentMode)
        if !PamFilteredImage.drawScaleMatches(appliedBlurDrawScale, scale) { refilter() }
    }
}
