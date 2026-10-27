import Foundation
import ImageIO
import ObjectiveC
import QuartzCore
import UIKit

/// Animated GIF, WebP, APNG and HEICS sources (animated emoji, GIF panel,
/// WebP stickers), the iOS counterpart of Android's `AnimatedImageDrawable`.
///
/// `UIImage(data:)` shows only the first frame of these files. Here the
/// encoded bytes stay in a `CGImageSource` and frames are decoded on demand,
/// at the covering display size (never the full sticker resolution), on a
/// background queue. Decoded frames live in a small per-animation cache:
/// short loops (an emoji is ~30 frames of ~100 px) keep every frame, long
/// GIFs keep a sliding window and decode the next frame ahead.
/// Like Android, animations loop forever.
final class PamAnimatedImage: @unchecked Sendable {
    /// Below this, browsers and Android treat a frame delay as "unset".
    static let minimumDelay: TimeInterval = 0.011
    static let defaultDelay: TimeInterval = 0.1
    /// Decoded frames one animation may keep (all frames when they fit).
    static let frameCacheBytes = 6 * 1_024 * 1_024

    let frameCount: Int
    let delays: [TimeInterval]
    let duration: TimeInterval
    /// Pixel size of a decoded frame.
    let pixelSize: CGSize
    private let source: CGImageSource
    private let maxPixelSize: CGFloat?
    private let frames = NSCache<NSNumber, CGImage>()
    private let lock = NSLock()

    private static let decodeQueue = DispatchQueue(
        label: "dev.pam.image.animated",
        qos: .utility
    )

    private init?(source: CGImageSource, targetSize: CGSize, scale: CGFloat, multiplier: CGFloat) {
        let count = CGImageSourceGetCount(source)
        guard count > 1 else { return nil }
        let sourceSize = PamImageDownsampling.pixelSize(source)
        guard sourceSize.width > 0, sourceSize.height > 0 else { return nil }
        self.source = source
        frameCount = count
        delays = (0..<count).map { Self.delay(source, index: $0) }
        duration = delays.reduce(0, +)
        if targetSize.width > 0, targetSize.height > 0 {
            maxPixelSize = PamImageDownsampling.coverMaxPixelSize(
                sourceSize: sourceSize,
                targetSize: targetSize,
                scale: scale,
                multiplier: multiplier
            )
        } else {
            // Unknown view size: cap animated frames at 1024 px.
            let longest = max(sourceSize.width, sourceSize.height)
            maxPixelSize = longest > 1_024 ? 1_024 : nil
        }
        if let maxPixelSize {
            let ratio = maxPixelSize / max(sourceSize.width, sourceSize.height)
            pixelSize = CGSize(
                width: max(1, (sourceSize.width * ratio).rounded()),
                height: max(1, (sourceSize.height * ratio).rounded())
            )
        } else {
            pixelSize = sourceSize
        }
        frames.totalCostLimit = Self.frameCacheBytes
    }

    /// Animated `UIImage` (first frame as its bitmap, the animation attached)
    /// or nil when the bytes are not an animated image.
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

    static func image(
        source: CGImageSource,
        targetSize: CGSize,
        scale: CGFloat,
        multiplier: CGFloat
    ) -> UIImage? {
        guard let animation = PamAnimatedImage(
            source: source,
            targetSize: targetSize,
            scale: scale,
            multiplier: multiplier
        ), let poster = animation.decodeFrame(0) else { return nil }
        let image = UIImage(cgImage: poster)
        image.pamAnimation = animation
        return image
    }

    /// Frame shown `elapsed` seconds into the (infinitely looping) animation.
    static func frameIndex(elapsed: TimeInterval, delays: [TimeInterval]) -> Int {
        guard delays.count > 1 else { return 0 }
        let total = delays.reduce(0, +)
        guard total > 0, elapsed.isFinite, elapsed > 0 else { return 0 }
        var remainder = elapsed.truncatingRemainder(dividingBy: total)
        for (index, delay) in delays.enumerated() {
            if remainder < delay { return index }
            remainder -= delay
        }
        return delays.count - 1
    }

    /// Browser/Android rule: delays below 11 ms (often 0) play at 100 ms.
    static func normalizedDelay(_ value: Double?) -> TimeInterval {
        guard let value, value.isFinite, value >= minimumDelay else { return defaultDelay }
        return value
    }

