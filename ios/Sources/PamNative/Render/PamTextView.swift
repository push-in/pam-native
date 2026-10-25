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
final class PamTextView: UILabel, UIGestureRecognizerDelegate, PamPointerEventsHost {
    var pamPointerEvents = PamPointerEvents.auto

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        PamPointerEvents.hitTest(self, mode: pamPointerEvents) {
            super.hitTest(point, with: event)
        }
    }

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

    /// `Text::selectable()`: a long press offers Copy (iOS 16+ edit menu;
    /// no-op before).
    var isSelectable = false {
        didSet { if isSelectable != oldValue { updateInteraction() } }
    }

    /// `Text::dataDetector()`: 1 none, 2 phone, 3 link, 4 email, 5 all. A tap
    /// on a detected run opens it (Android `Linkify`).
    var dataDetectorType = 1 {
        didSet {
            if dataDetectorType != oldValue {
                dataDetector = nil
                updateInteraction()
            }
        }
    }
    private var dataDetector: NSDataDetector?
    private var editMenuInteraction: UIInteraction?
    private lazy var copyPress: UILongPressGestureRecognizer = {
        let recognizer = UILongPressGestureRecognizer(target: self, action: #selector(onCopyPress(_:)))
        recognizer.delegate = self
        recognizer.cancelsTouchesInView = false
        return recognizer
    }()
    private lazy var linkTap: UITapGestureRecognizer = {
        let recognizer = UITapGestureRecognizer(target: self, action: #selector(onLinkTap(_:)))
        recognizer.delegate = self
        recognizer.cancelsTouchesInView = true
        return recognizer
    }()

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

    /// Android `gravity = CENTER_VERTICAL`: a box taller than its text
    /// centres the lines instead of pinning them to the top.
    func textBox(for result: PamTextLayoutResult) -> CGRect {
        let extra = bounds.height - result.size.height
        guard extra > 0.5 else { return bounds }
        return CGRect(x: bounds.minX, y: bounds.minY + extra / 2, width: bounds.width, height: result.size.height)
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
            bounds: textBox(for: result),
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
        } else if spanTap.view != nil {
            removeGestureRecognizer(spanTap)
        }
        let detects = dataDetectorType > 1
        if detects {
            if linkTap.view == nil { addGestureRecognizer(linkTap) }
        } else if linkTap.view != nil {
            removeGestureRecognizer(linkTap)
        }
        let selectable: Bool
        if #available(iOS 16.0, *) {
            selectable = isSelectable
        } else {
            selectable = false
        }
        if selectable {
            if copyPress.view == nil { addGestureRecognizer(copyPress) }
        } else if copyPress.view != nil {
            removeGestureRecognizer(copyPress)
        }
        if enabled || detects || selectable {
            isUserInteractionEnabled = true
        }
    }

    // MARK: Selectable (copy) + data detectors

    @objc private func onCopyPress(_ recognizer: UILongPressGestureRecognizer) {
        guard recognizer.state == .began else { return }
        if #available(iOS 16.0, *) {
            let interaction: UIEditMenuInteraction
            if let existing = editMenuInteraction as? UIEditMenuInteraction {
                interaction = existing
            } else {
                let created = UIEditMenuInteraction(delegate: self)
                addInteraction(created)
                editMenuInteraction = created
                interaction = created
            }
            let configuration = UIEditMenuConfiguration(
                identifier: nil,
                sourcePoint: recognizer.location(in: self)
            )
            interaction.presentEditMenu(with: configuration)
        }
    }

    private func copyTextToPasteboard() {
        let value = pamContent?.text ?? text ?? ""
        guard !value.isEmpty else { return }
        UIPasteboard.general.string = value
    }

    private func detector() -> NSDataDetector? {
        if let dataDetector { return dataDetector }
        let types: NSTextCheckingResult.CheckingType
        switch dataDetectorType {
        case 2: types = .phoneNumber
        case 3, 4: types = .link
        case 5: types = [.link, .phoneNumber]
        default: return nil
        }
        dataDetector = try? NSDataDetector(types: types.rawValue)
        return dataDetector
    }

    private func detectedURL(for match: NSTextCheckingResult, in string: String) -> URL? {
        if let number = match.phoneNumber {
            let digits = number.filter { $0.isNumber || $0 == "+" }
            return digits.isEmpty ? nil : URL(string: "tel:\(digits)")
        }
        guard let url = match.url else { return nil }
        let mail = url.scheme?.lowercased() == "mailto"
        switch dataDetectorType {
        case 3: return mail ? nil : url
        case 4: return mail ? url : nil
        default: return url
        }
    }

    /// URL of the detected run under `point` (nil when none).
    func detectedURL(at point: CGPoint) -> URL? {
        guard let content = pamContent else { return nil }
        let result = currentLayout(content)
        let attributed = attributed(content)
        let string = attributed.string
        guard !string.isEmpty, let index = PamTextLayout.characterIndex(
            at: centredPoint(point, for: result),
            in: result,
            boxWidth: bounds.width,
            alignment: content.options.alignment
        ) else { return nil }
        let range = NSRange(location: 0, length: (string as NSString).length)
        for match in detector.matches(in: string, options: [], range: range)
        where NSLocationInRange(index, match.range) {
            return detectedURL(for: match, in: string)
        }
        return nil
    }

    @objc private func onLinkTap(_ recognizer: UITapGestureRecognizer) {
        guard recognizer.state == .ended,
              let url = detectedURL(at: recognizer.location(in: self)) else { return }
        UIApplication.shared.open(url)
    }

    /// Maps a view point into the (vertically centred) text box.
    private func centredPoint(_ point: CGPoint, for result: PamTextLayoutResult) -> CGPoint {
        let box = textBox(for: result)
        return CGPoint(x: point.x - (box.minX - bounds.minX), y: point.y - (box.minY - bounds.minY))
    }

    func spanSlot(at point: CGPoint) -> Int? {
        guard let content = pamContent else { return nil }
        let result = currentLayout(content)
        return PamTextLayout.pressSlot(
            at: centredPoint(point, for: result),
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
        if gestureRecognizer === spanTap {
            return spanSlot(at: gestureRecognizer.location(in: self)) != nil
        }
        if gestureRecognizer === linkTap {
            return detectedURL(at: gestureRecognizer.location(in: self)) != nil
        }
        return super.gestureRecognizerShouldBegin(gestureRecognizer)
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        // A node-level press waits until the span/link tap fails.
        (gestureRecognizer === spanTap || gestureRecognizer === linkTap) && otherGestureRecognizer.view === self
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

@available(iOS 16.0, *)
extension PamTextView: UIEditMenuInteractionDelegate {
    func editMenuInteraction(
        _ interaction: UIEditMenuInteraction,
        menuFor configuration: UIEditMenuConfiguration,
        suggestedActions: [UIMenuElement]
    ) -> UIMenu? {
        let copy = UIAction(title: "Copy", image: UIImage(systemName: "doc.on.doc")) { [weak self] _ in
            self?.copyTextToPasteboard()
        }
        return UIMenu(children: [copy])
    }
}
