import Foundation
import QuartzCore
import UIKit

// UI-thread motion primitives shared by animation programs, CSS transitions,
// drag release and tap effects (port of the Android PamMotion.kt). Curves and
// parsing are pure Swift so they are unit-testable; only PamMotionRunner and
// PamMotionTarget touch UIKit. Lengths are points (the iOS equivalent of dp).

struct PamSpringConfig: Equatable {
    let stiffness: Double
    let damping: Double
    let mass: Double

    init?(stiffness: Double = 100, damping: Double = 10, mass: Double = 1) {
        guard stiffness.isFinite, stiffness > 0,
              damping.isFinite, damping >= 0,
              mass.isFinite, mass > 0 else { return nil }
        self.stiffness = stiffness
        self.damping = damping
        self.mass = mass
    }

    static let standard = PamSpringConfig()!
}

/// Closed-form damped harmonic oscillator (Reanimated `withSpring` model).
/// Velocity is expressed in value units per second.
final class PamSpring {
    static let maxDurationMs: Int64 = 10_000

    let config: PamSpringConfig
    let from: Double
    let to: Double
    let initialVelocity: Double
    private let x0: Double
    private let omega0: Double
    private let zeta: Double
    private let restDisplacement: Double
    private let restVelocity: Double
    private(set) var durationMs: Int64 = 0

    init(config: PamSpringConfig, from: Double, to: Double, initialVelocity: Double = 0) {
        self.config = config
        self.from = from
        self.to = to
        self.initialVelocity = initialVelocity
        x0 = from - to
        omega0 = (config.stiffness / config.mass).squareRoot()
        zeta = config.damping / (2 * (config.stiffness * config.mass).squareRoot())
        let scale = max(max(abs(to - from), abs(initialVelocity) * 0.05), 1e-3)
        restDisplacement = scale * 0.002
        restVelocity = scale * 0.02
        durationMs = computeDuration()
    }

    private func displacement(_ seconds: Double) -> Double {
        let v0 = initialVelocity
        if zeta < 1 {
            let omegaD = omega0 * (1 - zeta * zeta).squareRoot()
            return exp(-zeta * omega0 * seconds) * (
                x0 * cos(omegaD * seconds) +
                    (v0 + zeta * omega0 * x0) / omegaD * sin(omegaD * seconds)
            )
        }
        if zeta == 1 {
            return (x0 + (v0 + omega0 * x0) * seconds) * exp(-omega0 * seconds)
        }
        let root = (zeta * zeta - 1).squareRoot()
        let r1 = -omega0 * (zeta - root)
        let r2 = -omega0 * (zeta + root)
        let a = (v0 - r2 * x0) / (r1 - r2)
        let b = x0 - a
        return a * exp(r1 * seconds) + b * exp(r2 * seconds)
    }

    func position(_ seconds: Double) -> Double {
        seconds * 1_000 >= Double(durationMs) ? to : to + displacement(seconds)
    }

    func velocity(_ seconds: Double) -> Double {
        let h = 1e-4
        let denominator = seconds > h ? 2 * h : h + seconds
        return (displacement(seconds + h) - displacement(max(0, seconds - h))) / denominator
    }

    private func velocityAt(_ seconds: Double) -> Double {
        let h = 5e-4
        return (displacement(seconds + h) - displacement(seconds - h)) / (2 * h)
    }

    private func computeDuration() -> Int64 {
        if abs(x0) < restDisplacement && abs(initialVelocity) < restVelocity { return 0 }
        var ms: Int64 = 1
        while ms < Self.maxDurationMs {
            let seconds = Double(ms) / 1_000
            if abs(displacement(seconds)) < restDisplacement && abs(velocityAt(seconds)) < restVelocity {
                return ms
            }
            ms += 1
        }
        return Self.maxDurationMs
    }
}

struct PamEasing {
    let transform: (Double) -> Double

    func callAsFunction(_ progress: Double) -> Double { transform(progress) }

    static let linear = PamEasing { $0 }

