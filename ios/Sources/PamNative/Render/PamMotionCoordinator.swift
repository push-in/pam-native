import Foundation
import UIKit

/// Property ids used by the 1.5.0 motion features that the iOS protocol table
/// does not name yet (kept private to the motion layer).
enum PamMotionKeys {
    static let textEllipsizeMode = 162
    static let pressRetentionLeft = 237
    static let pressRetentionTop = 238
    static let pressRetentionRight = 239
    static let pressRetentionBottom = 240
    static let pressDelayLongMs = 241
    static let pressDelayInMs = 242
    static let pressDelayOutMs = 243
    static let textEllipsizeMarquee: Int64 = 5
    static let dragSnapRequestStride: Int64 = 64
    static let maxTextLayoutLines = 200
}

/// Per-node motion state owned by the renderer (programs, transitions, drag,
/// text layout signature).
final class PamNodeMotion {
    var runner: PamMotionRunner?
    var programId: Int64 = .min
    var completedProgramId: Int64 = .min
    var restartKey: Int64 = .min
    var transitionRules: [String: PamTransitionRule] = [:]
    var transitionRunners: [PamMotionProperty: PamMotionRunner] = [:]
    var drag: PamDragController?
    var dragSnapRequest: Int64 = .min
    var textLayoutSignature = ""
    var textLayoutScheduled = false
    var tapEffectRunner: PamMotionRunner?

    func cancel() {
        runner?.cancel()
        runner = nil
        transitionRunners.values.forEach { $0.cancel() }
        transitionRunners = [:]
        tapEffectRunner?.cancel()
        tapEffectRunner = nil
    }
}

/// Glue between PamRenderer and the UI-thread motion primitives: animation
/// programs, CSS transitions, drags, tap effects, text layout and marquee.
final class PamMotionCoordinator {
    private var nodes: [Int64: PamNodeMotion] = [:]
    private let dispatch: (Int64, Int, Data) -> Void
    private let isMounted: (Int64) -> Bool
    private let wants: (Int64, Int) -> Bool
    /// First renderer-managed child of a node (not UIKit-internal subviews).
    private let firstChild: (Int64) -> UIView?

    init(
        dispatch: @escaping (Int64, Int, Data) -> Void,
        isMounted: @escaping (Int64) -> Bool,
        wants: @escaping (Int64, Int) -> Bool,
        firstChild: @escaping (Int64) -> UIView?
    ) {
        self.dispatch = dispatch
        self.isMounted = isMounted
        self.wants = wants
        self.firstChild = firstChild
    }

    func state(_ nodeId: Int64) -> PamNodeMotion {
        if let existing = nodes[nodeId] { return existing }
        let created = PamNodeMotion()
        nodes[nodeId] = created
        return created
    }

    func existing(_ nodeId: Int64) -> PamNodeMotion? { nodes[nodeId] }

    /// Direct manipulation adopts the displayed transform, leaving opacity alone.
    func takeOverTransform(nodeId: Int64, view: UIView) {
        let displayed = view.layer.animation(forKey: "transform") != nil
            ? view.layer.presentation()?.affineTransform() : nil
        if let motion = nodes[nodeId] {
            for property in Array(motion.transitionRunners.keys) where property.affectsTransform {
                motion.transitionRunners.removeValue(forKey: property)?.cancel()
            }
            if motion.runner?.animatesTransform == true {
                motion.runner?.cancel()
                motion.runner = nil
            }
            if motion.tapEffectRunner?.animatesTransform == true {
                motion.tapEffectRunner?.cancel()
                motion.tapEffectRunner = nil
            }
        }
        view.layer.removeAnimation(forKey: "transform")
        if let displayed { view.transform = displayed }
        PamMotionTarget.setTransform(PamMotionTarget.transform(of: view), on: view)
    }

    /// Node removed from the tree.
    func remove(_ nodeId: Int64) {
        guard let motion = nodes.removeValue(forKey: nodeId) else { return }
        motion.cancel()
        motion.drag?.detach()
    }

