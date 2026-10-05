import ObjectiveC
import UIKit

/// One CSS box-shadow in points. Blur follows CSS (Gaussian σ = blur / 2).
struct PamBoxShadow: Equatable {
    var offsetX: CGFloat
    var offsetY: CGFloat
    var blurRadius: CGFloat
    var spreadRadius: CGFloat
    var color: Int64
    var inset = false

    /// Parses the compiler's `boxShadows` wire list
    /// (`[[x, y, blur, spread, argb, inset], …]`, points).
    static func parse(_ wire: String?) -> [PamBoxShadow] {
        guard let wire, !wire.isEmpty, let data = wire.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[Any]] else { return [] }
        func number(_ item: [Any], _ index: Int) -> CGFloat {
            index < item.count ? (item[index] as? NSNumber).map { CGFloat(truncating: $0) } ?? 0 : 0
        }
        return array.compactMap { item in
            guard item.count >= 5, let color = item[4] as? NSNumber else { return nil }
            return PamBoxShadow(
                offsetX: number(item, 0),
                offsetY: number(item, 1),
                blurRadius: max(0, number(item, 2)),
                spreadRadius: number(item, 3),
                color: color.int64Value,
                inset: item.count > 5 && (item[5] as? NSNumber)?.intValue == 1
            )
        }
    }
}

/// Everything the renderer paints for a box beyond its background color.
struct PamPaintSpec: Equatable {
    var radii = PamCornerRadii.zero
    /// left, top, right, bottom
    var borderWidths: [CGFloat] = [0, 0, 0, 0]
    /// left, top, right, bottom (ARGB)
    var borderColors: [Int64] = [0, 0, 0, 0]
    /// 1 solid, 2 dashed, 3 dotted
    var borderStyle = 1
    var gradients: [PamGradientLayer] = []
    var borderGradient: PamGradientLayer?
    var shadows: [PamBoxShadow] = []
    var clips = false
    /// Clip regardless of `overflow` (images clip to their radius like React Native).
    var clipsContent = false

    var outerShadows: [PamBoxShadow] { shadows.filter { !$0.inset && PamARGB.alpha($0.color) > 0 } }
    var insetShadows: [PamBoxShadow] { shadows.filter { $0.inset && PamARGB.alpha($0.color) > 0 } }

    var hasBorder: Bool { borderWidths.contains { $0 > 0 } }

    var uniformBorder: Bool {
        let width = borderWidths[0]
        guard borderWidths.allSatisfy({ $0 == width }) else { return false }
        let painted = (0..<4).filter { borderWidths[$0] > 0 }.map { borderColors[$0] }
        return Set(painted).count <= 1
    }

    /// Corner radii that `CALayer.cornerRadius` + `maskedCorners` can express.
    var layerCorners: (radius: CGFloat, corners: CACornerMask)? {
        let values = [radii.topLeft, radii.topRight, radii.bottomRight, radii.bottomLeft]
        let nonZero = values.filter { $0 > 0 }
        guard let radius = nonZero.first else { return (0, [.layerMinXMinYCorner, .layerMaxXMinYCorner, .layerMinXMaxYCorner, .layerMaxXMaxYCorner]) }
        guard nonZero.allSatisfy({ $0 == radius }) else { return nil }
        var mask: CACornerMask = []
        if radii.topLeft > 0 { mask.insert(.layerMinXMinYCorner) }
        if radii.topRight > 0 { mask.insert(.layerMaxXMinYCorner) }
        if radii.bottomRight > 0 { mask.insert(.layerMaxXMaxYCorner) }
        if radii.bottomLeft > 0 { mask.insert(.layerMinXMaxYCorner) }
        return (radius, mask)
    }
}

/// Per-view paint state: gradient/inset-shadow layer below the children,
/// border layer above them, outer shadows (in the view or, when the view
/// clips, as a sibling right below it), shimmer and backdrop blur.
final class PamViewEffects {
    private static var key: UInt8 = 0

    private weak var view: UIView?
    private var spec = PamPaintSpec()
    private var appliedSize = CGSize(width: -1, height: -1)
    private var paintLayer: PamPaintLayer?
    private var borderLayer: PamPaintLayer?
    private var dashedLayer: CAShapeLayer?
    private var maskLayer: CAShapeLayer?
    private var shadowContainer: CALayer?
    private var insetContainer: CALayer?
    private var shadowInSibling = false
    private var shimmer: PamShimmerLayer?
    private(set) var backdrop: PamBackdropView?