    static func cubicBezier(_ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double) -> PamEasing {
        func sample(_ t: Double, _ a: Double, _ b: Double) -> Double {
            ((1 - 3 * b + 3 * a) * t + (3 * b - 6 * a)) * t * t + 3 * a * t
        }
        func derivative(_ t: Double, _ a: Double, _ b: Double) -> Double {
            3 * (1 - 3 * b + 3 * a) * t * t + 2 * (3 * b - 6 * a) * t + 3 * a
        }
        return PamEasing { progress in
            if progress <= 0 { return 0 }
            if progress >= 1 { return 1 }
            var t = progress
            for _ in 0..<8 {
                let x = sample(t, x1, x2) - progress
                if abs(x) < 1e-5 { return sample(t, y1, y2) }
                let slope = derivative(t, x1, x2)
                if abs(slope) < 1e-6 { break }
                t -= x / slope
            }
            var low = 0.0
            var high = 1.0
            t = progress
            for _ in 0..<24 {
                let x = sample(t, x1, x2)
                if abs(x - progress) < 1e-5 { return sample(t, y1, y2) }
                if x < progress { low = t } else { high = t }
                t = (low + high) / 2
            }
            return sample(t, y1, y2)
        }
    }
}

enum PamEasings {
    /// Cubic-bezier control points for every named curve, so the same token
    /// can drive both the CPU sampler and Core Animation timing functions.
    static let bezierPoints: [String: (Double, Double, Double, Double)] = [
        "ease": (0.25, 0.1, 0.25, 1),
        "ease-in": (0.42, 0, 1, 1),
        "ease-out": (0, 0, 0.58, 1),
        "ease-in-out": (0.42, 0, 0.58, 1),
        "ease-out-back": (0.34, 1.56, 0.64, 1),
    ]

    /// Approximations used only for CAMediaTimingFunction (Core Animation
    /// cannot express polynomial curves exactly).
    static let polynomialApproximations: [String: (Double, Double, Double, Double)] = [
        "ease-in-quad": (0.55, 0.085, 0.68, 0.53),
        "ease-out-quad": (0.25, 0.46, 0.45, 0.94),
        "ease-in-out-quad": (0.455, 0.03, 0.515, 0.955),
        "ease-in-cubic": (0.55, 0.055, 0.675, 0.19),
        "ease-out-cubic": (0.215, 0.61, 0.355, 1),
        "ease-in-out-cubic": (0.645, 0.045, 0.355, 1),
    ]

    private static let polynomial: [String: PamEasing] = [
        "linear": .linear,
        "ease-in-quad": PamEasing { $0 * $0 },
        "ease-out-quad": PamEasing { $0 * (2 - $0) },
        "ease-in-out-quad": PamEasing { p in p < 0.5 ? 2 * p * p : -1 + (4 - 2 * p) * p },
        "ease-in-cubic": PamEasing { $0 * $0 * $0 },
        "ease-out-cubic": PamEasing { p in let q = p - 1; return q * q * q + 1 },
        "ease-in-out-cubic": PamEasing { p in
            p < 0.5 ? 4 * p * p * p : (p - 1) * (2 * p - 2) * (2 * p - 2) + 1
        },
    ]

    /// `linear`, CSS names, `*-quad`, `*-cubic`, or `bezier:x1:y1:x2:y2`.
    static func parse(_ token: String) -> PamEasing {
        if let easing = polynomial[token] { return easing }
        if let points = controlPoints(token) {
            return .cubicBezier(points.0, points.1, points.2, points.3)
        }
        let fallback = bezierPoints["ease-in-out"]!
        return .cubicBezier(fallback.0, fallback.1, fallback.2, fallback.3)
    }

    static func controlPoints(_ token: String) -> (Double, Double, Double, Double)? {
        if let points = bezierPoints[token] { return points }
        guard token.hasPrefix("bezier:") else { return nil }
        let parts = token.dropFirst("bezier:".count).split(separator: ":").compactMap { Double($0) }
        guard parts.count == 4, parts.allSatisfy(\.isFinite) else { return nil }
        return (min(max(parts[0], 0), 1), parts[1], min(max(parts[2], 0), 1), parts[3])
    }