    /// View recycled out of a virtual list; the node survives. Finished
    /// finite programs are not replayed when the cell comes back.
    func dematerialize(_ nodeId: Int64) {
        guard let motion = nodes[nodeId] else { return }
        motion.cancel()
        motion.drag?.detach()
        motion.drag = nil
        motion.dragSnapRequest = .min
        motion.textLayoutSignature = ""
        if motion.programId != motion.completedProgramId {
            motion.programId = .min
        }
    }

    // MARK: Animation programs

    /// Plays a `pam-motion` program. A program id that already played is not
    /// restarted, so re-rendering an unchanged Animation never replays it.
    func configureProgram(nodeId: Int64, view: UIView, source: String?, force: Bool = false) {
        guard let source, let program = PamMotionProgram.parse(source) else { return }
        let motion = state(nodeId)
        if !force && program.id == motion.programId {
            if motion.runner == nil && motion.completedProgramId == program.id && program.iterations >= 0 {
                // Recycled view: restore the end state without replaying.
                apply(program: program, nodeId: nodeId, view: view, reduced: true, notify: false)
            }
            return
        }
        motion.programId = program.id
        motion.runner?.cancel()
        motion.runner = nil
        // Percentages resolve against the laid-out size.
        DispatchQueue.main.async { [weak self, weak view] in
            guard let self, let view, self.isMounted(nodeId), self.nodes[nodeId] === motion,
                  motion.programId == program.id else { return }
            self.apply(program: program, nodeId: nodeId, view: view, reduced: PamMotionPolicy.isReduced, notify: true)
        }
    }

    private func apply(program: PamMotionProgram, nodeId: Int64, view: UIView, reduced: Bool, notify: Bool) {
        let motion = state(nodeId)
        let timeline = PamMotionTimeline.build(
            program,
            current: { PamMotionTarget.read(view, $0) },
            resolve: { PamMotionTarget.resolve(view, $0, $1) }
        )
        let runner = PamMotionRunner(
            view: view,
            timeline: timeline,
            iterations: program.iterations,
            onComplete: { [weak self, weak motion] in
                guard let self, let motion else { return }
                motion.runner = nil
                motion.completedProgramId = program.id
                guard notify, self.isMounted(nodeId),
                      self.wants(nodeId, PamConstants.onAnimationComplete) else { return }
                self.dispatch(nodeId, EventKind.animationComplete.rawValue, Data(String(program.id).utf8))
            }
        )
        motion.runner = runner
        runner.start(reducedMotion: reduced)
    }

    func cancelProgram(_ nodeId: Int64) {
        guard let motion = nodes[nodeId] else { return }
        motion.runner?.cancel()
        motion.runner = nil
        motion.programId = .min
    }

    /// `replayKey`: replays programs and keyframes when the key changes
    /// (never on the first value).
    func restart(nodeId: Int64, key: Int64, replay: () -> Void) {
        let motion = state(nodeId)
        guard key != motion.restartKey else { return }
        let first = motion.restartKey == .min
        motion.restartKey = key
        if !first { replay() }
    }

    // MARK: CSS transitions

    func setTransitionSpec(nodeId: Int64, source: String?) {
        let motion = state(nodeId)
        motion.transitionRules = source.map(PamTransitionSpec.parse) ?? [:]
    }

