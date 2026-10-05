import UIKit
import XCTest
@testable import PamNative

/// Parity with Android's PamNestedPressableInstrumentedTest: a backdrop
/// Pressable wrapping a panel Pressable with a ScrollView of tiles must route
/// every tile tap to that tile (hit testing never re-centres on the panel).
final class PamNestedPressableTests: XCTestCase {
    @MainActor
    func testEveryTileInsideNestedPressablesIsItsOwnHitTarget() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 360, height: 720))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        var mutations: [Mutation] = [
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .pressable, properties: [
                PamConstants.testId: .text("backdrop"),
            ])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .pressable, properties: [
                PamConstants.testId: .text("panel"),
            ])),
            .create(NodeSpec(id: 4, parent: 3, index: 0, kind: .scroll, properties: [:])),
            .create(NodeSpec(id: 5, parent: 4, index: 0, kind: .column, properties: [:])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 360, height: 720)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 360, height: 720)),
            .layout(id: 3, frame: Frame(x: 20, y: 100, width: 320, height: 480)),
            .layout(id: 4, frame: Frame(x: 20, y: 100, width: 320, height: 480)),
            .layout(id: 5, frame: Frame(x: 20, y: 100, width: 320, height: 12 * 48 + 8)),
        ]
        for index in 0..<12 {
            let tile = Int64(100 + index)
            mutations.append(.create(NodeSpec(id: tile, parent: 5, index: index, kind: .pressable, properties: [
                PamConstants.testId: .text("tile-\(index)"),
            ])))
            mutations.append(.create(NodeSpec(id: tile + 100, parent: tile, index: 0, kind: .text, properties: [
                PamConstants.text: .text("Action \(index)"),
            ])))
            mutations.append(.layout(id: tile, frame: Frame(x: 20, y: Float(104 + index * 48), width: 320, height: 48)))
            mutations.append(.layout(id: tile + 100, frame: Frame(x: 36, y: Float(118 + index * 48), width: 200, height: 20)))
        }
        mutations.append(.setRoot(1))
        renderer.commit([mutations])
        host.layoutIfNeeded()

        var reached = 0
        for index in 0..<12 {
            let center = CGPoint(x: 180, y: CGFloat(104 + index * 48 + 24))
            guard center.y < 100 + 480 - 24 else { continue }
            let hit = try XCTUnwrap(host.hitTest(center, with: nil), "tile \(index)")
            var button: UIView? = hit
            while let candidate = button, !(candidate is PamPressButton) {
                button = candidate.superview
            }
            XCTAssertEqual(button?.accessibilityIdentifier, "tile-\(index)")
            reached += 1
        }
        XCTAssertGreaterThanOrEqual(reached, 6)
        renderer.close()
    }
}
