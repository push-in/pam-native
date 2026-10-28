import AVFoundation
import UIKit

/// How media content sits in its view (`resizeMode`: 1 cover, 2 contain,
/// 3 fill), Android `PamSharedFit`.
enum PamSharedFit {
    static let cover = 1
    static let contain = 2
    static let fill = 3
}

/// The whole, uncropped content of a media view for a shared-element flight:
/// [view] draws it over its full bounds, whose natural size is [size].
struct PamSharedContent {
    let view: UIView
    let size: CGSize
    /// The picture as an image when there is one (a destination can keep it
    /// as its placeholder); a video frame snapshot has none.
    var image: UIImage?
}

/// A view that draws media (an image, a video frame). Its flight draws the
/// whole content and morphs the crop, so a cover thumbnail grows into a
/// contained full-screen view without stretching (Android parity).
protocol PamSharedContentView: UIView {
    var sharedFit: Int? { get }
    var hasSharedContent: Bool { get }
    func sharedContent() -> PamSharedContent?
    /// Shows [image] (what just flew in) until the view draws its own media;
    /// false when it already draws or cannot take it.
    func adoptSharedPlaceholder(_ image: UIImage) -> Bool
}

extension PamImageView: PamSharedContentView {
    var sharedFit: Int? {
        switch contentMode {
        case .scaleAspectFill: return PamSharedFit.cover
        case .scaleAspectFit: return PamSharedFit.contain
        case .scaleToFill: return PamSharedFit.fill
        default: return nil
        }
    }

    var hasSharedContent: Bool {
        guard let image else { return false }
        return image.size.width > 0 && image.size.height > 0
    }

    func sharedContent() -> PamSharedContent? {
        guard let image, image.size.width > 0, image.size.height > 0 else { return nil }
        let copy = UIImageView(image: image)
        copy.contentMode = .scaleToFill
        copy.frame = CGRect(origin: .zero, size: image.size)
        return PamSharedContent(view: copy, size: image.size, image: image)
    }

    /// Until the decoded image arrives (the loader replaces it).
    func adoptSharedPlaceholder(_ image: UIImage) -> Bool {
        guard !hasSharedContent else { return false }
        self.image = image
        return true
    }
}

/// Pure geometry of a flight (unit tested), Android `PamSharedGeometry`.
enum PamSharedGeometry {
    static func fit(_ box: CGSize, content: CGSize, fit: Int) -> CGRect {
        guard box.width > 0, box.height > 0, content.width > 0, content.height > 0,
              fit != PamSharedFit.fill else {
            return CGRect(origin: .zero, size: box)
        }
        let scale = fit == PamSharedFit.contain
            ? min(box.width / content.width, box.height / content.height)
            : max(box.width / content.width, box.height / content.height)
        let size = CGSize(width: content.width * scale, height: content.height * scale)
        return CGRect(
            x: (box.width - size.width) / 2,
            y: (box.height - size.height) / 2,
            width: size.width,
            height: size.height
        )
    }

    static func clip(_ box: CGSize, content: CGRect, fit: Int) -> CGRect {
        fit == PamSharedFit.contain
            ? content.intersection(CGRect(origin: .zero, size: box))
            : CGRect(origin: .zero, size: box)
    }

    static func lerp(_ from: CGRect, _ to: CGRect, _ progress: CGFloat) -> CGRect {
        CGRect(
            x: from.minX + (to.minX - from.minX) * progress,
            y: from.minY + (to.minY - from.minY) * progress,
            width: from.width + (to.width - from.width) * progress,
            height: from.height + (to.height - from.height) * progress
        )
    }
}

/// Shared-element flights of one navigation transition (Android
/// `PamSharedElements`). Each tag on both routes flies from its source frame
/// to its destination frame above the routes, animated by Core Animation:
/// no PHP per frame. Media flights interpolate the content rect, the visible
/// crop and the corner radius; other views fly as a snapshot. Only views on
/// screen take part, so a tag that scrolled away has no flight.
final class PamSharedElementFlights {
    struct Config: Decodable {
        var durationMs = 500
        var easing = 2
        var resizeMode = 1
        var crossFade = true
        var damping = 0.82
        var stiffness = 220.0
        var mass = 1.0
        var returnDurationMs: Int?
        var returnEasing: Int?
        var launch: Bool?
        var launchBackdrop: String?