    /// Animates one property with its CSS transition rule. Returns false when
    /// no rule applies (the caller sets the value directly).
    func animateTransition(nodeId: Int64, view: UIView, property: PamMotionProperty, target: Double) -> Bool {
        guard let motion = nodes[nodeId], !motion.transitionRules.isEmpty,
              let rule = PamTransitionSpec.rule(motion.transitionRules, for: property) else { return false }
        motion.transitionRunners.removeValue(forKey: property)?.cancel()
        // Mount and recycled views take the value immediately, like CSS.
        guard view.window != nil, !PamMotionPolicy.isReduced,
              rule.spring != nil || rule.durationMs > 0 || rule.delayMs > 0 else {
            PamMotionTarget.write(view, property, target)
            return true
        }
        view.layer.removeAnimation(forKey: "opacity")
        view.layer.removeAnimation(forKey: "transform")
        let step: PamMotionStep
        if let spring = rule.spring {
            step = .spring(to: PamMotionValue(target), config: spring, velocity: 0, delayMs: rule.delayMs)
        } else {
            step = .timing(to: PamMotionValue(target), durationMs: rule.durationMs, easing: rule.easing, delayMs: rule.delayMs)
        }
        let timeline = PamMotionTimeline.build(
            PamMotionProgram(id: 0, iterations: 1, phases: [[(property, [step])]]),
            current: { PamMotionTarget.read(view, $0) },
            resolve: { _, value in value.number }
        )
        let runner = PamMotionRunner(
            view: view,
            timeline: timeline,
            iterations: 1,
            onComplete: { [weak motion] in motion?.transitionRunners[property] = nil }
        )
        motion.transitionRunners[property] = runner
        runner.start(reducedMotion: false)
        return true
    }

    // MARK: Drag

    func configureDrag(nodeId: Int64, host: UIView, source: String?) -> PamDragController? {
        let motion = state(nodeId)
        guard let source, let config = PamDragConfig.cached(source) else {
            motion.drag?.configure(nil, onSettle: nil)
            return nil
        }
        let drag = motion.drag ?? PamDragController(host: host) { [weak self] in self?.firstChild(nodeId) }
        motion.drag = drag
        drag.configure(config) { [weak self] index, position in
            guard let self, self.isMounted(nodeId), self.wants(nodeId, PamConstants.onGestureSettle) else { return }
            let payload = (try? WireMap.encode([
                "snapIndex": .integer(Int64(index)),
                "position": .decimal(position),
            ])) ?? Data()
            self.dispatch(nodeId, EventKind.gestureSettle.rawValue, payload)
        }
        return drag
    }

    /// `dragSnap="index@request"` arrives as `request * 64 + index`.
    func applyDragSnap(nodeId: Int64, view: UIView, request: Int64?) {
        guard let request, request >= 0, let motion = nodes[nodeId], request != motion.dragSnapRequest,
              let drag = motion.drag else { return }
        motion.dragSnapRequest = request
        drag.snap(
            to: Int(request % PamMotionKeys.dragSnapRequestStride),
            animated: view.window != nil && view.bounds.width > 0
        )
    }

    // MARK: Tap effect

    /// Plays the TapEffect program centred on [point] (pressable coordinates).
    func playTapEffect(nodeId: Int64, pressable: UIView, source: String?, point: CGPoint) {
        guard let source, let effect = PamTapEffect.parse(source),
              let anchor = pressable.pamFindNativeRef(effect.ref),
              let container = anchor.superview else { return }
        let animated = firstChild(Int64(anchor.tag)) ?? anchor
        let target = pressable.convert(point, to: container)
        var transform = PamMotionTarget.transform(of: anchor)
        transform.translateX = target.x - anchor.center.x
        transform.translateY = target.y - anchor.center.y
        transform.rotationDegrees = effect.tiltDegrees > 0
            ? CGFloat(Double.random(in: -effect.tiltDegrees...effect.tiltDegrees))
            : 0
        PamMotionTarget.setTransform(transform, on: anchor)
        let motion = state(nodeId)
        motion.tapEffectRunner?.cancel()
        let timeline = PamMotionTimeline.build(
            effect.program,
            current: { PamMotionTarget.read(animated, $0) },
            resolve: { PamMotionTarget.resolve(animated, $0, $1) }
        )
        let runner = PamMotionRunner(view: animated, timeline: timeline, iterations: effect.program.iterations)
        motion.tapEffectRunner = runner
        runner.start(reducedMotion: PamMotionPolicy.isReduced)
    }

    // MARK: Text layout and marquee

