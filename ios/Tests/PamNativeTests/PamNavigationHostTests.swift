import XCTest
import UIKit
@testable import PamNative

@MainActor
final class PamNavigationHostTests: XCTestCase {
    func testRetainedRouteReleasesFirstResponderAndPreservesDraft() {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let controller = UIViewController()
        window.rootViewController = controller
        window.makeKeyAndVisible()
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        defer { window.isHidden = true }
        let host = PamNavigationHost(frame: window.bounds)
        controller.view.addSubview(host)
        host.operation = 2
        host.transition = 8
        let source = UIView(frame: window.bounds)
        let destination = UIView(frame: window.bounds)
        let input = PamInputField(frame: CGRect(x: 0, y: 100, width: 300, height: 44))
        input.text = "retained draft"
        source.addSubview(input)
        host.insert(source, index: 0)
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        XCTAssertTrue(input.becomeFirstResponder())
        host.insert(destination, index: 1)
        host.navigate(1)
        XCTAssertFalse(input.isFirstResponder)
        XCTAssertEqual(input.text, "retained draft")
        host.reorderRoute(source, index: 1)
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        XCTAssertTrue(input.becomeFirstResponder())
        XCTAssertEqual(input.text, "retained draft")
    }

    func testHidingOldRouteDoesNotResignDestinationInput() {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let controller = UIViewController()
        window.rootViewController = controller
        window.makeKeyAndVisible()
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        defer { window.isHidden = true }
        let host = PamNavigationHost(frame: window.bounds)
        controller.view.addSubview(host)
        host.operation = 2
        host.transition = 8
        let source = UIView(frame: window.bounds)
        let destination = UIView(frame: window.bounds)
        let first = PamInputField(frame: CGRect(x: 0, y: 100, width: 300, height: 44))
        let next = PamInputField(frame: first.frame)
        source.addSubview(first)
        destination.addSubview(next)
        host.insert(source, index: 0)
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        XCTAssertTrue(first.becomeFirstResponder())
        host.insert(destination, index: 1)
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        XCTAssertTrue(next.becomeFirstResponder())
        host.navigate(1)
        XCTAssertFalse(first.isFirstResponder)
        XCTAssertTrue(next.isFirstResponder)
    }

    func testLateAutomaticFocusRespectsHiddenAncestors() {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let controller = UIViewController()
        window.rootViewController = controller
        window.makeKeyAndVisible()
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        defer { window.isHidden = true }
        let source = UIView(frame: window.bounds)
        let input = PamInputField(frame: CGRect(x: 0, y: 100, width: 300, height: 44))
        controller.view.addSubview(source)
        source.addSubview(input)
        input.autoFocusRequested = true
        source.isHidden = true
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        XCTAssertFalse(input.isFirstResponder)
        source.isHidden = false
        input.requestAutomaticFocus()
        let deadline = Date().addingTimeInterval(2)
        while !input.isFirstResponder, Date() < deadline {
            RunLoop.main.run(until: Date().addingTimeInterval(0.02))
        }
        XCTAssertTrue(input.isFirstResponder)
    }

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

    func testRouteChangeReleasesTheInputOfTheScreenBelowAModalAtOnce() {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let controller = UIViewController()
        window.rootViewController = controller
        window.makeKeyAndVisible()
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        defer { window.isHidden = true }
        let host = PamNavigationHost(frame: window.bounds)
        controller.view.addSubview(host)
        host.operation = 2
        host.transition = 5
        host.duration = 0.2
        let below = UIView(frame: window.bounds)
        let modal = UIView(frame: window.bounds)
        let search = PamInputField(frame: CGRect(x: 0, y: 100, width: 300, height: 44))
        search.text = "draft"
        below.addSubview(search)
        host.insert(below, index: 0)
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        XCTAssertTrue(search.becomeFirstResponder())
        host.screenPresentation = 5
        host.insert(modal, index: 1)
        host.navigate(1)
        // React Navigation: blur + Keyboard.dismiss on the focus change, not
        // at the end of the transition (the screen below stays drawn).
        XCTAssertFalse(search.isFirstResponder)
        XCTAssertFalse(below.isHidden)
        XCTAssertEqual(search.text, "draft")
    }

    func testTransparentModalKeepsTheScreenBelowDrawnBeforeAndAfterItsFade() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let host = PamNavigationHost(frame: window.bounds)
        window.addSubview(host)
        host.operation = 2
        host.transition = 5
        host.duration = 0.05
        let below = UIView(frame: window.bounds)
        let modal = UIView(frame: window.bounds)
        host.insert(below, index: 0)
        host.screenPresentation = 5
        host.insert(modal, index: 1)
        // Mounted, not yet navigated: the screen on display stays.
        XCTAssertFalse(below.isHidden)
        XCTAssertTrue(modal.isHidden)
        host.navigate(1)
        XCTAssertFalse(modal.isHidden)
        XCTAssertEqual(below.alpha, 1)
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        XCTAssertFalse(below.isHidden)
        XCTAssertFalse(modal.isHidden)
        XCTAssertEqual(modal.alpha, 1)
    }

    func testARouteMountedWithoutANavigationShowsByTheNextRunLoop() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let host = PamNavigationHost(frame: window.bounds)
        window.addSubview(host)
        let first = UIView(frame: window.bounds)
        let second = UIView(frame: window.bounds)
        host.insert(first, index: 0)
        host.insert(second, index: 1)
        XCTAssertTrue(second.isHidden)
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        XCTAssertTrue(first.isHidden)
        XCTAssertFalse(second.isHidden)
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

    func testEdgePopUsesAScreenEdgeRecognizerThatScrollViewsWaitFor() {
        let host = PamNavigationHost(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        host.setGestureNavigation(
            enabled: true, edgeWidth: 24, threshold: 0.35, direction: 1, fullScreen: false,
            onPop: nil, onTransitionEnd: nil, onGestureStart: nil, onGestureEnd: nil, onGestureCancel: nil
        )
        XCTAssertTrue(host.screenEdgeGesture.isEnabled)
        XCTAssertEqual(host.screenEdgeGesture.edges, .left)
        let scroll = UIScrollView()
        XCTAssertTrue(host.gestureRecognizer(host.screenEdgeGesture, shouldBeRequiredToFailBy: scroll.panGestureRecognizer))
        XCTAssertFalse(host.gestureRecognizer(scroll.panGestureRecognizer, shouldBeRequiredToFailBy: host.screenEdgeGesture))
        // Full-screen and vertical gestures fall back to the plain pan.
        host.setGestureNavigation(
            enabled: true, edgeWidth: 24, threshold: 0.35, direction: 1, fullScreen: true,
            onPop: nil, onTransitionEnd: nil, onGestureStart: nil, onGestureEnd: nil, onGestureCancel: nil
        )
        XCTAssertFalse(host.screenEdgeGesture.isEnabled)
        host.setGestureNavigation(
            enabled: false, edgeWidth: 24, threshold: 0.35, direction: 1, fullScreen: false,
            onPop: nil, onTransitionEnd: nil, onGestureStart: nil, onGestureEnd: nil, onGestureCancel: nil
        )
        XCTAssertFalse(host.screenEdgeGesture.isEnabled)
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