        /// The curve of a flight back home (the element's route is popped).
        func returning() -> Config {
            var copy = self
            copy.durationMs = returnDurationMs ?? durationMs
            copy.easing = returnEasing ?? easing
            return copy
        }

        /// Core Animation timing of this curve (Android `PamSharedGeometry.ease`).
        var timing: UITimingCurveProvider {
            switch easing {
            case 1: return UICubicTimingParameters(animationCurve: .linear)
            case 3:
                // Damping ratio -> coefficient, like Reanimated's physics spring.
                let coefficient = min(max(damping, 0.01), 1) * 2 * (stiffness * mass).squareRoot()
                return UISpringTimingParameters(
                    mass: CGFloat(mass), stiffness: CGFloat(stiffness),
                    damping: CGFloat(coefficient), initialVelocity: .zero
                )
            case 4:
                return UICubicTimingParameters(
                    controlPoint1: CGPoint(x: 0.32, y: 0), controlPoint2: CGPoint(x: 0.67, y: 0)
                )
            case 5:
                // Ease-out cubic: a handed-off launch continues at speed.
                return UICubicTimingParameters(
                    controlPoint1: CGPoint(x: 0.215, y: 0.61), controlPoint2: CGPoint(x: 0.355, y: 1)
                )
            default: return UICubicTimingParameters(animationCurve: .easeInOut)
            }
        }
    }

    private struct Side {
        var clip: CGRect
        var content: CGRect
        var radius: CGFloat
    }

    private final class Flight {
        let source: UIView
        var destination: UIView?
        let sourceAlpha: CGFloat
        var destinationAlpha: CGFloat = 1
        var start: Side
        var end: Side?
        let container = UIView()
        var content: UIView
        var contentSize: CGSize
        var image: UIImage?
        var target: UIView?
        var config: Config
        /// The source's media view when it draws content; the destination's,
        /// once attached, gives the end geometry.
        let sourceMedia: PamSharedContentView?
        let contentFromSource: Bool
        var destinationMedia: PamSharedContentView?

        init(
            source: UIView,
            start: Side,
            content: UIView,
            contentSize: CGSize,
            target: UIView?,
            config: Config,
            sourceMedia: PamSharedContentView?,
            contentFromSource: Bool
        ) {
            self.source = source
            sourceAlpha = source.alpha
            self.start = start
            self.content = content
            self.contentSize = contentSize
            self.target = target
            self.config = config
            self.sourceMedia = sourceMedia
            self.contentFromSource = contentFromSource
        }
    }

    private unowned let host: UIView
    /// Above every route and a presented modal: flights fly over both.
    private var overlay: UIView?
    private var popping = false
    private var defaultConfig = Config()
    private var captured: [String: Flight] = [:]
    private var flights: [Flight] = []
    private var holding: [Flight] = []
    private var holdGeneration = 0

    init(host: UIView) {
        self.host = host
    }

    var count: Int { flights.count }
    var duration: TimeInterval {
        Double(flights.map(\.config.durationMs).max() ?? 0) / 1000
    }
    /// The curve of the longest flight (2 ease-in-out, 3 spring, 1 linear).
    var leadingConfig: Config? { flights.max { $0.config.durationMs < $1.config.durationMs }?.config }

    private var touch: CGPoint?

    /// The last touch in host coordinates: when a tag is on screen more than
    /// once (one author's avatar on several posts) the instance under the
    /// finger flies, and the flight home lands there (Android parity).
    func touched(_ point: CGPoint) { touch = point }

    private func pick(_ candidates: [UIView], route: UIView, minimumShare: CGFloat) -> UIView? {
        let visible = candidates.filter { onScreen($0, route: route, minimumShare: minimumShare) }
        guard visible.count > 1, let touch else { return visible.first }
        return visible.first { $0.convert($0.bounds, to: host).contains(touch) } ?? visible.first
    }

