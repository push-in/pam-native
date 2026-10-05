import UIKit

/// CSS linear/radial gradient layer decoded from the compiler wire format
/// (`CssEffects.php`, shared with Android `PamGradient.kt`). Decoding is cached
/// per wire string; stops interpolate in premultiplied space like browsers.
struct PamGradientLayer: Equatable {
    static let typeLinear = 1
    static let typeRadial = 2
    static let modeAngle = 1
    static let modeCorner = 2
    static let modePoints = 3
    static let unitAuto = 0
    static let unitFraction = 1
    static let unitPoints = 2
    static let sizeClosestSide = 1
    static let sizeClosestCorner = 2
    static let sizeFarthestSide = 3
    static let sizeFarthestCorner = 4
    static let sizeExplicit = 5

    var type = typeLinear
    var repeating = false
    var mode = modeAngle
    var angle: CGFloat = 180
    var cornerX: CGFloat = 0
    var cornerY: CGFloat = 0
    var points: [CGFloat] = [0, 0, 0, 0]
    var ellipse = true
    var size = sizeFarthestCorner
    var sizeW: CGFloat = 0
    var sizeWUnit = unitPoints
    var sizeH: CGFloat = 0
    var sizeHUnit = unitPoints
    var centerX: CGFloat = 0.5
    var centerXUnit = unitFraction
    var centerY: CGFloat = 0.5
    var centerYUnit = unitFraction
    var colors: [Int64] = []
    var positions: [CGFloat] = []
    var units: [Int] = []

    private static var cache: [String: [PamGradientLayer]] = [:]
    private static let cacheLock = NSLock()

    static func parse(_ wire: String?) -> [PamGradientLayer] {
        guard let wire, !wire.isEmpty else { return [] }
        cacheLock.lock()
        if let cached = cache[wire] {
            cacheLock.unlock()
            return cached
        }
        cacheLock.unlock()
        let layers = decode(wire)
        cacheLock.lock()
        if cache.count >= 128 { cache.removeAll(keepingCapacity: true) }
        cache[wire] = layers
        cacheLock.unlock()
        return layers
    }

    private static func decode(_ wire: String) -> [PamGradientLayer] {
        guard let data = wire.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        func number(_ value: Any?) -> CGFloat? {
            (value as? NSNumber).map { CGFloat(truncating: $0) }
        }
        func integer(_ value: Any?) -> Int? {
            (value as? NSNumber)?.intValue
        }
        return array.compactMap { object in
            guard let stops = object["s"] as? [[Any]], !stops.isEmpty else { return nil }
            var layer = PamGradientLayer()
            layer.type = integer(object["t"]) ?? typeLinear
            layer.repeating = integer(object["r"]) == 1
            layer.mode = integer(object["m"]) ?? modeAngle
            layer.angle = number(object["a"]) ?? 180
            layer.cornerX = number(object["x"]) ?? 0
            layer.cornerY = number(object["y"]) ?? 0
            if let points = object["p"] as? [Any] {
                layer.points = (0..<4).map { index in index < points.count ? number(points[index]) ?? 0 : 0 }
            }
            layer.ellipse = (integer(object["e"]) ?? 1) == 1
            layer.size = integer(object["z"]) ?? sizeFarthestCorner
            if let width = object["w"] as? [Any] {
                layer.sizeW = width.count > 0 ? number(width[0]) ?? 0 : 0
                layer.sizeWUnit = width.count > 1 ? integer(width[1]) ?? unitPoints : unitPoints
            }
            if let height = object["h"] as? [Any] {
                layer.sizeH = height.count > 0 ? number(height[0]) ?? 0 : 0
                layer.sizeHUnit = height.count > 1 ? integer(height[1]) ?? unitPoints : unitPoints
            }
            if let center = object["c"] as? [Any] {
                if let x = center.first as? [Any] {
                    layer.centerX = x.count > 0 ? number(x[0]) ?? 0.5 : 0.5
                    layer.centerXUnit = x.count > 1 ? integer(x[1]) ?? unitFraction : unitFraction
                }
                if center.count > 1, let y = center[1] as? [Any] {
                    layer.centerY = y.count > 0 ? number(y[0]) ?? 0.5 : 0.5
                    layer.centerYUnit = y.count > 1 ? integer(y[1]) ?? unitFraction : unitFraction
                }
            }
            layer.colors = stops.map { ($0.first as? NSNumber)?.int64Value ?? 0 }
            layer.positions = stops.map { $0.count > 1 ? number($0[1]) ?? 0 : 0 }
            layer.units = stops.map { $0.count > 2 ? integer($0[2]) ?? unitAuto : unitAuto }
            return layer
        }
    }

