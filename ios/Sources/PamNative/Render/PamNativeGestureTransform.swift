import UIKit

/// Native gesture deltas applied to one surface without overwriting another
/// simultaneous gesture's scale, translation or rotation.
struct PamNativeGestureTransform {
    private(set) var base = PamMotionTransform()

    mutating func begin(on view: UIView) {
        base = PamMotionTarget.transform(of: view)
    }

    func apply(
        on view: UIView,
        type: Int,
        translation: CGPoint,
        scale: CGFloat,
        rotation: CGFloat,
        minimumScale: CGFloat,
        maximumScale: CGFloat,
        translationLimitX: CGFloat
    ) -> PamMotionTransform {
        var current = PamMotionTarget.transform(of: view)
        switch type {
        case 2, 5:
            let x = base.translateX + translation.x
            current.translateX = translationLimitX > 0
                ? min(translationLimitX, max(-translationLimitX, x)) : x
            current.translateY = base.translateY + translation.y
        case 3:
            let target = min(maximumScale, max(minimumScale, base.scaleX * scale))
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
