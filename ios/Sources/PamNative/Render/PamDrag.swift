import Foundation
import UIKit

// UI-thread drag (port of the Android PamDrag.kt): bounded one-axis pan with
// rubber band, per-frame drivers on nativeRef views, snap selection and
// spring/timing settle. PHP receives only gesture end and settle events.

struct PamDragDriver: Equatable {
    let ref: String
    let property: PamMotionProperty
    let input: [PamMotionValue]
    let output: [PamMotionValue]
}

struct PamDragSettle: Equatable {
    let spring: PamSpringConfig?
    let durationMs: Int64
    let easing: String

    init(spring: PamSpringConfig?, durationMs: Int64 = 0, easing: String = "ease-out") {
        self.spring = spring
        self.durationMs = durationMs
        self.easing = easing
    }

    static let standard = PamDragSettle(spring: PamSpringConfig(stiffness: 260, damping: 22, mass: 1))

    static func parse(_ token: String) -> PamDragSettle? {
        let parts = token.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        func number(_ index: Int) -> Double? { index < parts.count ? Double(parts[index]) : nil }
        switch parts.first ?? "" {
        case "spring":
            guard let config = PamSpringConfig(
                stiffness: number(1) ?? 260,
                damping: number(2) ?? 22,
                mass: number(3) ?? 1
            ) else { return nil }
            return PamDragSettle(spring: config)
        case "timing":
            let duration = parts.count > 1 ? Int64(parts[1]) ?? 200 : 200
            let easing = parts.count > 2 && !parts[2].isEmpty ? parts[2] : "ease-out"
            return PamDragSettle(spring: nil, durationMs: min(max(duration, 0), 10_000), easing: easing)
        default:
            return nil
        }
    }
}

/// `key=value` lines: `axis`, `min`, `max`, `rubber`, `target`, `snaps`,
/// `settle`, `settle.<i>`, `threshold`, `velocity`, `haptic`, `group` and
/// repeated `drive=<ref>|<property>|<inputs>|<outputs>`.
struct PamDragConfig: Equatable {
    let horizontal: Bool
    let min: Double?
    let max: Double?
    let rubber: Double
    let target: String
    let snaps: [PamMotionValue]
    let settle: PamDragSettle
    let snapSettles: [Int: PamDragSettle]
    let threshold: Double
    let velocity: Double
    let haptic: Bool
    let group: String
    let drivers: [PamDragDriver]
    let textDrivers: [PamDragTextDriver]
    let touchInset: Double?
    let snapOnRelease: Bool

    func settle(for index: Int) -> PamDragSettle { snapSettles[index] ?? settle }

    private static let parsed = PamParseCache<PamDragConfig>()

    /// `parse` memoized by source (every list row carries the same program).
    static func cached(_ source: String) -> PamDragConfig? {
        parsed.value(for: source, parse: parse)
    }

    static func parse(_ source: String) -> PamDragConfig? {
        var values: [String: String] = [:]
        var drivers: [PamDragDriver] = []
        var textDrivers: [PamDragTextDriver] = []
        for raw in source.split(whereSeparator: { $0 == "\n" || $0 == ";" }) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            let parts = line.split(separator: "=", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { continue }
            if parts[0] == "drive" {
                if let driver = parseDriver(parts[1]) { drivers.append(driver) }
            } else if parts[0] == "text" {
                if let driver = PamDragTextDriver.parse(parts[1]) { textDrivers.append(driver) }
            } else {
                values[parts[0]] = parts[1]
            }
        }
        let horizontal: Bool
        switch values["axis"] ?? "x" {
        case "x": horizontal = true
        case "y": horizontal = false
        default: return nil
        }
        let snaps = (values["snaps"] ?? "0").split(separator: ",").compactMap { PamMotionValue.parse(String($0)) }
        guard !snaps.isEmpty, snaps.count <= 16 else { return nil }
        var snapSettles: [Int: PamDragSettle] = [:]
        for (key, value) in values where key.hasPrefix("settle.") {
            if let index = Int(key.dropFirst("settle.".count)), let settle = PamDragSettle.parse(value) {
                snapSettles[index] = settle
            }
        }
        func finite(_ key: String) -> Double? {
            guard let raw = values[key], let number = Double(raw), number.isFinite else { return nil }
            return number
        }
        return PamDragConfig(
            horizontal: horizontal,
            min: finite("min"),
            max: finite("max"),
            rubber: Swift.min(Swift.max(Double(values["rubber"] ?? "") ?? 0, 0), 1),
            target: values["target"] ?? "",
            snaps: snaps,
            settle: values["settle"].flatMap(PamDragSettle.parse) ?? .standard,
            snapSettles: snapSettles,
            threshold: Swift.max(Double(values["threshold"] ?? "") ?? 0, 0),
            velocity: Swift.max(Double(values["velocity"] ?? "") ?? 0, 0),
            haptic: values["haptic"] == "1",
            group: values["group"] ?? "",
            drivers: Array(drivers.prefix(16)),
            textDrivers: Array(textDrivers.prefix(8)),
            touchInset: finite("touch"),
            snapOnRelease: values["snapOnRelease"] != "0"
        )
    }