    /// Phase 1: measures and copies the on-screen sources while the outgoing
    /// route is still in the window (a video frame needs it), then hides them.
    func capture(outgoing: UIView, defaultDuration: TimeInterval, popping: Bool = false) {
        let kept = launched.flatMap { !popping && $0.flight.source.isDescendant(of: outgoing) ? $0 : nil }
        if let current = launched, current.flight !== kept?.flight { returnLaunch(current) }
        launched = nil
        clear()
        handing = kept
        if let kept {
            kept.animator?.stopAnimation(true)
            kept.timeout?.cancel()
        }
        self.popping = popping
        defaultConfig = Config(durationMs: Int(defaultDuration * 1000))
        for (tag, candidates) in taggedViews(in: outgoing) {
            guard captured.count < 16, tag != handing?.tag,
                  let source = pick(candidates, route: outgoing, minimumShare: 0.5) else { continue }
            let config = Self.config(for: source) ?? defaultConfig
            if let flight = makeFlight(source: source, config: config) {
                captured[tag] = flight
            }
        }
        captured.values.forEach { $0.source.alpha = 0 }
    }

    /// Phase 2: pairs the captured sources with on-screen destinations of the
    /// laid-out incoming route; unmatched sources come back at once.
    func attach(incoming: UIView) {
        let destinations = taggedViews(in: incoming)
        if let handing { handOff(handing, destinations: destinations, incoming: incoming) }
        handing = nil
        for (tag, flight) in captured {
            guard let destination = pick(destinations[tag] ?? [], route: incoming, minimumShare: 0),
                  let end = endSide(flight, destination: destination) else {
                flight.source.alpha = flight.sourceAlpha
                continue
            }
            flight.destination = destination
            flight.destinationAlpha = destination.alpha
            flight.end = end
            // The style of the element on the top route; a pop flies back
            // with its return curve.
            let top = popping ? flight.source : destination
            let below = popping ? destination : flight.source
            if let styled = Self.config(for: top) ?? Self.config(for: below) {
                flight.config = popping ? styled.returning() : styled
            } else {
                flight.config = defaultConfig
            }
            if flight.sourceMedia == nil, flight.config.crossFade, destination.window != nil {
                flight.target = destination.snapshotView(afterScreenUpdates: true)
            }
            destination.alpha = 0
            mount(flight)
            flights.append(flight)
        }
        captured.removeAll()
    }

    private func mount(_ flight: Flight) {
        flight.container.clipsToBounds = true
        flight.container.isUserInteractionEnabled = false
        flight.content.bounds = CGRect(origin: .zero, size: flight.contentSize)
        flight.container.addSubview(flight.content)
        if let target = flight.target {
            target.bounds = CGRect(origin: .zero, size: flight.contentSize)
            target.alpha = 0
            flight.container.addSubview(target)
        }
        overlayView().addSubview(flight.container)
    }

    func apply(_ progress: CGFloat) {
        flights.forEach { apply($0, progress) }
    }

    private func apply(_ flight: Flight, _ progress: CGFloat) {
        do {
            guard let end = flight.end else { return }
            let clip = PamSharedGeometry.lerp(flight.start.clip, end.clip, progress)
            let content = PamSharedGeometry.lerp(flight.start.content, end.content, progress)
            flight.container.frame = clip
            flight.container.layer.cornerRadius = max(0, flight.start.radius + (end.radius - flight.start.radius) * progress)
            for view in [flight.content, flight.target].compactMap({ $0 }) {
                view.center = CGPoint(x: content.midX - clip.minX, y: content.midY - clip.minY)
                view.transform = CGAffineTransform(
                    scaleX: content.width / max(flight.contentSize.width, 1),
                    y: content.height / max(flight.contentSize.height, 1)
                )
            }
            if let target = flight.target {
                flight.content.alpha = 1 - progress
                target.alpha = progress
            }
        }
    }

    /// A property animator with the leading flight's curve (Core Animation
    /// interpolates on the render server), running [alongside] in step: a
    /// route fade follows the flight.
    func animator(duration: TimeInterval, alongside: (() -> Void)? = nil) -> UIViewPropertyAnimator {
        apply(0)
        let config = leadingConfig ?? Config()
        let span = flights.isEmpty ? duration : self.duration
        let animator = UIViewPropertyAnimator(duration: max(span, 0.01), timingParameters: config.timing)
        animator.addAnimations { [weak self] in
            self?.apply(1)
            alongside?()
        }
        return animator
    }

    /// Animates every flight to its destination with its own curve.
    func animate(duration: TimeInterval) {
        guard !flights.isEmpty else { return }
        animator(duration: duration).startAnimation()
    }

