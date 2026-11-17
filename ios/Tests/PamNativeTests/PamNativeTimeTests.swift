import XCTest
import UIKit
@testable import PamNative

/// Native time primitives (`clock*`, `progress*`, `reveal*`). Mirrors
/// PamNativeTimeTest and PamNativeTimeInstrumentedTest on Android.
@MainActor
final class PamNativeTimeTests: XCTestCase {
    private let start: Int64 = 1_763_300_000_000

    override func tearDown() {
        PamMotionPolicy.reduceMotionOverride = nil
        super.tearDown()
    }

    func testFormatsEveryClockFormat() {
        XCTAssertEqual(PamClockFormat.format(PamClockFormat.minutesSeconds, wholeSeconds: 42), "0:42")
        XCTAssertEqual(PamClockFormat.format(PamClockFormat.minutesSeconds, wholeSeconds: 3_665), "1:01:05")
        XCTAssertEqual(PamClockFormat.format(PamClockFormat.paddedMinutesSeconds, wholeSeconds: 42), "00:42")
        XCTAssertEqual(PamClockFormat.format(PamClockFormat.paddedMinutesSeconds, wholeSeconds: 4_503), "75:03")
        XCTAssertEqual(PamClockFormat.format(PamClockFormat.hoursMinutesSeconds, wholeSeconds: 42), "0:00:42")
        XCTAssertEqual(PamClockFormat.format(PamClockFormat.seconds, wholeSeconds: 42), "42")
    }

    func testClocksCountUpPauseAndCountDown() {
        let up = PamClockSpec(
            source: PamTimeSource(startedAt: start, offsetMs: 2_000, running: true),
            durationMs: nil, endsAt: nil, format: PamClockFormat.paddedMinutesSeconds
        )
        XCTAssertEqual(up.label(at: start + 42_999), "00:44")
        XCTAssertEqual(up.nextChange(at: start + 42_999), 1)
        let paused = PamClockSpec(
            source: PamTimeSource(startedAt: start, offsetMs: 61_000, running: false),
            durationMs: nil, endsAt: nil, format: PamClockFormat.minutesSeconds
        )
        XCTAssertEqual(paused.label(at: start + 99_000), "1:01")
        XCTAssertEqual(paused.nextChange(at: start + 99_000), -1)
        let deadline = PamClockSpec(
            source: PamTimeSource(startedAt: nil, offsetMs: 0, running: true),
            durationMs: nil, endsAt: start + 5_000, format: PamClockFormat.seconds
        )
        XCTAssertEqual(deadline.label(at: start + 1), "5")
        XCTAssertEqual(deadline.nextChange(at: start + 1), 999)
        XCTAssertEqual(deadline.label(at: start + 5_000), "0")
        XCTAssertEqual(deadline.nextChange(at: start + 5_000), -1)
    }

    func testProgressAndReveal() {
        let bar = PamProgressSpec(source: PamTimeSource(startedAt: start, offsetMs: 0, running: true), durationMs: 30_000)
        XCTAssertEqual(bar.fraction(at: start + 15_000), 0.5, accuracy: 1e-6)
        XCTAssertFalse(bar.moving(at: start + 30_000))
        let typed = PamRevealSpec(charMs: 55, durationMs: 0, key: "a")
        XCTAssertEqual(typed.shown(elapsedMs: 54, count: 5), 0)
        XCTAssertEqual(typed.shown(elapsedMs: 55, count: 5), 1)
        XCTAssertEqual(typed.nextChange(elapsedMs: 275, count: 5), -1)
        XCTAssertEqual(PamNativeTime.graphemeEnds("a👍🏽b"), [1, 5, 6])
    }

    func testClockLabelTicksNativelyAndStopsWhenHidden() {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        defer { window.isHidden = true }
        let controller = UIViewController()
        window.rootViewController = controller
        window.makeKeyAndVisible()
        let route = UIView(frame: window.bounds)
        controller.view.addSubview(route)
        let label = PamTextView(frame: CGRect(x: 0, y: 100, width: 200, height: 40))
        label.text = "00:00"
        route.addSubview(label)
        PamTimeBinding.sync(
            label,
            clock: PamClockSpec(
                source: PamTimeSource(startedAt: PamNativeTime.nowMs - 5_000, offsetMs: 0, running: true),
                durationMs: nil, endsAt: nil, format: PamClockFormat.paddedMinutesSeconds
            ),
            progress: nil,
            reveal: nil
        )
        XCTAssertEqual(label.text, "00:05")
        XCTAssertTrue(label.pamTimeBinding?.isTicking == true)
        // The label ticks on its own. A loaded CI simulator may fire the next
        // tick late (or skip a second), so assert that it advanced.
        let deadline = Date().addingTimeInterval(4)
        while label.text == "00:05", Date() < deadline {
            RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        }
        XCTAssertTrue(["00:06", "00:07", "00:08", "00:09"].contains(label.text ?? ""), "clock stayed at \(label.text ?? "nil")")
        // A covered route: the next tick finds it hidden and stops.
        route.isHidden = true
        RunLoop.main.run(until: Date().addingTimeInterval(1.1))
        XCTAssertFalse(label.pamTimeBinding?.isTicking == true)
        XCTAssertTrue(label.pamTimeBinding?.isWaiting == true)
        route.isHidden = false
        label.pamTimeBinding?.wake()
        XCTAssertTrue(label.pamTimeBinding?.isTicking == true)
        PamTimeBinding.clear(label)
        XCTAssertEqual(label.text, "00:00")
        XCTAssertNil(label.pamTimeBinding)
    }

    func testRevealClipsUntilComplete() {
        PamMotionPolicy.reduceMotionOverride = false
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        defer { window.isHidden = true }
        let controller = UIViewController()
        window.rootViewController = controller
        window.makeKeyAndVisible()
        let label = PamTextView(frame: CGRect(x: 0, y: 100, width: 300, height: 40))
        label.text = "Hello"
        controller.view.addSubview(label)
        PamTimeBinding.sync(label, clock: nil, progress: nil, reveal: PamRevealSpec(charMs: 40, durationMs: 0, key: "a"))
        XCTAssertEqual(label.revealEnd, 0)
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        XCTAssertEqual(label.revealEnd, -1)
        XCTAssertFalse(label.pamTimeBinding?.isTicking == true)
    }
}