    private static func parseDriver(_ source: String) -> PamDragDriver? {
        let parts = source.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 4, let property = PamMotionProperty.parse(parts[1]) else { return nil }
        var input: [PamMotionValue] = []
        for token in parts[2].split(separator: ",") {
            guard let value = PamMotionValue.parse(String(token)) else { return nil }
            input.append(value)
        }
        var output: [PamMotionValue] = []
        for token in parts[3].split(separator: ",") {
            guard let value = PamMotionValue.parse(String(token)) else { return nil }
            output.append(value)
        }
        guard input.count >= 2, input.count == output.count, input.count <= 8 else { return nil }
        return PamDragDriver(ref: parts[0], property: property, input: input, output: output)
    }
}

/// Pure drag math so release decisions are unit-testable.
enum PamDragMath {
    static func bound(_ raw: Double, min: Double?, max: Double?, rubber: Double) -> Double {
        if let max, raw > max { return max + (raw - max) * rubber }
        if let min, raw < min { return min + (raw - min) * rubber }
        return raw
    }

    static func nearest(_ snaps: [Double], _ position: Double) -> Int {
        snaps.indices.min { abs(snaps[$0] - position) < abs(snaps[$1] - position) } ?? 0
    }

    /// Crossing the distance [threshold] or [velocityThreshold] moves to the
    /// adjacent snap in the travel direction; otherwise the drag springs back.
    static func release(
        _ snaps: [Double],
        origin: Int,
        position: Double,
        velocity: Double,
        threshold: Double,
        velocityThreshold: Double
    ) -> Int {
        let start = snaps.indices.contains(origin) ? snaps[origin] : 0
        let displacement = position - start
        let fast = velocityThreshold > 0 && abs(velocity) >= velocityThreshold &&
            (displacement == 0 || sign(velocity) == sign(displacement))
        let far = threshold > 0 && abs(displacement) >= threshold
        guard fast || far else { return origin }
        let direction = fast ? sign(velocity) : sign(displacement)
        guard direction != 0 else { return origin }
        return snaps.indices
            .filter { (snaps[$0] - start) * direction > 0 }
            .min { abs(snaps[$0] - start) < abs(snaps[$1] - start) } ?? origin
    }

    static func interpolate(_ input: [Double], _ output: [Double], _ value: Double) -> Double {
        guard input.count >= 2 else { return output.first ?? value }
        let ascending = input.first! <= input.last!
        let xs = ascending ? input : input.reversed()
        let ys = ascending ? output : output.reversed()
        if value <= xs.first! { return ys.first! }
        if value >= xs.last! { return ys.last! }
        for index in 1..<xs.count where value <= xs[index] {
            let span = xs[index] - xs[index - 1]
            if span == 0 { return ys[index] }
            let progress = (value - xs[index - 1]) / span
            return ys[index - 1] + (ys[index] - ys[index - 1]) * progress
        }
        return ys.last!
    }

    private static func sign(_ value: Double) -> Double {
        value > 0 ? 1 : (value < 0 ? -1 : 0)
    }
}

struct PamDragRelease: Equatable {
    let snapIndex: Int
    let thresholdReached: Bool
}

/// Drives a pan entirely on the main thread: bounded translation,
/// interpolated drivers, snap selection and settle.
final class PamDragController {
    private static var groups: [String: [WeakDrag]] = [:]

