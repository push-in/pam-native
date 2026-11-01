import UIKit
import XCTest
@testable import PamNative

/// Scroll/list prop parity with Android `PamScrollContainer` /
/// `PamRecyclerList`: `horizontal` (ListHorizontal), `inverted`,
/// `initialScrollIndex`, `scrollEnabled`, `showsScrollIndicator`,
/// `contentOffset` and `keyboardDismissMode`. Uncompiled on Linux — needs
/// Mac validation.
@MainActor
final class PamListScrollPropParityTests: XCTestCase {
    /// Keeps the test windows alive for the duration of each test.
    private var windows: [UIWindow] = []

    override func tearDown() {
        windows.forEach { $0.isHidden = true }
        windows.removeAll()
        super.tearDown()
    }

    private func renderList(
        _ properties: [Int: PropValue],
        kind: NodeKind = .virtualList,
        cells: Int = 10,
        cellExtent: Float = 100,
        horizontal: Bool = false
    ) throws -> (renderer: PamRenderer, list: PamVirtualListView) {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        windows.append(window)
        let host = UIView(frame: window.bounds)
        window.addSubview(host)
        window.isHidden = false
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        var mutations: [Mutation] = [
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: kind, properties: properties)),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
        ]
        for index in 0..<cells {
            let id = Int64(10 + index)
            mutations.append(.create(NodeSpec(id: id, parent: 2, index: index, kind: .view, properties: [:])))
            mutations.append(.layout(
                id: id,
                frame: horizontal
                    ? Frame(x: Float(index) * cellExtent, y: 0, width: cellExtent, height: 480)
                    : Frame(x: 0, y: Float(index) * cellExtent, width: 320, height: cellExtent)
            ))
        }
        mutations.append(.setRoot(1))
        renderer.commit([mutations])
        let list = try XCTUnwrap(host.viewWithTag(2) as? PamVirtualListView)
        list.layoutIfNeeded()
        return (renderer, list)
    }

    func testListHorizontalKeyDrivesTheAxisAndIndicators() throws {
        let (renderer, list) = try renderList(
            [PamConstants.listHorizontal: .flag(true)],
            horizontal: true
        )
        defer { renderer.close() }
        XCTAssertTrue(list.horizontal)
        XCTAssertTrue(list.alwaysBounceHorizontal)
        XCTAssertFalse(list.alwaysBounceVertical)
        XCTAssertTrue(list.showsHorizontalScrollIndicator)
        XCTAssertFalse(list.showsVerticalScrollIndicator)
        XCTAssertEqual(list.contentSize.width, 1_000, accuracy: 0.5)
        XCTAssertEqual(list.pamItemStarts.first, 0)
        XCTAssertEqual(list.pamItemStarts.last, 900)
    }

    func testInvertedListMirrorsItselfAndMirrorsCellsBack() throws {
        let (renderer, list) = try renderList([PamConstants.listInverted: .flag(true)])
        defer { renderer.close() }
        let flip = CGAffineTransform(scaleX: 1, y: -1)
        XCTAssertEqual(list.transform, flip)
        let firstCell = try XCTUnwrap(list.viewWithTag(10))
        XCTAssertEqual(firstCell.transform, flip, "cells are mirrored back like RN")
        XCTAssertEqual(firstCell.bounds.size, CGSize(width: 320, height: 100))
        XCTAssertEqual(firstCell.center, CGPoint(x: 160, y: 50))

        renderer.commit([[.update(id: 2, key: PamConstants.listInverted, value: .flag(false))]])
        list.layoutIfNeeded()
        XCTAssertEqual(list.transform, .identity)
        XCTAssertEqual(firstCell.transform, .identity, "restored once the list is upright")
    }

    func testInvertedHorizontalListMirrorsAlongX() throws {
        let (renderer, list) = try renderList(
            [PamConstants.listHorizontal: .flag(true), PamConstants.listInverted: .flag(true)],
            horizontal: true
        )
        defer { renderer.close() }
        XCTAssertEqual(list.transform, CGAffineTransform(scaleX: -1, y: 1))
    }

    func testStackFromEndRestsRowsThatFitAgainstTheViewportEnd() throws {
        let (renderer, list) = try renderList([PamConstants.listStackFromEnd: .flag(true)], cells: 3)
        defer { renderer.close() }
        let available = list.bounds.height - list.adjustedContentInset.top + list.pamEndAnchorGap
            - list.adjustedContentInset.bottom
        XCTAssertEqual(list.pamEndAnchorGap, available - 300, accuracy: 1)
        XCTAssertEqual(list.contentSize.height, 300, accuracy: 0.5)
        XCTAssertEqual(list.contentOffset.y, -list.adjustedContentInset.top, accuracy: 0.5)
        XCTAssertEqual(list.pamLogicalContentOffset.y, 0, accuracy: 0.5, "the gap is not content")
        let lastCell = try XCTUnwrap(list.viewWithTag(12))
        let bottom = list.convert(lastCell.frame, to: list.superview).maxY
        XCTAssertEqual(bottom, list.frame.maxY - list.adjustedContentInset.bottom, accuracy: 1)
    }

    func testStackFromEndIsInertWhenRowsOverflow() throws {
        let (renderer, list) = try renderList([PamConstants.listStackFromEnd: .flag(true)], cells: 10)
        defer { renderer.close() }
        XCTAssertEqual(list.pamEndAnchorGap, 0)
        XCTAssertEqual(list.contentSize.height, 1_000, accuracy: 0.5)
    }

    func testInitialScrollIndexLandsOnTheItemOnFirstSync() throws {
        let (renderer, list) = try renderList([PamConstants.listInitialScrollIndex: .integer(5)])
        defer { renderer.close() }
        XCTAssertTrue(list.pamInitialScrollIndexApplied)
        XCTAssertEqual(list.contentOffset.y, 500, accuracy: 0.5)
        // Clamped to the end for an index past the content.
        renderer.commit([[.update(id: 2, key: PamConstants.listInitialScrollIndex, value: .integer(99))]])
        list.layoutIfNeeded()
        XCTAssertEqual(list.contentOffset.y, list.primaryMaximumOffset, accuracy: 0.5)
    }

    func testScrollEnabledAndIndicatorFlagsOnLists() throws {
        let (renderer, list) = try renderList([
            PamConstants.scrollEnabled: .flag(false),
            PamConstants.showsScrollIndicator: .flag(false),
        ])
        defer { renderer.close() }
        XCTAssertFalse(list.isScrollEnabled)
        XCTAssertFalse(list.showsVerticalScrollIndicator)
        XCTAssertFalse(list.showsHorizontalScrollIndicator)

        renderer.commit([[
            .update(id: 2, key: PamConstants.scrollEnabled, value: .flag(true)),
            .update(id: 2, key: PamConstants.showsScrollIndicator, value: .flag(true)),
        ]])
        XCTAssertTrue(list.isScrollEnabled)
        XCTAssertTrue(list.showsVerticalScrollIndicator)
        XCTAssertFalse(list.showsHorizontalScrollIndicator, "only the primary axis shows one")

        // The flag survives an axis change (Android re-applies it in
        // `updateLayoutManager`).
        renderer.commit([[
            .update(id: 2, key: PamConstants.showsScrollIndicator, value: .flag(false)),
            .update(id: 2, key: PamConstants.listHorizontal, value: .flag(true)),
        ]])
        XCTAssertFalse(list.showsHorizontalScrollIndicator)
        XCTAssertFalse(list.showsVerticalScrollIndicator)
    }

    func testScrollEnabledAndIndicatorFlagsOnScrollViews() throws {
        let (_, renderer, view) = PamRenderTestSupport.render(
            [
                PamConstants.scrollEnabled: .flag(false),
                PamConstants.showsScrollIndicator: .flag(false),
            ],
            kind: .scroll
        )
        defer { renderer.close() }
        let scroll = try XCTUnwrap(view as? PamAnchoredScrollView)
        XCTAssertFalse(scroll.isScrollEnabled)
        XCTAssertFalse(scroll.showsVerticalScrollIndicator)
        XCTAssertFalse(scroll.showsHorizontalScrollIndicator)
        renderer.commit([[.update(id: 2, key: PamConstants.showsScrollIndicator, value: nil)]])
        XCTAssertTrue(scroll.showsVerticalScrollIndicator, "removed flag restores the default")
    }

    func testKeyboardDismissModeUsesTheAndroidWireValues() throws {
        let (_, renderer, view) = PamRenderTestSupport.render(
            [PamConstants.scrollKeyboardDismissMode: .integer(2)],
            kind: .scroll
        )
        defer { renderer.close() }
        let scroll = try XCTUnwrap(view as? UIScrollView)
        XCTAssertEqual(scroll.keyboardDismissMode, .onDrag)
        renderer.commit([[.update(id: 2, key: PamConstants.scrollKeyboardDismissMode, value: .integer(3))]])
        XCTAssertEqual(scroll.keyboardDismissMode, .interactive)
        renderer.commit([[.update(id: 2, key: PamConstants.scrollKeyboardDismissMode, value: .integer(1))]])
        XCTAssertEqual(scroll.keyboardDismissMode, .none)

        let (listRenderer, list) = try renderList([PamConstants.scrollKeyboardDismissMode: .integer(3)])
        defer { listRenderer.close() }
        XCTAssertEqual(list.keyboardDismissMode, .interactive)
    }

    func testContentOffsetWaitsForTheContentToBeMeasured() throws {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        windows.append(window)
        let host = UIView(frame: window.bounds)
        window.addSubview(host)
        window.isHidden = false
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .scroll, properties: [
                PamConstants.scrollContentOffsetX: .decimal(0),
                PamConstants.scrollContentOffsetY: .decimal(200),
            ])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view, properties: [:])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
            .layout(id: 3, frame: Frame(x: 0, y: 0, width: 320, height: 2_000)),
            .setRoot(1),
        ]])
        let scroll = try XCTUnwrap(host.viewWithTag(2) as? PamAnchoredScrollView)
        scroll.layoutIfNeeded()
        XCTAssertEqual(scroll.contentOffset.y, 200, accuracy: 0.5)
        XCTAssertNil(scroll.pamRequestedContentOffsetY, "applied once")
        // A later request is clamped to the content.
        renderer.commit([[.update(id: 2, key: PamConstants.scrollContentOffsetY, value: .decimal(5_000))]])
        scroll.layoutIfNeeded()
        let maximum = scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom
        XCTAssertEqual(scroll.contentOffset.y, maximum, accuracy: 0.5)
    }
}
