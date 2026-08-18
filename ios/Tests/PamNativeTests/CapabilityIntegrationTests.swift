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
