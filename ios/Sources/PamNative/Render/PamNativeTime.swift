import QuartzCore
import UIKit

/// `ClockFormat` (PHP authority; Android `PamClockFormat`): 1 `m:ss` .. 4 whole seconds.
enum PamClockFormat {
    static let minutesSeconds = 1
    static let paddedMinutesSeconds = 2
    static let hoursMinutesSeconds = 3
    static let seconds = 4

    static func format(_ format: Int, wholeSeconds: Int64) -> String {
        let total = max(0, wholeSeconds)
        let hours = total / 3_600
        let minutes = total % 3_600 / 60
        let secs = total % 60
        let two = { (value: Int64) in value < 10 ? "0\(value)" : "\(value)" }
        switch format {
        case paddedMinutesSeconds: return two(total / 60) + ":" + two(secs)
        case hoursMinutesSeconds: return "\(hours):" + two(minutes) + ":" + two(secs)
        case seconds: return "\(total)"
        default: return hours > 0 ? "\(hours):" + two(minutes) + ":" + two(secs) : "\(minutes):" + two(secs)
        }
    }
}

/// A PHP-owned time source: `offsetMs` already counted plus, while
/// `running`, the time since `startedAt` (epoch ms).
struct PamTimeSource: Equatable {
    var startedAt: Int64?
    var offsetMs: Int64
    var running: Bool

    var advancing: Bool { running && startedAt != nil }

    func elapsed(at now: Int64) -> Int64 {
        let base = max(0, offsetMs)
        guard let startedAt, running else { return base }
        return base + max(0, now - startedAt)
    }
}

/// `clock*` on a Text (Android `PamClockSpec`).
struct PamClockSpec: Equatable {
    var source: PamTimeSource
    var durationMs: Int64?
    var endsAt: Int64?
    var format: Int

    var countsDown: Bool { endsAt != nil || durationMs != nil }

    func value(at now: Int64) -> Int64 {
        if let endsAt { return max(0, endsAt - now) }
        if let durationMs { return max(0, durationMs - source.elapsed(at: now)) }
        return source.elapsed(at: now)
    }

    func label(at now: Int64) -> String {
        let value = value(at: now)
        return PamClockFormat.format(format, wholeSeconds: countsDown ? (value + 999) / 1_000 : value / 1_000)
    }

    /// Milliseconds until `label` changes, or -1 when it no longer changes.
    func nextChange(at now: Int64) -> Int64 {
        if endsAt == nil {
            guard source.advancing, let start = source.startedAt else { return -1 }
            if start > now { return start - now }
        }
        let value = value(at: now)
        if !countsDown { return 1_000 - value % 1_000 }
        if value <= 0 { return -1 }
        let part = value % 1_000
        return part == 0 ? 1_000 : part
    }
}

/// `progress*`: the fill fraction `elapsed / durationMs`.
struct PamProgressSpec: Equatable {
    var source: PamTimeSource
    var durationMs: Int64

    func fraction(at now: Int64) -> CGFloat {
        guard durationMs > 0 else { return 0 }
        return CGFloat(min(1, max(0, Double(source.elapsed(at: now)) / Double(durationMs))))
    }

    func moving(at now: Int64) -> Bool {
        source.advancing && durationMs > 0 && source.elapsed(at: now) < durationMs
    }
}

/// `reveal*`: one grapheme every `charMs` (or the text over `durationMs`).
struct PamRevealSpec: Equatable {
    var charMs: Int64
    var durationMs: Int64
    var key: String?

    private func step(_ count: Int) -> Double {
        charMs > 0 ? Double(charMs) : Double(durationMs) / Double(max(1, count))
    }

    func shown(elapsedMs: Double, count: Int) -> Int {
        guard count > 0 else { return 0 }
        let step = step(count)
        guard step > 0 else { return count }
        return Int(min(Double(count), (elapsedMs / step).rounded(.down)))
    }

    func nextChange(elapsedMs: Double, count: Int) -> Int64 {
        let shown = shown(elapsedMs: elapsedMs, count: count)
        guard shown < count else { return -1 }
        return max(1, Int64((Double(shown + 1) * step(count) - elapsedMs).rounded(.up)))
    }
}

enum PamNativeTime {
    static var nowMs: Int64 { Int64((Date().timeIntervalSince1970 * 1_000).rounded(.down)) }

    /// UTF-16 end offset of each grapheme (Swift `Character`) of `text`.
    static func graphemeEnds(_ text: String) -> [Int] {
        var ends: [Int] = []
        ends.reserveCapacity(text.utf16.count)
        var offset = 0
        for character in text {
            offset += character.utf16.count
            ends.append(offset)
        }
        return ends
    }
}