    // MARK: Geometry

    /// Linear gradient line endpoints for a box (CSS gradient line rules).
    func linearEndpoints(width: CGFloat, height: CGFloat) -> (CGPoint, CGPoint) {
        if mode == Self.modePoints {
            return (
                CGPoint(x: points[0] * width, y: points[1] * height),
                CGPoint(x: points[2] * width, y: points[3] * height)
            )
        }
        let radians: CGFloat
        if mode == Self.modeCorner {
            // Perpendicular to the diagonal joining the two neighbouring corners.
            radians = atan2(cornerX * height, -(cornerY * width))
        } else {
            radians = angle * .pi / 180
        }
        let dx = sin(radians)
        let dy = -cos(radians)
        let length = abs(width * dx) + abs(height * dy)
        return (
            CGPoint(x: width / 2 - dx * length / 2, y: height / 2 - dy * length / 2),
            CGPoint(x: width / 2 + dx * length / 2, y: height / 2 + dy * length / 2)
        )
    }

    func radialGeometry(width: CGFloat, height: CGFloat) -> (center: CGPoint, rx: CGFloat, ry: CGFloat) {
        func resolve(_ value: CGFloat, _ unit: Int, _ reference: CGFloat) -> CGFloat {
            unit == Self.unitFraction ? value * reference : value
        }
        let cx = resolve(centerX, centerXUnit, width)
        let cy = resolve(centerY, centerYUnit, height)
        let left = abs(cx)
        let right = abs(width - cx)
        let top = abs(cy)
        let bottom = abs(height - cy)
        let rx: CGFloat
        let ry: CGFloat
        if size == Self.sizeExplicit {
            rx = resolve(sizeW, sizeWUnit, width)
            ry = ellipse ? resolve(sizeH, sizeHUnit, height) : rx
        } else if !ellipse {
            let corners = [hypot(left, top), hypot(right, top), hypot(left, bottom), hypot(right, bottom)]
            switch size {
            case Self.sizeClosestSide: rx = min(min(left, right), min(top, bottom))
            case Self.sizeFarthestSide: rx = max(max(left, right), max(top, bottom))
            case Self.sizeClosestCorner: rx = corners.min() ?? 0
            default: rx = corners.max() ?? 0
            }
            ry = rx
        } else {
            let closest = size == Self.sizeClosestSide || size == Self.sizeClosestCorner
            let sideX = closest ? min(left, right) : max(left, right)
            let sideY = closest ? min(top, bottom) : max(top, bottom)
            if size == Self.sizeClosestSide || size == Self.sizeFarthestSide {
                rx = sideX
                ry = sideY
            } else {
                // Passes through the chosen corner with the matching side ratio.
                rx = sideX * sqrt(2)
                ry = sideY * sqrt(2)
            }
        }
        return (CGPoint(x: cx, y: cy), max(rx, 0.001), max(ry, 0.001))
    }

    /// CSS color-stop fix-up: defaults, monotonic clamping, auto distribution.
    /// Positions are fractions of `lineLength`.
    func resolvedStops(lineLength: CGFloat) -> [(color: Int64, position: CGFloat)] {
        let count = colors.count
        guard count > 0 else { return [] }
        var resolved: [CGFloat?] = (0..<count).map { index -> CGFloat? in
            switch index < units.count ? units[index] : Self.unitAuto {
            case Self.unitFraction: return positions[index]
            case Self.unitPoints: return positions[index] / max(lineLength, 0.001)
            default: return nil
            }
        }
        if resolved[0] == nil { resolved[0] = 0 }
        if resolved[count - 1] == nil { resolved[count - 1] = 1 }
        var maximum = resolved[0] ?? 0
        if count > 1 {
            for index in 1..<count {
                if let value = resolved[index] {
                    if value < maximum { resolved[index] = maximum }
                    maximum = resolved[index] ?? maximum
                }
            }
        }
        var index = 1
        while index < count {
            if resolved[index] == nil {
                let start = index - 1
                var end = index
                while end < count, resolved[end] == nil { end += 1 }
                let from = resolved[start] ?? 0
                let to = end < count ? (resolved[end] ?? 1) : 1
                for fill in (start + 1)..<end {
                    resolved[fill] = from + (to - from) * CGFloat(fill - start) / CGFloat(end - start)
                }
                index = end
            }
            index += 1
        }
        return (0..<count).map { (colors[$0], resolved[$0] ?? 0) }
    }

    // MARK: Drawing

    /// Paints this layer over the whole `size` box (callers clip to the border radius).
    func draw(in context: CGContext, size: CGSize) {
        guard size.width > 0, size.height > 0, !colors.isEmpty else { return }
        if type == Self.typeRadial {
            drawRadial(in: context, size: size)
        } else {
            drawLinear(in: context, size: size)
        }
    }

