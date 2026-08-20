import XCTest
@testable import PamNative

private final class PluginFixtureModule: NativeModule {
    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        completion(.success, Data("\(method):".utf8) + payload)
    }
}

@MainActor
private final class PluginFixtureViewFactory: NativeViewFactory {
    func create(context: AnyObject?, emit: @escaping (Data) -> Void) -> UIView {
        UIView()
    }

    func update(view: UIView, properties: [String: WireValue]) {}
    func close() {}
}

@MainActor
final class CapabilityIntegrationTests: XCTestCase {
    func testVoiceOverExposesSemanticRoleStateValueAndImportance() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(
                id: 2,
                parent: 1,
                index: 0,
                kind: .text,
                properties: [
                    PamConstants.text: .text("Upload"),
                    PamConstants.accessibilityLabel: .text("Upload progress"),
                    PamConstants.accessibilityRole: .integer(8),
                    PamConstants.accessibilityImportance: .integer(2),
                    PamConstants.accessibilityLiveRegion: .integer(3),
                    PamConstants.accessibilityCheckedState: .integer(3),
                    PamConstants.accessibilityExpanded: .flag(false),
                    PamConstants.accessibilityBusy: .flag(true),
                    PamConstants.accessibilityValueMin: .decimal(0),
                    PamConstants.accessibilityValueMax: .decimal(100),
                    PamConstants.accessibilityValueNow: .decimal(40),
                    PamConstants.accessibilityValueText: .text("40 percent"),
                    PamConstants.selected: .flag(true),
                    PamConstants.enabled: .flag(false),
                    PamConstants.testId: .text("accessible-state"),
                ]
            )),
            .create(NodeSpec(
                id: 3,
                parent: 1,
                index: 1,
                kind: .text,
                properties: [
                    PamConstants.text: .text("Decorative"),
                    PamConstants.accessibilityImportance: .integer(4),
                    PamConstants.testId: .text("hidden-state"),
                ]
            )),
            .create(NodeSpec(
                id: 4,
                parent: 1,
                index: 2,
                kind: .text,
                properties: [
                    PamConstants.text: .text("Upload range"),
                    PamConstants.accessibilityRole: .integer(6),
                    PamConstants.accessibilityValueMin: .decimal(0),
                    PamConstants.accessibilityValueMax: .decimal(100),
                    PamConstants.accessibilityValueNow: .decimal(40),
                    PamConstants.testId: .text("accessible-range"),
                ]
            )),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 390, height: 844)),
            .layout(id: 2, frame: Frame(x: 16, y: 16, width: 200, height: 48)),
            .layout(id: 3, frame: Frame(x: 16, y: 72, width: 200, height: 48)),
            .layout(id: 4, frame: Frame(x: 16, y: 128, width: 200, height: 48)),
            .setRoot(1),
        ]])

        let view = try XCTUnwrap(host.descendant(accessibilityIdentifier: "accessible-state"))
        XCTAssertEqual(view.accessibilityLabel, "Upload progress")
        XCTAssertTrue(view.isAccessibilityElement)
        XCTAssertTrue(view.accessibilityTraits.contains(.button))
        XCTAssertTrue(view.accessibilityTraits.contains(.selected))
        XCTAssertTrue(view.accessibilityTraits.contains(.notEnabled))
        XCTAssertTrue(view.accessibilityTraits.contains(.updatesFrequently))
        XCTAssertEqual(view.accessibilityValue, "40 percent, Mixed, Collapsed, Loading")

        let hidden = try XCTUnwrap(host.descendant(accessibilityIdentifier: "hidden-state"))
        XCTAssertTrue(hidden.accessibilityElementsHidden)
        let range = try XCTUnwrap(host.descendant(accessibilityIdentifier: "accessible-range"))
        XCTAssertTrue(range.accessibilityTraits.contains(.adjustable))
        XCTAssertEqual(range.accessibilityValue, "40 / 100")
        renderer.close()
    }

    func testVoiceOverCustomActionDispatchesItsBoundedIdentifier() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        var events: [(Int64, Int, Data)] = []
        let renderer = PamRenderer(hostView: host) { id, kind, payload in
            events.append((id, kind, payload))
        }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(
                id: 2,
                parent: 1,
                index: 0,
                kind: .text,
                properties: [
                    PamConstants.text: .text("Message"),
                    PamConstants.accessibilityActions: .text(
                        #"[{"name":"archive","label":"Archive message"}]"#
                    ),
                    PamConstants.onAccessibilityAction: .flag(true),
                    PamConstants.testId: .text("message-actions"),
                ]
            )),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 390, height: 844)),
            .layout(id: 2, frame: Frame(x: 16, y: 16, width: 200, height: 48)),
            .setRoot(1),
        ]])

        let view = try XCTUnwrap(host.descendant(accessibilityIdentifier: "message-actions"))
        let action = try XCTUnwrap(view.accessibilityCustomActions?.first)
        XCTAssertEqual(action.name, "Archive message")
        XCTAssertTrue(action.actionHandler?(action) == true)
        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(events[0].0, 2)
        XCTAssertEqual(events[0].1, EventKind.accessibilityAction.rawValue)
        XCTAssertEqual(String(data: events[0].2, encoding: .utf8), "archive")
        renderer.close()
    }

    func testPluginRegistriesInjectModulesAndViews() {
        let modules = NativeModuleRegistry(additionalModules: [
            "fixture.echo": PluginFixtureModule(),
        ])
        let moduleExpectation = expectation(description: "plugin module result")
        modules.invoke(module: "fixture.echo", method: "ping", payload: Data("pam".utf8)) { status, data in
            XCTAssertEqual(status, .success)
            XCTAssertEqual(String(data: data, encoding: .utf8), "ping:pam")
            moduleExpectation.fulfill()
        }

        let views = NativeViewRegistry(additionalFactories: [
            "fixture.view": PluginFixtureViewFactory(),
        ])
        let view = views.create(name: "fixture.view") { _, _ in }
        XCTAssertTrue(type(of: view) == UIView.self)
        views.release(view: view)
        wait(for: [moduleExpectation], timeout: 1)
    }

    func testDevToolsRendersCapabilityFailureTimeline() {
        let overlay = PamDevToolsOverlay(frame: CGRect(x: 0, y: 0, width: 360, height: 500))
        overlay.update(RuntimeFrameMetrics(
            batches: 1,
            decodeNanos: 1_000_000,
            mountNanos: 2_000_000,
            stats: RuntimeStats(
                commits: 1,
                nodes: 4,
                created: 4,
                removed: 0,
                updated: 0,
                retainedBytes: 1_024,
                fullCommits: 1,
                patchCommits: 0,
                inputBytes: 128,
                outputBytes: 256,
                decodeP95Micros: 900,
                reconcileP95Micros: 700,
                layoutP95Micros: 1_100,
                encodeP95Micros: 100,
                coalescedCommands: 3,
                bufferReuses: 8,
                reusedBufferBytes: 4_096,
                measuredFrames: 120,
                deadlineMisses: 2
            )
        ))
        overlay.record(RuntimeDiagnostic(
            kind: .moduleCall,
            label: "permissions.request",
            durationNanos: 12_000_000,
            failed: true
        ))
        overlay.setVisible(true)

        XCTAssertTrue(overlay.accessibilityValue?.contains("FAIL") == true)
        XCTAssertTrue(overlay.accessibilityValue?.contains("permissions.request") == true)
    }

    func testDevToolsExportsBoundedRedactedCrossHostSnapshot() throws {
        let overlay = PamDevToolsOverlay(frame: .zero)
        for index in 0..<7 {
            overlay.record(RuntimeDiagnostic(
                kind: .error,
                label: "secret-\(index)",
                failed: true
            ))
        }
        overlay.record(RuntimeDiagnostic(
            kind: .network,
            label: "PATCH https://secret.example/private?token=secret",
            durationNanos: 12_345_000,
            methodCode: RuntimeHttpMethod.patch.rawValue,
            statusCode: 202,
            requestBytes: 17,
            responseBytes: 8
        ))

        let data = try overlay.snapshotData(capturedAtUnixMs: 1_234)
        let snapshot = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        XCTAssertEqual(snapshot["schemaVersion"] as? Int, 1)
        XCTAssertEqual(snapshot["surfaceCode"] as? Int, 2)
        XCTAssertEqual(snapshot["platformCode"] as? Int, 2)
        XCTAssertEqual(snapshot["capturedAtUnixMs"] as? Int, 1_234)
        XCTAssertEqual((snapshot["timeline"] as? [[String: Any]])?.count, 8)
        XCTAssertFalse(String(data: data, encoding: .utf8)?.contains("secret-") == true)
        XCTAssertFalse(String(data: data, encoding: .utf8)?.contains("secret.example") == true)
        let network = try XCTUnwrap((snapshot["timeline"] as? [[String: Any]])?.last)
        XCTAssertEqual(network["kindCode"] as? Int, RuntimeDiagnosticKind.network.rawValue)
        XCTAssertEqual(network["methodCode"] as? Int, RuntimeHttpMethod.patch.rawValue)
        XCTAssertEqual(network["statusCode"] as? Int, 202)
        XCTAssertEqual(network["requestBytes"] as? Int, 17)
        XCTAssertEqual(network["responseBytes"] as? Int, 8)
    }

    func testWatchChannelDropsOldValuesAndKeepsLatestBackpressureWindow() {
        let channel = WatchChannel()
        for value in 1...8 {
            channel.offer(Data([UInt8(value)]))
        }
        var received: [UInt8] = []
        for _ in 0..<4 {
            channel.next { status, data in
                XCTAssertEqual(status, .success)
                received.append(data[0])
            }
        }
        XCTAssertEqual(received, [5, 6, 7, 8])
    }
}

@MainActor
private extension UIView {
    func descendant(accessibilityIdentifier identifier: String) -> UIView? {
        if accessibilityIdentifier == identifier { return self }
        for child in subviews {
            if let match = child.descendant(accessibilityIdentifier: identifier) {
                return match
            }
        }
        return nil
    }
}
