import UIKit
import XCTest
@testable import PamNative

/// "Native scrolling guarantees of long lists" on iOS (mirrors Android
/// `PamListNativeScrollInstrumentedTest`, `PamHeadInsertAnchorTest`,
/// `PamListFlingWindowInstrumentedTest`, `PamKeyboardFollowTest`).
@MainActor
final class PamListNativeScrollTests: XCTestCase {
    private var windows: [UIWindow] = []

    override func tearDown() {
        windows.forEach { $0.isHidden = true }
        windows.removeAll()
        super.tearDown()
    }

    private func renderList(
        _ properties: [Int: PropValue] = [:],
        ids: [Int64],
        cellExtent: Float = 100,
        paddingTop: Float = 0,
        events: @escaping (Int64, Int, Data) -> Void = { _, _, _ in }
    ) throws -> (renderer: PamRenderer, list: PamVirtualListView) {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        windows.append(window)
        let host = UIView(frame: window.bounds)
        window.addSubview(host)
        window.isHidden = false
        let renderer = PamRenderer(hostView: host, dispatchEvent: events)
        var mutations: [Mutation] = [
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .virtualList, properties: properties)),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
        ]
        for (index, id) in ids.enumerated() {
            mutations.append(.create(NodeSpec(id: id, parent: 2, index: index, kind: .view, properties: [:])))
            mutations.append(.layout(id: id, frame: Frame(x: 0, y: paddingTop + Float(index) * cellExtent, width: 320, height: cellExtent)))
        }
        mutations.append(.setRoot(1))
        renderer.commit([mutations])
        let list = try XCTUnwrap(host.viewWithTag(2) as? PamVirtualListView)
        list.layoutIfNeeded()
        return (renderer, list)
    }

    /// Rows `ids` laid out from the top (index order) after `inserted` joined.
    private func relayout(_ ids: [Int64], extent: Float = 100, paddingTop: Float = 0) -> [Mutation] {
        ids.enumerated().flatMap { index, id -> [Mutation] in
            [
                .move(id: id, parent: 2, index: index),
                .layout(id: id, frame: Frame(x: 0, y: paddingTop + Float(index) * extent, width: 320, height: extent)),
            ]
        }
    }

    private func screenTop(_ list: PamVirtualListView, _ id: Int64) -> CGFloat? {
        list.viewWithTag(Int(id)).map { $0.frame.minY - list.contentOffset.y }
    }

    func testPrependAboveTheViewportKeepsTheVisibleRowInPlace() throws {
        let ids = (0..<30).map { Int64(100 + $0) }
        let (renderer, list) = try renderList(ids: ids)
        defer { renderer.close() }
        list.contentOffset.y = 1_030
        list.layoutIfNeeded()
        let before = try XCTUnwrap(screenTop(list, 111))
        // An older page of five rows lands above.
        let older = (0..<5).map { Int64(500 + $0) }
        var mutations: [Mutation] = older.enumerated().map { index, id in
            .create(NodeSpec(id: id, parent: 2, index: index, kind: .view, properties: [:]))
        }
        mutations += relayout(older + ids)
        renderer.commit([mutations])
        list.layoutIfNeeded()
        XCTAssertEqual(try XCTUnwrap(screenTop(list, 111)), before, accuracy: 0.5)
        XCTAssertEqual(list.contentOffset.y, 1_530, accuracy: 0.5)
    }

    func testPrependWithNativeContentInsetsKeepsTheKeyAndScreenPosition() throws {
        let ids = (0..<30).map { Int64(100 + $0) }
        let (renderer, list) = try renderList([
            PamConstants.paddingTop: .decimal(12),
            PamConstants.paddingBottom: .decimal(48),
        ], ids: ids, paddingTop: 12)
        defer { renderer.close() }
        list.contentOffset.y = 1_030
        list.layoutIfNeeded()
        let row = try XCTUnwrap(list.viewWithTag(111))
        let before = try XCTUnwrap(screenTop(list, 111))
        let older = (0..<5).map { Int64(500 + $0) }
        var mutations: [Mutation] = older.enumerated().map { index, id in
            .create(NodeSpec(id: id, parent: 2, index: index, kind: .view, properties: [:]))
        }
        mutations += relayout(older + ids, paddingTop: 12)
        renderer.commit([mutations])
        list.layoutIfNeeded()
        XCTAssertTrue(list.viewWithTag(111) === row)
        XCTAssertEqual(try XCTUnwrap(screenTop(list, 111)), before, accuracy: 0.5)
        XCTAssertEqual(list.contentInset.top, 12)
        XCTAssertEqual(list.contentInset.bottom, 48)
        XCTAssertEqual(list.primaryMaximumOffset, 3_500 - 480 + 48, accuracy: 0.5)
    }

    func testHeadInsertBelowALeadingRowKeepsTheFirstRowAfterIt() throws {
        // Row 100 is a header/loader at the top of the screen.
        let ids = (0..<30).map { Int64(100 + $0) }
        let (renderer, list) = try renderList(ids: ids)
        defer { renderer.close() }
        let before = try XCTUnwrap(screenTop(list, 101))
        let older = [Int64(600), 601, 602]
        var mutations: [Mutation] = older.enumerated().map { index, id in
            .create(NodeSpec(id: id, parent: 2, index: index + 1, kind: .view, properties: [:]))
        }
        mutations += relayout([ids[0]] + older + Array(ids.dropFirst()))
        renderer.commit([mutations])
        list.layoutIfNeeded()
        XCTAssertEqual(try XCTUnwrap(screenTop(list, 101)), before, accuracy: 0.5,
                       "the content under the header does not move")
    }

    func testRowGrowingAboveTheFirstFullyVisibleRowAtRestMovesNothingShown() throws {
        let ids = (0..<30).map { Int64(100 + $0) }
        let (renderer, list) = try renderList(ids: ids)
        defer { renderer.close() }
        list.contentOffset.y = 550 // row 105 partly hidden at the top
        list.layoutIfNeeded()
        let before = try XCTUnwrap(screenTop(list, 106))
        // Row 105 grows by 80 (an image arrived).
        var mutations: [Mutation] = [.layout(id: 105, frame: Frame(x: 0, y: 500, width: 320, height: 180))]
        for index in 6..<30 {
            mutations.append(.layout(id: ids[index], frame: Frame(x: 0, y: Float(index) * 100 + 80, width: 320, height: 100)))
        }
        renderer.commit([mutations])
        list.layoutIfNeeded()
        XCTAssertEqual(try XCTUnwrap(screenTop(list, 106)), before, accuracy: 0.5)
    }

    func testAListAtItsEndKeepsFollowingTheEnd() throws {
        let ids = (0..<10).map { Int64(100 + $0) }
        let (renderer, list) = try renderList([PamConstants.listInitialScrollIndex: .integer(9)], ids: ids)
        defer { renderer.close() }
        list.layoutIfNeeded()
        XCTAssertEqual(list.contentOffset.y, list.primaryMaximumOffset, accuracy: 0.5)
        renderer.commit([[
            .create(NodeSpec(id: 700, parent: 2, index: 10, kind: .view, properties: [:])),
            .layout(id: 700, frame: Frame(x: 0, y: 1_000, width: 320, height: 100)),
        ]])
        list.layoutIfNeeded()
        XCTAssertEqual(list.contentOffset.y, list.primaryMaximumOffset, accuracy: 0.5)
    }

    func testProgrammaticScrollIsAcceptedAtRest() throws {
        let (renderer, list) = try renderList(ids: (0..<10).map { Int64(100 + $0) })
        defer { renderer.close() }
        XCTAssertFalse(list.pamUserScrolling)
        XCTAssertTrue(list.pamAcceptsProgrammaticScroll(userActionAt: 0))
    }

    // MARK: Head insert rule (pure, Android PamHeadInsertAnchorTest)

    private func anchor(previous: [Int64], visible: [Int64]) -> PamListKeyAnchor {
        PamListKeyAnchor(
            visible: visible.enumerated().map { index, id in .init(id: id, start: CGFloat(index) * 100, full: true) },
            ids: previous,
            idSet: Set(previous),
            atEnd: false
        )
    }

    func testHeadInsertAnchorRule() {
        // Inserted right after the header on screen: the first kept row after them.
        XCTAssertEqual(anchor(previous: [1, 2, 3], visible: [1, 2, 3]).headInsertAnchor(next: [1, 9, 2, 3])?.0, 2)
        // Inserted before the first visible row: the default anchor keeps it.
        XCTAssertNil(anchor(previous: [1, 2, 3], visible: [1, 2, 3]).headInsertAnchor(next: [9, 1, 2, 3]))
        // Far from the head: nothing to do here.
        XCTAssertNil(anchor(previous: Array(1...10), visible: [6, 7]).headInsertAnchor(next: [1, 2, 3, 4, 5, 9, 6, 7, 8, 10]))
    }

    // MARK: Keyboard follow

    func testKeyboardFramesFollowTheKeyboardAnimation() throws {
        let animation = try XCTUnwrap(PamKeyboardFollow.animation(duration: 0.25, curve: 7, reduced: false, now: 10))
        XCTAssertEqual(animation.duration, 0.25)
        XCTAssertEqual(animation.curve, 7)
        XCTAssertEqual(animation.expires, 11.25, accuracy: 0.001)
        XCTAssertNil(PamKeyboardFollow.animation(duration: 0.25, curve: 7, reduced: true, now: 10))
        XCTAssertNil(PamKeyboardFollow.animation(duration: 0, curve: 7, reduced: false, now: 10))
    }

    /// A busy runtime that delivers the layout's response to the keyboard
    /// after it settled: that first frame still eases in over the keyboard's
    /// duration (it used to jump); later late frames apply at once.
    func testLateFirstResponseToTheKeyboardStillEasesIn() throws {
        let animation = try XCTUnwrap(PamKeyboardFollow.animation(duration: 0.25, curve: 7, reduced: false, now: 10))
        let during = try XCTUnwrap(PamKeyboardFollow.commitDuration(animation, responsePending: true, now: 10.1))
        XCTAssertEqual(during, 0.2, accuracy: 0.001, "the rest of the keyboard animation, at least the minimum")
        let late = try XCTUnwrap(PamKeyboardFollow.commitDuration(animation, responsePending: true, now: 11.7))
        XCTAssertEqual(late, 0.25, accuracy: 0.001, "a late first response follows the keyboard's whole duration")
        XCTAssertNil(PamKeyboardFollow.commitDuration(animation, responsePending: false, now: 11.7), "only the first response")
        XCTAssertNil(PamKeyboardFollow.commitDuration(animation, responsePending: true, now: 16.5), "past the late window it applies at once")
    }
}

