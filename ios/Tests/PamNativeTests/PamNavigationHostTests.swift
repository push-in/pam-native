import XCTest
import UIKit
@testable import PamNative

final class PamNavigationHostTests: XCTestCase {
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
