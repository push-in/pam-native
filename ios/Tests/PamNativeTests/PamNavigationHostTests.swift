import XCTest
import UIKit
@testable import PamNative

final class PamNavigationHostTests: XCTestCase {
    override func tearDown() {
        PamMotionPolicy.reduceMotionOverride = nil
        super.tearDown()
    }

    func testReducedMotionCommitsNavigationWithoutWaitingForAuthoredDuration() {
        PamMotionPolicy.reduceMotionOverride = true
        let completed = expectation(description: "reduced-motion transition")
        let host = PamNavigationHost(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        host.operation = 2
        host.transition = 2
        host.duration = 2
        host.setGestureNavigation(
            enabled: false,
            edgeWidth: 24,
            threshold: 0.35,
            onPop: nil,
            onTransitionEnd: { completed.fulfill() },
            onGestureStart: nil,
            onGestureEnd: nil,
            onGestureCancel: nil
        )
        let first = UIView()
        let second = UIView()
        host.insert(first, index: 0)
        host.insert(second, index: 1)
        host.navigate(1)

        wait(for: [completed], timeout: 0.5)
        XCTAssertTrue(first.isHidden)
        XCTAssertFalse(second.isHidden)
        XCTAssertEqual(second.alpha, 1)
        XCTAssertEqual(second.transform, .identity)
    }

    func testReorderingAPrewarmedRouteKeepsItInTheWindow() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let host = PamNavigationHost(frame: window.bounds)
        window.addSubview(host)
        let prewarmed = MovedToWindowCounter()
        let current = UIView()
        host.insert(current, index: 0)
        host.insert(prewarmed, index: 0)
        let attachments = prewarmed.windowChanges

        host.reorderRoute(prewarmed, index: 1)

        XCTAssertTrue(host.subviews.last === prewarmed)
        XCTAssertNotNil(prewarmed.window)
        XCTAssertEqual(prewarmed.windowChanges, attachments)
    }

    func testNativeTabHostRetainsScenesAndSelectsWithoutRemounting() {
        let host = PamTabHost(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let first = UIView()
        let second = UIView()
        host.insertScene(first, index: 0)
        host.insertScene(second, index: 1)
        host.configure(
            encodedItems: #"[{"name":"home","label":"Home","badge":null},{"name":"orders","label":"Orders","badge":"2"}]"#,
            selectedIndex: 1,
            position: 1,
            activeColor: .label,
            inactiveColor: .secondaryLabel,
            barColor: .systemBackground,
            indicatorColor: .label,
            swipeEnabled: false
        )
        host.selectForTesting(2)

        XCTAssertEqual(host.activeSceneIndex, 2)
        XCTAssertTrue(first.isHidden)
        XCTAssertFalse(second.isHidden)
    }

    /// Android parity (PamNavigationHostInstrumentedTest
    /// resetThenPushLandsOnThePushedRouteWhateverTheCommitTiming): a reset and
    /// a push folded into one commit create the routes in node-id order (the
    /// pushed chat before the Inbox under it); the destination is the top of
    /// the engine's route order, not the last inserted route.
    func testResetThenPushInOneCommitLandsOnThePushedRoute() {
        for attached in [false, true] {
            let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
            let root = UIViewController()
            window.rootViewController = root
            if attached { window.makeKeyAndVisible() }
            let host = PamNavigationHost(frame: root.view.bounds)
            root.view.addSubview(host)
            let launch = UIView()
            host.insert(launch, index: 0)
            host.removeRoute(launch)
            let chat = UIView()
            let inbox = UIView()
            host.insert(chat, index: 0)
            host.insert(inbox, index: 0)
            host.operation = 2
            host.transition = 8
            host.navigate(1)
            host.operation = 1
            host.navigate(2)

            XCTAssertFalse(chat.isHidden, "attached=\(attached)")
            XCTAssertTrue(inbox.isHidden, "attached=\(attached)")
        }
    }

    /// Keep-alive routes (Navigator::keepAlive, tab screens): a parked route
    /// moved back on top keeps its view and controller, and the move decides
    /// the destination like on Android (child order, not insertion order).
    func testMovedKeepAliveRouteBecomesTheDestinationWithItsView() {
        for attached in [false, true] {
            let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
            let root = UIViewController()
            window.rootViewController = root
            if attached { window.makeKeyAndVisible() }
            let host = PamNavigationHost(frame: root.view.bounds)
            root.view.addSubview(host)
            let inbox = UIView()
            let feed = UIView()
            host.insert(inbox, index: 0)
            host.insert(feed, index: 1)
            host.operation = 2
            host.transition = 8
            host.navigate(1)
            // Feed popped, then parked under the Inbox (moved to index 0).
            host.operation = 3
            host.navigate(2)
            feed.removeFromSuperview()
            host.insert(feed, index: 0)
            XCTAssertTrue(feed.isHidden, "attached=\(attached)")
            XCTAssertFalse(inbox.isHidden, "attached=\(attached)")
            // Back to the Feed tab: the same view moves on top.
            feed.removeFromSuperview()
            host.insert(feed, index: 1)
            host.operation = 2
            host.navigate(3)

            XCTAssertEqual(host.routeControllerCount, 2, "attached=\(attached)")
            XCTAssertFalse(feed.isHidden, "attached=\(attached)")
            XCTAssertTrue(inbox.isHidden, "attached=\(attached)")
        }
    }

    func testAttachedRoutesReceiveNativeViewControllers() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let root = UIViewController()
        window.rootViewController = root
        window.makeKeyAndVisible()
        let host = PamNavigationHost(frame: root.view.bounds)
        root.view.addSubview(host)
        let first = UIView()
        let second = UIView()
        host.insert(first, index: 0)
        host.insert(second, index: 1)
        host.operation = 2
        host.transition = 8
        host.navigate(1)

        XCTAssertTrue(host.usesNativeNavigationController)
        XCTAssertEqual(host.routeControllerCount, 2)
        XCTAssertFalse(second.isHidden)
        XCTAssertTrue(first.isHidden)
    }