    private static func delay(_ source: CGImageSource, index: Int) -> TimeInterval {
        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any] else {
            return defaultDelay
        }
        let containers: [(CFString, CFString, CFString)] = [
            (kCGImagePropertyGIFDictionary, kCGImagePropertyGIFUnclampedDelayTime, kCGImagePropertyGIFDelayTime),
            (kCGImagePropertyWebPDictionary, kCGImagePropertyWebPUnclampedDelayTime, kCGImagePropertyWebPDelayTime),
            (kCGImagePropertyPNGDictionary, kCGImagePropertyAPNGUnclampedDelayTime, kCGImagePropertyAPNGDelayTime),
            (kCGImagePropertyHEICSDictionary, kCGImagePropertyHEICSUnclampedDelayTime, kCGImagePropertyHEICSDelayTime),
        ]
        for (container, unclamped, clamped) in containers {
            guard let values = properties[container] as? [CFString: Any] else { continue }
            let raw = (values[unclamped] as? NSNumber)?.doubleValue
                ?? (values[clamped] as? NSNumber)?.doubleValue
            return normalizedDelay(raw)
        }
        return defaultDelay
    }

    /// Cached frame, or nil when it still has to be decoded.
    func cachedFrame(_ index: Int) -> CGImage? {
        frames.object(forKey: NSNumber(value: index))
    }

    /// Decodes (or returns the cached) frame on the calling thread.
    func decodeFrame(_ index: Int) -> CGImage? {
        guard index >= 0, index < frameCount else { return nil }
        if let cached = cachedFrame(index) { return cached }
        lock.lock()
        defer { lock.unlock() }
        if let cached = cachedFrame(index) { return cached }
        let image: CGImage?
        if let maxPixelSize {
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: max(maxPixelSize, 1),
                kCGImageSourceShouldCacheImmediately: true,
            ]
            image = CGImageSourceCreateThumbnailAtIndex(source, index, options as CFDictionary)
        } else {
            let options = [kCGImageSourceShouldCacheImmediately: true] as CFDictionary
            image = CGImageSourceCreateImageAtIndex(source, index, options)
        }
        if let image {
            frames.setObject(image, forKey: NSNumber(value: index), cost: image.bytesPerRow * image.height)
        }
        return image
    }

    /// Decodes `index` off the main thread; `completion` runs on main.
    func decodeFrameAsync(_ index: Int, completion: @escaping (CGImage?) -> Void) {
        Self.decodeQueue.async { [self] in
            let image = decodeFrame(index)
            DispatchQueue.main.async { completion(image) }
        }
    }

    /// Drops decoded frames (memory warning); the source bytes stay.
    func purgeFrames() {
        frames.removeAllObjects()
    }
}

private var pamAnimationKey: UInt8 = 0

extension UIImage {
    /// The animation behind an animated image decoded by the renderer.
    var pamAnimation: PamAnimatedImage? {
        get { objc_getAssociatedObject(self, &pamAnimationKey) as? PamAnimatedImage }
        set { objc_setAssociatedObject(self, &pamAnimationKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC) }
    }
}

/// Plays one `PamAnimatedImage` in an image view. Time only advances while
/// the view is really on screen (in a window, not hidden or transparent,
/// intersecting the window): off-screen cells, covered routes and the
/// background app neither decode nor draw (lean-resources).
final class PamAnimatedImagePlayer {
    let animation: PamAnimatedImage
    private(set) var frameIndex = 0
    private(set) var elapsed: TimeInterval = 0
    private var decoding = false
    private let display: (CGImage) -> Void

    init(animation: PamAnimatedImage, display: @escaping (CGImage) -> Void) {
        self.animation = animation
        self.display = display
    }

    /// Advances by `delta` seconds of visible time.
    func advance(by delta: TimeInterval) {
        guard delta > 0, delta.isFinite else { return }
        // A long hiccup (debugger, app switch) never fast-forwards many loops.
        elapsed += min(delta, 1)
        if animation.duration > 0 {
            elapsed = elapsed.truncatingRemainder(dividingBy: animation.duration)
        }
        let target = PamAnimatedImage.frameIndex(elapsed: elapsed, delays: animation.delays)
        guard target != frameIndex else { return }
        show(target)
    }

    private func show(_ index: Int) {
        if let frame = animation.cachedFrame(index) {
            frameIndex = index
            display(frame)
            prefetch((index + 1) % animation.frameCount)
            return
        }
        // Not decoded yet: keep the current frame and draw it when ready.
        guard !decoding else { return }
        decoding = true
        animation.decodeFrameAsync(index) { [weak self] frame in
            guard let self else { return }
            self.decoding = false
            guard let frame else { return }
            self.frameIndex = index
            self.display(frame)
            self.prefetch((index + 1) % self.animation.frameCount)
        }
    }

    private func prefetch(_ index: Int) {
        guard animation.cachedFrame(index) == nil, !decoding else { return }
        decoding = true
        animation.decodeFrameAsync(index) { [weak self] _ in self?.decoding = false }
    }
}

/// One display link (30 Hz) for every animated image on screen; it stops
/// when no animated image is attached to a window.
final class PamAnimatedImageClock: NSObject {
    static let shared = PamAnimatedImageClock()

    private let views = NSHashTable<PamImageView>.weakObjects()
    private var link: CADisplayLink?
    private var lastTimestamp: CFTimeInterval = 0
    private var memoryObserver: NSObjectProtocol?

    var isRunning: Bool { link != nil }
    var registeredCount: Int { views.allObjects.count }

    func register(_ view: PamImageView) {
        views.add(view)
        guard link == nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.preferredFramesPerSecond = 30
        link.add(to: .main, forMode: .common)
        self.link = link
        lastTimestamp = 0
        if memoryObserver == nil {
            memoryObserver = NotificationCenter.default.addObserver(
                forName: UIApplication.didReceiveMemoryWarningNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.views.allObjects.forEach { $0.purgeAnimationFrames() }
            }
        }
    }

    func unregister(_ view: PamImageView) {
        views.remove(view)
        if views.allObjects.isEmpty { stop() }
    }

    private func stop() {
        link?.invalidate()
        link = nil
        lastTimestamp = 0
    }

    @objc private func tick(_ link: CADisplayLink) {
        let delta = lastTimestamp == 0 ? 0 : link.timestamp - lastTimestamp
        lastTimestamp = link.timestamp
        step(by: delta)
    }

    /// One clock step (exposed for tests).
    func step(by delta: TimeInterval) {
        let active = views.allObjects
        if active.isEmpty {
            stop()
            return
        }
        for view in active where view.isAnimationVisible {
            view.advanceAnimation(by: delta)
        }
    }
}