    private struct WeakDrag {
        weak var controller: PamDragController?
    }

    private weak var host: UIView?
    private let firstChild: () -> UIView?
    private(set) var config: PamDragConfig?
    private var onSettle: ((Int, Double) -> Void)?
    private var origin = 0
    private var restingIndex = -1
    private var startPosition = 0.0
    private var thresholdReached = false
    private var runner: PamMotionRunner?
    private let haptics = UIImpactFeedbackGenerator(style: .light)
    private var releasing = false
    private var releaseSettle: (() -> Void)?

    var currentIndex: Int { restingIndex }

    init(host: UIView, firstChild: @escaping () -> UIView?) {
        self.host = host
        self.firstChild = firstChild
    }

    deinit {
        runner?.cancel()
    }

    func configure(_ next: PamDragConfig?, onSettle: ((Int, Double) -> Void)?) {
        let previousGroup = config?.group ?? ""
        config = next
        self.onSettle = onSettle
        if !previousGroup.isEmpty { unregister(previousGroup) }
        if let group = next?.group, !group.isEmpty { register(group) }
    }

    func detach() {
        runner?.cancel()
        runner = nil
        if let group = config?.group, !group.isEmpty { unregister(group) }
    }

    func begin(location: CGPoint = .zero, translation: CGPoint = .zero) {
        guard let current = config, let target = target() else { return }
        runner?.cancel()
        runner = nil
        if let inset = current.touchInset {
            startPosition = Double(current.horizontal ? location.x - translation.x : location.y - translation.y) - inset
        } else {
            startPosition = read(target, current)
        }
        origin = PamDragMath.nearest(snapPositions(target, current), startPosition)
        thresholdReached = false
        if current.haptic { haptics.prepare() }
        closeOthers(current.group)
    }

    func update(translation: CGPoint) {
        guard let current = config, let target = target() else { return }
        let delta = Double(current.horizontal ? translation.x : translation.y)
        let bounded = PamDragMath.bound(
            startPosition + delta,
            min: current.min,
            max: current.max,
            rubber: current.rubber
        )
        write(target, current, bounded)
        let snaps = snapPositions(target, current)
        let originPosition = snaps.indices.contains(origin) ? snaps[origin] : 0
        let reached = current.threshold > 0 && abs(bounded - originPosition) >= current.threshold
        if reached != thresholdReached {
            thresholdReached = reached
            if reached && current.haptic { haptics.impactOccurred() }
        }
    }

    /// Ends the drag. A settle that completes within the release (reduced
    /// motion, or already at its snap) is reported by `flushReleaseSettle()`,
    /// after the gesture end has been delivered: settle never precedes end.
    func end(velocity: CGPoint, cancelled: Bool) -> PamDragRelease? {
        releasing = true
        defer { releasing = false }
        return release(velocity: velocity, cancelled: cancelled)
    }

    /// Reports a settle deferred by `end`; call after delivering the end.
    func flushReleaseSettle() {
        guard let settle = releaseSettle else { return }
        releaseSettle = nil
        settle()
    }

    private func settled(_ index: Int, _ position: Double) {
        guard let callback = onSettle else { return }
        if releasing {
            releaseSettle = { callback(index, position) }
        } else {
            callback(index, position)
        }
    }

    private func release(velocity: CGPoint, cancelled: Bool) -> PamDragRelease? {
        guard let current = config, let target = target() else { return nil }
        let position = read(target, current)
        if !current.snapOnRelease {
            settled(-1, position)
            return PamDragRelease(snapIndex: -1, thresholdReached: false)
        }
        let axisVelocity = Double(current.horizontal ? velocity.x : velocity.y)
        let index = cancelled ? origin : PamDragMath.release(
            snapPositions(target, current),
            origin: origin,
            position: position,
            velocity: axisVelocity,
            threshold: current.threshold,
            velocityThreshold: current.velocity
        )
        let reached = thresholdReached
        settle(to: index, velocity: axisVelocity, animated: true)
        return PamDragRelease(snapIndex: index, thresholdReached: reached)
    }

    /// Programmatic open/close; ignored while that snap is already resting.
    func snap(to index: Int, animated: Bool) {
        guard let current = config, current.snaps.indices.contains(index),
              index != restingIndex || runner != nil else { return }
        settle(to: index, velocity: 0, animated: animated)
    }