    func scheduleTextLayout(nodeId: Int64, label: UILabel) {
        // PamTextView reports `onTextLayout` from its own CoreText layout.
        guard !(label is PamTextView) else { return }
        guard wants(nodeId, PamConstants.onTextLayout) else {
            nodes[nodeId]?.textLayoutSignature = ""
            return
        }
        let motion = state(nodeId)
        guard !motion.textLayoutScheduled else { return }
        motion.textLayoutScheduled = true
        DispatchQueue.main.async { [weak self, weak label] in
            motion.textLayoutScheduled = false
            guard let self, let label, self.isMounted(nodeId) else { return }
            self.reportTextLayout(nodeId: nodeId, label: label, motion: motion)
        }
    }

    private func reportTextLayout(nodeId: Int64, label: UILabel, motion: PamNodeMotion) {
        guard wants(nodeId, PamConstants.onTextLayout),
              let measurement = PamTextLayoutMeasurement.measure(label) else { return }
        let signature = measurement.signature
        guard signature != motion.textLayoutSignature else { return }
        motion.textLayoutSignature = signature
        let payload = (try? WireMap.encode([
            "lines": .integer(Int64(measurement.lines)),
            "visibleLines": .integer(Int64(measurement.visibleLines)),
            "truncated": .flag(measurement.truncated),
            "width": .decimal(Double(measurement.width)),
            "height": .decimal(Double(measurement.height)),
            "lineWidths": .text(measurement.lineWidthsJSON),
        ])) ?? Data()
        dispatch(nodeId, EventKind.textLayout.rawValue, payload)
    }

    func applyEllipsizeMode(label: UILabel, mode: Int64?) {
        // PamTextView draws its own marquee; only plain labels are re-classed.
        guard !(label is PamTextView) else { return }
        PamMarqueeLabel.setMarquee(mode == PamMotionKeys.textEllipsizeMarquee, on: label)
    }
}

/// RN `onTextLayout`: total wrapped lines at the current width (even when
/// `numberOfLines` truncates), visible lines, truncation and line widths.
struct PamTextLayoutMeasurement: Equatable {
    let lines: Int
    let visibleLines: Int
    let truncated: Bool
    let width: CGFloat
    let height: CGFloat
    let lineWidths: [CGFloat]

    var lineWidthsJSON: String {
        "[" + lineWidths.prefix(PamMotionKeys.maxTextLayoutLines)
            .map { String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), Double($0)) }
            .joined(separator: ",") + "]"
    }

    var signature: String { "\(lines)|\(visibleLines)|\(truncated)|\(width)|\(lineWidthsJSON)" }

    static func measure(_ label: UILabel) -> PamTextLayoutMeasurement? {
        let width = label.bounds.width
        guard width > 0 else { return nil }
        let text: NSAttributedString
        if let attributed = label.attributedText, attributed.length > 0 {
            text = attributed
        } else {
            text = NSAttributedString(
                string: label.text ?? "",
                attributes: [.font: label.font ?? UIFont.systemFont(ofSize: 14)]
            )
        }
        return measure(text: text, width: width, numberOfLines: label.numberOfLines)
    }

    static func measure(text: NSAttributedString, width: CGFloat, numberOfLines: Int) -> PamTextLayoutMeasurement {
        let storage = NSTextStorage(attributedString: text)
        let layout = NSLayoutManager()
        let container = NSTextContainer(size: CGSize(width: width, height: .greatestFiniteMagnitude))
        container.lineFragmentPadding = 0
        container.maximumNumberOfLines = 0
        container.lineBreakMode = .byWordWrapping
        layout.addTextContainer(container)
        storage.addLayoutManager(layout)
        layout.ensureLayout(for: container)
        var widths: [CGFloat] = []
        var height: CGFloat = 0
        if storage.length > 0 {
            layout.enumerateLineFragments(
                forGlyphRange: layout.glyphRange(for: container)
            ) { _, usedRect, _, _, _ in
                widths.append(usedRect.width)
                height = max(height, usedRect.maxY)
            }
        }
        let total = widths.count
        let visible = numberOfLines > 0 ? min(total, numberOfLines) : total
        return PamTextLayoutMeasurement(
            lines: total,
            visibleLines: visible,
            truncated: total > visible,
            width: width,
            height: height,
            lineWidths: widths
        )
    }
}