    func testFormSheetUsesNativeControllerAndConfiguredDetents() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let root = UIViewController()
        window.rootViewController = root
        window.makeKeyAndVisible()
        let host = PamNavigationHost(frame: root.view.bounds)
        root.view.addSubview(host)
        host.insert(UIView(), index: 0)
        host.insert(UIView(), index: 1)
        host.operation = 2
        host.transition = 8
        host.screenPresentation = 7
        host.sheetDetents = [0.5, 1]
        host.sheetInitialDetentIndex = 1
        host.sheetGrabberVisible = true
        host.navigate(1)

        XCTAssertTrue(host.usesNativeModalController)
        XCTAssertEqual(host.activeSheetDetentCount, 2)
    }

    func testEveryPublicTransitionCompletesWithOnlyDestinationVisible() {
        for transition in 2...13 where transition != 8 {
            let completed = expectation(description: "transition \(transition)")
            let host = PamNavigationHost(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
            host.operation = 2
            host.transition = transition
            host.duration = 0.001
            host.setGestureNavigation(
                enabled: false,
                edgeWidth: 24,
                threshold: 0.35,
                onPop: nil,
                onTransitionEnd: { completed.fulfill() },
                onGestureStart: nil,
                onGestureEnd: nil,
                onGestureCancel: nil
            )
            let first = UIView()
            let second = UIView()
            host.insert(first, index: 0)
            host.insert(second, index: 1)
            host.navigate(Int64(transition))
            wait(for: [completed], timeout: 1)
            XCTAssertFalse(second.isHidden, "destination hidden for transition \(transition)")
            XCTAssertTrue(first.isHidden, "source visible after transition \(transition)")
            XCTAssertEqual(second.alpha, 1)
            XCTAssertEqual(second.transform, .identity)
        }
    }

    func testReducedDurationTransitionKeepsViewHierarchyRetained() {
        let host = PamNavigationHost(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        host.operation = 2
        host.transition = 8
        host.duration = 0
        let first = UIView()
        let second = UIView()
        host.insert(first, index: 0)
        host.insert(second, index: 1)
        host.navigate(1)
        XCTAssertEqual(host.subviews.count, 2)
        XCTAssertTrue(first.isHidden)
        XCTAssertFalse(second.isHidden)
    }

    func testCommittedInteractivePopSkipsASecondTransition() {
        let host = PamNavigationHost(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        host.operation = 2
        host.duration = 0
        host.insert(UIView(), index: 0)
        host.insert(UIView(), index: 1)
        host.navigate(1)

        // The interactive callback and its revision are covered together by
        // UI tests; this retained-host assertion guards the zero-duration
        // semantic commit path used to avoid replaying the pop animation.
        host.operation = 3
        host.navigate(2)
        XCTAssertEqual(host.subviews.count, 2)
        XCTAssertFalse(host.subviews[0].isHidden)
        XCTAssertTrue(host.subviews[1].isHidden)
    }

    func testNativeControllerAnimatesMultipleSharedElementsAndRestoresViews() throws {
        if UIAccessibility.isReduceMotionEnabled {
            throw XCTSkip("Reduced Motion intentionally disables shared-element movement")
        }
        let completed = expectation(description: "shared transition")
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let root = UIViewController()
        window.rootViewController = root
        window.makeKeyAndVisible()
        let host = PamNavigationHost(frame: root.view.bounds)
        host.operation = 2
        host.transition = 2
        host.duration = 0.02
        host.setGestureNavigation(
            enabled: false,
            edgeWidth: 24,
            threshold: 0.35,
            onPop: nil,
            onTransitionEnd: { completed.fulfill() },
            onGestureStart: nil,
            onGestureEnd: nil,
            onGestureCancel: nil
        )
        root.view.addSubview(host)

        let first = UIView(frame: host.bounds)
        let second = UIView(frame: host.bounds)
        for index in 0..<2 {
            let source = UIView(frame: CGRect(x: CGFloat(20 + index * 70), y: 80, width: 52, height: 52))
            source.backgroundColor = .systemBlue
            source.layer.setValue("item:\(index)", forKey: "pamSharedTransitionTag")
            first.addSubview(source)
            let destination = UIView(frame: CGRect(x: 180, y: CGFloat(180 + index * 90), width: 120, height: 72))
            destination.backgroundColor = .systemOrange
            destination.layer.cornerRadius = 18
            destination.layer.setValue("item:\(index)", forKey: "pamSharedTransitionTag")
            destination.layer.setValue(
                #"{"durationMs":40,"easing":2,"resizeMode":2,"crossFade":true,"damping":0.82,"stiffness":220,"mass":1}"#,
                forKey: "pamSharedTransitionConfig"
            )
            second.addSubview(destination)
        }
        host.insert(first, index: 0)
        host.insert(second, index: 1)
        host.navigate(1)

        XCTAssertTrue(host.usesNativeNavigationController)
        XCTAssertEqual(host.activeSharedElementCount, 2)
        wait(for: [completed], timeout: 1)
        XCTAssertEqual(host.activeSharedElementCount, 0)
        XCTAssertTrue(first.subviews.allSatisfy { !$0.isHidden })
        XCTAssertTrue(second.subviews.allSatisfy { !$0.isHidden })
    }
}

private final class MovedToWindowCounter: UIView {
    var windowChanges = 0

    override func didMoveToWindow() {
        super.didMoveToWindow()
        windowChanges += 1
    }
}
