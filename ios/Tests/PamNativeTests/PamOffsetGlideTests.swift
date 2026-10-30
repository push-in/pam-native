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

    func testCubeFacesFollowTheFinalOffsetOfAGlide() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 300, height: 600))
        let scroll = PamAnchoredScrollView(frame: window.bounds)
        scroll.horizontal = true
        scroll.contentSize = CGSize(width: 900, height: 600)
        let pages = (0..<3).map { index -> UIView in
            let page = UIView(frame: CGRect(x: CGFloat(index) * 300, y: 0, width: 300, height: 600))
            page.tag = 10 + index
            scroll.addSubview(page)
            return page
        }
        window.addSubview(scroll)
        window.isHidden = false
        defer { window.isHidden = true }
        scroll.pamPageTransformer.setKind(PamPageTransformKind.cube.rawValue, on: scroll)
        scroll.pamOffsetAnimationMs = 280
        scroll.pamRequestedContentOffsetX = 0
        scroll.layoutIfNeeded()
        scroll.pamRequestedContentOffsetX = 300
        scroll.layoutIfNeeded()
        let deadline = Date().addingTimeInterval(1.5)
        while Date() < deadline, scroll.pamOffsetGlide?.isRunning == true {
            RunLoop.main.run(until: Date().addingTimeInterval(1.0 / 60.0))
        }
        // No layout pass after the last step: the faces already follow it.
        XCTAssertEqual(scroll.contentOffset.x, 300, accuracy: 0.5)
        XCTAssertTrue(CATransform3DIsIdentity(pages[1].layer.transform), "the front page faces the viewer")
        XCTAssertTrue(CATransform3DIsIdentity(pages[0].layer.transform), "the page left behind is cleared")
    }

    func testCubeFacesFollowAProgrammaticJumpWithoutALayoutPass() {
        let scroll = PamAnchoredScrollView(frame: CGRect(x: 0, y: 0, width: 300, height: 600))
        scroll.horizontal = true
        scroll.contentSize = CGSize(width: 900, height: 600)
        let pages = (0..<3).map { index -> UIView in
            let page = UIView(frame: CGRect(x: CGFloat(index) * 300, y: 0, width: 300, height: 600))
            page.tag = 10 + index
            scroll.addSubview(page)
            return page
        }
        scroll.pamPageTransformer.setKind(PamPageTransformKind.cube.rawValue, on: scroll)
        // An intermediate step turned the faces.
        scroll.contentOffset = CGPoint(x: 120, y: 0)
        XCTAssertFalse(CATransform3DIsIdentity(pages[0].layer.transform))
        // A jump to page 1: the resting faces at once.
        scroll.setContentOffset(CGPoint(x: 300, y: 0), animated: false)
        XCTAssertTrue(CATransform3DIsIdentity(pages[1].layer.transform))
        XCTAssertTrue(CATransform3DIsIdentity(pages[0].layer.transform))
        XCTAssertTrue(CATransform3DIsIdentity(pages[2].layer.transform))
    }
}
