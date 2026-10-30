import UIKit
import XCTest
@testable import PamNative

/// A nested Pressable without onLongPress lends the long press to the nearest
/// enclosing Pressable that has one (React Native), never across a scroll
/// view; Android `PamPressableGestureTest`.
@MainActor
final class PamNestedLongPressTests: XCTestCase {
    func testNestedPressableWithoutLongPressLendsItToTheNearestHandler() {
        typealias Node = PamNestedLongPress.Node
        let plain = Node(hasLongPress: false, enabled: true, boundary: false)
        let bubble = Node(hasLongPress: true, enabled: true, boundary: false)
        let list = Node(hasLongPress: false, enabled: false, boundary: true)
        var disabled = bubble
        disabled.enabled = false
        XCTAssertEqual(PamNestedLongPress.target([bubble, list]), 0)
        XCTAssertEqual(PamNestedLongPress.target([plain, bubble, bubble]), 1)
        XCTAssertEqual(PamNestedLongPress.target([disabled, bubble]), -1)
        XCTAssertEqual(PamNestedLongPress.target([plain, list, bubble]), -1)
        XCTAssertEqual(PamNestedLongPress.target([]), -1)
    }

    private func render(_ specs: [NodeSpec], frames: [Int64: Frame]) -> (UIView, PamRenderer) {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 360, height: 720))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        var batch: [Mutation] = [.create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:]))]
        batch += specs.map { .create($0) }
        batch.append(.layout(id: 1, frame: Frame(x: 0, y: 0, width: 360, height: 720)))
        batch += frames.sorted { $0.key < $1.key }.map { .layout(id: $0.key, frame: $0.value) }
        batch.append(.setRoot(1))
        renderer.commit([batch])
        return (host, renderer)
    }

    func testTheBubbleTakesTheLongPressOfANestedPlayButton() throws {
        let (host, renderer) = render([
            NodeSpec(id: 2, parent: 1, index: 0, kind: .pressable, properties: [
                PamConstants.onPress: .integer(1), PamConstants.onLongPress: .integer(1),
            ]),
            NodeSpec(id: 3, parent: 2, index: 0, kind: .pressable, properties: [PamConstants.onPress: .integer(1)]),
            NodeSpec(id: 4, parent: 3, index: 0, kind: .text, properties: [PamConstants.text: .text("Play")]),
        ], frames: [
            2: Frame(x: 20, y: 20, width: 300, height: 120),
            3: Frame(x: 30, y: 30, width: 60, height: 60),
            4: Frame(x: 30, y: 30, width: 60, height: 24),
        ])
        defer { renderer.close() }
        let bubble = try XCTUnwrap(host.viewWithTag(2))
        let play = try XCTUnwrap(host.viewWithTag(3))
        let label = try XCTUnwrap(host.viewWithTag(4))
        XCTAssertTrue(PamNestedLongPress.hasLongPress(bubble))
        XCTAssertFalse(PamNestedLongPress.hasLongPress(play))
        XCTAssertTrue(PamNestedLongPress.receives(touchView: play, receiver: bubble))
        XCTAssertTrue(PamNestedLongPress.receives(touchView: label, receiver: bubble))
        XCTAssertTrue(PamNestedLongPress.receives(touchView: bubble, receiver: bubble))
    }

    func testANestedPressableWithItsOwnLongPressKeepsIt() throws {
        let (host, renderer) = render([
            NodeSpec(id: 2, parent: 1, index: 0, kind: .pressable, properties: [PamConstants.onLongPress: .integer(1)]),
            NodeSpec(id: 3, parent: 2, index: 0, kind: .pressable, properties: [PamConstants.onLongPress: .integer(1)]),
        ], frames: [
            2: Frame(x: 20, y: 20, width: 300, height: 120),
            3: Frame(x: 30, y: 30, width: 60, height: 60),
        ])
        defer { renderer.close() }
        let outer = try XCTUnwrap(host.viewWithTag(2))
        let inner = try XCTUnwrap(host.viewWithTag(3))
        XCTAssertFalse(PamNestedLongPress.receives(touchView: inner, receiver: outer))
        XCTAssertTrue(PamNestedLongPress.receives(touchView: inner, receiver: inner))
    }

    func testTheLongPressNeverCrossesAScrollView() {
        let outer = PamPressButton(type: .system)
        let longPress = UILongPressGestureRecognizer()
        longPress.name = PamNestedLongPress.recognizerName
        outer.addGestureRecognizer(longPress)
        let scroll = UIScrollView()
        let row = PamPressButton(type: .system)
        outer.addSubview(scroll)
        scroll.addSubview(row)
        XCTAssertFalse(PamNestedLongPress.receives(touchView: row, receiver: outer))
        // Without the scroll view in between the row lends it.
        outer.addSubview(row)
        XCTAssertTrue(PamNestedLongPress.receives(touchView: row, receiver: outer))
    }

    func testADisabledNearerHandlerSwallowsTheLongPress() {
        let outer = PamPressButton(type: .system)
        let middle = PamPressButton(type: .system)
        let inner = PamPressButton(type: .system)
        for view in [outer, middle] {
            let recognizer = UILongPressGestureRecognizer()
            recognizer.name = PamNestedLongPress.recognizerName
            view.addGestureRecognizer(recognizer)
        }
        outer.addSubview(middle)
        middle.addSubview(inner)
        middle.isEnabled = false
        XCTAssertFalse(PamNestedLongPress.receives(touchView: inner, receiver: outer))
        middle.isEnabled = true
        XCTAssertTrue(PamNestedLongPress.receives(touchView: inner, receiver: middle))
        XCTAssertFalse(PamNestedLongPress.receives(touchView: inner, receiver: outer))
    }
}
