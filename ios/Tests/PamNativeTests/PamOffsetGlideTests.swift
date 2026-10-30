import UIKit
import XCTest
@testable import PamNative

/// `contentOffsetAnimationMs` pagers (stories): a requested offset glides
/// there once, even when the same request is committed again mid-glide.
@MainActor
final class PamOffsetGlideTests: XCTestCase {
    func testSameTargetCommittedAgainKeepsTheRunningGlide() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 300, height: 600))
        let scroll = PamAnchoredScrollView(frame: window.bounds)
        scroll.horizontal = true
        scroll.contentSize = CGSize(width: 900, height: 600)
        window.addSubview(scroll)
        window.isHidden = false
        defer { window.isHidden = true }
        scroll.pamOffsetAnimationMs = 280
        // The first request positions without a glide.
        scroll.pamRequestedContentOffsetX = 0
        scroll.layoutIfNeeded()
        scroll.pamRequestedContentOffsetX = 300
        scroll.layoutIfNeeded()
        guard let glide = scroll.pamOffsetGlide else { return XCTFail("no glide started") }
        XCTAssertTrue(glide.isRunning)
        // A re-render every frame re-sends the same offset.
        let deadline = Date().addingTimeInterval(1.5)
        while Date() < deadline, scroll.contentOffset.x < 299.5 {
            RunLoop.main.run(until: Date().addingTimeInterval(1.0 / 60.0))
            scroll.pamRequestedContentOffsetX = 300
            scroll.layoutIfNeeded()
            if let current = scroll.pamOffsetGlide { XCTAssertTrue(current === glide) }
        }
        XCTAssertEqual(scroll.contentOffset.x, 300, accuracy: 0.5)
    }
}
