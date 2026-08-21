import XCTest
@testable import PamNative

final class PamHotReloadTests: XCTestCase {
    func testMeasuresOneConfirmedVersionThroughItsFirstCommittedFrame() throws {
        let latency = PamHotReloadLatency()
        try latency.begin(confirmedAtNanos: 1_000, bundleBytes: 4_096)

        let timing = latency.complete(completedAtNanos: 3_500, failed: false)

        XCTAssertEqual(timing?.durationNanos, 2_500)
        XCTAssertEqual(timing?.bundleBytes, 4_096)
        XCTAssertEqual(timing?.failed, false)
        XCTAssertNil(latency.complete(completedAtNanos: 4_000, failed: false))
    }

    func testNewerVersionReplacesPendingMeasurementAndFailureCompletesIt() throws {
        let latency = PamHotReloadLatency()
        try latency.begin(confirmedAtNanos: 1_000, bundleBytes: 100)
        try latency.begin(confirmedAtNanos: 2_000, bundleBytes: 200)

        let timing = latency.complete(completedAtNanos: 2_500, failed: true)

        XCTAssertEqual(timing?.durationNanos, 500)
        XCTAssertEqual(timing?.bundleBytes, 200)
        XCTAssertEqual(timing?.failed, true)
    }

    func testRejectsInvalidMonotonicTimeAndBundleSizes() {
        let latency = PamHotReloadLatency()
        XCTAssertThrowsError(try latency.begin(confirmedAtNanos: 0, bundleBytes: 1))
        XCTAssertThrowsError(try latency.begin(confirmedAtNanos: 1, bundleBytes: 0))
        XCTAssertThrowsError(try latency.begin(
            confirmedAtNanos: 1,
            bundleBytes: PamHotReloadClient.maximumBundleBytes + 1
        ))
    }

    func testStatisticsReportsNearestRankP95AndFailureRate() throws {
        let statistics = try PamHotReloadStatistics(capacity: 32, p95BudgetNanos: 19_000)
        for duration in 1...20 {
            try statistics.record(PamHotReloadTiming(
                durationNanos: Int64(duration * 1_000), bundleBytes: 100, failed: false
            ))
        }
        try statistics.record(PamHotReloadTiming(durationNanos: 50_000, bundleBytes: 100, failed: true))

        let snapshot = statistics.snapshot()
        XCTAssertEqual(snapshot.sampleCount, 21)
        XCTAssertEqual(snapshot.successfulCount, 20)
        XCTAssertEqual(snapshot.failureCount, 1)
        XCTAssertEqual(snapshot.p95DurationNanos, 19_000)
        XCTAssertEqual(snapshot.failureRateBasisPoints, 476)
        XCTAssertEqual(snapshot.p95WithinBudget, true)
    }

    func testStatisticsEvictsOldestAndDoesNotInventFailureLatency() throws {
        let statistics = try PamHotReloadStatistics(capacity: 2, p95BudgetNanos: 100)
        XCTAssertNil(statistics.snapshot().p95DurationNanos)
        try statistics.record(PamHotReloadTiming(durationNanos: 10, bundleBytes: 10, failed: true))
        XCTAssertNil(statistics.snapshot().p95DurationNanos)
        try statistics.record(PamHotReloadTiming(durationNanos: 90, bundleBytes: 10, failed: false))
        try statistics.record(PamHotReloadTiming(durationNanos: 101, bundleBytes: 10, failed: false))

        let snapshot = statistics.snapshot()
        XCTAssertEqual(snapshot.sampleCount, 2)
        XCTAssertEqual(snapshot.failureCount, 0)
        XCTAssertEqual(snapshot.p95DurationNanos, 101)
        XCTAssertEqual(snapshot.p95WithinBudget, false)
    }
}