    static func timingFunction(_ token: String) -> CAMediaTimingFunction {
        if token == "linear" { return CAMediaTimingFunction(name: .linear) }
        let points = controlPoints(token) ?? polynomialApproximations[token] ?? bezierPoints["ease-in-out"]!
        return CAMediaTimingFunction(
            controlPoints: Float(points.0),
            Float(points.1),
            Float(points.2),
            Float(points.3)
        )
    }
}

enum PamMotionProperty: String, CaseIterable {
    case opacity
    case translateX
    case translateY
    case scale
    case scaleX
    case scaleY
    case rotate
    case radius = "borderRadius"

    static func parse(_ token: String) -> PamMotionProperty? { PamMotionProperty(rawValue: token) }
}

/// A number in the property's unit (points for lengths) or a percentage of the
/// view's own extent.
struct PamMotionValue: Equatable {
    let number: Double
    let percent: Bool

    init(_ number: Double, percent: Bool = false) {
        self.number = number
        self.percent = percent
    }

    static func parse(_ token: String) -> PamMotionValue? {
        let trimmed = token.trimmingCharacters(in: .whitespaces)
        let percent = trimmed.hasSuffix("%")
        guard let number = Double(percent ? String(trimmed.dropLast()) : trimmed), number.isFinite else {
            return nil
        }
        return PamMotionValue(number, percent: percent)
    }
}

enum PamMotionStep: Equatable {
    case set(PamMotionValue)
    case wait(durationMs: Int64)
    case timing(to: PamMotionValue, durationMs: Int64, easing: String, delayMs: Int64)
    case spring(to: PamMotionValue, config: PamSpringConfig, velocity: Double, delayMs: Int64)
}

struct PamMotionProgram {
    let id: Int64
    let iterations: Int
    /// Phases run sequentially; property tracks inside a phase run in parallel.
    let phases: [[(PamMotionProperty, [PamMotionStep])]]

    func steps(phase: Int, property: PamMotionProperty) -> [PamMotionStep]? {
        guard phases.indices.contains(phase) else { return nil }
        return phases[phase].first { $0.0 == property }?.1
    }

    /// `pam-motion 1 id=<n> iterations=<n>` followed by
    /// `<phase> <property> <step> <step>...` lines.
    static func parse(_ source: String) -> PamMotionProgram? {
        let lines = source.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        guard let headerLine = lines.first else { return nil }
        let header = headerLine.split(separator: " ").map(String.init)
        guard header.count >= 2, header[0] == "pam-motion", header[1] == "1" else { return nil }
        var attributes: [String: String] = [:]
        for item in header.dropFirst(2) {
            let parts = item.split(separator: "=", maxSplits: 1).map(String.init)
            if parts.count == 2 { attributes[parts[0]] = parts[1] }
        }
        let id = attributes["id"].flatMap { Int64($0) } ?? 0
        let iterations = min(max(attributes["iterations"].flatMap { Int($0) } ?? 1, -1), 10_000)
        var phases: [Int: [(PamMotionProperty, [PamMotionStep])]] = [:]
        for line in lines.dropFirst() {
            let tokens = line.split(separator: " ").map(String.init).filter { !$0.isEmpty }
            guard tokens.count >= 3,
                  let phase = Int(tokens[0]), (0...63).contains(phase),
                  let property = PamMotionProperty.parse(tokens[1]) else { return nil }
            var tracks = phases[phase] ?? []
            var index = tracks.firstIndex { $0.0 == property }
            if index == nil {
                tracks.append((property, []))
                index = tracks.count - 1
            }
            for token in tokens.dropFirst(2) {
                guard let step = parseStep(token) else { return nil }
                tracks[index!].1.append(step)
                if tracks[index!].1.count > 64 { return nil }
            }
            phases[phase] = tracks
        }
        guard !phases.isEmpty else { return nil }
        return PamMotionProgram(
            id: id,
            iterations: iterations,
            phases: phases.keys.sorted().map { phases[$0]! }
        )
    }

