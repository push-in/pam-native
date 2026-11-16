import XCTest
@testable import PamNative

/// The same retry rules as Android's NativeMediaNetworkTest.
final class PamMediaNetworkTests: XCTestCase {
    func testRetryAfterAcceptsSecondsAndHttpDatesAndIsCapped() {
        XCTAssertEqual(PamMediaRetry.retryAfter("1"), 1)
        XCTAssertEqual(PamMediaRetry.retryAfter("3600"), PamMediaRetry.maxDelay)
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        XCTAssertEqual(PamMediaRetry.retryAfter("Tue, 14 Nov 2023 22:13:25 GMT", now: now), 5)
        XCTAssertEqual(PamMediaRetry.retryAfter("Tue, 14 Nov 2023 22:00:00 GMT", now: now), 0)
        XCTAssertNil(PamMediaRetry.retryAfter(nil))
        XCTAssertNil(PamMediaRetry.retryAfter("soon"))
    }

    func testBackoffDoublesWithJitterUpToThirtySeconds() {
        XCTAssertEqual(PamMediaRetry.backoff(attempt: 0, random: 0), 0.25)
        XCTAssertEqual(PamMediaRetry.backoff(attempt: 0, random: 1), 0.5)
        XCTAssertEqual(PamMediaRetry.backoff(attempt: 2, random: 1), 2)
        XCTAssertEqual(PamMediaRetry.backoff(attempt: 12, random: 1), PamMediaRetry.maxDelay)
    }

    func testTransientStatusesRetryAndHonorRetryAfter() throws {
        let delay = try XCTUnwrap(PamMediaRetry.delay(status: 429, retryAfter: 1, attempt: 3, random: 0.5))
        XCTAssertTrue((1...2).contains(delay))
        for status in [408, 502, 503, 504] {
            XCTAssertNotNil(PamMediaRetry.delay(status: status, retryAfter: nil, attempt: 0))
        }
        for status in [400, 401, 403, 404, 410, 500] {
            XCTAssertNil(PamMediaRetry.delay(status: status, retryAfter: nil, attempt: 0))
        }
        XCTAssertTrue(PamMediaRetry.isTransient(URLError(.timedOut)))
        XCTAssertTrue(PamMediaRetry.isTransient(URLError(.networkConnectionLost)))
        XCTAssertFalse(PamMediaRetry.isTransient(URLError(.badURL)))
    }

    func testRateLimitedHostPausesAndClears() {
        let gate = PamMediaHostGate()
        XCTAssertEqual(gate.remaining("api.test"), 0)
        gate.pause("API.test", retryAfter: 0.3)
        XCTAssertGreaterThan(gate.remaining("api.test"), 0.2)
        XCTAssertEqual(gate.remaining("cdn.test"), 0)
        // A request that started before the pause does not lift it.
        gate.succeeded("api.test")
        XCTAssertGreaterThan(gate.remaining("api.test"), 0)
        Thread.sleep(forTimeInterval: 0.35)
        XCTAssertEqual(gate.remaining("api.test"), 0)
    }
}
