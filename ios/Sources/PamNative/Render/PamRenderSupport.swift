import UIKit

/// Typed, optional accessors shared by the renderer helpers (the renderer's
/// own private accessors are file-scoped).
extension PropValue {
    var pamText: String? {
        if case let .text(value) = self { return value }
        return nil
    }

    var pamInteger: Int64? {
        switch self {
        case let .integer(value): return value
        case let .decimal(value) where value.isFinite: return Int64(value)
        default: return nil
        }
    }

    var pamNumber: Double? {
        switch self {
        case let .decimal(value) where value.isFinite: return value
        case let .integer(value): return Double(value)
        default: return nil
        }
    }

    var pamFlag: Bool? {
        if case let .flag(value) = self { return value }
        return nil
    }
}

/// Process-wide inputs shared by drawing (main thread) and the engine text
/// measurer (PHP worker thread). Written on the main thread by the runtime.
enum PamTextEnvironment {
    private static let lock = NSLock()
    private static var storedTextScale: CGFloat = 1
    private static var storedDisplayScale: CGFloat = 2

    /// Device accessibility text multiplier, identical to the value the engine
    /// receives through `pam_native_runtime_start/relayout`.
    static var textScale: CGFloat {
        get { lock.lock(); defer { lock.unlock() }; return storedTextScale }
        set { lock.lock(); storedTextScale = max(0.1, newValue); lock.unlock() }
    }

    /// Pixels per point used for React Native pixel rounding.
    static var displayScale: CGFloat {
        get { lock.lock(); defer { lock.unlock() }; return storedDisplayScale }
        set { lock.lock(); storedDisplayScale = max(1, newValue); lock.unlock() }
    }

    /// Engine contract: `allowFontScaling=false` pins 1; a positive
    /// `maxFontSizeMultiplier` caps the device scale (never below 1).
    static func effectiveScale(allowsScaling: Bool, maximumMultiplier: Double, device: CGFloat? = nil) -> CGFloat {
        let scale = device ?? textScale
        guard allowsScaling else { return 1 }
        if maximumMultiplier > 0 {
            return min(scale, CGFloat(max(1, maximumMultiplier)))
        }
        return scale
    }

    static func ceilToPixel(_ value: CGFloat, scale: CGFloat? = nil) -> CGFloat {
        let pixels = scale ?? displayScale
        return ceil(value * pixels - 0.001) / pixels
    }
}

/// Marks views the renderer adds for its own painting (backdrop blur, …) so
/// child insertion indices only count PHP-managed subviews.
protocol PamAuxiliaryView: AnyObject {}

extension UIView {
    /// Inserts a managed child at a sibling index that ignores auxiliary views.
    func pamInsertManagedSubview(_ view: UIView, at index: Int) {
        let managed = subviews.filter { !($0 is PamAuxiliaryView) && $0 !== view }
        let target = min(max(index, 0), managed.count)
        if target >= managed.count {
            addSubview(view)
        } else {
            insertSubview(view, belowSubview: managed[target])
        }
    }
}

/// Four corner radii (top-left, top-right, bottom-right, bottom-left) in points.
struct PamCornerRadii: Equatable {
    var topLeft: CGFloat
    var topRight: CGFloat
    var bottomRight: CGFloat
    var bottomLeft: CGFloat

    static let zero = PamCornerRadii(topLeft: 0, topRight: 0, bottomRight: 0, bottomLeft: 0)

    var isUniform: Bool {
        topLeft == topRight && topLeft == bottomRight && topLeft == bottomLeft
    }

    var isZero: Bool {
        topLeft <= 0 && topRight <= 0 && bottomRight <= 0 && bottomLeft <= 0
    }

    /// CSS corner overlap rule: every radius shrinks when adjacent ones overflow a side.
    func fitted(to size: CGSize) -> PamCornerRadii {
        var factor: CGFloat = 1
        func fit(_ sum: CGFloat, _ side: CGFloat) {
            if sum > side, sum > 0 { factor = min(factor, max(0, side) / sum) }
        }
        fit(topLeft + topRight, size.width)
        fit(bottomLeft + bottomRight, size.width)
        fit(topLeft + bottomLeft, size.height)
        fit(topRight + bottomRight, size.height)
        guard factor < 1 else { return self }
        return PamCornerRadii(
            topLeft: topLeft * factor,
            topRight: topRight * factor,
            bottomRight: bottomRight * factor,
            bottomLeft: bottomLeft * factor
        )
    }

    /// Radii of a box grown (positive) or shrunk (negative) by `spread`, CSS box-shadow rule.
    func spread(_ amount: CGFloat) -> PamCornerRadii {
        func adjust(_ radius: CGFloat) -> CGFloat {
            guard radius > 0 else { return 0 }
            if amount >= 0 { return radius + amount }
            return max(0, radius + amount)
        }
        return PamCornerRadii(
            topLeft: adjust(topLeft),
            topRight: adjust(topRight),
            bottomRight: adjust(bottomRight),
            bottomLeft: adjust(bottomLeft)
        )
    }

