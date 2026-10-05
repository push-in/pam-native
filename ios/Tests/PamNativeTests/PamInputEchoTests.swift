import XCTest
@testable import PamNative

/// Controlled TextInput reconciliation (Android `PamControlledInputEchoTest`
/// parity): a late PHP echo never overwrites newer keyboard text.
final class PamInputEchoTests: XCTestCase {
    private func typed(_ values: String...) -> [(String, TimeInterval)] {
        var queue: [(String, TimeInterval)] = []
        for (index, value) in values.enumerated() {
            PamInputEcho.record(&queue, value: value, now: TimeInterval(index))
        }
        return queue
    }

    func testStaleEchoOfAnOlderKeystrokeIsIgnored() {
        var inFlight = typed("qa tes", "qa test", "qa teste")
        XCTAssertTrue(PamInputEcho.isStale(&inFlight, value: "qa test", current: "qa teste", now: 3))
        XCTAssertEqual(inFlight.map(\.0), ["qa teste"])
        XCTAssertFalse(PamInputEcho.isStale(&inFlight, value: "qa teste", current: "qa teste", now: 3))
        XCTAssertTrue(inFlight.isEmpty)
    }

    func testDeleteBurstEchoesNeverResurrectText() {
        let original = "J6pKKx9QwZ4rTyU2"
        let values = (1...16).map { String(original.dropLast($0)) }
        var inFlight: [(String, TimeInterval)] = []
        for value in values { PamInputEcho.record(&inFlight, value: value, now: 0) }
        for echoed in values.dropLast() {
            XCTAssertTrue(PamInputEcho.isStale(&inFlight, value: echoed, current: "", now: 1))
        }
        XCTAssertFalse(PamInputEcho.isStale(&inFlight, value: "", current: "", now: 1))
    }

    func testAuthoredValuesAreApplied() {
        var inFlight = typed("o", "ok")
        XCTAssertFalse(PamInputEcho.isStale(&inFlight, value: "", current: "ok", now: 2))
        XCTAssertTrue(inFlight.isEmpty)
    }

    func testExpiredEntriesNoLongerSuppressAuthoredValues() {
        var inFlight = typed("draft", "draft!")
        XCTAssertFalse(PamInputEcho.isStale(&inFlight, value: "draft", current: "draft!", now: PamInputEcho.window + 5))
    }
}