/// Visible autoplay (Android `PamAutoplayPolicyTest`, `PamVisibleAutoplayInstrumentedTest`).
@MainActor
final class PamVisibleAutoplayTests: XCTestCase {
    private func player(
        _ fraction: CGFloat,
        _ top: CGFloat,
        active: Bool = false,
        idle: Bool = true,
        scope: String? = "feed#1",
        threshold: CGFloat = 0.5
    ) -> PamAutoplayCandidate {
        PamAutoplayCandidate(fraction: fraction, threshold: threshold, idle: idle, scope: scope, top: top, active: active)
    }

    func testStartsOnlyAtRestWithTheThresholdOnScreen() {
        XCTAssertEqual(PamAutoplayPolicy.choose([player(0.5, 0)]), [true])
        XCTAssertEqual(PamAutoplayPolicy.choose([player(0.49, 0)]), [false])
        XCTAssertEqual(PamAutoplayPolicy.choose([player(1, 0, idle: false)]), [false], "not while the list moves")
    }

    func testAPlayingVideoKeepsPlayingWhileTheListMovesUntilBelowItsFloor() {
        XCTAssertEqual(PamAutoplayPolicy.floor(0.5), 0.3, accuracy: 0.0001)
        XCTAssertEqual(PamAutoplayPolicy.choose([player(0.35, 0, active: true, idle: false)]), [true])
        XCTAssertEqual(PamAutoplayPolicy.choose([player(0.29, 0, active: true, idle: false)]), [false])
        XCTAssertEqual(PamAutoplayPolicy.choose([player(0, 0, active: true, threshold: 0.01)]), [false])
    }

