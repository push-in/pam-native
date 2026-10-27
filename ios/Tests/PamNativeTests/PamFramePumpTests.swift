import XCTest
@testable import PamNative

final class PamFramePumpTests: XCTestCase {
    func testRequestResumesTheLinkOncePerTick() {
        var pump = PamFramePump()
        XCTAssertTrue(pump.request())
        XCTAssertFalse(pump.request())
        pump.ticked()
        XCTAssertTrue(pump.request())
    }

    /// A heavy commit flushed after the frame pauses the link while a request
    /// is pending; the next batch must resume it instead of waiting forever.
    func testPausingDropsThePendingRequest() {
        var pump = PamFramePump()
        XCTAssertTrue(pump.request())
        pump.paused()
        XCTAssertTrue(pump.request())
    }
}
