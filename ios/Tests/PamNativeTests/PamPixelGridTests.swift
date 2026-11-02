import UIKit
import XCTest
@testable import PamNative

/// Yoga pixel-grid rounding of mounted frames (Android `PamPixelSnappingTest`).
@MainActor
final class PamPixelGridTests: XCTestCase {
    func testPositionRoundsRelativeToTheParentAndSizeFromAbsoluteEdges() {
        // 3x: parent at 10.2 pt (30.6 px), child at 10.2 + 5.25 pt.
        let span = PamPixelGrid.span(start: 15.45, extent: 20.3, parentStart: 10.2, scale: 3, text: false)
        XCTAssertEqual(span.offset * 3, 16, accuracy: 0.0001) // 15.75 px -> 16
        // edges 46.35 -> 46, 107.25 -> 107
        XCTAssertEqual(span.extent * 3, 61, accuracy: 0.0001)
    }

    func testTextFloorsItsPositionAndCeilsAFractionalSize() {
        let span = PamPixelGrid.span(start: 0.9, extent: 10.1, parentStart: 0, scale: 3, text: true)
        XCTAssertEqual(span.offset * 3, 2, accuracy: 0.0001) // 2.7 px floored
        // 2.7 floored -> 2, 33.0 whole -> 33: extent 31, but the 30.3 px text ceils to 31
        XCTAssertEqual(span.extent * 3, 31, accuracy: 0.0001)
    }

    func testNearlyWholePixelsSnapToThatPixel() {
        XCTAssertEqual(PamPixelGrid.pixels(10.0 / 3.0 - 0.00001, scale: 3, forceFloor: true), 10)
        XCTAssertEqual(PamPixelGrid.pixels(1.5 / 3.0, scale: 3), 2)
    }

    func testRendererMountsFramesOnThePixelGrid() throws {
        let previous = PamTextEnvironment.displayScale
        PamTextEnvironment.displayScale = 3
        defer { PamTextEnvironment.displayScale = previous }
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 200, height: 200))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .view, properties: [:])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view, properties: [:])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 200, height: 200)),
            .layout(id: 2, frame: Frame(x: 0, y: 10.2, width: 200, height: 50.5)),
            .layout(id: 3, frame: Frame(x: 4.1, y: 15.45, width: 20.3, height: 20.3)),
            .setRoot(1),
        ]])
        let child = try XCTUnwrap(host.viewWithTag(3))
        XCTAssertEqual(child.frame.minY * 3, 16, accuracy: 0.0001)
        XCTAssertEqual(child.frame.height * 3, 61, accuracy: 0.0001)
        XCTAssertEqual(child.frame.minX * 3, 12, accuracy: 0.0001) // 12.3 px
    }
}