    func testOneVideoPerGroupTheTopmostEligible() {
        XCTAssertEqual(PamAutoplayPolicy.choose([player(1, 900), player(0.8, 100), player(0.2, 50)]), [false, true, false])
    }

    func testThePlayingVideoStaysWhileItMeetsItsThreshold() {
        XCTAssertEqual(PamAutoplayPolicy.choose([player(0.6, 900, active: true), player(1, 100)]), [true, false])
    }

    func testAtRestABetterVisibleVideoTakesOverFromOneInItsHysteresisBand() {
        XCTAssertEqual(PamAutoplayPolicy.choose([player(0.4, 0, active: true), player(1, 600)]), [false, true])
        XCTAssertTrue(PamAutoplayPolicy.choose([player(0.4, 0, active: true), player(0.2, 600)])[0])
    }

    func testWhileMovingNoGroupSwitchesToANewVideo() {
        XCTAssertEqual(
            PamAutoplayPolicy.choose([player(0.25, 0, active: true, idle: false), player(1, 600, idle: false)]),
            [false, false]
        )
    }

    func testGroupsAreScopedPerListAndUngroupedPlayersDecideAlone() {
        XCTAssertEqual(
            PamAutoplayPolicy.choose([
                player(1, 0),
                player(1, 10, scope: "feed#2"),
                player(1, 20, scope: nil),
                player(0.6, 30, scope: nil),
            ]),
            [true, true, true, true]
        )
    }

