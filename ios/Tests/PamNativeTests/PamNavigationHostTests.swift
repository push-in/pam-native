import XCTest
import UIKit
@testable import PamNative

final class PamNavigationHostTests: XCTestCase {
    func testEveryPublicTransitionCompletesWithOnlyDestinationVisible() {
        let completed = expectation(description: "all navigation transitions")
        DispatchQueue.main.async {
            for transition in 2...11 where transition != 8 {
                let host = PamNavigationHost(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
                host.operation = 2
                host.transition = transition
                host.duration = 0.001
                let first = UIView()
                let second = UIView()
                host.insert(first, index: 0)
                host.insert(second, index: 1)
                host.navigate(Int64(transition))
                RunLoop.main.run(until: Date().addingTimeInterval(0.01))
                XCTAssertFalse(second.isHidden, "destination hidden for transition \(transition)")
                XCTAssertTrue(first.isHidden, "source visible after transition \(transition)")
                XCTAssertEqual(second.alpha, 1)
                XCTAssertEqual(second.transform, .identity)
            }
            completed.fulfill()
        }
        wait(for: [completed], timeout: 2)
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
}