    static func parseStep(_ token: String) -> PamMotionStep? {
        guard let open = token.firstIndex(of: "("), token.hasSuffix(")") else { return nil }
        let name = String(token[..<open])
        guard !name.isEmpty, name.allSatisfy({ $0.isLetter && $0.isLowercase }) else { return nil }
        let inner = token[token.index(after: open)..<token.index(before: token.endIndex)]
        guard !inner.contains("(") && !inner.contains(")") else { return nil }
        let args = inner.split(separator: ",", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
        func long(_ index: Int, _ fallback: Int64 = 0) -> Int64 {
            let value = index < args.count ? Double(args[index]).map { Int64($0) } : nil
            return min(max(value ?? fallback, 0), 60_000)
        }
        func double(_ index: Int, _ fallback: Double) -> Double {
            guard index < args.count, let value = Double(args[index]), value.isFinite else { return fallback }
            return value
        }
        switch name {
        case "set":
            guard let value = PamMotionValue.parse(args[0]) else { return nil }
            return .set(value)
        case "wait":
            return .wait(durationMs: long(0))
        case "timing":
            guard let to = PamMotionValue.parse(args[0]) else { return nil }
            let easing = args.count > 2 && !args[2].isEmpty ? args[2] : "ease-in-out"
            return .timing(to: to, durationMs: long(1, 300), easing: easing, delayMs: long(3))
        case "spring":
            guard let to = PamMotionValue.parse(args[0]),
                  let config = PamSpringConfig(
                    stiffness: double(1, 100),
                    damping: double(2, 10),
                    mass: double(3, 1)
                  ) else { return nil }
            return .spring(to: to, config: config, velocity: double(4, 0), delayMs: long(5))
        default:
            return nil
        }
    }
}

/// One resolved piece of a property's timeline, in absolute milliseconds.
struct PamMotionSegment {
    let startMs: Int64
    let endMs: Int64
    let sample: (Int64) -> Double

    func value(at ms: Int64) -> Double {
        sample(min(max(ms - startMs, 0), endMs - startMs))
    }
}

struct PamMotionTimeline {
    let durationMs: Int64
    let tracks: [(PamMotionProperty, [PamMotionSegment])]
    let initial: [PamMotionProperty: Double]

    var properties: [PamMotionProperty] { tracks.map(\.0) }

    func value(_ property: PamMotionProperty, at ms: Int64) -> Double? {
        guard let segments = tracks.first(where: { $0.0 == property })?.1,
              var value = initial[property] else { return nil }
        for segment in segments {
            if ms < segment.startMs { break }
            value = segment.value(at: ms)
        }
        return value
    }

    func finalValues() -> [PamMotionProperty: Double] {
        var result: [PamMotionProperty: Double] = [:]
        for property in properties { result[property] = value(property, at: durationMs) ?? 0 }
        return result
    }

    /// [current] supplies each property's starting value in output units and
    /// [resolve] converts authored values (points or percent) into them.
    static func build(
        _ program: PamMotionProgram,
        current: (PamMotionProperty) -> Double,
        resolve: (PamMotionProperty, PamMotionValue) -> Double
    ) -> PamMotionTimeline {
        var tracks: [(PamMotionProperty, [PamMotionSegment])] = []
        var initial: [PamMotionProperty: Double] = [:]
        var latest: [PamMotionProperty: Double] = [:]
        var phaseStart: Int64 = 0
        for phase in program.phases {
            var phaseEnd = phaseStart
            for (property, steps) in phase {
                var cursor = phaseStart
                var value: Double
                if let known = latest[property] {
                    value = known
                } else {
                    value = current(property)
                    initial[property] = value
                    latest[property] = value
                }
                var trackIndex = tracks.firstIndex { $0.0 == property }
                if trackIndex == nil {
                    tracks.append((property, []))
                    trackIndex = tracks.count - 1
                }
                for step in steps {
                    switch step {
                    case let .set(raw):
                        let target = resolve(property, raw)
                        tracks[trackIndex!].1.append(PamMotionSegment(startMs: cursor, endMs: cursor) { _ in target })
                        value = target
                    case let .wait(duration):
                        cursor += duration
                    case let .timing(raw, duration, easingToken, delay):
                        cursor += delay
                        let from = value
                        let to = resolve(property, raw)
                        let length = max(duration, 0)
                        let easing = PamEasings.parse(easingToken)
                        tracks[trackIndex!].1.append(
                            PamMotionSegment(startMs: cursor, endMs: cursor + length) { elapsed in
                                length == 0 ? to : from + (to - from) * easing(Double(elapsed) / Double(length))
                            }
                        )
                        cursor += length
                        value = to
                    case let .spring(raw, config, velocity, delay):
                        cursor += delay
                        let to = resolve(property, raw)
                        let spring = PamSpring(config: config, from: value, to: to, initialVelocity: velocity)
                        tracks[trackIndex!].1.append(
                            PamMotionSegment(startMs: cursor, endMs: cursor + spring.durationMs) {
                                spring.position(Double($0) / 1_000)
                            }
                        )
                        cursor += spring.durationMs
                        value = to
                    }
                }
                latest[property] = value
                phaseEnd = max(phaseEnd, cursor)
            }
            phaseStart = phaseEnd
        }
        return PamMotionTimeline(durationMs: phaseStart, tracks: tracks, initial: initial)
    }
}

/// Decomposed transform the motion system writes; UIView only stores the
/// composed matrix, so each component is remembered next to it.
struct PamMotionTransform: Equatable {
    var translateX: CGFloat = 0
    var translateY: CGFloat = 0
    var scaleX: CGFloat = 1
    var scaleY: CGFloat = 1
    var rotationDegrees: CGFloat = 0

