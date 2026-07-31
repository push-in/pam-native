import UIKit

final class PamVectorCanvas: UIView {
    private var commands: [[String: Any]] = []

    override class var layerClass: AnyClass {
        CAShapeLayer.self
    }

    func setCommands(_ value: String) {
        guard let data = value.data(using: .utf8), data.count <= 1_000_000,
              let decoded = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            commands = []
            setNeedsDisplay()
            return
        }
        commands = Array(decoded.prefix(10_000))
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        context.setAllowsAntialiasing(true)
        context.setShouldAntialias(true)
        for command in commands {
            let kind = Int(number(command["kind"]) ?? 0)
            context.setFillColor(color(command["color"]).cgColor)
            switch kind {
            case 1:
                context.fill(CGRect(
                    x: number(command["x"]) ?? 0,
                    y: number(command["y"]) ?? 0,
                    width: number(command["width"]) ?? 0,
                    height: number(command["height"]) ?? 0
                ))
            case 2:
                let bounds = CGRect(
                    x: number(command["x"]) ?? 0,
                    y: number(command["y"]) ?? 0,
                    width: number(command["width"]) ?? 0,
                    height: number(command["height"]) ?? 0
                )
                let path = UIBezierPath(
                    roundedRect: bounds,
                    cornerRadius: max(0, number(command["radius"]) ?? 0)
                )
                context.addPath(path.cgPath)
                context.fillPath()
            case 3:
                let radius = max(0, number(command["radius"]) ?? 0)
                context.fillEllipse(in: CGRect(
                    x: (number(command["centerX"]) ?? 0) - radius,
                    y: (number(command["centerY"]) ?? 0) - radius,
                    width: radius * 2,
                    height: radius * 2
                ))
            case 4:
                context.setStrokeColor(color(command["color"]).cgColor)
                context.setLineWidth(max(0, number(command["width"]) ?? 0))
                context.setLineCap(.round)
                context.move(to: CGPoint(
                    x: number(command["startX"]) ?? 0,
                    y: number(command["startY"]) ?? 0
                ))
                context.addLine(to: CGPoint(
                    x: number(command["endX"]) ?? 0,
                    y: number(command["endY"]) ?? 0
                ))
                context.strokePath()
            default:
                continue
            }
        }
    }

    private func number(_ value: Any?) -> CGFloat? {
        guard let number = value as? NSNumber else { return nil }
        let result = CGFloat(number.doubleValue)
        return result.isFinite ? result : nil
    }

    private func color(_ value: Any?) -> UIColor {
        guard let number = value as? NSNumber else { return .clear }
        return UIColor(argb: number.int64Value)
    }
}
