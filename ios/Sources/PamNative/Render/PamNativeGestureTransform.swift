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

extension PamMotionTransform {
    var gesturePayload: [String: WireValue] {
        [
            "nativeScale": .decimal(Double(scaleX)),
            "nativeTranslationX": .decimal(Double(translateX)),
            "nativeTranslationY": .decimal(Double(translateY)),
        ]
    }
}