    private func settle(to index: Int, velocity: Double, animated: Bool) {
        guard let current = config, let target = target() else { return }
        let snaps = snapPositions(target, current)
        let destination = snaps.indices.contains(index) ? snaps[index] : 0
        runner?.cancel()
        let property = axisProperty(current)
        let settle = current.settle(for: index)
        let step: PamMotionStep
        if let spring = settle.spring {
            step = .spring(to: PamMotionValue(destination), config: spring, velocity: velocity, delayMs: 0)
        } else {
            step = .timing(to: PamMotionValue(destination), durationMs: settle.durationMs, easing: settle.easing, delayMs: 0)
        }
        let timeline = PamMotionTimeline.build(
            PamMotionProgram(id: 0, iterations: 1, phases: [[(property, [step])]]),
            current: { _ in self.read(target, current) },
            resolve: { _, value in value.number }
        )
        let next = PamMotionRunner(
            view: target,
            timeline: timeline,
            iterations: 1,
            onFrame: { [weak self, weak target] in
                guard let self, let target else { return }
                self.applyDrivers(target, current)
            },
            onComplete: { [weak self] in
                guard let self else { return }
                self.runner = nil
                self.restingIndex = index
                self.settled(index, destination)
            }
        )
        runner = next
        next.start(reducedMotion: !animated || PamMotionPolicy.isReduced)
    }

    private func axisProperty(_ current: PamDragConfig) -> PamMotionProperty {
        current.horizontal ? .translateX : .translateY
    }

    private func read(_ target: UIView, _ current: PamDragConfig) -> Double {
        PamMotionTarget.read(target, axisProperty(current))
    }

    private func write(_ target: UIView, _ current: PamDragConfig, _ value: Double) {
        PamMotionTarget.write(target, axisProperty(current), value)
        applyDrivers(target, current)
    }

    private func snapPositions(_ target: UIView, _ current: PamDragConfig) -> [Double] {
        current.snaps.map { PamMotionTarget.resolve(target, axisProperty(current), $0) }
    }

    private func applyDrivers(_ target: UIView, _ current: PamDragConfig) {
        guard !current.drivers.isEmpty || !current.textDrivers.isEmpty, let host else { return }
        let position = read(target, current)
        let size = PamMotionTarget.size(of: target)
        let extent = Double(current.horizontal ? size.width : size.height)
        for driver in current.drivers {
            let view = driver.ref.isEmpty ? target : host.pamFindNativeRef(driver.ref)
            guard let view else { continue }
            let input = driver.input.map { $0.percent ? extent * $0.number / 100 : $0.number }
            let output = driver.output.map { PamMotionTarget.resolve(view, driver.property, $0) }
            PamMotionTarget.write(view, driver.property, PamDragMath.interpolate(input, output, position))
        }
        for driver in current.textDrivers {
            guard let view = host.pamFindNativeRef(driver.ref) as? UILabel else { continue }
            let label = driver.label(position: position, extent: extent)
            if view.text != label { view.text = label }
        }
    }

    private func target() -> UIView? {
        guard let current = config else { return nil }
        if !current.target.isEmpty, let match = host?.pamFindNativeRef(current.target) {
            return match
        }
        return firstChild()
    }

    private func restIndex(_ current: PamDragConfig) -> Int {
        current.snaps.firstIndex { !$0.percent && $0.number == 0 } ?? 0
    }

    private func closeOthers(_ group: String) {
        guard !group.isEmpty, var members = Self.groups[group] else { return }
        members.removeAll { $0.controller == nil }
        Self.groups[group] = members
        for member in members {
            guard let other = member.controller, other !== self, let otherConfig = other.config else { continue }
            let rest = other.restIndex(otherConfig)
            if (other.restingIndex != rest && other.restingIndex >= 0) || other.runner != nil {
                other.snap(to: rest, animated: true)
            }
        }
    }

    private func register(_ group: String) {
        var members = Self.groups[group] ?? []
        members.removeAll { $0.controller == nil || $0.controller === self }
        members.append(WeakDrag(controller: self))
        Self.groups[group] = members
    }

    private func unregister(_ group: String) {
        Self.groups[group]?.removeAll { $0.controller == nil || $0.controller === self }
    }
}
