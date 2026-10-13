import UIKit
import XCTest
@testable import PamNative

@MainActor
final class PamZIndexTests: XCTestCase {
    func testAuthoredStackingControlsPixelsAndHitTestingAndResetsToLogicalOrder() {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .column,
                properties: [PamConstants.zIndex: .decimal(2)])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view,
                properties: [PamConstants.backgroundColor: .integer(0xFFFF0000)])),
            .create(NodeSpec(id: 4, parent: 1, index: 1, kind: .view,
                properties: [PamConstants.backgroundColor: .integer(0xFF00FF00)])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
            .layout(id: 2, frame: Frame(x: 20, y: 20, width: 160, height: 120)),
            .layout(id: 3, frame: Frame(x: 20, y: 20, width: 160, height: 120)),
            .layout(id: 4, frame: Frame(x: 20, y: 20, width: 160, height: 120)),
            .setRoot(1),
        ]])
        let child = host.viewWithTag(3)!
        func assertTop(_ id: Int, _ color: UInt32) {
            XCTAssertEqual(host.hitTest(CGPoint(x: 80, y: 80), with: nil)?.tag, id)
            PamRenderTestSupport.assertColor(
                PamRenderTestSupport.pixel(PamRenderTestSupport.snapshot(host), 80, 80), color)
        }
        assertTop(3, 0xFFFF0000)
        renderer.commit([[.update(id: 2, key: PamConstants.zIndex, value: .decimal(-2))]])
        assertTop(4, 0xFF00FF00)
        renderer.commit([[.update(id: 2, key: PamConstants.zIndex, value: .decimal(2))]])
        assertTop(3, 0xFFFF0000)
        renderer.commit([[.update(id: 2, key: PamConstants.zIndex, value: nil)]])
        XCTAssertEqual(host.viewWithTag(2)?.layer.zPosition, 0)
        XCTAssertTrue(host.viewWithTag(3) === child)
        assertTop(4, 0xFF00FF00)
    }
}