/// Drives one view's native time primitives from PHP-owned values with no
/// PHP render per second, frame or character (Android `PamTimeBinding`).
///
/// It works only while the view is on screen: in a window, no hidden or
/// transparent ancestor (covered route, inactive tab), inside the window and
/// with the app in the foreground. A clock wakes once per label change (a
/// one-shot timer), a fill every frame (a display link; 1 s steps with
/// reduced motion), a reveal once per character. Off screen it stops, and a
/// screen change (window move, route coverage, scroll or layout sweep, the
/// app returning) checks again.
final class PamTimeBinding: NSObject {
    private weak var view: UIView?
    private(set) var clock: PamClockSpec?
    private(set) var progress: PamProgressSpec?
    private(set) var reveal: PamRevealSpec?

    private var timer: Timer?
    private var link: CADisplayLink?
    private var observers: [NSObjectProtocol] = []
    private var waiting = false
    private var reduced = false
    private var revealElapsedMs: Double = 0
    private var revealLastTick: CFTimeInterval = -1
    private var graphemeSource: String?
    private var graphemes: [Int] = []

    /// Ticks that ran on screen (tests, diagnostics).
    private(set) var ticks = 0
    var isTicking: Bool { timer != nil || link != nil }
    var isWaiting: Bool { waiting }