    var affine: CGAffineTransform {
        CGAffineTransform(translationX: translateX, y: translateY)
            .rotated(by: rotationDegrees * .pi / 180)
            .scaledBy(x: scaleX, y: scaleY)
    }

    init() {}

    init(decomposing t: CGAffineTransform) {
        translateX = t.tx
        translateY = t.ty
        let sx = (t.a * t.a + t.b * t.b).squareRoot()
        scaleX = sx
        rotationDegrees = atan2(t.b, t.a) * 180 / .pi
        scaleY = sx > 0 ? (t.a * t.d - t.b * t.c) / sx : (t.c * t.c + t.d * t.d).squareRoot()
    }
}

private var pamMotionTransformKey: UInt8 = 0

/// Reads and writes animatable properties in view units (points).
enum PamMotionTarget {
    static func transform(of view: UIView) -> PamMotionTransform {
        if let stored = objc_getAssociatedObject(view, &pamMotionTransformKey) as? NSValue {
            var value = PamMotionTransform()
            let decoded = stored.cgAffineTransformValue
            // The stored components are only trusted while the view still
            // shows the matrix they compose to (the renderer may have set a
            // new transform since).
            if let components = objc_getAssociatedObject(view, &pamMotionComponentsKey) as? [CGFloat],
               components.count == 5, decoded == view.transform {
                value.translateX = components[0]
                value.translateY = components[1]
                value.scaleX = components[2]
                value.scaleY = components[3]
                value.rotationDegrees = components[4]
                return value
            }
        }
        return PamMotionTransform(decomposing: view.transform)
    }

    static func setTransform(_ value: PamMotionTransform, on view: UIView) {
        let affine = value.affine
        view.transform = affine
        objc_setAssociatedObject(
            view,
            &pamMotionTransformKey,
            NSValue(cgAffineTransform: affine),
            .OBJC_ASSOCIATION_RETAIN_NONATOMIC
        )
        objc_setAssociatedObject(
            view,
            &pamMotionComponentsKey,
            [value.translateX, value.translateY, value.scaleX, value.scaleY, value.rotationDegrees],
            .OBJC_ASSOCIATION_RETAIN_NONATOMIC
        )
    }

    static func read(_ view: UIView, _ property: PamMotionProperty) -> Double {
        let current = transform(of: view)
        switch property {
        case .opacity: return Double(view.alpha)
        case .translateX: return Double(current.translateX)
        case .translateY: return Double(current.translateY)
        case .scale, .scaleX: return Double(current.scaleX)
        case .scaleY: return Double(current.scaleY)
        case .rotate: return Double(current.rotationDegrees)
        case .radius: return Double(view.layer.cornerRadius)
        }
    }