    /// Clipping the view had before any CSS paint (scroll views always clip).
    private let baseClips: Bool

    private init(view: UIView) {
        self.view = view
        baseClips = view.layer.masksToBounds
    }

    static func of(_ view: UIView, create: Bool = true) -> PamViewEffects? {
        if let existing = objc_getAssociatedObject(view, &key) as? PamViewEffects { return existing }
        guard create else { return nil }
        let effects = PamViewEffects(view: view)
        objc_setAssociatedObject(view, &key, effects, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        return effects
    }

    /// Removes every layer the effects added (view recycled or removed).
    static func reset(_ view: UIView) {
        guard let effects = of(view, create: false) else { return }
        effects.apply(PamPaintSpec(), force: true)
        effects.setShimmer(enabled: false, color: 0, durationMs: 0)
        effects.setBackdrop(radius: 0)
        effects.shadowContainer?.removeFromSuperlayer()
        effects.shadowContainer = nil
        effects.insetContainer?.removeFromSuperlayer()
        effects.insetContainer = nil
    }

    // MARK: Paint

    func apply(_ next: PamPaintSpec, force: Bool = false) {
        guard let view else { return }
        let size = view.bounds.size
        guard force || next != spec || size != appliedSize else {
            syncSiblingShadow()
            return
        }
        spec = next
        appliedSize = size
        let layer = view.layer
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        defer { CATransaction.commit() }

        // Corners and clipping.
        let corners = next.layerCorners
        let wantsClip = next.clips || next.clipsContent || baseClips
        if let corners {
            layer.cornerRadius = corners.radius
            layer.maskedCorners = corners.corners
            if maskLayer != nil {
                layer.mask = nil
                maskLayer = nil
            }
            layer.masksToBounds = wantsClip
        } else {
            // Mixed radii: a path mask clips the background, border and children.
            layer.cornerRadius = 0
            layer.masksToBounds = false
            let mask = maskLayer ?? CAShapeLayer()
            mask.frame = layer.bounds
            mask.path = next.radii.path(in: layer.bounds)
            layer.mask = mask
            maskLayer = mask
        }
        let clipsSelf = layer.masksToBounds || layer.mask != nil

        // Background gradients and inset shadows (below the children).
        if !next.gradients.isEmpty {
            let paint = paintLayer ?? PamPaintLayer()
            if paint.superlayer !== layer {
                paint.zPosition = -1_000
                paint.contentsScale = UIScreen.main.scale
                paint.needsDisplayOnBoundsChange = true
                layer.insertSublayer(paint, at: 0)
            }
            paint.frame = layer.bounds
            paint.mode = .background(next)
            paint.setNeedsDisplay()
            paintLayer = paint
        } else if let paint = paintLayer {
            paint.removeFromSuperlayer()
            paintLayer = nil
        }

        applyInsetShadows(next, layer: layer)
        applyBorder(next, layer: layer)
        applyOuterShadows(next, clipsSelf: clipsSelf)
        shimmer?.update(bounds: layer.bounds, radii: next.radii)
        backdrop?.update(radii: next.radii)
    }

    private func applyBorder(_ next: PamPaintSpec, layer: CALayer) {
        let simple = next.uniformBorder && next.borderStyle == 1 && next.borderGradient == nil
            && next.layerCorners != nil
        if simple || !next.hasBorder {
            let width = next.hasBorder ? next.borderWidths.first(where: { $0 > 0 }) ?? 0 : 0
            let index = next.borderWidths.firstIndex(where: { $0 > 0 }) ?? 0
            layer.borderWidth = width
            layer.borderColor = width > 0 ? PamARGB.cgColor(next.borderColors[index]) : nil
            borderLayer?.removeFromSuperlayer()
            borderLayer = nil
            dashedLayer?.removeFromSuperlayer()
            dashedLayer = nil
            return
        }
        layer.borderWidth = 0
        layer.borderColor = nil
        if next.borderStyle != 1, next.uniformBorder, next.borderGradient == nil {
            borderLayer?.removeFromSuperlayer()
            borderLayer = nil
            let width = next.borderWidths[0]
            let dashed = dashedLayer ?? CAShapeLayer()
            if dashed.superlayer !== layer {
                dashed.zPosition = 10_000
                layer.addSublayer(dashed)
            }
            let inset = width / 2
            dashed.frame = layer.bounds
            dashed.path = next.radii.spread(-inset).path(in: layer.bounds.insetBy(dx: inset, dy: inset))
            dashed.fillColor = UIColor.clear.cgColor
            let color = next.borderColors.first(where: { PamARGB.alpha($0) > 0 }) ?? next.borderColors[0]
            dashed.strokeColor = PamARGB.cgColor(color)
            dashed.lineWidth = width
            dashed.lineCap = next.borderStyle == 3 ? .round : .butt
            dashed.lineDashPattern = next.borderStyle == 3
                ? [NSNumber(value: Double(width)), NSNumber(value: Double(width * 1.5))]
                : [NSNumber(value: Double(width * 3)), NSNumber(value: Double(width * 2))]
            dashedLayer = dashed
            return
        }
        dashedLayer?.removeFromSuperlayer()
        dashedLayer = nil
        let border = borderLayer ?? PamPaintLayer()
        if border.superlayer !== layer {
            border.zPosition = 10_000
            border.contentsScale = UIScreen.main.scale
            border.needsDisplayOnBoundsChange = true
            layer.addSublayer(border)
        }
        border.frame = layer.bounds
        border.mode = .border(next)
        border.setNeedsDisplay()
        borderLayer = border
    }

    private func applyOuterShadows(_ next: PamPaintSpec, clipsSelf: Bool) {
        guard let view else { return }
        let shadows = next.outerShadows
        guard !shadows.isEmpty else {
            shadowContainer?.removeFromSuperlayer()
            shadowContainer = nil
            return
        }
        let container = shadowContainer ?? CALayer()
        shadowContainer = container
        shadowInSibling = clipsSelf
        let bounds = view.bounds
        container.sublayers?.forEach { $0.removeFromSuperlayer() }
        // CSS paints the first shadow on top: add the last one first.
        for shadow in shadows.reversed() {
            container.addSublayer(Self.outerShadowLayer(shadow, bounds: bounds, radii: next.radii))
        }
        if clipsSelf {
            syncSiblingShadow()
        } else {
            if container.superlayer !== view.layer {
                container.removeFromSuperlayer()
                container.zPosition = -2_000
                view.layer.insertSublayer(container, at: 0)
            }
            container.transform = CATransform3DIdentity
            container.frame = bounds
            container.opacity = 1
            container.isHidden = false
        }
    }

    /// Keeps a sibling shadow aligned with its (clipping) view.
    func syncSiblingShadow() {
        guard shadowInSibling, let container = shadowContainer, let view else { return }
        guard let superlayer = view.layer.superlayer else {
            container.removeFromSuperlayer()
            return
        }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        if container.superlayer !== superlayer || container.zPosition != view.layer.zPosition {
            container.removeFromSuperlayer()
            container.zPosition = view.layer.zPosition
            superlayer.insertSublayer(container, below: view.layer)
        } else if let sublayers = superlayer.sublayers,
                  let containerIndex = sublayers.firstIndex(where: { $0 === container }),
                  let viewIndex = sublayers.firstIndex(where: { $0 === view.layer }),
                  containerIndex != viewIndex - 1 {
            container.removeFromSuperlayer()
            superlayer.insertSublayer(container, below: view.layer)
        }
        container.bounds = view.layer.bounds
        container.anchorPoint = view.layer.anchorPoint
        container.position = view.layer.position
        container.transform = view.layer.transform
        container.opacity = view.layer.opacity
        container.isHidden = view.isHidden
        CATransaction.commit()
    }

    /// Inset shadows: a ring around the (offset, spread-shrunk) padding box
    /// casts its blurred shadow into the padding box, clipped to it.
    private func applyInsetShadows(_ next: PamPaintSpec, layer: CALayer) {
        let shadows = next.insetShadows
        guard !shadows.isEmpty else {
            insetContainer?.removeFromSuperlayer()
            insetContainer = nil
            return
        }
        let container = insetContainer ?? CALayer()
        if container.superlayer !== layer {
            container.zPosition = -900
            layer.insertSublayer(container, at: 0)
        }
        insetContainer = container
        let bounds = layer.bounds
        container.frame = bounds
        let widths = next.borderWidths
        let inner = CGRect(
            x: widths[0],
            y: widths[1],
            width: max(0, bounds.width - widths[0] - widths[2]),
            height: max(0, bounds.height - widths[1] - widths[3])
        )
        let fitted = next.radii.fitted(to: bounds.size)
        let corners = fitted.inset(left: widths[0], top: widths[1], right: widths[2], bottom: widths[3])
        let clip = CAShapeLayer()
        clip.frame = bounds
        clip.path = PamCornerRadii.path(
            in: inner,
            topLeft: corners.0,
            topRight: corners.1,
            bottomRight: corners.2,
            bottomLeft: corners.3
        )
        container.mask = clip
        container.sublayers?.forEach { $0.removeFromSuperlayer() }
        let innerRadii = PamCornerRadii(
            topLeft: corners.0.width,
            topRight: corners.1.width,
            bottomRight: corners.2.width,
            bottomLeft: corners.3.width
        )
        for shadow in shadows.reversed() {
            container.addSublayer(Self.insetShadowLayer(shadow, bounds: bounds, inner: inner, radii: innerRadii))
        }
    }

    static func insetShadowLayer(_ shadow: PamBoxShadow, bounds: CGRect, inner: CGRect, radii: PamCornerRadii) -> CALayer {
        let layer = CALayer()
        layer.frame = bounds
        let hole = inner.insetBy(dx: shadow.spreadRadius, dy: shadow.spreadRadius)
        let reach = shadow.blurRadius * 2 + abs(shadow.offsetX) + abs(shadow.offsetY) + abs(shadow.spreadRadius) + 8
        let outer = inner.insetBy(dx: -reach, dy: -reach)
        let ring = CGMutablePath()
        // Counter-clockwise outer contour + clockwise hole: non-zero winding leaves the hole empty.
        ring.move(to: CGPoint(x: outer.minX, y: outer.minY))
        ring.addLine(to: CGPoint(x: outer.minX, y: outer.maxY))
        ring.addLine(to: CGPoint(x: outer.maxX, y: outer.maxY))
        ring.addLine(to: CGPoint(x: outer.maxX, y: outer.minY))
        ring.closeSubpath()
        if hole.width > 0, hole.height > 0 {
            ring.addPath(radii.spread(-shadow.spreadRadius).path(in: hole))
        }
        layer.shadowPath = ring
        layer.shadowColor = PamARGB.cgColor(shadow.color | Int64(0xFF00_0000))
        layer.shadowOpacity = Float(PamARGB.alpha(shadow.color))
        layer.shadowOffset = CGSize(width: shadow.offsetX, height: shadow.offsetY)
        layer.shadowRadius = shadow.blurRadius / 2
        return layer
    }

    static func outerShadowLayer(_ shadow: PamBoxShadow, bounds: CGRect, radii: PamCornerRadii) -> CALayer {
        let layer = CALayer()
        layer.frame = bounds
        let spread = shadow.spreadRadius
        let shape = bounds.insetBy(dx: -spread, dy: -spread)
        layer.shadowPath = radii.fitted(to: bounds.size).spread(spread).path(in: shape)
        layer.shadowColor = PamARGB.cgColor(shadow.color | Int64(0xFF00_0000))
        layer.shadowOpacity = Float(PamARGB.alpha(shadow.color))
        layer.shadowOffset = CGSize(width: shadow.offsetX, height: shadow.offsetY)
        layer.shadowRadius = shadow.blurRadius / 2
        // CSS clips an outer shadow to the outside of the border box.
        let reach = shadow.blurRadius * 2 + abs(shadow.offsetX) + abs(shadow.offsetY) + abs(spread) + 4
        let outside = CGMutablePath()
        outside.addRect(bounds.insetBy(dx: -reach, dy: -reach))
        outside.addPath(radii.path(in: bounds))
        let mask = CAShapeLayer()
        mask.frame = bounds
        mask.path = outside
        mask.fillRule = .evenOdd
        layer.mask = mask
        return layer
    }

    // MARK: Shimmer

    func setShimmer(enabled: Bool, color: Int64, durationMs: Int64) {
        guard let view else { return }
        guard enabled else {
            shimmer?.removeFromSuperlayer()
            shimmer = nil
            return
        }
        let layer = shimmer ?? PamShimmerLayer()
        if layer.superlayer !== view.layer {
            layer.zPosition = -500
            view.layer.insertSublayer(layer, at: 0)
        }
        shimmer = layer
        layer.configure(color: color, duration: Double(max(1, durationMs)) / 1_000)
        layer.update(bounds: view.layer.bounds, radii: spec.radii)
    }

    // MARK: Backdrop

    func setBackdrop(radius: CGFloat) {
        guard let view else { return }
        guard radius > 0 else {
            if let backdrop {
                backdrop.teardown()
                backdrop.removeFromSuperview()
                if let tint = backdrop.tint { view.backgroundColor = tint }
                self.backdrop = nil
            }
            return
        }
        let backdrop = self.backdrop ?? PamBackdropView()
        if backdrop.superview !== view {
            backdrop.frame = view.bounds
            backdrop.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            backdrop.layer.zPosition = -1_500
            view.insertSubview(backdrop, at: 0)
            // The element background is drawn above the filtered backdrop.
            backdrop.tint = view.backgroundColor
            view.backgroundColor = .clear
        }
        self.backdrop = backdrop
        backdrop.setRadius(radius)
        backdrop.update(radii: spec.radii)
    }

    /// Background color routed above an active backdrop blur.
    func setBackgroundColor(_ color: UIColor?) -> Bool {
        guard let backdrop else { return false }
        backdrop.tint = color
        return true
    }
}

/// Draws CSS background gradients, inset shadows and complex borders.
final class PamPaintLayer: CALayer {
    enum Mode {
        case empty
        case background(PamPaintSpec)
        case border(PamPaintSpec)
    }

