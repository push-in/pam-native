import XCTest
import UIKit
@testable import PamNative

/// Who owns a drag under the finger (Android `PamGestureArbiterInstrumentedTest`).
@MainActor
final class PamGestureArbiterTests: XCTestCase {
    private func pager(offset: CGFloat) -> (root: UIView, pager: UIScrollView, page: UIView) {
        let root = UIView(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let pager = UIScrollView(frame: root.bounds)
        pager.contentSize = CGSize(width: 390 * 3, height: 844)
        pager.contentOffset = CGPoint(x: offset, y: 0)
        let page = UIView(frame: CGRect(x: offset, y: 0, width: 390, height: 844))
        pager.addSubview(page)
        root.addSubview(pager)
        return (root, pager, page)
    }

    func testPagerWithAPreviousPageOwnsARightwardDrag() {
        let (root, _, page) = pager(offset: 390)
        XCTAssertEqual(
            PamGestureArbiter.ownership(from: page, upTo: root, horizontal: true, sign: 1, navigation: true),
            PamGestureArbiter.directional
        )
    }

    func testFirstPageLeavesARightwardDragToTheBackGesture() {
        let (root, _, page) = pager(offset: 0)
        XCTAssertEqual(
            PamGestureArbiter.ownership(from: page, upTo: root, horizontal: true, sign: 1, navigation: true),
            PamGestureArbiter.noOwner
        )
        // Toward its next page the pager still owns the drag.
        XCTAssertEqual(
            PamGestureArbiter.ownership(from: page, upTo: root, horizontal: true, sign: -1, navigation: true),
            PamGestureArbiter.directional
        )
    }

    func testDisabledScrollAndOtherAxisOwnNothing() {
        let (root, pager, page) = pager(offset: 390)
        XCTAssertEqual(
            PamGestureArbiter.ownership(from: page, upTo: root, horizontal: false, sign: 1, navigation: true),
            PamGestureArbiter.noOwner
        )
        pager.isScrollEnabled = false
        XCTAssertEqual(
            PamGestureArbiter.ownership(from: page, upTo: root, horizontal: true, sign: 1, navigation: true),
            PamGestureArbiter.noOwner
        )
    }

    func testBlocksNavigationGestureIsExclusiveForNavigationOnly() {
        let (root, pager, page) = pager(offset: 0)
        pager.pamBlocksNavigationGesture = true
        XCTAssertEqual(
            PamGestureArbiter.ownership(from: page, upTo: root, horizontal: true, sign: 1, navigation: true),
            PamGestureArbiter.exclusive
        )
        XCTAssertEqual(
            PamGestureArbiter.ownership(from: page, upTo: root, horizontal: true, sign: 1, navigation: false),
            PamGestureArbiter.noOwner
        )
        pager.pamBlocksNavigationGesture = false
        XCTAssertFalse(pager.pamBlocksNavigationGesture)
    }

    func testListScrolledDownOwnsADownwardDrag() {
        let root = UIView(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let list = UIScrollView(frame: root.bounds)
        list.contentSize = CGSize(width: 390, height: 3_000)
        root.addSubview(list)
        XCTAssertFalse(PamGestureArbiter.canScroll(list, horizontal: false, sign: 1))
        list.contentOffset = CGPoint(x: 0, y: 400)
        XCTAssertTrue(PamGestureArbiter.canScroll(list, horizontal: false, sign: 1))
        XCTAssertEqual(
            PamGestureArbiter.ownership(from: list, upTo: root, horizontal: false, sign: 1, navigation: false),
            PamGestureArbiter.directional
        )
    }
}
