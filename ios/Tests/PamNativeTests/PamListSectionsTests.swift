import XCTest
import UIKit
@testable import PamNative

/// Keyed list sections (`listSection` + `activeSection`): tabs over one
/// VirtualizedList keep the inactive sections' views parked (switching back
/// reattaches them, no remount) and each section's own scroll offset while
/// the tab rail is pinned.
final class PamListSectionsTests: XCTestCase {
    private let sections: [Int64: String] = [10: "media", 11: "media", 20: "files"]

    func testOnlySharedRowsAndTheActiveSectionAreListed() {
        let ids: [Int64] = [1, 2, 10, 11, 20, 3]
        let sections = self.sections
        let sectionOf: (Int64) -> String? = { sections[$0] }
        XCTAssertEqual(PamListSections.visibleCells(ids, active: "media", sectionOf: sectionOf), [1, 2, 10, 11, 3])
        XCTAssertEqual(PamListSections.visibleCells(ids, active: "files", sectionOf: sectionOf), [1, 2, 20, 3])
        XCTAssertEqual(PamListSections.visibleCells(ids, active: "", sectionOf: sectionOf), [1, 2, 3])
        XCTAssertEqual(PamListSections.railId(ids, sectionOf: sectionOf), 2)
        XCTAssertNil(PamListSections.railId([10, 1], sectionOf: sectionOf))
    }

    func testSwitchOffsetsFollowThePinnedRail() {
        // Header visible: nothing moves, nothing is saved.
        let visible = PamListSections.switchOffsets(current: 40, rail: 160, saved: 900, minimum: 0, maximum: 2_000)
        XCTAssertNil(visible.saved)
        XCTAssertNil(visible.target)
        // Rail pinned: save the outgoing offset, restore the incoming one.
        let pinned = PamListSections.switchOffsets(current: 700, rail: 160, saved: 900, minimum: 0, maximum: 2_000)
        XCTAssertEqual(pinned.saved, 700)
        XCTAssertEqual(pinned.target, 900)
        // First visit: the rail at the top; a short section clamps to its end.
        XCTAssertEqual(PamListSections.switchOffsets(current: 700, rail: 160, saved: nil, minimum: 0, maximum: 2_000).target, 160)
        XCTAssertEqual(PamListSections.switchOffsets(current: 700, rail: 160, saved: 900, minimum: 0, maximum: 120).target, 120)
    }

    @MainActor
    func testSwitchingBackReattachesTheSameViewsAndOffsets() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        var batch: [Mutation] = [
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .virtualList, properties: [
                PamConstants.listActiveSection: .text("media"),
            ])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view, properties: [:])),
            .create(NodeSpec(id: 4, parent: 2, index: 1, kind: .view, properties: [
                PamConstants.stickyHeader: .flag(true),
            ])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
            .layout(id: 3, frame: Frame(x: 0, y: 0, width: 320, height: 160)),
            .layout(id: 4, frame: Frame(x: 0, y: 160, width: 320, height: 48)),
        ]
        var index = 2
        // Like the engine: both sections start at the block origin (208).
        for (base, key, extent) in [(Int64(1_000), "media", Float(72)), (Int64(2_000), "files", Float(56))] {
            var y: Float = 208
            for row in 0..<30 {
                let id = base + Int64(row)
                batch.append(.create(NodeSpec(id: id, parent: 2, index: index, kind: .view, properties: [
                    PamConstants.listSection: .text(key),
                ])))
                batch.append(.layout(id: id, frame: Frame(x: 0, y: y, width: 320, height: extent)))
                index += 1
                y += extent
            }
        }
        batch.append(.setRoot(1))
        renderer.commit([batch])
        let list = try XCTUnwrap(host.viewWithTag(2) as? PamVirtualListView)
        list.layoutIfNeeded()
        XCTAssertEqual(list.contentSize.height, 208 + 30 * 72, accuracy: 0.5, "only the active section sizes the list")
        let media = try XCTUnwrap(host.viewWithTag(1_000))

        list.contentOffset = CGPoint(x: 0, y: 600)
        list.layoutIfNeeded()
        renderer.commit([[.update(id: 2, key: PamConstants.listActiveSection, value: .text("files"))]])
        list.layoutIfNeeded()
        XCTAssertNil(media.superview, "the inactive section is parked out of the list")
        XCTAssertEqual(list.contentSize.height, 208 + 30 * 56, accuracy: 0.5)
        XCTAssertEqual(list.contentOffset.y, 160, accuracy: 0.5, "files starts with the rail at the top")
        XCTAssertNotNil(host.viewWithTag(2_000))

        list.contentOffset = CGPoint(x: 0, y: 400)
        list.layoutIfNeeded()
        renderer.commit([[.update(id: 2, key: PamConstants.listActiveSection, value: .text("media"))]])
        list.layoutIfNeeded()
        XCTAssertEqual(list.contentOffset.y, 600, accuracy: 0.5, "media returns to its own offset")
        XCTAssertTrue(host.viewWithTag(1_000) === media, "switching back reuses the same view")

        renderer.commit([[.update(id: 2, key: PamConstants.listActiveSection, value: .text("files"))]])
        list.layoutIfNeeded()
        XCTAssertEqual(list.contentOffset.y, 400, accuracy: 0.5, "files returns to its own offset")
    }
}
