import XCTest
import UIKit
@testable import PamNative

/// Covered-route policy, native media clock and exact scroll requests
/// (1.41.0). Mirrors PamCoveredRouteMediaInstrumentedTest,
/// PamScrollRequestFlingInstrumentedTest and PamMediaClockTest on Android.
final class PamCoveredRouteTests: XCTestCase {
    private func window() -> (UIWindow, UIViewController) {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let controller = UIViewController()
        window.rootViewController = controller
        window.makeKeyAndVisible()
        return (window, controller)
    }

    private func drainMainQueue() {
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    }

    func testHiddenRouteAndInactiveTabCoverTheirDescendants() {
        let (window, controller) = window()
        defer { window.isHidden = true }
        let host = PamNavigationHost(frame: window.bounds)
        let tabs = PamTabHost(frame: window.bounds)
        controller.view.addSubview(tabs)
        let feed = UIView(frame: window.bounds)
        let other = UIView(frame: window.bounds)
        tabs.insertScene(feed, index: 0)
        tabs.insertScene(other, index: 1)
        tabs.configure(
            encodedItems: #"[{"name":"feed","label":"Feed","badge":null},{"name":"chat","label":"Chat","badge":null}]"#,
            selectedIndex: 1, position: 1, activeColor: .black, inactiveColor: .gray,
            barColor: .white, indicatorColor: .black, swipeEnabled: false
        )
        let leaf = UIView()
        feed.addSubview(host)
        let route = UIView(frame: window.bounds)
        route.addSubview(leaf)
        host.insert(route, index: 0)
        XCTAssertFalse(PamRouteCoverage.isCovered(leaf))
        tabs.selectForTesting(2)
        XCTAssertTrue(PamRouteCoverage.isCovered(leaf), "An inactive tab scene covers its routes")
        tabs.selectForTesting(1)
        XCTAssertFalse(PamRouteCoverage.isCovered(leaf))
        route.isHidden = true
        XCTAssertTrue(PamRouteCoverage.isCovered(leaf), "A hidden route covers its descendants")
        leaf.removeFromSuperview()
        XCTAssertTrue(PamRouteCoverage.isCovered(leaf), "Out of the window counts as covered")
    }

    func testCoveredRouteReleasesThePlayerAndKeepAliveOptsOut() {
        let (window, controller) = window()
        defer { window.isHidden = true }
        let route = UIView(frame: window.bounds)
        let host = PamNavigationHost(frame: window.bounds)
        controller.view.addSubview(host)
        host.operation = 2
        host.transition = 8
        let media = PamMediaView(frame: CGRect(x: 0, y: 0, width: 200, height: 120))
        let kept = PamMediaView(frame: CGRect(x: 0, y: 0, width: 200, height: 120))
        kept.setKeepAliveWhenCovered(true)
        route.addSubview(media)
        route.addSubview(kept)
        host.insert(route, index: 0)
        let source = FileManager.default.temporaryDirectory.appendingPathComponent("pam-covered.mp4").absoluteString
        media.setSource(source)
        kept.setSource(source)
        drainMainQueue()
        XCTAssertFalse(media.releasedForCover)
        host.insert(UIView(frame: window.bounds), index: 1)
        host.navigate(1)
        drainMainQueue()
        XCTAssertTrue(media.releasedForCover, "A covered route releases its player")
        XCTAssertFalse(kept.releasedForCover, "keepAliveWhenCovered opts out")
        route.isHidden = false
        PamRouteCoverage.notifyChanged()
        drainMainQueue()
        XCTAssertFalse(media.releasedForCover, "The route visible again recreates the player")
    }

    func testRouteFocusTrackerIsEdgeTriggered() {
        var tracker = PamRouteFocusTracker()
        XCTAssertNil(tracker.update(false))
        XCTAssertEqual(tracker.update(true), true)
        XCTAssertNil(tracker.update(true))
        XCTAssertEqual(tracker.update(false), false)
    }

    func testMediaClockFormatsMatchAndroid() {
        XCTAssertEqual(PamMediaClock.format(PamMediaClock.elapsed, position: 42.9, duration: 120), "0:42")
        XCTAssertEqual(PamMediaClock.format(PamMediaClock.remaining, position: 42, duration: 120), "1:18")
        XCTAssertEqual(PamMediaClock.format(PamMediaClock.remaining, position: 41.5, duration: 120), "1:19")
        XCTAssertEqual(PamMediaClock.format(PamMediaClock.elapsedAndDuration, position: 42.2, duration: 120), "0:42 / 2:00")
        XCTAssertEqual(PamMediaClock.format(PamMediaClock.duration, position: 0, duration: 120), "2:00")
        XCTAssertEqual(PamMediaClock.format(PamMediaClock.countdownSeconds, position: 10.2, duration: 15), "5")
        XCTAssertEqual(PamMediaClock.format(PamMediaClock.countdownSeconds, position: 15, duration: 15), "0")
        XCTAssertEqual(PamMediaClock.format(PamMediaClock.elapsed, position: 3_665, duration: 4_000), "1:01:05")
        XCTAssertEqual(PamMediaClock.format(PamMediaClock.elapsed, position: .nan, duration: .nan), "0:00")
        XCTAssertEqual(PamMediaClock.fraction(position: 30, duration: 120), 0.25, accuracy: 1e-6)
        XCTAssertEqual(PamMediaClock.fraction(position: 10, duration: 0), 0)
    }

    func testListScrollRequestLandsExactlyAndStopsDeceleration() {
        let (window, controller) = window()
        defer { window.isHidden = true }
        let list = PamVirtualListView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        controller.view.addSubview(list)
        list.contentSize = CGSize(width: 390, height: 20_000)
        list.setContentOffset(CGPoint(x: 0, y: 6_000), animated: true)
        list.scrollToLogicalOffset(0)
        XCTAssertEqual(list.contentOffset.y, 0, "A non-animated request lands exactly at once")
        drainMainQueue()
        XCTAssertEqual(list.contentOffset.y, 0, "Nothing keeps moving after the jump")
        list.scrollToLogicalOffset(1_000, animated: true)
        RunLoop.main.run(until: Date().addingTimeInterval(0.8))
        XCTAssertEqual(list.contentOffset.y, 1_000, accuracy: 0.5)
    }
}
