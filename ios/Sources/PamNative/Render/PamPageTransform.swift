import QuartzCore
import UIKit

/// `ScrollPageTransform` values (PHP enum, starting at 1).
enum PamPageTransformKind: Int {
    case none = 0
    case cube = 1
}

/// One face of the cube pager for a page at relative position `t`
/// (-1 = one page to the left, 0 = front, 1 = right): it rotates around the
/// edge it shares with the neighbour (`pivotFraction` of its width) and
/// darkens by `shade` while it turns away. Same math as Android
/// `pamCubeFace` and the RN StoryCubePager `cubeFace`.
struct PamCubeFace: Equatable {
    let angleDegrees: CGFloat
    let pivotFraction: CGFloat
    let shade: CGFloat

    static let maxAngleDegrees: CGFloat = 90
    static let maxShade: CGFloat = 0.55
    /// Perspective of the cube, as a multiple of the page width.
    static let perspectiveWidths: CGFloat = 2.4

    static func at(_ t: CGFloat) -> PamCubeFace {
        let clamped = min(max(t, -1), 1)
        return PamCubeFace(
            angleDegrees: clamped * maxAngleDegrees,
            pivotFraction: clamped < 0 ? 1 : 0,
            shade: abs(clamped) * maxShade
        )
    }

    /// Rotation around the shared edge with perspective, for a page [width] wide.
    func transform(width: CGFloat) -> CATransform3D {
        guard angleDegrees != 0 else { return CATransform3DIdentity }
        // Pivot relative to the layer's centre (anchorPoint stays 0.5).
        let pivot = (pivotFraction - 0.5) * width
        var perspective = CATransform3DIdentity
        perspective.m34 = -1 / max(1, width * Self.perspectiveWidths)
        var transform = CATransform3DMakeTranslation(-pivot, 0, 0)
        transform = CATransform3DConcat(transform, CATransform3DMakeRotation(angleDegrees * .pi / 180, 0, 1, 0))
        transform = CATransform3DConcat(transform, CATransform3DMakeTranslation(pivot, 0, 0))
        return CATransform3DConcat(transform, perspective)
    }
}

/// Applies a per-page transform to the pages of a horizontal pager from its
/// content offset. It runs from the scroll view's `layoutSubviews` (every
/// offset change), so nothing works while the pager is still. Pages are the
/// node subviews of the single wider-than-the-viewport content row, or the
/// scroll view's own node subviews when the row was flattened.
final class PamPageTransformer {
    private(set) var kind: PamPageTransformKind = .none
    private var shades: [ObjectIdentifier: (page: UIView, layer: CALayer)] = [:]

    func setKind(_ value: Int, on scroll: UIScrollView) {
        let next = PamPageTransformKind(rawValue: value) ?? .none
        guard next != kind else { return }
        kind = next
        if next == .none { reset() }
    }

    func apply(to scroll: UIScrollView) {
        guard kind == .cube, scroll.bounds.width > 0 else { return }
        let row = pageRow(scroll)
        let rowOrigin = row === scroll ? 0 : row.frame.minX
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        var seen = Set<ObjectIdentifier>()
        for page in row.subviews where page.tag > 0 {
            let width = page.bounds.width
            guard width > 0 else { continue }
            let left = page.center.x - width / 2
            let t = (rowOrigin + left - scroll.contentOffset.x) / width
            let id = ObjectIdentifier(page)
            if abs(t) >= 1 {
                if shades[id] != nil { clear(page) }
                continue
            }
            #if DEBUG
            if PamListTrace.enabled, abs(t) > 0.001,
               abs(scroll.contentOffset.x.truncatingRemainder(dividingBy: max(1, scroll.bounds.width))) < 0.5 {
                // At rest on a page every face is either front (0) or aside (±1).
                NSLog("PamCube rest offset=%.1f page=%ld frameX=%.1f centerX=%.1f width=%.1f t=%.3f",
                      scroll.contentOffset.x, page.tag, page.frame.minX, page.center.x, width, t)
            }
            #endif
            let face = PamCubeFace.at(t)
            page.layer.transform = face.transform(width: width)
            shade(page, face.shade)
            seen.insert(id)
        }
        for (id, entry) in shades where !seen.contains(id) {
            if entry.page.superview !== row { clear(entry.page) }
        }
        CATransaction.commit()
    }

    func reset() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for entry in shades.values { clear(entry.page) }
        CATransaction.commit()
        shades.removeAll()
    }

    private func pageRow(_ scroll: UIScrollView) -> UIView {
        let nodes = scroll.subviews.filter { $0.tag > 0 }
        if nodes.count == 1, let only = nodes.first, only.bounds.width > scroll.bounds.width {
            return only
        }
        return scroll
    }

    private func shade(_ page: UIView, _ amount: CGFloat) {
        let id = ObjectIdentifier(page)
        let layer = shades[id]?.layer ?? {
            let overlay = CALayer()
            overlay.backgroundColor = UIColor.black.cgColor
            overlay.zPosition = 10_000
            overlay.opacity = 0
            page.layer.addSublayer(overlay)
            shades[id] = (page, overlay)
            return overlay
        }()
        layer.frame = page.bounds
        layer.opacity = Float(min(max(amount, 0), 1))
    }

    private func clear(_ page: UIView) {
        page.layer.transform = CATransform3DIdentity
        let id = ObjectIdentifier(page)
        shades[id]?.layer.removeFromSuperlayer()
        shades[id] = nil
    }
}