    var mode: Mode = .empty

    override init() {
        super.init()
        isOpaque = false
    }

    override init(layer: Any) {
        super.init(layer: layer)
        if let other = layer as? PamPaintLayer { mode = other.mode }
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    override func action(forKey event: String) -> CAAction? { nil }

    override func draw(in context: CGContext) {
        let rect = bounds
        guard rect.width > 0, rect.height > 0 else { return }
        switch mode {
        case .empty:
            return
        case let .background(spec):
            drawBackground(spec, rect: rect, context: context)
        case let .border(spec):
            drawBorder(spec, rect: rect, context: context)
        }
    }

    private func drawBackground(_ spec: PamPaintSpec, rect: CGRect, context: CGContext) {
        let outer = spec.radii.path(in: rect)
        if !spec.gradients.isEmpty {
            context.saveGState()
            context.addPath(outer)
            context.clip()
            // The first CSS layer is on top: paint last-to-first.
            for gradient in spec.gradients.reversed() {
                gradient.draw(in: context, size: rect.size)
            }
            context.restoreGState()
        }
    }

    private func drawBorder(_ spec: PamPaintSpec, rect: CGRect, context: CGContext) {
        let widths = spec.borderWidths
        let fitted = spec.radii.fitted(to: rect.size)
        let outer = fitted.path(in: rect)
        let inner = CGRect(
            x: widths[0],
            y: widths[1],
            width: max(0, rect.width - widths[0] - widths[2]),
            height: max(0, rect.height - widths[1] - widths[3])
        )
        let innerCorners = fitted.inset(left: widths[0], top: widths[1], right: widths[2], bottom: widths[3])
        let ring = CGMutablePath()
        ring.addPath(outer)
        if inner.width > 0, inner.height > 0 {
            ring.addPath(PamCornerRadii.path(
                in: inner,
                topLeft: innerCorners.0,
                topRight: innerCorners.1,
                bottomRight: innerCorners.2,
                bottomLeft: innerCorners.3
            ))
        }
        if let gradient = spec.borderGradient {
            context.saveGState()
            context.addPath(ring)
            context.clip(using: .evenOdd)
            gradient.draw(in: context, size: rect.size)
            context.restoreGState()
            return
        }
        // Each side owns the trapezoid between its outer and inner corners (CSS miter joins).
        let outerCorners = [
            CGPoint(x: rect.minX, y: rect.minY),
            CGPoint(x: rect.maxX, y: rect.minY),
            CGPoint(x: rect.maxX, y: rect.maxY),
            CGPoint(x: rect.minX, y: rect.maxY),
        ]
        let innerPoints = [
            CGPoint(x: inner.minX, y: inner.minY),
            CGPoint(x: inner.maxX, y: inner.minY),
            CGPoint(x: inner.maxX, y: inner.maxY),
            CGPoint(x: inner.minX, y: inner.maxY),
        ]
        // side index: 0 left (corners 3,0), 1 top (0,1), 2 right (1,2), 3 bottom (2,3)
        let sideCorners = [(3, 0), (0, 1), (1, 2), (2, 3)]
        for side in 0..<4 where widths[side] > 0 && PamARGB.alpha(spec.borderColors[side]) > 0 {
            let (a, b) = sideCorners[side]
            let trapezoid = CGMutablePath()
            trapezoid.move(to: outerCorners[a])
            trapezoid.addLine(to: outerCorners[b])
            trapezoid.addLine(to: innerPoints[b])
            trapezoid.addLine(to: innerPoints[a])
            trapezoid.closeSubpath()
            context.saveGState()
            context.addPath(trapezoid)
            context.clip()
            context.addPath(ring)
            context.setFillColor(PamARGB.cgColor(spec.borderColors[side]))
            context.fillPath(using: .evenOdd)
            context.restoreGState()
        }
    }
}

/// `<Shimmer>`: a soft highlight strip 1.25× the box width sweeps left → right
/// once per duration over the background and below the children (Core
/// Animation drives it off the main thread).
final class PamShimmerLayer: CALayer {
    private let strip = CAGradientLayer()
    private let clip = CAShapeLayer()
    private var color: Int64 = 0x59FF_FFFF
    private var duration: CFTimeInterval = 1.2
    private var animatedWidth: CGFloat = -1

