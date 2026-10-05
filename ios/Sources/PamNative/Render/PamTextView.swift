import CoreText
import UIKit

/// Everything a `Text` node needs to lay out and draw.
struct PamTextContent: Equatable {
    var text: String
    var spans: String?
    var style: PamTextStyle
    var options: PamTextDrawOptions
}

/// `onTextLayout` payload (React Native semantics).
struct PamTextLayoutReport: Equatable {
    let lines: Int
    let visibleLines: Int
    let truncated: Bool
    let width: CGFloat
    let height: CGFloat
    let lineWidths: [CGFloat]
}

/// `Text` host. Draws with `PamTextLayout`, the same CoreText pipeline that
/// measures the box for the engine; still a `UILabel` so accessibility, color
/// policies and existing callers keep working.
final class PamTextView: UILabel, UIGestureRecognizerDelegate {
    var pamContent: PamTextContent? {
        didSet {
            guard pamContent != oldValue else { return }
            invalidateTextLayout()
            updateMarquee()
        }
    }

    /// Pressed inline run slot (`EventKind.spanPress`).
    var onSpanPress: ((Int) -> Void)? {
        didSet { updateInteraction() }
    }

    var onTextLayout: ((PamTextLayoutReport) -> Void)? {
        didSet {
            lastReport = nil
            if onTextLayout != nil { setNeedsLayout() }
        }
    }

    private var cachedAttributed: NSAttributedString?
    private var cachedLayout: (width: CGFloat, scale: CGFloat, result: PamTextLayoutResult)?
    private var fitScale: CGFloat = 1
    private var fitScaleWidth: CGFloat = -1
    private var fitScaleHeight: CGFloat = -1
    private var lastReport: PamTextLayoutReport?
    private lazy var spanTap: UITapGestureRecognizer = {
        let recognizer = UITapGestureRecognizer(target: self, action: #selector(onSpanTap(_:)))
        recognizer.delegate = self
        recognizer.cancelsTouchesInView = true
        return recognizer
    }()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        contentMode = .redraw
        clipsToBounds = false
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        isOpaque = false
        contentMode = .redraw
    }

    override var textColor: UIColor! {
        didSet { setNeedsDisplay() }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        reportTextLayoutIfNeeded()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        setNeedsDisplay()
    }

    override func sizeThatFits(_ size: CGSize) -> CGSize {
        guard let content = pamContent else { return super.sizeThatFits(size) }
        let result = PamTextLayout.layout(
            attributed(content),
            style: content.style,
            width: size.width > 0 ? size.width : .infinity,
            ellipsize: content.options.ellipsize
        )
        return CGSize(
            width: PamTextEnvironment.ceilToPixel(result.size.width),
            height: PamTextEnvironment.ceilToPixel(result.size.height)
        )
    }

    override var intrinsicContentSize: CGSize {
        pamContent == nil ? super.intrinsicContentSize : sizeThatFits(CGSize(width: CGFloat.infinity, height: .infinity))
    }

    override func draw(_ rect: CGRect) {
        guard let content = pamContent else {
            super.draw(rect)
            return
        }
        guard let context = UIGraphicsGetCurrentContext(), bounds.width > 0 else { return }
        if content.options.ellipsize == 5 {
            drawMarquee(content, in: context)
            return
        }
        let result = currentLayout(content)
        PamTextLayout.draw(
            result,
            in: context,
            bounds: bounds,
            color: textColor ?? .label,
            options: content.options,
            traits: traitCollection
        )
    }

    // MARK: Marquee (`ellipsizeMode="marquee"`)

    static let marqueePointsPerSecond: CGFloat = 30
    static let marqueeStartDelay: CFTimeInterval = 1.2
    static let marqueeGap: CGFloat = 40
    private var marqueeLink: CADisplayLink?
    private var marqueeOffset: CGFloat = 0
    private var marqueeResume: CFTimeInterval = 0
    private var marqueeLastTick: CFTimeInterval = 0