    private func drawLinear(in context: CGContext, size: CGSize) {
        let (start, end) = linearEndpoints(width: size.width, height: size.height)
        let vector = CGPoint(x: end.x - start.x, y: end.y - start.y)
        let length = max(hypot(vector.x, vector.y), 0.001)
        let stops = resolvedStops(lineLength: length)
        guard let first = stops.first?.position, let last = stops.last?.position else { return }
        let span = max(last - first, 0.0001)
        if !repeating {
            let normalized = Self.premultiplied(stops.map { ($0.color, min(1, max(0, ($0.position - first) / span))) })
            guard let gradient = Self.cgGradient(normalized) else { return }
            context.drawLinearGradient(
                gradient,
                start: CGPoint(x: start.x + vector.x * first, y: start.y + vector.y * first),
                end: CGPoint(x: start.x + vector.x * (first + span), y: start.y + vector.y * (first + span)),
                options: [.drawsBeforeStartLocation, .drawsAfterEndLocation]
            )
            return
        }
        // Repeating: tile the pattern over the box's projection on the gradient line.
        let unit = CGPoint(x: vector.x / (length * length), y: vector.y / (length * length))
        let corners = [CGPoint.zero, CGPoint(x: size.width, y: 0), CGPoint(x: 0, y: size.height), CGPoint(x: size.width, y: size.height)]
        let projections = corners.map { ($0.x - start.x) * unit.x + ($0.y - start.y) * unit.y }
        let minimum = projections.min() ?? 0
        let maximum = projections.max() ?? 1
        let tiled = Self.tiled(stops: stops, first: first, span: span, from: minimum, to: maximum)
        guard let gradient = Self.cgGradient(tiled.stops) else { return }
        context.drawLinearGradient(
            gradient,
            start: CGPoint(x: start.x + vector.x * tiled.from, y: start.y + vector.y * tiled.from),
            end: CGPoint(x: start.x + vector.x * tiled.to, y: start.y + vector.y * tiled.to),
            options: [.drawsBeforeStartLocation, .drawsAfterEndLocation]
        )
    }

    private func drawRadial(in context: CGContext, size: CGSize) {
        let geometry = radialGeometry(width: size.width, height: size.height)
        var stops = resolvedStops(lineLength: geometry.rx)
        stops = stops.map { ($0.color, max(0, $0.position)) }
        guard let last = stops.last?.position else { return }
        let first = repeating ? (stops.first?.position ?? 0) : 0
        let span = max(last - first, 0.0001)
        context.saveGState()
        defer { context.restoreGState() }
        if abs(geometry.rx - geometry.ry) > 0.01 {
            context.translateBy(x: geometry.center.x, y: geometry.center.y)
            context.scaleBy(x: 1, y: geometry.ry / geometry.rx)
            context.translateBy(x: -geometry.center.x, y: -geometry.center.y)
        }
        if !repeating {
            let normalized = Self.premultiplied(stops.map { ($0.color, min(1, max(0, $0.position / span))) })
            guard let gradient = Self.cgGradient(normalized) else { return }
            context.drawRadialGradient(
                gradient,
                startCenter: geometry.center,
                startRadius: 0,
                endCenter: geometry.center,
                endRadius: geometry.rx * span,
                options: [.drawsBeforeStartLocation, .drawsAfterEndLocation]
            )
            return
        }
        // Radius (in units of rx) that reaches the farthest box corner.
        let scaleY = geometry.ry / geometry.rx
        let corners = [CGPoint.zero, CGPoint(x: size.width, y: 0), CGPoint(x: 0, y: size.height), CGPoint(x: size.width, y: size.height)]
        let reach = corners.map { corner -> CGFloat in
            hypot(corner.x - geometry.center.x, (corner.y - geometry.center.y) / max(scaleY, 0.001)) / geometry.rx
        }.max() ?? 1
        let tiled = Self.tiled(stops: stops, first: first, span: span, from: 0, to: reach)
        guard let gradient = Self.cgGradient(tiled.stops) else { return }
        context.drawRadialGradient(
            gradient,
            startCenter: geometry.center,
            startRadius: geometry.rx * tiled.from,
            endCenter: geometry.center,
            endRadius: geometry.rx * tiled.to,
            options: [.drawsBeforeStartLocation, .drawsAfterEndLocation]
        )
    }