    private init(view: UIView) {
        self.view = view
        super.init()
        let center = NotificationCenter.default
        for name in [
            PamRouteCoverage.didChange,
            PamImageResidency.sweepRequested,
            UIApplication.willEnterForegroundNotification,
            UIApplication.didBecomeActiveNotification,
        ] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                self?.wake()
            })
        }
        observers.append(center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.suspend() })
        observers.append(center.addObserver(
            forName: UIAccessibility.reduceMotionStatusDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.refresh() })
    }

    deinit {
        timer?.invalidate()
        link?.invalidate()
        for observer in observers {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    private func update(clock: PamClockSpec?, progress: PamProgressSpec?, reveal: PamRevealSpec?) {
        guard clock != self.clock || progress != self.progress || reveal != self.reveal else { return }
        let hadProgress = self.progress != nil
        if reveal == nil || self.reveal == nil || reveal?.key != self.reveal?.key {
            revealElapsedMs = 0
            revealLastTick = -1
        }
        self.clock = clock
        self.progress = progress
        self.reveal = reveal
        if clock == nil { (view as? PamTextView)?.nativeText = nil }
        if reveal == nil { (view as? PamTextView)?.revealEnd = -1 }
        if hadProgress, progress == nil { view?.transform = .identity }
        if clock == nil, progress == nil, reveal == nil {
            suspend()
            waiting = false
            return
        }
        refresh()
    }

    /// Applies the current values now and (re)starts ticking if on screen.
    func refresh() {
        stopTicking()
        reduced = PamMotionPolicy.isReduced
        if showing() {
            waiting = false
            ticks += 1
            schedule(apply(onScreen: true))
        } else {
            revealLastTick = -1
            // Only something that still moves waits for the screen.
            waiting = apply(onScreen: false) >= 0
        }
    }

    /// A screen change: a waiting binding checks whether it is seen again.
    func wake() {
        guard waiting, showing() else { return }
        waiting = false
        ticks += 1
        schedule(apply(onScreen: true))
    }

    private func suspend() {
        guard isTicking else { return }
        stopTicking()
        revealLastTick = -1
        waiting = true
    }

    fileprivate func tick() {
        timer = nil
        guard showing() else {
            link?.invalidate()
            link = nil
            revealLastTick = -1
            waiting = true
            return
        }
        ticks += 1
        schedule(apply(onScreen: true))
    }

    private func schedule(_ delay: Int64) {
        if delay < 0 {
            stopTicking()
            return
        }
        if delay == 0 {
            timer?.invalidate()
            timer = nil
            if link == nil {
                let link = CADisplayLink(target: PamTimeTicker(self), selector: #selector(PamTimeTicker.tick(_:)))
                link.add(to: .main, forMode: .common)
                self.link = link
            }
            return
        }
        link?.invalidate()
        link = nil
        timer?.invalidate()
        let timer = Timer(
            timeInterval: Double(delay) / 1_000,
            target: PamTimeTicker(self),
            selector: #selector(PamTimeTicker.fire(_:)),
            userInfo: nil,
            repeats: false
        )
        timer.tolerance = min(0.05, Double(delay) / 10_000)
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    private func stopTicking() {
        timer?.invalidate()
        timer = nil
        link?.invalidate()
        link = nil
    }

    private func showing() -> Bool {
        guard let view, let window = view.window else { return false }
        if window.windowScene?.activationState == .background { return false }
        if PamRouteCoverage.isCovered(view) { return false }
        // A fill's own scale (near 0 at the start) must not hide it: test its track.
        let probe = progress != nil ? (view.superview ?? view) : view
        let frame = probe.convert(probe.bounds, to: window)
        return !frame.isEmpty && frame.intersects(window.bounds)
    }

    /// Sets the values now; returns the delay to the next change, -1 for none.
    private func apply(onScreen: Bool) -> Int64 {
        guard let view else { return -1 }
        let now = PamNativeTime.nowMs
        var next: Int64 = -1
        func soonest(_ value: Int64) {
            guard value >= 0 else { return }
            next = next < 0 ? value : min(next, value)
        }
        if let clock {
            let label = clock.label(at: now)
            if let text = view as? PamTextView {
                text.nativeText = label
            } else if let plain = view as? UILabel, plain.text != label {
                plain.text = label
            }
            soonest(clock.nextChange(at: now))
        }
        if let progress {
            // Scale from the leading edge without moving the anchor point
            // (the renderer positions transformed views through center).
            let fraction = max(progress.fraction(at: now), 0.0001)
            let rtl = view.effectiveUserInterfaceLayoutDirection == .rightToLeft
            let shift = (1 - fraction) * view.bounds.width / 2 * (rtl ? 1 : -1)
            view.transform = CGAffineTransform(translationX: shift, y: 0).scaledBy(x: fraction, y: 1)
            if progress.moving(at: now) {
                if reduced {
                    soonest(1_000)
                } else {
                    let msPerPoint = progress.durationMs / Int64(max(1, view.bounds.width * view.contentScaleFactor))
                    soonest(msPerPoint >= 33 ? msPerPoint / 2 : 0)
                }
            }
        }
        if let reveal, let text = view as? PamTextView {
            if reduced {
                text.revealEnd = -1
            } else {
                if onScreen {
                    let tick = CACurrentMediaTime()
                    if revealLastTick >= 0 { revealElapsedMs += (tick - revealLastTick) * 1_000 }
                    revealLastTick = tick
                }
                let content = text.revealContent
                if content != graphemeSource {
                    graphemeSource = content
                    graphemes = PamNativeTime.graphemeEnds(content)
                }
                let shown = reveal.shown(elapsedMs: revealElapsedMs, count: graphemes.count)
                text.revealEnd = shown >= graphemes.count ? -1 : (shown == 0 ? 0 : graphemes[shown - 1])
                soonest(reveal.nextChange(elapsedMs: revealElapsedMs, count: graphemes.count))
            }
        }
        return next
    }

    /// Binds (or with all nil, releases) the primitives of `view`.
    static func sync(_ view: UIView, clock: PamClockSpec?, progress: PamProgressSpec?, reveal: PamRevealSpec?) {
        if clock == nil, progress == nil, reveal == nil {
            clear(view)
            return
        }
        let binding = view.pamTimeBinding ?? {
            let created = PamTimeBinding(view: view)
            view.pamTimeBinding = created
            return created
        }()
        binding.update(clock: clock, progress: progress, reveal: reveal)
    }

    /// Releases `view`'s primitives (node removed).
    static func clear(_ view: UIView) {
        guard let binding = view.pamTimeBinding else { return }
        binding.update(clock: nil, progress: nil, reveal: nil)
        binding.stopTicking()
        view.pamTimeBinding = nil
    }
}

/// Breaks the timer/display link → binding retain cycle.
private final class PamTimeTicker: NSObject {
    private weak var binding: PamTimeBinding?

    init(_ binding: PamTimeBinding) {
        self.binding = binding
    }

    @objc func tick(_ link: CADisplayLink) {
        guard let binding else {
            link.invalidate()
            return
        }
        binding.tick()
    }

    @objc func fire(_ timer: Timer) {
        binding?.tick()
    }
}

private var pamTimeBindingKey: UInt8 = 0

extension UIView {
    /// Native time primitives of this view (`clock*`, `progress*`, `reveal*`).
    var pamTimeBinding: PamTimeBinding? {
        get { objc_getAssociatedObject(self, &pamTimeBindingKey) as? PamTimeBinding }
        set { objc_setAssociatedObject(self, &pamTimeBindingKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC) }
    }
}
