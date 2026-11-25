import UIKit
import XCTest
@testable import PamNative

@MainActor
final class PamPendingNativeInputTests: XCTestCase {
    private func edit(_ field: PamInputField, _ value: String) {
        field.text = value
        for target in field.allTargets {
            guard let object = target.base as? NSObject else { continue }
            for action in field.actions(forTarget: object, forControlEvent: .editingChanged) ?? [] {
                _ = object.perform(NSSelectorFromString(action), with: field)
            }
        }
    }

    func testReplacingEventBridgesPreservesTheLatestPendingText() throws {
        var values: [String] = []
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.onChange: .integer(1),
            PamConstants.inputSyncMode: .integer(1),
        ], kind: .input) { _, kind, payload in
            if kind == EventKind.change.rawValue { values.append(String(decoding: payload, as: UTF8.self)) }
        }
        defer { renderer.close() }
        let field = try XCTUnwrap(view as? PamInputField)
        edit(field, "latest native draft")
        renderer.commit([[.update(id: 2, key: PamConstants.onBlur, value: .integer(1))]])
        XCTAssertTrue(values.isEmpty)
        renderer.routeEvent(999, EventKind.press.rawValue, Data())
        renderer.routeEvent(999, EventKind.press.rawValue, Data())
        XCTAssertEqual(values, ["latest native draft"])
    }

    func testUnmountedOrUnsubscribedInputsNeverFlushIntoAnAction() throws {
        for removeNode in [false, true] {
            var values: [String] = []
            let (_, renderer, view) = PamRenderTestSupport.render([
                PamConstants.onChange: .integer(1),
                PamConstants.inputSyncMode: .integer(2),
                PamConstants.inputDebounceMs: .integer(1_000),
            ], kind: .input) { _, kind, payload in
                if kind == EventKind.change.rawValue { values.append(String(decoding: payload, as: UTF8.self)) }
            }
            defer { renderer.close() }
            edit(try XCTUnwrap(view as? PamInputField), "stale")
            renderer.commit([[removeNode ? .remove(2) : .update(id: 2, key: PamConstants.onChange, value: nil)]])
            renderer.routeEvent(999, EventKind.press.rawValue, Data())
            XCTAssertTrue(values.isEmpty)
        }
    }

    func testReentrantActionSeesNewTextWithoutResendingTheFlushedValue() throws {
        var events: [String] = []
        var onFirstChange: (() -> Void)?
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.onChange: .integer(1),
            PamConstants.inputSyncMode: .integer(1),
        ], kind: .input) { _, kind, payload in
            if kind == EventKind.change.rawValue {
                events.append(String(decoding: payload, as: UTF8.self))
                let action = onFirstChange
                onFirstChange = nil
                action?()
            } else if kind == EventKind.press.rawValue { events.append("press") }
        }
        defer { renderer.close() }
        let field = try XCTUnwrap(view as? PamInputField)
        edit(field, "first")
        onFirstChange = {
            self.edit(field, "second")
            renderer.routeEvent(999, EventKind.press.rawValue, Data())
        }
        renderer.routeEvent(999, EventKind.press.rawValue, Data())
        XCTAssertEqual(events, ["first", "second", "press", "press"])
    }

    func testClosingRendererCancelsPendingNativeInput() throws {
        var changes = 0
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.onChange: .integer(1),
            PamConstants.inputSyncMode: .integer(1),
        ], kind: .input) { _, kind, _ in
            if kind == EventKind.change.rawValue { changes += 1 }
        }
        edit(try XCTUnwrap(view as? PamInputField), "pending")
        renderer.close()
        renderer.routeEvent(999, EventKind.press.rawValue, Data())
        XCTAssertEqual(changes, 0)
    }
    func testAuthoritativeValueWinsOverPendingDraftInEveryHandlerModeOrder() throws {
        let orders = [[0, 1, 2], [0, 2, 1], [1, 0, 2], [1, 2, 0], [2, 0, 1], [2, 1, 0]]
        for order in orders {
            var values: [String] = []
            let (_, renderer, view) = PamRenderTestSupport.render([
                PamConstants.onChange: .integer(1),
                PamConstants.inputSyncMode: .integer(2),
                PamConstants.inputDebounceMs: .integer(1_000),
            ], kind: .input) { _, kind, payload in
                if kind == EventKind.change.rawValue { values.append(String(decoding: payload, as: UTF8.self)) }
            }
            defer { renderer.close() }
            let field = try XCTUnwrap(view as? PamInputField)
            edit(field, "stale draft")
            let changes: [Mutation] = [
                .update(id: 2, key: PamConstants.value, value: .text("authored reset")),
                .update(id: 2, key: PamConstants.onChange, value: .integer(2)),
                .update(id: 2, key: PamConstants.inputSyncMode, value: .integer(3)),
            ]
            renderer.commit([order.map { changes[$0] }])
            renderer.routeEvent(999, EventKind.press.rawValue, Data())
            XCTAssertEqual(field.inputText, "authored reset", "order \(order)")
            XCTAssertTrue(values.isEmpty, "A pending old value must never overwrite authored state: \(order)")
        }
    }

    func testAuthoritativeTextAlsoInvalidatesNativePendingValue() throws {
        var values: [String] = []
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.onChange: .integer(1),
            PamConstants.inputSyncMode: .integer(1),
        ], kind: .input) { _, kind, payload in
            if kind == EventKind.change.rawValue { values.append(String(decoding: payload, as: UTF8.self)) }
        }
        defer { renderer.close() }
        let field = try XCTUnwrap(view as? PamInputField)
        edit(field, "draft")
        renderer.commit([[.update(id: 2, key: PamConstants.text, value: .text("new value"))]])
        renderer.routeEvent(999, EventKind.submit.rawValue, Data())
        XCTAssertEqual(field.inputText, "new value")
        XCTAssertTrue(values.isEmpty)
    }

    func testSwitchToImmediatePreservesUnchangedVisibleDraftExactlyOnce() throws {
        var values: [String] = []
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.onChange: .integer(1),
            PamConstants.inputSyncMode: .integer(1),
        ], kind: .input) { _, kind, payload in
            if kind == EventKind.change.rawValue { values.append(String(decoding: payload, as: UTF8.self)) }
        }
        defer { renderer.close() }
        edit(try XCTUnwrap(view as? PamInputField), "retain me")
        renderer.commit([[
            .update(id: 2, key: PamConstants.inputSyncMode, value: .integer(3)),
            .update(id: 2, key: PamConstants.onChange, value: .integer(2)),
        ]])
        renderer.routeEvent(999, EventKind.press.rawValue, Data())
        XCTAssertEqual(values, ["retain me"])
    }

}
