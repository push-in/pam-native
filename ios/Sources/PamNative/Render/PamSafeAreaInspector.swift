import UIKit

/// One view that ends inside a safe-area inset (status bar, notch/Dynamic
/// Island, home indicator, landscape sensor housing).
public struct PamSafeAreaViolation {
    public let view: UIView
    /// 0 left, 1 top, 2 right, 3 bottom.
    public let edge: Int
    public let intrusion: CGFloat
    public let description: String

    public var edgeName: String { ["left", "top", "right", "bottom"][min(max(edge, 0), 3)] }
}

/// Debug safe-area lint on the real views (parity with Android's
/// `PamSafeAreaInspector`): after a commit settles, text, inputs, buttons and
/// controls are checked against the window's `safeAreaInsets` and every
/// intrusion is logged (`PamSafeArea:` lines). Content inside a scroll view
/// counts only when it cannot scroll further toward that edge. Debug builds
/// only; `PAM_SAFE_AREA=0` in the scheme's environment disables it and
/// `PAM_SAFE_AREA_OVERLAY=1` paints the insets and outlines offenders.
public enum PamSafeAreaInspector {
    static var enabled: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.environment["PAM_SAFE_AREA"] != "0"
        #else
        return false
        #endif
    }

    private static var pending: DispatchWorkItem?
    private static var lastReport = ""
    private static let overlayTag = 0x5AFE_A4EA

    /// Schedules a scan of `view`'s window once layout settles.
    static func scheduleScan(of view: UIView) {
        guard enabled else { return }
        pending?.cancel()
        let work = DispatchWorkItem { [weak view] in
            guard let window = view?.window, !window.isHidden else { return }
            report(scan(window), in: window)
        }
        pending = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3, execute: work)
    }

    public static func scan(_ window: UIWindow) -> [PamSafeAreaViolation] {
        let insets = window.safeAreaInsets
        guard insets != .zero else { return [] }
        let bounds = window.bounds
        let bands = [insets.left, insets.top, insets.right, insets.bottom]
        var result: [PamSafeAreaViolation] = []
        func visit(_ view: UIView) {
            if view.isHidden || view.alpha <= 0.01 || view.tag == overlayTag { return }
            if isContent(view, in: bounds) {
                let frame = view.convert(view.bounds, to: window)
                let distance = [
                    frame.minX - bounds.minX,
                    frame.minY - bounds.minY,
                    bounds.maxX - frame.maxX,
                    bounds.maxY - frame.maxY,
                ]
                for edge in 0..<4 where bands[edge] > 0 {
                    let intrusion = bands[edge] - distance[edge]
                    guard intrusion > 1, frame.intersects(bounds), !scrollsAway(view, edge: edge) else {
                        continue
                    }
                    result.append(PamSafeAreaViolation(
                        view: view,
                        edge: edge,
                        intrusion: intrusion,
                        description: describe(view)
                    ))
                }
            }
            view.subviews.forEach(visit)
        }
        visit(window)
        return result
    }

    private static func isContent(_ view: UIView, in bounds: CGRect) -> Bool {
        guard view.bounds.width > 0, view.bounds.height > 0 else { return false }
        if let label = view as? UILabel { return !(label.text ?? "").isEmpty }
        if view is UITextField || view is UITextView || view is UISwitch || view is UIButton { return true }
        if view is UIControl || !(view.gestureRecognizers ?? []).isEmpty {
            return view.bounds.width * view.bounds.height < bounds.width * bounds.height / 2
        }
        return false
    }

    private static func scrollsAway(_ view: UIView, edge: Int) -> Bool {
        var current = view.superview
        while let candidate = current {
            if let scroll = candidate as? UIScrollView, scroll.isScrollEnabled {
                let offset = scroll.contentOffset
                let inset = scroll.adjustedContentInset
                let size = scroll.contentSize
                let bounds = scroll.bounds.size
                switch edge {
                case 3 where offset.y < size.height + inset.bottom - bounds.height - 0.5: return true
                case 1 where offset.y > -inset.top + 0.5: return true
                case 2 where offset.x < size.width + inset.right - bounds.width - 0.5: return true
                case 0 where offset.x > -inset.left + 0.5: return true
                default: break
                }
            }
            current = candidate.superview
        }
        return false
    }

    private static func describe(_ view: UIView) -> String {
        var text = String(describing: type(of: view))
        if let identifier = view.accessibilityIdentifier, !identifier.isEmpty {
            text += " testId=\(identifier)"
        }
        if let label = (view as? UILabel)?.text ?? view.accessibilityLabel, !label.isEmpty {
            text += " \"\(label.prefix(48))\""
        }
        return text
    }

    private static func report(_ violations: [PamSafeAreaViolation], in window: UIWindow) {
        let lines = violations.map {
            "PamSafeArea: \($0.description) ends \(String(format: "%.1f", $0.intrusion)) pt inside the \($0.edgeName) inset"
        }
        let report = lines.joined(separator: "\n")
        if report != lastReport {
            lastReport = report
            print(report.isEmpty ? "PamSafeArea: no content inside the safe-area insets" : report)
        }
        window.viewWithTag(overlayTag)?.removeFromSuperview()
        guard ProcessInfo.processInfo.environment["PAM_SAFE_AREA_OVERLAY"] == "1" else { return }
        let overlay = PamSafeAreaOverlay(frame: window.bounds)
        overlay.tag = overlayTag
        overlay.insets = window.safeAreaInsets
        overlay.outlines = violations.map { $0.view.convert($0.view.bounds, to: window) }
        window.addSubview(overlay)
    }
}

private final class PamSafeAreaOverlay: UIView {
    var insets: UIEdgeInsets = .zero
    var outlines: [CGRect] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        isOpaque = false
        backgroundColor = .clear
        accessibilityElementsHidden = true
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
    }

    required init?(coder: NSCoder) { nil }

    override func draw(_ rect: CGRect) {
        let band = UIColor(red: 1, green: 0, blue: 0.25, alpha: 0.27)
        band.setFill()
        UIRectFill(CGRect(x: 0, y: 0, width: insets.left, height: bounds.height))
        UIRectFill(CGRect(x: 0, y: 0, width: bounds.width, height: insets.top))
        UIRectFill(CGRect(x: bounds.width - insets.right, y: 0, width: insets.right, height: bounds.height))
        UIRectFill(CGRect(x: 0, y: bounds.height - insets.bottom, width: bounds.width, height: insets.bottom))
        UIColor.red.setStroke()
        outlines.forEach { outline in
            let path = UIBezierPath(rect: outline)
            path.lineWidth = 3
            path.stroke()
        }
    }
}