private var pamMarqueeStateKey: UInt8 = 0

private final class PamMarqueeState {
    var offset: CGFloat = 0
    var link: CADisplayLink?
    var lastTick: CFTimeInterval = 0
    var pausedUntil: CFTimeInterval = 0

    deinit { link?.invalidate() }
}

/// Single-line ticker for `ellipsizeMode="marquee"`. Plain UILabels are
/// re-classed (no extra ivars, so isa-swizzling is layout compatible) only
/// while the marquee is requested; other label subclasses clip instead.
final class PamMarqueeLabel: UILabel {
    static let pointsPerSecond: CGFloat = 30
    static let startDelay: CFTimeInterval = 1.2

    static func setMarquee(_ enabled: Bool, on label: UILabel) {
        if enabled {
            label.numberOfLines = 1
            label.lineBreakMode = .byClipping
            if type(of: label) == UILabel.self {
                object_setClass(label, PamMarqueeLabel.self)
            }
            (label as? PamMarqueeLabel)?.startMarquee()
        } else if let marquee = label as? PamMarqueeLabel {
            marquee.stopMarquee()
            object_setClass(marquee, UILabel.self)
            marquee.setNeedsDisplay()
        }
    }

    private var marquee: PamMarqueeState {
        if let existing = objc_getAssociatedObject(self, &pamMarqueeStateKey) as? PamMarqueeState {
            return existing
        }
        let created = PamMarqueeState()
        objc_setAssociatedObject(self, &pamMarqueeStateKey, created, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        return created
    }

    private var contentWidth: CGFloat {
        let size = CGSize(width: CGFloat.greatestFiniteMagnitude, height: bounds.height)
        return ceil(super.textRect(forBounds: CGRect(origin: .zero, size: size), limitedToNumberOfLines: 1).width)
    }

    func startMarquee() {
        let state = marquee
        guard state.link == nil, window != nil, !PamMotionPolicy.isReduced else {
            setNeedsDisplay()
            return
        }
        state.pausedUntil = CACurrentMediaTime() + Self.startDelay
        state.lastTick = CACurrentMediaTime()
        let link = CADisplayLink(target: PamMarqueeTicker(self), selector: #selector(PamMarqueeTicker.tick(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 60, preferred: 60)
        link.add(to: .main, forMode: .common)
        state.link = link
    }

    func stopMarquee() {
        let state = marquee
        state.link?.invalidate()
        state.link = nil
        state.offset = 0
    }

    fileprivate func advance(_ now: CFTimeInterval) {
        let state = marquee
        defer { state.lastTick = now }
        guard now >= state.pausedUntil, contentWidth > bounds.width else { return }
        state.offset += Self.pointsPerSecond * CGFloat(now - state.lastTick)
        setNeedsDisplay()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { stopMarquee() } else { startMarquee() }
    }

    override func drawText(in rect: CGRect) {
        let full = contentWidth
        guard full > rect.width, objc_getAssociatedObject(self, &pamMarqueeStateKey) != nil else {
            super.drawText(in: rect)
            return
        }
        let gap = max(24, rect.width / 3)
        let cycle = full + gap
        let offset = marquee.offset.truncatingRemainder(dividingBy: cycle)
        super.drawText(in: CGRect(x: rect.minX - offset, y: rect.minY, width: full, height: rect.height))
        super.drawText(in: CGRect(x: rect.minX - offset + cycle, y: rect.minY, width: full, height: rect.height))
    }
}

private final class PamMarqueeTicker: NSObject {
    private weak var label: PamMarqueeLabel?

    init(_ label: PamMarqueeLabel) { self.label = label }

    @objc func tick(_ link: CADisplayLink) {
        guard let label, label.window != nil else {
            link.invalidate()
            return
        }
        label.advance(link.timestamp)
    }
}
