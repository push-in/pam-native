import XCTest
import UIKit
@testable import PamNative

final class PamVirtualCellInsertionTests: XCTestCase {
    @MainActor
    func testInsertedDescendantsMountWithoutReplacingVisibleCell() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 300, height: 400))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .virtualList, properties: [:])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .pressable, properties: [:])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 300, height: 400)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 300, height: 400)),
            .layout(id: 3, frame: Frame(x: 0, y: 0, width: 300, height: 48)),
            .setRoot(1),
        ]])
        let row = try XCTUnwrap(host.viewWithTag(3))
        for _ in 0..<2 {
            renderer.commit([[
                .create(NodeSpec(id: 4, parent: 3, index: 0, kind: .text, properties: [
                    PamConstants.text: .text("Selected"),
                ])),
                .layout(id: 4, frame: Frame(x: 8, y: 8, width: 80, height: 24)),
            ]])
            let label = try XCTUnwrap(host.viewWithTag(4) as? UILabel)
            XCTAssertTrue(label.superview === row)
            XCTAssertTrue(host.viewWithTag(3) === row)
            XCTAssertEqual(label.text, "Selected")
            XCTAssertEqual(label.frame.size, CGSize(width: 80, height: 24))
            XCTAssertEqual(row.subviews.filter { $0.tag == 4 }.count, 1)
            renderer.commit([[.remove(4)]])
            XCTAssertNil(host.viewWithTag(4))
        }
    }
}