    /// Repeats `[first, first + span)` across `[from, to]` (line fractions) and
    /// returns normalized stops for that range.
    static func tiled(
        stops: [(color: Int64, position: CGFloat)],
        first: CGFloat,
        span: CGFloat,
        from: CGFloat,
        to: CGFloat
    ) -> (stops: [(Int64, CGFloat)], from: CGFloat, to: CGFloat) {
        let startTile = floor((from - first) / span)
        var endTile = ceil((to - first) / span)
        endTile = min(endTile, startTile + 512)
        let rangeStart = first + startTile * span
        let rangeEnd = first + max(endTile, startTile + 1) * span
        let total = max(rangeEnd - rangeStart, 0.0001)
        var expanded: [(Int64, CGFloat)] = []
        var tile = startTile
        while tile < max(endTile, startTile + 1) {
            let offset = first + tile * span
            for stop in stops {
                let relative = min(span, max(0, stop.position - first))
                expanded.append((stop.color, (offset + relative - rangeStart) / total))
            }
            tile += 1
        }
        return (premultiplied(expanded), rangeStart, rangeEnd)
    }

    private static func cgGradient(_ stops: [(Int64, CGFloat)]) -> CGGradient? {
        guard !stops.isEmpty else { return nil }
        let space = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        var components: [CGFloat] = []
        var locations: [CGFloat] = []
        var previous: CGFloat = 0
        for (color, position) in stops {
            let parts = PamARGB.components(color)
            components.append(contentsOf: [parts.r, parts.g, parts.b, parts.a])
            let clamped = min(1, max(previous, position))
            locations.append(clamped)
            previous = clamped
        }
        return CGGradient(colorSpace: space, colorComponents: components, locations: locations, count: locations.count)
    }

    /// Re-expresses each segment with stops whose straight-alpha interpolation
    /// matches premultiplied interpolation (`transparent` never darkens).
    static func premultiplied(_ stops: [(Int64, CGFloat)]) -> [(Int64, CGFloat)] {
        guard stops.count > 1 else {
            guard let only = stops.first else { return [] }
            return [(only.0, 0), (only.0, 1)]
        }
        var output: [(Int64, CGFloat)] = []
        func add(_ color: Int64, _ position: CGFloat) {
            if let last = output.last, last.0 == color, last.1 == position { return }
            output.append((color, position))
        }
        for index in 1..<stops.count {
            let (from, start) = stops[index - 1]
            let (to, end) = stops[index]
            let fromAlpha = (UInt64(truncatingIfNeeded: from) >> 24) & 0xFF
            let toAlpha = (UInt64(truncatingIfNeeded: to) >> 24) & 0xFF
            let fromRGB = UInt64(truncatingIfNeeded: from) & 0xFF_FFFF
            let toRGB = UInt64(truncatingIfNeeded: to) & 0xFF_FFFF
            if fromAlpha == toAlpha || fromRGB == toRGB {
                add(from, start)
                add(to, end)
            } else if fromAlpha == 0 {
                add(Int64(toRGB), start)
                add(to, end)
            } else if toAlpha == 0 {
                add(from, start)
                add(Int64(fromRGB), end)
            } else {
                for step in 0...8 {
                    let fraction = CGFloat(step) / 8
                    add(premultipliedLerp(from, to, fraction), start + (end - start) * fraction)
                }
            }
        }
        if output.count == 1 { output.append(output[0]) }
        return output
    }

    static func premultipliedLerp(_ from: Int64, _ to: Int64, _ fraction: CGFloat) -> Int64 {
        let a = PamARGB.components(from)
        let b = PamARGB.components(to)
        let alpha = a.a + (b.a - a.a) * fraction
        guard alpha > 0 else { return 0 }
        func channel(_ x: CGFloat, _ y: CGFloat) -> CGFloat {
            let left = x * a.a
            let right = y * b.a
            return min(1, max(0, (left + (right - left) * fraction) / alpha))
        }
        return PamARGB.make(r: channel(a.r, b.r), g: channel(a.g, b.g), b: channel(a.b, b.b), a: alpha)
    }

    static func == (lhs: PamGradientLayer, rhs: PamGradientLayer) -> Bool {
        lhs.type == rhs.type && lhs.repeating == rhs.repeating && lhs.mode == rhs.mode
            && lhs.angle == rhs.angle && lhs.colors == rhs.colors && lhs.positions == rhs.positions
            && lhs.units == rhs.units && lhs.points == rhs.points && lhs.size == rhs.size
            && lhs.centerX == rhs.centerX && lhs.centerY == rhs.centerY && lhs.ellipse == rhs.ellipse
            && lhs.sizeW == rhs.sizeW && lhs.sizeH == rhs.sizeH && lhs.cornerX == rhs.cornerX
            && lhs.cornerY == rhs.cornerY
    }
}
