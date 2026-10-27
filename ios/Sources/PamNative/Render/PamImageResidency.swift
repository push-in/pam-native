import UIKit

/// Decoded photos shared by every image view, keyed by cache identity and
/// decoded pixel size (Android's decoded `LruCache`): two views of the same
/// photo at the same size, or a cell scrolled back into the window, reuse
/// one bitmap instead of decoding it again. Only bitmaps no view displays
/// count against the budget in practice: a displayed bitmap is pinned by
/// its view anyway.
final class PamDecodedImageCache: @unchecked Sendable {
    static let shared = PamDecodedImageCache()

    private let cache = NSCache<NSString, UIImage>()

    init(costLimit: Int = PamDecodedImageCache.defaultCostLimit()) {
        cache.totalCostLimit = costLimit
    }

    /// Half of the media memory budget (8-48 MB): decoded photos are about
    /// ten times their encoded size, so a few screens of them stay warm.
    static func defaultCostLimit(physicalMemory: UInt64 = ProcessInfo.processInfo.physicalMemory) -> Int {
        PamImageDownsampling.memoryCacheBytes(physicalMemory: physicalMemory) / 2
    }

    static func key(identity: String, targetSize: CGSize, scale: CGFloat, multiplier: CGFloat) -> String {
        let width = Int((targetSize.width * scale).rounded())
        let height = Int((targetSize.height * scale).rounded())
        return "\(identity)|\(width)x\(height)|\(multiplier)"
    }

    static func cost(_ image: UIImage) -> Int {
        guard let cgImage = image.cgImage else {
            return Int(image.size.width * image.size.height * image.scale * image.scale * 4)
        }
        return cgImage.bytesPerRow * cgImage.height
    }

    func image(forKey key: String) -> UIImage? {
        cache.object(forKey: key as NSString)
    }

    func store(_ image: UIImage, forKey key: String) {
        // Animated images keep their own frame cache; never pin them here.
        guard image.pamAnimation == nil else { return }
        cache.setObject(image, forKey: key as NSString, cost: Self.cost(image))
    }

    func removeAll() {
        cache.removeAllObjects()
    }
}

/// Whether a remote image view keeps its decoded pixels. Like Android's
/// `onShownChanged`, pixels of an image far from the screen (a feed cell or
/// an album page beyond the prefetch window, a covered route, an inactive
/// tab) are released and restored when it comes back near the screen: the
/// prefetch window is one screen on each side, so cells decode before they
/// are visible.
enum PamImageResidency {
    static let sweepRequested = Notification.Name("PamImageResidencySweepRequested")
    private static var pending = false
    /// Trailing throttle: a sweep at most every 120 ms while scrolling, and
    /// nothing at all while nothing moves.
    static let interval: TimeInterval = 0.12

    static func requestSweep() {
        guard !pending else { return }
        pending = true
        DispatchQueue.main.asyncAfter(deadline: .now() + interval) {
            pending = false
            NotificationCenter.default.post(name: sweepRequested, object: nil)
        }
    }

    /// True while `frame` (in window coordinates) lies within one screen of
    /// the window on each side.
    static func isNearScreen(_ frame: CGRect, window: CGRect) -> Bool {
        guard !frame.isEmpty else { return false }
        let near = window.insetBy(dx: -window.width, dy: -window.height)
        return frame.intersects(near)
    }

    /// Out of the window, hidden by itself or an ancestor (covered route,
    /// inactive tab), or far from the screen. Transparency does not count:
    /// an image fading in from opacity 0 must already hold its pixels.
    static func shouldHold(_ view: UIView) -> Bool {
        guard let window = view.window else { return false }
        var current: UIView? = view
        while let candidate = current {
            if candidate.isHidden { return false }
            current = candidate.superview
        }
        return isNearScreen(view.convert(view.bounds, to: window), window: window.bounds)
    }
}