    /// Untransformed size (frame is undefined while a transform is applied).
    static func size(of view: UIView) -> CGSize { view.bounds.size }

    static func resolve(_ view: UIView, _ property: PamMotionProperty, _ value: PamMotionValue) -> Double {
        guard value.percent else { return value.number }
        let size = size(of: view)
        switch property {
        case .translateX: return Double(size.width) * value.number / 100
        case .translateY: return Double(size.height) * value.number / 100
        case .radius: return Double(min(size.width, size.height)) * value.number / 100
        default: return value.number / 100
        }
    }

    static func write(_ view: UIView, _ property: PamMotionProperty, _ value: Double) {
        let number = CGFloat(value)
        switch property {
        case .opacity:
            view.alpha = min(max(number, 0), 1)
            return
        case .radius:
            let radius = max(number, 0)
            view.layer.cornerRadius = radius
            view.layer.masksToBounds = radius > 0 || view.clipsToBounds
            return
        default:
            break
        }
        var current = transform(of: view)
        switch property {
        case .translateX: current.translateX = number
        case .translateY: current.translateY = number
        case .scale:
            current.scaleX = number
            current.scaleY = number
        case .scaleX: current.scaleX = number
        case .scaleY: current.scaleY = number
        case .rotate: current.rotationDegrees = number
        case .opacity, .radius: break
        }
        setTransform(current, on: view)
    }
}

private var pamMotionComponentsKey: UInt8 = 0
private var pamNativeRefKey: UInt8 = 0

extension UIView {
    /// `nativeRef` name used by drags, drivers and tap effects.
    var pamNativeRef: String? {
        get { objc_getAssociatedObject(self, &pamNativeRefKey) as? String }
        set { objc_setAssociatedObject(self, &pamNativeRefKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC) }
    }

    func pamFindNativeRef(_ ref: String) -> UIView? {
        if pamNativeRef == ref { return self }
        for child in subviews {
            if let match = child.pamFindNativeRef(ref) { return match }
        }
        return nil
    }
}

/// Frame-synchronised driver (CADisplayLink) for one PamMotionTimeline on one
/// view. Nothing reaches PHP until onComplete.
final class PamMotionRunner {
    private weak var view: UIView?
    private let timeline: PamMotionTimeline
    private let iterations: Int
    private let onFrame: (() -> Void)?
    private let onComplete: (() -> Void)?
    private var link: CADisplayLink?
    private var startTime: CFTimeInterval = 0
    private var cancelled = false

    var isRunning: Bool { link != nil }

    init(
        view: UIView,
        timeline: PamMotionTimeline,
        iterations: Int,
        onFrame: (() -> Void)? = nil,
        onComplete: (() -> Void)? = nil
    ) {
        self.view = view
        self.timeline = timeline
        self.iterations = iterations
        self.onFrame = onFrame
        self.onComplete = onComplete
    }

    func start(reducedMotion: Bool) {
        if reducedMotion || timeline.durationMs <= 0 || iterations == 0 {
            apply(timeline.durationMs)
            onComplete?()
            return
        }
        apply(0)
        startTime = CACurrentMediaTime()
        let proxy = PamDisplayLinkProxy(self)
        let link = CADisplayLink(target: proxy, selector: #selector(PamDisplayLinkProxy.tick(_:)))
        if #available(iOS 15.0, *) {
            link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        }
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    func cancel() {
        cancelled = true
        link?.invalidate()
        link = nil
    }

    fileprivate func tick() {
        guard !cancelled, view != nil else {
            cancel()
            return
        }
        let elapsedMs = Int64((CACurrentMediaTime() - startTime) * 1_000)
        let duration = timeline.durationMs
        let iteration = elapsedMs / max(duration, 1)
        if iterations >= 0 && iteration >= Int64(iterations) {
            link?.invalidate()
            link = nil
            apply(duration)
            onComplete?()
            return
        }
        apply(elapsedMs % max(duration, 1))
    }

    private func apply(_ ms: Int64) {
        guard let view else { return }
        for property in timeline.properties {
            if let value = timeline.value(property, at: ms) {
                PamMotionTarget.write(view, property, value)
            }
        }
        onFrame?()
    }
}

private final class PamDisplayLinkProxy: NSObject {
    private weak var runner: PamMotionRunner?