    /// Inner (padding box) radii for the given border widths.
    func inset(left: CGFloat, top: CGFloat, right: CGFloat, bottom: CGFloat) -> (CGSize, CGSize, CGSize, CGSize) {
        (
            CGSize(width: max(0, topLeft - left), height: max(0, topLeft - top)),
            CGSize(width: max(0, topRight - right), height: max(0, topRight - top)),
            CGSize(width: max(0, bottomRight - right), height: max(0, bottomRight - bottom)),
            CGSize(width: max(0, bottomLeft - left), height: max(0, bottomLeft - bottom))
        )
    }

    func path(in rect: CGRect) -> CGPath {
        let fitted = fitted(to: rect.size)
        return PamCornerRadii.path(
            in: rect,
            topLeft: CGSize(width: fitted.topLeft, height: fitted.topLeft),
            topRight: CGSize(width: fitted.topRight, height: fitted.topRight),
            bottomRight: CGSize(width: fitted.bottomRight, height: fitted.bottomRight),
            bottomLeft: CGSize(width: fitted.bottomLeft, height: fitted.bottomLeft)
        )
    }

    /// Rounded rectangle with elliptical corners (each corner its own x/y radius).
    static func path(
        in rect: CGRect,
        topLeft: CGSize,
        topRight: CGSize,
        bottomRight: CGSize,
        bottomLeft: CGSize
    ) -> CGPath {
        let path = CGMutablePath()
        guard rect.width > 0, rect.height > 0 else { return path }
        // Clamp every corner to half of its sides so arcs never cross.
        func clamp(_ size: CGSize) -> CGSize {
            CGSize(width: min(max(0, size.width), rect.width / 2), height: min(max(0, size.height), rect.height / 2))
        }
        let tl = clamp(topLeft)
        let tr = clamp(topRight)
        let br = clamp(bottomRight)
        let bl = clamp(bottomLeft)
        // Bezier circle approximation constant.
        let k: CGFloat = 0.552_284_75
        path.move(to: CGPoint(x: rect.minX + tl.width, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - tr.width, y: rect.minY))
        if tr.width > 0, tr.height > 0 {
            path.addCurve(
                to: CGPoint(x: rect.maxX, y: rect.minY + tr.height),
                control1: CGPoint(x: rect.maxX - tr.width + tr.width * k, y: rect.minY),
                control2: CGPoint(x: rect.maxX, y: rect.minY + tr.height - tr.height * k)
            )
        }
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - br.height))
        if br.width > 0, br.height > 0 {
            path.addCurve(
                to: CGPoint(x: rect.maxX - br.width, y: rect.maxY),
                control1: CGPoint(x: rect.maxX, y: rect.maxY - br.height + br.height * k),
                control2: CGPoint(x: rect.maxX - br.width + br.width * k, y: rect.maxY)
            )
        }
        path.addLine(to: CGPoint(x: rect.minX + bl.width, y: rect.maxY))
        if bl.width > 0, bl.height > 0 {
            path.addCurve(
                to: CGPoint(x: rect.minX, y: rect.maxY - bl.height),
                control1: CGPoint(x: rect.minX + bl.width - bl.width * k, y: rect.maxY),
                control2: CGPoint(x: rect.minX, y: rect.maxY - bl.height + bl.height * k)
            )
        }
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + tl.height))
        if tl.width > 0, tl.height > 0 {
            path.addCurve(
                to: CGPoint(x: rect.minX + tl.width, y: rect.minY),
                control1: CGPoint(x: rect.minX, y: rect.minY + tl.height - tl.height * k),
                control2: CGPoint(x: rect.minX + tl.width - tl.width * k, y: rect.minY)
            )
        }
        path.closeSubpath()
        return path
    }
}

/// ARGB integer helpers (protocol colors are 0xAARRGGBB).
enum PamARGB {
    static func alpha(_ value: Int64) -> CGFloat {
        CGFloat((UInt64(truncatingIfNeeded: value) >> 24) & 0xFF) / 255
    }

    static func cgColor(_ value: Int64) -> CGColor {
        UIColor(argb: value).cgColor
    }

    static func components(_ value: Int64) -> (r: CGFloat, g: CGFloat, b: CGFloat, a: CGFloat) {
        let bits = UInt64(truncatingIfNeeded: value)
        return (
            CGFloat((bits >> 16) & 0xFF) / 255,
            CGFloat((bits >> 8) & 0xFF) / 255,
            CGFloat(bits & 0xFF) / 255,
            CGFloat((bits >> 24) & 0xFF) / 255
        )
    }

    static func from(_ color: UIColor) -> Int64 {
        var r: CGFloat = 0
        var g: CGFloat = 0
        var b: CGFloat = 0
        var a: CGFloat = 0
        guard color.getRed(&r, green: &g, blue: &b, alpha: &a) else { return 0 }
        return make(r: r, g: g, b: b, a: a)
    }

    static func make(r: CGFloat, g: CGFloat, b: CGFloat, a: CGFloat) -> Int64 {
        func byte(_ value: CGFloat) -> UInt64 { UInt64(min(255, max(0, (value * 255).rounded()))) }
        return Int64(bitPattern: (byte(a) << 24) | (byte(r) << 16) | (byte(g) << 8) | byte(b))
    }
}