    private func overlayView() -> UIView {
        let parent: UIView = host.window ?? host
        if let overlay, overlay.superview === parent {
            parent.bringSubviewToFront(overlay)
            return overlay
        }
        let view = overlay ?? UIView()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        view.frame = parent === host ? host.bounds : host.convert(host.bounds, to: parent)
        parent.addSubview(view)
        overlay = view
        return view
    }

    private func dropOverlayIfIdle() {
        guard flights.isEmpty, holding.isEmpty else { return }
        overlay?.removeFromSuperview()
        overlay = nil
    }

    /// Ends the flights. A destination whose media is not drawn yet keeps its
    /// flight on top, at the end frame, until it draws or 0.7 s pass.
    func finish() {
        fadeFloor = 0
        let ended = flights
        flights.removeAll()
        captured.values.forEach { $0.source.alpha = $0.sourceAlpha }
        captured.removeAll()
        for flight in ended {
            flight.source.alpha = flight.sourceAlpha
            flight.destination?.alpha = flight.destinationAlpha
            if let media = flight.destinationMedia, !media.hasSharedContent {
                // The picture that flew in stays as the placeholder until the
                // destination draws (never an empty or black box).
                if let image = flight.image, media.adoptSharedPlaceholder(image) {
                    flight.container.removeFromSuperview()
                } else {
                    holding.append(flight)
                }
            } else {
                flight.container.removeFromSuperview()
            }
        }
        if !holding.isEmpty {
            holdGeneration += 1
            // Only a guard against a destination that never draws.
            pollHolding(generation: holdGeneration, deadline: CACurrentMediaTime() + 15)
        }
        dropOverlayIfIdle()
    }

    // MARK: Launch on tap

    private final class Launch {
        let tag: String
        let flight: Flight
        let backdrop: UIView
        let startedAt = CACurrentMediaTime()
        var animator: UIViewPropertyAnimator?
        var timeout: DispatchWorkItem?

        init(tag: String, flight: Flight, backdrop: UIView) {
            self.tag = tag
            self.flight = flight
            self.backdrop = backdrop
        }
    }

    private var launched: Launch?
    private var handing: Launch?
    /// Alpha the incoming route already shows through a launch backdrop.
    private(set) var fadeFloor: CGFloat = 0