    func testAFullyVisibleThresholdToleratesRounding() {
        XCTAssertEqual(PamAutoplayPolicy.choose([player(0.9995, 0, threshold: 1)]), [true])
    }

    func testAPlayerIsCreatedOnlyWithItsGrantAndReleasedAfterTheWithdrawal() throws {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        defer { window.isHidden = true }
        window.isHidden = false
        let media = PamMediaView(frame: CGRect(x: 0, y: 0, width: 320, height: 200))
        window.addSubview(media)
        media.setAutoPlay(true)
        media.setAutoPlayVisible(0.5)
        media.setSource(try PamMediaParityTests.makeVideo().absoluteString)
        XCTAssertFalse(media.hasPlayer, "no player before the policy grants playback")
        PamMediaVisibility.evaluateNow()
        XCTAssertTrue(media.autoplayGranted)
        XCTAssertTrue(media.hasPlayer, "granted: prepared")
        // Scrolled away: withdrawn, released after the delay.
        let previousDelay = PamMediaView.autoplayReleaseDelay
        PamMediaView.autoplayReleaseDelay = 0.05
        defer { PamMediaView.autoplayReleaseDelay = previousDelay }
        media.frame.origin.y = 900
        PamMediaVisibility.evaluateNow()
        XCTAssertFalse(media.autoplayGranted)
        XCTAssertTrue(media.hasPlayer, "a short withdrawal keeps the paused player")
        let deadline = Date().addingTimeInterval(3)
        while media.hasPlayer, Date() < deadline {
            RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        }
        XCTAssertFalse(media.hasPlayer, "released after the delay")
        media.removeFromSuperview()
    }

    func testListMediaBoundAheadOfTheViewportWaitsForTheScreen() throws {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        defer { window.isHidden = true }
        window.isHidden = false
        let list = PamVirtualListView(frame: window.bounds)
        list.contentSize = CGSize(width: 320, height: 3_000)
        window.addSubview(list)
        let ahead = PamMediaView(frame: CGRect(x: 0, y: 1_200, width: 320, height: 200))
        list.addSubview(ahead)
        ahead.setSource(try PamMediaParityTests.makeVideo().absoluteString)
        XCTAssertFalse(ahead.hasPlayer, "bound ahead of the viewport: not prepared")
        XCTAssertTrue(ahead.awaitingScreen)
        list.contentOffset.y = 1_000
        PamMediaVisibility.evaluateNow()
        XCTAssertTrue(ahead.hasPlayer, "prepared once on screen at rest")
        XCTAssertFalse(ahead.awaitingScreen)
        ahead.removeFromSuperview()
    }

    func testAPlayerInAGlidingPagerWaitsForTheGlideToEnd() throws {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 300, height: 600))
        defer { window.isHidden = true }
        window.isHidden = false
        let pager = PamAnchoredScrollView(frame: window.bounds)
        pager.horizontal = true
        pager.contentSize = CGSize(width: 900, height: 600)
        window.addSubview(pager)
        pager.pamOffsetAnimationMs = 280
        pager.pamRequestedContentOffsetX = 0
        pager.layoutIfNeeded()
        pager.pamRequestedContentOffsetX = 300
        pager.layoutIfNeeded()
        XCTAssertEqual(pager.pamOffsetGlide?.isRunning, true)
        let media = PamMediaView(frame: CGRect(x: 300, y: 0, width: 300, height: 600))
        pager.addSubview(media)
        media.setSource(try PamMediaParityTests.makeVideo().absoluteString)
        XCTAssertFalse(media.hasPlayer, "no AVPlayer inside the glide")
        XCTAssertTrue(media.awaitingGlide)
        let deadline = Date().addingTimeInterval(2)
        while Date() < deadline, !media.hasPlayer {
            RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        }
        XCTAssertTrue(media.hasPlayer, "prepared once the glide ended")
        media.removeFromSuperview()
    }
}
