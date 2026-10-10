import UIKit

/// Native gesture deltas applied to one surface without overwriting another
/// simultaneous gesture's scale, translation or rotation.
struct PamNativeGestureTransform {
    private(set) var base = PamMotionTransform()
    private var focalStart = CGPoint.zero

    /** Surfaces whose translation a focal pinch currently owns (shared by adjacent detectors). */
    static var focalZoomTargets = Set<ObjectIdentifier>()

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
        pivot: CGPoint? = nil
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
        case 3:
            let target = min(maximumScale, max(minimumScale, base.scaleX * scale))
            if let focal, let pivot {
                let ratio = target / max(base.scaleX, 0.0001)
                current.translateX = (focal.x - pivot.x) - (focalStart.x - pivot.x - base.translateX) * ratio
                current.translateY = (focal.y - pivot.y) - (focalStart.y - pivot.y - base.translateY) * ratio
            }
            current.scaleX = target
            current.scaleY = target
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
