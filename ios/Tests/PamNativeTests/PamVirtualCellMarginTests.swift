import XCTest
import UIKit
@testable import PamNative

/// React Native/Yoga parity: a VirtualizedList/VirtualGrid cell is its root's
/// margin box (the engine insets the root frame by its margins), and sticky
/// children pin and are pushed by their margin box.
final class PamVirtualCellMarginTests: XCTestCase {
    private let cellMargins: [Int: PropValue] = [
        PamConstants.marginTop: .decimal(6),
        PamConstants.marginBottom: .decimal(10),
        PamConstants.marginHorizontal: .decimal(12),
    ]

    func testCellMarginsResolveLikeTheEngine() {
        let margins = PamCellMargins(properties: [
            PamConstants.margin: .decimal(4),
            PamConstants.marginVertical: .integer(6),
            PamConstants.marginBottom: .decimal(10),
            PamConstants.marginLeft: .decimal(.nan),
        ])
        XCTAssertEqual(margins, PamCellMargins(left: 0, top: 6, right: 4, bottom: 10))
        XCTAssertEqual(PamCellMargins(properties: nil), .zero)
        XCTAssertEqual(
            PamCellMargins(left: 12, top: 6, right: 12, bottom: 10).slot(CGRect(x: 12, y: 6, width: 296, height: 40)),
            CGRect(x: 0, y: 0, width: 320, height: 56)
        )
    }

    @MainActor
    func testVirtualListContentIncludesTheLastCellBottomMargin() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .virtualList, properties: [:])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view, properties: cellMargins)),
            .create(NodeSpec(id: 4, parent: 2, index: 1, kind: .view, properties: cellMargins)),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
            .layout(id: 3, frame: Frame(x: 12, y: 6, width: 296, height: 40)),
            // Second slot starts at 56; its root is inset by the margins.
            .layout(id: 4, frame: Frame(x: 12, y: 62, width: 296, height: 600)),
            .setRoot(1),
        ]])
        let list = try XCTUnwrap(host.viewWithTag(2) as? PamVirtualListView)
        list.layoutIfNeeded()
        XCTAssertEqual(list.contentSize.height, 56 + 616, accuracy: 0.5, "slots include the margins")
        XCTAssertEqual(list.pamItemStarts, [0, 56], "item starts are slot starts")
        let first = try XCTUnwrap(host.viewWithTag(3))
        XCTAssertEqual(first.frame, CGRect(x: 12, y: 6, width: 296, height: 40))
    }

    func testStickyMarginBoxPinsAndIsPushedByTheNextMarginBox() {
        let registry = PamStickyRegistry()
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 300, height: 400))
        let header = UIView(frame: CGRect(x: 0, y: 100, width: 300, height: 40))
        let next = UIView(frame: CGRect(x: 0, y: 400, width: 300, height: 40))
        host.addSubview(header)
        host.addSubview(next)
        let margins = PamCellMargins(top: 8, bottom: 4)
        registry.set(header, frame: header.frame, margins: margins)
        registry.set(next, frame: next.frame, margins: margins)
        registry.update(offset: 50, host: host)
        XCTAssertEqual(header.frame.minY, 100, accuracy: 0.5)
        // Pinned with its top margin kept above it.
        registry.update(offset: 300, host: host)
        XCTAssertEqual(header.frame.minY, 308, accuracy: 0.5)
        // The next margin box (392) pushes this one (52 tall): 392 - 52 + 8.
        registry.update(offset: 360, host: host)
        XCTAssertEqual(header.frame.minY, 348, accuracy: 0.5)
    }

    @MainActor
    func testStickyScrollChildKeepsItsTopMarginWhenPinned() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 200, height: 300))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .scroll, properties: [:])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view, properties: [
                PamConstants.stickyHeader: .flag(true),
                PamConstants.marginTop: .decimal(8),
            ])),
            .create(NodeSpec(id: 4, parent: 2, index: 1, kind: .view, properties: [:])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 200, height: 300)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 200, height: 300)),
            .layout(id: 3, frame: Frame(x: 0, y: 58, width: 200, height: 40)),
            .layout(id: 4, frame: Frame(x: 0, y: 98, width: 200, height: 1_000)),
            .setRoot(1),
        ]])
        let scroll = try XCTUnwrap(host.viewWithTag(2) as? PamAnchoredScrollView)
        scroll.contentOffset = CGPoint(x: 0, y: 200)
        scroll.layoutIfNeeded()
        let header = try XCTUnwrap(host.viewWithTag(3))
        XCTAssertEqual(header.frame.minY, 208 + scroll.adjustedContentInset.top, accuracy: 0.5)
    }
}
