import UIKit

struct PamDrawingPoint {
    let x: CGFloat
    let y: CGFloat
}

struct PamDrawingStroke {
    let color: Int64
    let width: CGFloat
    let mode: Int
    let points: [PamDrawingPoint]
}

enum PamDrawingCodec {
    static let maxStrokes = 256
    static let maxPointsPerStroke = 2_048

    static func decode(_ value: String) -> [PamDrawingStroke] {
        guard !value.isEmpty,
              let data = value.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let source = root["strokes"] as? [[String: Any]] else {
            return []
        }
        return source.prefix(maxStrokes).compactMap { item in
            guard let rawPoints = item["points"] as? [NSNumber], rawPoints.count >= 2 else {
                return nil
            }
            var points: [PamDrawingPoint] = []
            points.reserveCapacity(min(rawPoints.count / 2, maxPointsPerStroke))
            for index in 0..<min(rawPoints.count / 2, maxPointsPerStroke) {
                points.append(PamDrawingPoint(
                    x: CGFloat(rawPoints[index * 2].doubleValue).clamped(to: 0...1),
                    y: CGFloat(rawPoints[index * 2 + 1].doubleValue).clamped(to: 0...1)
                ))
            }
            let color = (item["color"] as? NSNumber)?.int64Value ?? Int64(UInt32.max)
            let width = CGFloat((item["width"] as? NSNumber)?.doubleValue ?? 6.0 / 360.0)
                .clamped(to: 0.0005...0.25)
            let mode = max(1, min(2, (item["mode"] as? NSNumber)?.intValue ?? 1))
            return PamDrawingStroke(color: color, width: width, mode: mode, points: points)
        }
    }

    static func encode(_ strokes: [PamDrawingStroke]) -> String {
        let encoded = strokes.suffix(maxStrokes).map { stroke -> [String: Any] in
            var points: [Double] = []
            points.reserveCapacity(min(stroke.points.count, maxPointsPerStroke) * 2)
            stroke.points.prefix(maxPointsPerStroke).forEach { point in
                points.append(Double(point.x))
                points.append(Double(point.y))
            }
            return [
                "color": stroke.color,
                "width": Double(stroke.width),
                "mode": stroke.mode,
                "points": points,
            ]
        }
        guard let data = try? JSONSerialization.data(
            withJSONObject: ["version": 1, "strokes": encoded]
        ) else {
            return "{\"version\":1,\"strokes\":[]}"
        }
        return String(decoding: data, as: UTF8.self)
    }
}

final class PamDrawingCanvas: UIView {
    let imageView = UIImageView()
    private let overlay = PamDrawingOverlay()
    private var encodedValue = ""
    private var brushColor = Int64(UInt32.max)
    private var brushWidth: CGFloat = 6
    private var drawingMode = 1
    private var clearRequest = 0
    private var undoRequest = 0
    var onDrawingChange: ((String) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true
        addSubview(imageView)
        addSubview(overlay)
        overlay.onStrokeCommitted = { [weak self] in self?.emitChange() }
        overlay.configuration = { [weak self] contentWidth in
            guard let self else {
                return (Int64(UInt32.max), CGFloat(6.0 / 360.0), 1)
            }
            let normalizedWidth = (
                self.brushWidth / max(contentWidth, 1)
            ).clamped(to: 0.0005...0.25)
            return (self.brushColor, normalizedWidth, self.drawingMode)
        }
        isAccessibilityElement = true
        accessibilityTraits = [.allowsDirectInteraction]
    }

    required init?(coder: NSCoder) {
        nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        imageView.frame = bounds
        overlay.frame = bounds
        overlay.imageSize = imageView.image?.size ?? .zero
    }

    func setDrawing(_ value: String) {
        guard value != encodedValue else { return }
        let strokes = PamDrawingCodec.decode(value)
        overlay.setStrokes(strokes)
        encodedValue = strokes.isEmpty ? "" : PamDrawingCodec.encode(strokes)
    }

    func imageDidChange() {
        overlay.imageSize = imageView.image?.size ?? .zero
        setNeedsLayout()
    }

    func setBrushColor(_ value: Int64) {
        brushColor = value
    }

    func setBrushWidth(_ value: CGFloat) {
        brushWidth = value.clamped(to: 1...64)
    }

    func setDrawingMode(_ value: Int) {
        drawingMode = max(1, min(2, value))
    }

    func setClearRequest(_ value: Int) {
        guard value != clearRequest else { return }
        clearRequest = value
        if value > 0, overlay.clear() {
            emitChange()
        }
    }

    func setUndoRequest(_ value: Int) {
        guard value != undoRequest else { return }
        undoRequest = value
        if value > 0, overlay.undo() {
            emitChange()
        }
    }

    private func emitChange() {
        encodedValue = PamDrawingCodec.encode(overlay.strokes)
        onDrawingChange?(encodedValue)
    }
}