    override init() {
        super.init()
        strip.startPoint = CGPoint(x: 0, y: 0.5)
        strip.endPoint = CGPoint(x: 1, y: 0.5)
        strip.locations = [0, 0.35, 0.5, 0.65, 1]
        addSublayer(strip)
        mask = clip
    }

    override init(layer: Any) {
        super.init(layer: layer)
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    func configure(color: Int64, duration: CFTimeInterval) {
        let changed = color != self.color || duration != self.duration
        self.color = color
        self.duration = duration
        let parts = PamARGB.components(color)
        func tone(_ factor: CGFloat) -> CGColor {
            UIColor(red: parts.r, green: parts.g, blue: parts.b, alpha: parts.a * factor).cgColor
        }
        strip.colors = [tone(0), tone(0.24), tone(1), tone(0.24), tone(0)]
        if changed { animatedWidth = -1 }
    }

    func update(bounds: CGRect, radii: PamCornerRadii) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        frame = bounds
        clip.frame = bounds
        clip.path = radii.path(in: bounds)
        let width = max(1, bounds.width * 1.25)
        strip.bounds = CGRect(x: 0, y: 0, width: width, height: bounds.height)
        strip.position = CGPoint(x: -width / 2, y: bounds.height / 2)
        CATransaction.commit()
        guard bounds.width > 0, animatedWidth != bounds.width else { return }
        animatedWidth = bounds.width
        let sweep = CABasicAnimation(keyPath: "position.x")
        sweep.fromValue = -width / 2
        sweep.toValue = bounds.width + width / 2
        sweep.duration = duration
        sweep.repeatCount = .infinity
        sweep.isRemovedOnCompletion = false
        strip.removeAnimation(forKey: "pam.shimmer")
        if !PamMotionPolicy.isReduced {
            strip.add(sweep, forKey: "pam.shimmer")
        }
    }
}

/// `backdrop-filter: blur()` behind a container: a system blur whose strength
/// is scrubbed to the CSS radius with a paused property animator.
final class PamBackdropView: UIVisualEffectView, PamAuxiliaryView {
    private var animator: UIViewPropertyAnimator?
    private var radius: CGFloat = -1
    private let shape = CAShapeLayer()
    var tint: UIColor? {
        didSet { contentView.backgroundColor = tint }
    }

    init() {
        super.init(effect: nil)
        isUserInteractionEnabled = false
        layer.mask = shape
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    deinit {
        teardown()
    }

    /// UIBlurEffect `.regular` corresponds to roughly a 30 pt Gaussian blur.
    static let fullRadius: CGFloat = 30

    func setRadius(_ value: CGFloat) {
        guard value != radius else { return }
        radius = value
        teardown()
        effect = nil
        let animator = UIViewPropertyAnimator(duration: 1, curve: .linear) { [weak self] in
            self?.effect = UIBlurEffect(style: .regular)
        }
        animator.pausesOnCompletion = true
        animator.fractionComplete = min(1, max(0, value / Self.fullRadius))
        self.animator = animator
    }

    func update(radii: PamCornerRadii) {
        shape.frame = bounds
        shape.path = radii.path(in: bounds)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        shape.frame = bounds
    }

    func teardown() {
        guard let animator else { return }
        if animator.state == .active {
            animator.stopAnimation(false)
        }
        if animator.state == .stopped {
            animator.finishAnimation(at: .current)
        }
        self.animator = nil
    }
}
