import UIKit

/// Native gesture deltas applied to one surface without overwriting another
/// simultaneous gesture's scale, translation or rotation.
struct PamNativeGestureTransform {
    private(set) var base = PamMotionTransform()
    private var focalStart = CGPoint.zero

    /** Surfaces whose translation a focal pinch currently owns (shared by adjacent detectors). */
    static var focalZoomTargets = Set<ObjectIdentifier>()

    /** Bounded native gestures in progress per surface (adjacent pan + pinch share one). */
    static var boundedGestures = [ObjectIdentifier: Int]()

    mutating func begin(on view: UIView, focal: CGPoint? = nil) {
        base = PamMotionTarget.transform(of: view)
        if let focal {
            focalStart = focal
        }
    }

    /// `focal`/`pivot` (the fingers' centroid and the content's untranslated
    /// pivot, in the detector's coordinates) make a pinch keep the content point
    /// under the fingers and follow their movement.
    mutating func apply(
        on view: UIView,
        type: Int,
        translation: CGPoint,
        scale: CGFloat,
        rotation: CGFloat,
        minimumScale: CGFloat,
        maximumScale: CGFloat,
        translationLimitX: CGFloat,
        focal: CGPoint? = nil,
        pivot: CGPoint? = nil,
        bounds: Bool = false,
        contentAspect: CGFloat = 0
    ) -> PamMotionTransform {
        var current = PamMotionTarget.transform(of: view)
        switch type {
        case 2, 5:
            // The finger moves in screen points; the view translates in its
            // superview's space, which a scaled or rotated ancestor transforms.
            let translation = Self.parentSpaceVector(translation, for: view)
            if Self.focalZoomTargets.contains(ObjectIdentifier(view)) {
                // Resume panning from wherever the focal pinch leaves the content.
                base.translateX = current.translateX - translation.x
                base.translateY = current.translateY - translation.y
                return current
            }
            let x = base.translateX + translation.x
            current.translateX = translationLimitX > 0
                ? min(translationLimitX, max(-translationLimitX, x)) : x
            current.translateY = base.translateY + translation.y
            if bounds {
                // The content edge stops at the viewport edge; rebasing makes a
                // reversed drag move the content at once.
                let bounded = Self.bounded(current, in: view, contentAspect: contentAspect)
                base.translateX += bounded.translateX - current.translateX
                base.translateY += bounded.translateY - current.translateY
                current = bounded
            }
        case 3:
            let target = min(maximumScale, max(minimumScale, base.scaleX * scale))
            if let focal, let pivot {
                let ratio = target / max(base.scaleX, 0.0001)
                current.translateX = (focal.x - pivot.x) - (focalStart.x - pivot.x - base.translateX) * ratio
                current.translateY = (focal.y - pivot.y) - (focalStart.y - pivot.y - base.translateY) * ratio
            }
            current.scaleX = target
            current.scaleY = target
            if bounds {
                current = Self.bounded(current, in: view, contentAspect: contentAspect)
            }
        case 4:
            current.rotationDegrees = base.rotationDegrees + rotation * 180 / .pi
        default:
            break
        }
        PamMotionTarget.setTransform(current, on: view)
        return current
    }
}

extension PamNativeGestureTransform {
    static func containedContentSize(_ size: CGSize, aspect: CGFloat) -> CGSize {
        guard aspect > 0, size.width > 0, size.height > 0 else { return size }
        let width = min(size.width, size.height * aspect)
        return CGSize(width: width, height: width / aspect)
    }

    /// One axis of a bounded transform: the content (`content` long, centred
    /// in a `viewport`-long view scaled by `scale` around `pivot`) moves only
    /// while it covers the viewport; shorter content stays centred.
    static func boundedTranslation(
        _ translation: CGFloat,
        viewport: CGFloat,
        content: CGFloat,
        pivot: CGFloat,
        scale: CGFloat
    ) -> CGFloat {
        let contentStart = (viewport - content) / 2
        let start = pivot - (pivot - contentStart) * scale
        let end = pivot + (contentStart + content - pivot) * scale
        if end - start <= viewport { return viewport / 2 - (start + end) / 2 }
        return min(-start, max(viewport - end, translation))
    }

    static func bounded(
        _ transform: PamMotionTransform,
        in view: UIView,
        contentAspect: CGFloat,
        scale: CGFloat? = nil
    ) -> PamMotionTransform {
        let size = view.bounds.size
        guard size.width > 0, size.height > 0 else { return transform }
        let content = containedContentSize(size, aspect: contentAspect)
        let anchor = view.layer.anchorPoint
        var result = transform
        let applied = scale ?? transform.scaleX
        if let scale {
            result.scaleX = scale
            result.scaleY = scale
        }
        result.translateX = boundedTranslation(
            transform.translateX, viewport: size.width, content: content.width,
            pivot: anchor.x * size.width, scale: applied
        )
        result.translateY = boundedTranslation(
            transform.translateY, viewport: size.height, content: content.height,
            pivot: anchor.y * size.height, scale: applied
        )
        return result
    }

    /// Resting transform of a bounded surface on release: below (or barely
    /// above) 1x the centred identity, otherwise the scale with its
    /// translation inside the content bounds.
    /// Below this scale a native transform snaps back to identity; above it
    /// the content shows zoomed in and its pan owns the touch.
    static let zoomedPanMinimumScale: CGFloat = 1.02

    /// A native transform zoomed past the identity snap owns the drag over
    /// it: a navigator's swipe-back or a pager may not take that touch.
    static func zoomedContentOwnsPan(scale: CGFloat) -> Bool {
        scale > zoomedPanMinimumScale
    }

    static func settled(
        _ transform: PamMotionTransform,
        in view: UIView,
        contentAspect: CGFloat,
        maximumScale: CGFloat
    ) -> PamMotionTransform {
        var scale = min(max(1, maximumScale), max(1, transform.scaleX))
        if !zoomedContentOwnsPan(scale: scale) { scale = 1 }
        return bounded(transform, in: view, contentAspect: contentAspect, scale: scale)
    }

    /// A screen-space vector expressed in `view`'s superview coordinates: the
    /// inverse of the linear part (scale, rotation) of every ancestor's
    /// transform, from the superview up to the window.
    static func parentSpaceVector(_ vector: CGPoint, for view: UIView) -> CGPoint {
        var combined = CGAffineTransform.identity
        var ancestor = view.superview
        while let current = ancestor {
            let transform = current.layer.affineTransform()
            if !transform.isIdentity {
                combined = combined.concatenating(CGAffineTransform(a: transform.a, b: transform.b, c: transform.c, d: transform.d, tx: 0, ty: 0))
            }
            ancestor = current.superview
        }
        if combined.isIdentity { return vector }
        let determinant = combined.a * combined.d - combined.b * combined.c
        guard abs(determinant) > 0.000_001 else { return vector }
        return CGPoint(x: vector.x, y: vector.y).applying(combined.inverted())
    }
}

extension PamMotionTransform {
    var gesturePayload: [String: WireValue] {
        [
            "nativeScale": .decimal(Double(scaleX)),
            "nativeTranslationX": .decimal(Double(translateX)),
            "nativeTranslationY": .decimal(Double(translateY)),
        ]
    }
}