    override func didMoveToWindow() {
        super.didMoveToWindow()
        updateMarquee()
    }

    private func marqueeLayout(_ content: PamTextContent) -> PamTextLayoutResult {
        var style = content.style
        style.maxLines = 0
        return PamTextLayout.layout(attributed(content), style: style, width: .infinity, ellipsize: 4)
    }

    private func updateMarquee() {
        let wants = pamContent?.options.ellipsize == 5 && window != nil && !PamMotionPolicy.isReduced
        if wants, marqueeLink == nil {
            marqueeOffset = 0
            marqueeResume = CACurrentMediaTime() + Self.marqueeStartDelay
            marqueeLastTick = CACurrentMediaTime()
            let link = CADisplayLink(target: PamTextMarqueeTicker(self), selector: #selector(PamTextMarqueeTicker.tick(_:)))
            link.add(to: .main, forMode: .common)
            marqueeLink = link
        } else if !wants, let link = marqueeLink {
            link.invalidate()
            marqueeLink = nil
            marqueeOffset = 0
            setNeedsDisplay()
        }
    }

    fileprivate func advanceMarquee(_ now: CFTimeInterval) {
        defer { marqueeLastTick = now }
        guard now >= marqueeResume, let content = pamContent else { return }
        let width = marqueeLayout(content).size.width
        guard width > bounds.width else { return }
        marqueeOffset += Self.marqueePointsPerSecond * CGFloat(now - marqueeLastTick)
        let cycle = width + Self.marqueeGap
        if marqueeOffset >= cycle {
            marqueeOffset -= cycle
            marqueeResume = now + Self.marqueeStartDelay
        }
        setNeedsDisplay()
    }

    private func drawMarquee(_ content: PamTextContent, in context: CGContext) {
        let result = marqueeLayout(content)
        var options = content.options
        if result.size.width > bounds.width { options.alignment = 1 }
        context.saveGState()
        context.clip(to: bounds)
        let width = max(result.size.width, bounds.width)
        let first = CGRect(x: -marqueeOffset, y: 0, width: width, height: bounds.height)
        PamTextLayout.draw(result, in: context, bounds: first, color: textColor ?? .label, options: options, traits: traitCollection)
        if result.size.width > bounds.width {
            let second = first.offsetBy(dx: result.size.width + Self.marqueeGap, dy: 0)
            PamTextLayout.draw(result, in: context, bounds: second, color: textColor ?? .label, options: options, traits: traitCollection)
        }
        context.restoreGState()
    }

    // MARK: Layout

    func invalidateTextLayout() {
        cachedAttributed = nil
        cachedLayout = nil
        fitScaleWidth = -1
        fitScaleHeight = -1
        lastReport = nil
        updateInteraction()
        setNeedsDisplay()
        setNeedsLayout()
    }

    private func attributed(_ content: PamTextContent, scale: CGFloat = 1) -> NSAttributedString {
        if scale == 1, let cachedAttributed { return cachedAttributed }
        var style = content.style
        style.fontScale *= scale
        let value = PamTextLayout.content(raw: content.text, spansWire: content.spans, style: style)
        if scale == 1 { cachedAttributed = value }
        return value
    }

    /// Layout at the current width, shrinking fonts first when
    /// `adjustsFontSizeToFit` is set (React Native iOS semantics).
    func currentLayout(_ content: PamTextContent) -> PamTextLayoutResult {
        let width = bounds.width
        let scale = content.options.adjustsFontSizeToFit ? fittingScale(content) : 1
        if let cachedLayout, cachedLayout.width == width, cachedLayout.scale == scale {
            return cachedLayout.result
        }
        var style = content.style
        style.fontScale *= scale
        let result = PamTextLayout.layout(
            attributed(content, scale: scale),
            style: style,
            width: width,
            ellipsize: content.options.ellipsize
        )
        cachedLayout = (width, scale, result)
        return result
    }

    private func fittingScale(_ content: PamTextContent) -> CGFloat {
        if fitScaleWidth == bounds.width, fitScaleHeight == bounds.height { return fitScale }
        fitScaleWidth = bounds.width
        fitScaleHeight = bounds.height
        func fits(_ scale: CGFloat) -> Bool {
            var unlimited = content.style
            unlimited.fontScale *= scale
            unlimited.maxLines = 0
            let result = PamTextLayout.layout(
                attributed(content, scale: scale),
                style: unlimited,
                width: bounds.width,
                ellipsize: content.options.ellipsize
            )
            let lineLimit = content.style.maxLines > 0 ? content.style.maxLines : Int.max
            return result.totalLineCount <= lineLimit && result.size.height <= bounds.height + 0.5
        }
        if fits(1) {
            fitScale = 1
            return 1
        }
        var low = max(0.01, min(1, content.options.minimumFontScale))
        var high: CGFloat = 1
        for _ in 0..<7 {
            let middle = (low + high) / 2
            if fits(middle) { low = middle } else { high = middle }
        }
        fitScale = low
        return low
    }

    private func reportTextLayoutIfNeeded() {
        guard let onTextLayout, let content = pamContent, bounds.width > 0 else { return }
        let visible = currentLayout(content)
        var unlimited = content.style
        unlimited.maxLines = 0
        let full = PamTextLayout.layout(attributed(content), style: unlimited, width: bounds.width)
        let report = PamTextLayoutReport(
            lines: full.totalLineCount,
            visibleLines: visible.lines.count,
            truncated: visible.truncated,
            width: bounds.width,
            height: full.size.height,
            lineWidths: Array(full.lineWidths.prefix(200))
        )
        guard report != lastReport else { return }
        lastReport = report
        onTextLayout(report)
    }

    // MARK: Span presses

    private var hasPressableSpans: Bool {
        guard let spans = pamContent?.spans, !spans.isEmpty else { return false }
        return PamTextSpanSpec.parse(spans).contains { $0.press != nil }
    }

    private func updateInteraction() {
        let enabled = onSpanPress != nil && hasPressableSpans
        if enabled {
            if spanTap.view == nil { addGestureRecognizer(spanTap) }
            isUserInteractionEnabled = true
        } else if spanTap.view != nil {
            removeGestureRecognizer(spanTap)
        }
    }

    func spanSlot(at point: CGPoint) -> Int? {
        guard let content = pamContent else { return nil }
        let result = currentLayout(content)
        return PamTextLayout.pressSlot(
            at: point,
            in: result,
            content: attributed(content),
            boxWidth: bounds.width,
            alignment: content.options.alignment
        )
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        guard super.point(inside: point, with: event) else { return false }
        // Only pressable runs capture touches; the rest reach the parent
        // (Pressable/Button) like a plain label.
        if spanTap.view != nil, onSpanPress != nil {
            return spanSlot(at: point) != nil || (gestureRecognizers?.contains { $0 !== spanTap } ?? false)
        }
        return true
    }

    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer === spanTap else {
            return super.gestureRecognizerShouldBegin(gestureRecognizer)
        }
        return spanSlot(at: gestureRecognizer.location(in: self)) != nil
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        // A node-level press waits until the span tap fails.
        gestureRecognizer === spanTap && otherGestureRecognizer.view === self
    }

    @objc private func onSpanTap(_ recognizer: UITapGestureRecognizer) {
        guard recognizer.state == .ended,
              let slot = spanSlot(at: recognizer.location(in: self)) else { return }
        onSpanPress?(slot)
    }
}

/// Breaks the CADisplayLink → view retain cycle.
private final class PamTextMarqueeTicker: NSObject {
    private weak var view: PamTextView?

    init(_ view: PamTextView) {
        self.view = view
    }

    @objc func tick(_ link: CADisplayLink) {
        guard let view else {
            link.invalidate()
            return
        }
        view.advanceMarquee(link.timestamp)
    }
}
