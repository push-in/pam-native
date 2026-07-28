import XCTest
@testable import PamNative

@MainActor
final class CapabilityIntegrationTests: XCTestCase {
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
                outputBytes: 256
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
