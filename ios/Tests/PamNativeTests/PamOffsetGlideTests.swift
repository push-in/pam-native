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

    /// A page laid out again while its cube face is turned keeps its slot:
    /// once it faces the viewer it sits exactly at its frame.
    func testAPageLaidOutMidTurnRestsAtItsFrame() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 300, height: 600))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        var mutations: [Mutation] = [
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .scroll, properties: [
                PamConstants.scrollHorizontal: .flag(true),
                PamConstants.scrollPageTransform: .integer(1),
            ])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .row, properties: [:])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 300, height: 600)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 300, height: 600)),
            .layout(id: 3, frame: Frame(x: 0, y: 0, width: 900, height: 600)),
        ]
        for index in 0..<3 {
            mutations.append(.create(NodeSpec(id: Int64(10 + index), parent: 3, index: index, kind: .view, properties: [:])))
            mutations.append(.layout(id: Int64(10 + index), frame: Frame(x: Float(index) * 300, y: 0, width: 300, height: 600)))
        }
        mutations.append(.setRoot(1))
        renderer.commit([mutations])
        let scroll = try XCTUnwrap(host.viewWithTag(2) as? PamAnchoredScrollView)
        let page = try XCTUnwrap(host.viewWithTag(11))
        scroll.layoutIfNeeded()
        // Mid turn toward page 1, the engine lays the page out again.
        scroll.contentOffset = CGPoint(x: 120, y: 0)
        XCTAssertFalse(CATransform3DIsIdentity(page.layer.transform))
        renderer.commit([[.layout(id: 11, frame: Frame(x: 300, y: 0, width: 300, height: 600))]])
        scroll.setContentOffset(CGPoint(x: 300, y: 0), animated: false)
        XCTAssertTrue(CATransform3DIsIdentity(page.layer.transform))
        XCTAssertEqual(page.frame, CGRect(x: 300, y: 0, width: 300, height: 600))
    }

    /// A Row wrapping every page (with another node beside it) is the
    /// content row: its children are the faces, the row never turns.
    func testWrapperRowChildrenAreTheFaces() {
        let scroll = PamAnchoredScrollView(frame: CGRect(x: 0, y: 0, width: 300, height: 600))
        scroll.horizontal = true
        scroll.contentSize = CGSize(width: 900, height: 600)
        let row = UIView(frame: CGRect(x: 0, y: 0, width: 900, height: 600))
        row.tag = 5
        scroll.addSubview(row)
        let overlay = UIView(frame: CGRect(x: 0, y: 0, width: 10, height: 10))
        overlay.tag = 6
        scroll.addSubview(overlay)
        let pages = (0..<3).map { index -> UIView in
            let page = UIView(frame: CGRect(x: CGFloat(index) * 300, y: 0, width: 300, height: 600))
            page.tag = 10 + index
            row.addSubview(page)
            return page
        }
        XCTAssertTrue(PamPageTransformer.pageRow(scroll) === row)
        scroll.pamPageTransformer.setKind(PamPageTransformKind.cube.rawValue, on: scroll)
        scroll.contentOffset = CGPoint(x: 150, y: 0)
        XCTAssertTrue(CATransform3DIsIdentity(row.layer.transform), "the wrapper never turns")
        XCTAssertFalse(CATransform3DIsIdentity(pages[0].layer.transform))
        XCTAssertFalse(CATransform3DIsIdentity(pages[1].layer.transform))
        scroll.setContentOffset(CGPoint(x: 300, y: 0), animated: false)
        XCTAssertTrue(pages.allSatisfy { CATransform3DIsIdentity($0.layer.transform) }, "at rest on page 1")
        XCTAssertEqual(pages[1].frame.minX, 300)
    }
}