private final class PamDrawingOverlay: UIView {
    var strokes: [PamDrawingStroke] = []
    var imageSize: CGSize = .zero {
        didSet { setNeedsDisplay() }
    }
    var onStrokeCommitted: (() -> Void)?
    var configuration: ((CGFloat) -> (Int64, CGFloat, Int))?
    private var activePoints: [PamDrawingPoint]?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isMultipleTouchEnabled = false
        isExclusiveTouch = true
        contentMode = .redraw
    }

    required init?(coder: NSCoder) {
        nil
    }

    func setStrokes(_ value: [PamDrawingStroke]) {
        strokes = value
        activePoints = nil
        setNeedsDisplay()
    }

    func clear() -> Bool {
        guard !strokes.isEmpty || activePoints != nil else { return false }
        strokes.removeAll(keepingCapacity: true)
        activePoints = nil
        setNeedsDisplay()
        return true
    }

    func undo() -> Bool {
        guard !strokes.isEmpty else { return false }
        strokes.removeLast()
        setNeedsDisplay()
        return true
    }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        let content = contentRect
        context.saveGState()
        context.clip(to: content)
        strokes.forEach { drawStroke($0, in: context, content: content) }
        if let activePoints,
           let (color, width, mode) = configuration?(content.width) {
            drawStroke(
                PamDrawingStroke(color: color, width: width, mode: mode, points: activePoints),
                in: context,
                content: content
            )
        }
        context.restoreGState()
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let point = touches.first?.location(in: self), contentRect.contains(point) else {
            return
        }
        activePoints = [normalized(point)]
        setNeedsDisplay()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard var points = activePoints, let touch = touches.first else { return }
        for sample in event?.coalescedTouches(for: touch) ?? [touch] {
            append(sample.location(in: self), to: &points)
        }
        activePoints = points
        setNeedsDisplay()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard var points = activePoints else { return }
        if let point = touches.first?.location(in: self) {
            append(point, to: &points)
        }
        let config = configuration?(contentRect.width)
            ?? (Int64(UInt32.max), CGFloat(6.0 / 360.0), 1)
        strokes.append(PamDrawingStroke(
            color: config.0,
            width: config.1,
            mode: config.2,
            points: points
        ))
        if strokes.count > PamDrawingCodec.maxStrokes {
            strokes.removeFirst()
        }
        activePoints = nil
        setNeedsDisplay()
        onStrokeCommitted?()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        activePoints = nil
        setNeedsDisplay()
    }

    private var contentRect: CGRect {
        guard imageSize.width > 0, imageSize.height > 0 else { return bounds }
        let scale = min(bounds.width / imageSize.width, bounds.height / imageSize.height)
        let size = CGSize(width: imageSize.width * scale, height: imageSize.height * scale)
        return CGRect(
            x: (bounds.width - size.width) / 2,
            y: (bounds.height - size.height) / 2,
            width: size.width,
            height: size.height
        )
    }

    private func normalized(_ point: CGPoint) -> PamDrawingPoint {
        let content = contentRect
        return PamDrawingPoint(
            x: ((point.x - content.minX) / max(content.width, 1)).clamped(to: 0...1),
            y: ((point.y - content.minY) / max(content.height, 1)).clamped(to: 0...1)
        )
    }

    private func append(_ point: CGPoint, to points: inout [PamDrawingPoint]) {
        guard points.count < PamDrawingCodec.maxPointsPerStroke else { return }
        let next = normalized(point)
        guard let previous = points.last else {
            points.append(next)
            return
        }
        let content = contentRect
        let distance = hypot(
            (next.x - previous.x) * content.width,
            (next.y - previous.y) * content.height
        )
        if distance >= 1.25 {
            points.append(next)
        }
    }

    private func drawStroke(
        _ stroke: PamDrawingStroke,
        in context: CGContext,
        content: CGRect
    ) {
        guard let first = stroke.points.first else { return }
        context.saveGState()
        context.setBlendMode(stroke.mode == 2 ? .clear : .normal)
        context.setStrokeColor(UIColor(argb: stroke.color).cgColor)
        context.setLineWidth(max(1, stroke.width * content.width))
        context.setLineCap(.round)
        context.setLineJoin(.round)
        context.beginPath()
        context.move(to: CGPoint(
            x: content.minX + first.x * content.width,
            y: content.minY + first.y * content.height
        ))
        stroke.points.dropFirst().forEach { point in
            context.addLine(to: CGPoint(
                x: content.minX + point.x * content.width,
                y: content.minY + point.y * content.height
            ))
        }
        if stroke.points.count == 1 {
            context.addLine(to: CGPoint(
                x: content.minX + first.x * content.width + 0.01,
                y: content.minY + first.y * content.height + 0.01
            ))
        }
        context.strokePath()
        context.restoreGState()
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