    /// A tap on an element whose style says `launch` starts its flight now,
    /// toward the media contain-fit on the whole screen over a fading
    /// backdrop, before the app handles the tap (Android parity). The pushed
    /// route takes it over ([attach]); without one it flies back home.
    @discardableResult
    func launchFromTap(route: UIView, point: CGPoint) -> Bool {
        guard launched == nil, handing == nil, flights.isEmpty else { return false }
        var found: (String, UIView)?
        for (tag, views) in taggedViews(in: route) {
            for view in views where Self.config(for: view)?.launch == true
                && onScreen(view, route: route, minimumShare: 0.5)
                && view.convert(view.bounds, to: host).contains(point) {
                found = (tag, view)
            }
        }
        guard let (tag, source) = found, let config = Self.config(for: source),
              let flight = makeFlight(source: source, config: config),
              flight.sourceMedia != nil, flight.contentSize != .zero else { return false }
        let fitted = PamSharedGeometry.fit(host.bounds.size, content: flight.contentSize, fit: PamSharedFit.contain)
        flight.end = Side(clip: fitted, content: fitted, radius: 0)
        let backdrop = UIView(frame: host.bounds)
        backdrop.isUserInteractionEnabled = false
        backdrop.backgroundColor = Self.color(config.launchBackdrop) ?? .black
        backdrop.alpha = 0
        overlayView().addSubview(backdrop)
        mount(flight)
        source.alpha = 0
        let launch = Launch(tag: tag, flight: flight, backdrop: backdrop)
        launched = launch
        apply(flight, 0)
        let animator = UIViewPropertyAnimator(duration: max(Double(config.durationMs) / 1000, 0.01), timingParameters: config.timing)
        animator.addAnimations { [weak self] in
            self?.apply(flight, 1)
            backdrop.alpha = 1
        }
        animator.startAnimation()
        launch.animator = animator
        let timeout = DispatchWorkItem { [weak self] in
            guard let self, let current = self.launched, current === launch else { return }
            self.launched = nil
            self.returnLaunch(current)
        }
        launch.timeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + Double(config.durationMs) / 1000 + 0.9, execute: timeout)
        return true
    }

    private func handOff(_ launch: Launch, destinations: [String: [UIView]], incoming: UIView) {
        launch.timeout?.cancel()
        let flight = launch.flight
        guard let destination = pick(destinations[launch.tag] ?? [], route: incoming, minimumShare: 0) else {
            returnLaunch(launch)
            return
        }
        // The picture already flying stays: the end frame uses its aspect.
        let media = contentView(of: destination)
        flight.destinationMedia = media
        let frame = destination.convert(destination.bounds, to: host)
        let end = media.flatMap { item in
            item.sharedFit.flatMap { side(tagged: destination, media: item, fit: $0, contentSize: flight.contentSize) }
        } ?? Side(clip: frame, content: frame, radius: radius(of: destination))
        // Stopped where it is: the model values are the frame on screen now.
        flight.start = currentSide(flight)
        flight.end = end
        flight.destination = destination
        flight.destinationAlpha = destination.alpha
        destination.alpha = 0
        let elapsed = Int((CACurrentMediaTime() - launch.startedAt) * 1000)
        flight.config = Config(durationMs: min(max(flight.config.durationMs - elapsed, 120), 2_000), easing: 5)
        fadeFloor = launch.backdrop.alpha
        launch.backdrop.removeFromSuperview()
        flights.append(flight)
        // Above a modal presentation added since the launch.
        _ = overlayView()
    }

    private func returnLaunch(_ launch: Launch) {
        launch.timeout?.cancel()
        launch.animator?.stopAnimation(true)
        let flight = launch.flight
        flight.end = currentSide(flight)
        UIView.animate(withDuration: 0.18, delay: 0, options: [.curveEaseOut]) {
            self.apply(flight, 0)
            launch.backdrop.alpha = 0
        } completion: { _ in
            flight.source.alpha = flight.sourceAlpha
            flight.container.removeFromSuperview()
            launch.backdrop.removeFromSuperview()
            self.dropOverlayIfIdle()
        }
    }

    private func currentSide(_ flight: Flight) -> Side {
        let clip = flight.container.frame
        let scaleX = flight.content.transform.a
        let scaleY = flight.content.transform.d
        let size = CGSize(width: flight.contentSize.width * scaleX, height: flight.contentSize.height * scaleY)
        let center = CGPoint(x: flight.content.center.x + clip.minX, y: flight.content.center.y + clip.minY)
        return Side(
            clip: clip,
            content: CGRect(x: center.x - size.width / 2, y: center.y - size.height / 2, width: size.width, height: size.height),
            radius: flight.container.layer.cornerRadius
        )
    }

    private static func color(_ hex: String?) -> UIColor? {
        guard let hex, hex.count == 7, hex.hasPrefix("#"), let value = Int(hex.dropFirst(), radix: 16) else { return nil }
        return UIColor(
            red: CGFloat((value >> 16) & 0xFF) / 255, green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255, alpha: 1
        )
    }

    func clear() {
        fadeFloor = 0
        for flight in flights + Array(captured.values) {
            flight.source.alpha = flight.sourceAlpha
            flight.destination?.alpha = flight.destinationAlpha
            flight.container.removeFromSuperview()
        }
        flights.removeAll()
        captured.removeAll()
        holding.forEach { $0.container.removeFromSuperview() }
        holding.removeAll()
        holdGeneration += 1
        dropOverlayIfIdle()
    }

    private func pollHolding(generation: Int, deadline: CFTimeInterval) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
            guard let self, generation == self.holdGeneration else { return }
            let expired = CACurrentMediaTime() >= deadline
            self.holding.removeAll { flight in
                let ready = flight.destinationMedia?.hasSharedContent != false
                    || flight.destination?.window == nil
                if expired || ready { flight.container.removeFromSuperview() }
                return expired || ready
            }
            if self.holding.isEmpty {
                self.dropOverlayIfIdle()
            } else {
                self.pollHolding(generation: generation, deadline: deadline)
            }
        }
    }

    // MARK: Flights

    private func makeFlight(source: UIView, config: Config) -> Flight? {
        let media = contentView(of: source)
        if let media, let fit = media.sharedFit, let content = media.sharedContent(),
           let start = side(tagged: source, media: media, fit: fit, contentSize: content.size) {
            let flight = Flight(
                source: source, start: start, content: content.view, contentSize: content.size,
                target: nil, config: config, sourceMedia: media, contentFromSource: true
            )
            flight.image = content.image
            return flight
        }
        if let media, media.sharedFit != nil, !media.hasSharedContent {
            // Media still loading: the destination will provide the pixels.
            let frame = source.convert(source.bounds, to: host)
            return Flight(
                source: source,
                start: Side(clip: frame, content: frame, radius: radius(of: source)),
                content: UIView(), contentSize: .zero,
                target: nil, config: config, sourceMedia: media, contentFromSource: false
            )
        }
        guard let snapshot = Self.snapshot(of: source) else { return nil }
        let frame = source.convert(source.bounds, to: host)
        return Flight(
            source: source,
            start: Side(clip: frame, content: frame, radius: radius(of: source)),
            content: snapshot, contentSize: source.bounds.size,
            target: nil, config: config, sourceMedia: nil, contentFromSource: false
        )
    }

    private func endSide(_ flight: Flight, destination: UIView) -> Side? {
        let media = contentView(of: destination)
        flight.destinationMedia = media
        if flight.sourceMedia != nil, let media, let fit = media.sharedFit {
            // The sharper picture flies: the destination's when it is already
            // drawn larger with the same aspect (a full-screen image).
            if media.hasSharedContent, let content = media.sharedContent(),
               flight.contentSize == .zero || (
                   content.size.width * content.size.height > flight.contentSize.width * flight.contentSize.height
                   && abs(content.size.width / content.size.height
                       - flight.contentSize.width / max(flight.contentSize.height, 1)) < 0.02
               ) {
                flight.content = content.view
                flight.contentSize = content.size
                flight.image = content.image ?? flight.image
            }
            if flight.contentSize != .zero {
                return side(tagged: destination, media: media, fit: fit, contentSize: flight.contentSize)
            }
        }
        let frame = destination.convert(destination.bounds, to: host)
        let endRadius = radius(of: destination)
        switch flight.config.resizeMode {
        case 3:
            let size = flight.start.content.size
            let rect = CGRect(origin: frame.origin, size: size)
            return Side(clip: rect, content: rect, radius: endRadius)
        case 2:
            let fitted = PamSharedGeometry.fit(frame.size, content: flight.start.content.size, fit: PamSharedFit.cover)
            return Side(clip: frame, content: fitted.offsetBy(dx: frame.minX, dy: frame.minY), radius: endRadius)
        default:
            return Side(clip: frame, content: frame, radius: endRadius)
        }
    }

    private func side(tagged: UIView, media: PamSharedContentView, fit: Int, contentSize: CGSize) -> Side? {
        let box = media.bounds.size
        guard box.width > 0, box.height > 0 else { return nil }
        let fitted = PamSharedGeometry.fit(box, content: contentSize, fit: fit)
        let visible = PamSharedGeometry.clip(box, content: fitted, fit: fit)
        let content = media.convert(fitted, to: host)
        var clip = media.convert(visible, to: host)
        if tagged !== media {
            clip = clip.intersection(tagged.convert(tagged.bounds, to: host))
            guard !clip.isNull, !clip.isEmpty else { return nil }
        }
        let radius = tagged.layer.cornerRadius > 0
            ? tagged.layer.cornerRadius * scale(of: tagged)
            : media.layer.cornerRadius > 0 ? media.layer.cornerRadius * scale(of: media) : frameRadius(of: tagged)
        return Side(clip: clip, content: content, radius: radius)
    }

    /// Radius of the rounded frame right around [view]: an ancestor with the
    /// same frame that clips its children (a rounded bubble around a photo).
    private func frameRadius(of view: UIView) -> CGFloat {
        let box = view.convert(view.bounds, to: host)
        var current = view.superview
        for _ in 0..<3 {
            guard let ancestor = current, ancestor !== host else { return 0 }
            let frame = ancestor.convert(ancestor.bounds, to: host)
            guard abs(frame.minX - box.minX) <= 1, abs(frame.minY - box.minY) <= 1,
                  abs(frame.maxX - box.maxX) <= 1, abs(frame.maxY - box.maxY) <= 1 else { return 0 }
            if ancestor.layer.cornerRadius > 0, ancestor.layer.masksToBounds || ancestor.clipsToBounds {
                return ancestor.layer.cornerRadius * scale(of: ancestor)
            }
            current = ancestor.superview
        }
        return 0
    }

    private func radius(of view: UIView) -> CGFloat {
        view.layer.cornerRadius > 0 ? view.layer.cornerRadius * scale(of: view) : frameRadius(of: view)
    }

    /// The tagged view when it draws media, else its top visible media child filling it.
    private func contentView(of tagged: UIView) -> PamSharedContentView? {
        if let media = tagged as? PamSharedContentView, media.sharedFit != nil { return media }
        let outer = tagged.convert(tagged.bounds, to: host)
        let area = outer.width * outer.height
        guard area > 0 else { return nil }
        var found: PamSharedContentView?
        func visit(_ view: UIView) {
            for child in view.subviews.reversed() {
                if let found, found.hasSharedContent { return }
                guard !child.isHidden, child.alpha > 0.01 else { continue }
                if let media = child as? PamSharedContentView, media.sharedFit != nil {
                    let box = media.convert(media.bounds, to: host)
                    if box.width * box.height >= area * 0.8, media.hasSharedContent || found == nil {
                        found = media
                    }
                    continue
                }
                visit(child)
            }
        }
        visit(tagged)
        return found
    }

    private func taggedViews(in root: UIView) -> [String: [UIView]] {
        var result: [String: [UIView]] = [:]
        func visit(_ view: UIView) {
            if let tag = view.layer.value(forKey: "pamSharedTransitionTag") as? String, !tag.isEmpty {
                result[tag, default: []].append(view)
            }
            view.subviews.forEach(visit)
        }
        visit(root)
        return result
    }

    /// On screen inside its route: every ancestor below the route shown and
    /// at least [minimumShare] of its area inside the clipping ancestors.
    private func onScreen(_ view: UIView, route: UIView, minimumShare: CGFloat) -> Bool {
        guard view.bounds.width > 0, view.bounds.height > 0 else { return false }
        var current: UIView? = view
        var visible = view.convert(view.bounds, to: host)
        let full = visible.width * visible.height
        while let next = current, next !== route {
            if next.isHidden || next.alpha <= 0.01 { return false }
            if next !== view, next.clipsToBounds || next.layer.masksToBounds {
                visible = visible.intersection(next.convert(next.bounds, to: host))
                if visible.isNull || visible.isEmpty { return false }
            }
            current = next.superview
        }
        guard current === route else { return false }
        visible = visible.intersection(host.bounds)
        guard !visible.isNull, !visible.isEmpty else { return false }
        return minimumShare <= 0 || visible.width * visible.height >= full * minimumShare * 0.98
    }

    private func scale(of view: UIView) -> CGFloat {
        let unit = view.convert(CGRect(x: 0, y: 0, width: 100, height: 100), to: host)
        return min(unit.width, unit.height) / 100
    }

    static func config(for view: UIView) -> Config? {
        guard let encoded = view.layer.value(forKey: "pamSharedTransitionConfig") as? String,
              let data = encoded.data(using: .utf8),
              var config = try? JSONDecoder().decode(Config.self, from: data) else {
            return nil
        }
        config.durationMs = min(max(config.durationMs, 0), 2_000)
        config.easing = min(max(config.easing, 1), 4)
        config.returnEasing = config.returnEasing.flatMap { (1...4).contains($0) ? $0 : nil }
        config.returnDurationMs = config.returnDurationMs.map { min(max($0, 0), 2_000) }
        config.resizeMode = min(max(config.resizeMode, 1), 3)
        config.damping = min(max(config.damping, 0.01), 1)
        config.stiffness = min(max(config.stiffness, 1), 1_000)
        config.mass = min(max(config.mass, 0.1), 10)
        return config
    }

    static func snapshot(of view: UIView) -> UIView? {
        if view.window != nil, !view.isHidden,
           let snapshot = view.snapshotView(afterScreenUpdates: false) {
            return snapshot
        }
        let size = view.bounds.size
        guard size.width > 0, size.height > 0 else { return nil }
        let format = UIGraphicsImageRendererFormat.default()
        format.opaque = view.isOpaque
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            view.layer.render(in: context.cgContext)
        }
        return UIImageView(image: image)
    }
}