    init(_ runner: PamMotionRunner) {
        self.runner = runner
    }

    @objc func tick(_ link: CADisplayLink) {
        guard let runner else {
            link.invalidate()
            return
        }
        runner.tick()
    }
}

/// One resolved CSS transition entry.
struct PamTransitionRule: Equatable {
    let durationMs: Int64
    let delayMs: Int64
    let easing: String
    let spring: PamSpringConfig?
}

/// `@property a,b`, `@duration ms,ms`, `@timing token,token`, `@delay ms,ms`;
/// shorter lists repeat cyclically, as in CSS.
enum PamTransitionSpec {
    static func parse(_ source: String) -> [String: PamTransitionRule] {
        var lists: [String: [String]] = [:]
        for raw in source.split(whereSeparator: \.isNewline) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            guard line.hasPrefix("@"), let space = line.firstIndex(of: " ") else { continue }
            let name = String(line[line.index(after: line.startIndex)..<space])
            lists[name] = line[line.index(after: space)...]
                .split(separator: ",", omittingEmptySubsequences: false)
                .map { $0.trimmingCharacters(in: .whitespaces) }
        }
        let properties = lists["property"]?.filter { !$0.isEmpty } ?? ["all"]
        let durations = lists["duration"] ?? ["0"]
        let timings = lists["timing"] ?? ["ease"]
        let delays = lists["delay"] ?? ["0"]
        var rules: [String: PamTransitionRule] = [:]
        for (index, property) in properties.enumerated() where property != "none" {
            let duration = min(max(Int64(durations[index % durations.count]) ?? 0, 0), 60_000)
            let delay = min(max(Int64(delays[index % delays.count]) ?? 0, 0), 60_000)
            let timing = timings[index % timings.count]
            var spring: PamSpringConfig?
            if timing.hasPrefix("spring:") {
                let values = timing.dropFirst("spring:".count).split(separator: ":").map { Double($0) }
                spring = PamSpringConfig(
                    stiffness: values.count > 0 ? values[0] ?? 100 : 100,
                    damping: values.count > 1 ? values[1] ?? 10 : 10,
                    mass: values.count > 2 ? values[2] ?? 1 : 1
                )
            }
            rules[property] = PamTransitionRule(durationMs: duration, delayMs: delay, easing: timing, spring: spring)
        }
        return rules
    }

    static func keys(for property: PamMotionProperty) -> [String] {
        switch property {
        case .opacity: return ["opacity", "all"]
        case .translateX, .translateY: return ["translate", "transform", "all"]
        case .scale, .scaleX, .scaleY: return ["scale", "transform", "all"]
        case .rotate: return ["rotate", "transform", "all"]
        case .radius: return ["border-radius", "all"]
        }
    }

    static func rule(_ rules: [String: PamTransitionRule], for property: PamMotionProperty) -> PamTransitionRule? {
        for key in keys(for: property) {
            if let rule = rules[key] { return rule }
        }
        return nil
    }
}

/// `ref=<nativeRef>;tilt=<deg>` line followed by a `pam-motion` program.
struct PamTapEffect {
    let ref: String
    let tiltDegrees: Double
    let program: PamMotionProgram

    static func parse(_ source: String) -> PamTapEffect? {
        guard let newline = source.firstIndex(of: "\n") else { return nil }
        var options: [String: String] = [:]
        for item in source[..<newline].split(whereSeparator: { $0 == ";" || $0 == " " }) {
            let parts = item.trimmingCharacters(in: .whitespaces).split(separator: "=", maxSplits: 1).map(String.init)
            if parts.count == 2 { options[parts[0]] = parts[1] }
        }
        guard let ref = options["ref"], !ref.isEmpty,
              let program = PamMotionProgram.parse(String(source[source.index(after: newline)...])) else {
            return nil
        }
        return PamTapEffect(
            ref: ref,
            tiltDegrees: min(max(options["tilt"].flatMap { Double($0) } ?? 0, 0), 180),
            program: program
        )
    }
}
