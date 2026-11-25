import Foundation
import XCTest
@testable import PamNative

final class PamImageDecodeWorkTests: XCTestCase {
    func testCancelledPendingDecodeNeverRunsAndOtherViewStillFinishes() {
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 2
        queue.isSuspended = true
        let obsolete = PamImageDecodeWork()
        let visible = PamImageDecodeWork()
        let finished = expectation(description: "visible image decoded")
        obsolete.submit(on: queue) { XCTFail("recycled view decoded obsolete bytes") }
        visible.submit(on: queue) { finished.fulfill() }
        obsolete.cancel()
        queue.isSuspended = false
        wait(for: [finished], timeout: 2)
        queue.waitUntilAllOperationsAreFinished()
    }

    func testCancelledLookupCannotEnqueueWorkWhenDiskAnswersLater() {
        let queue = OperationQueue()
        let demand = PamImageDecodeWork()
        demand.cancel()
        demand.submit(on: queue) { XCTFail("late disk lookup decoded after removal") }
        queue.waitUntilAllOperationsAreFinished()
        XCTAssertEqual(queue.operationCount, 0)
        XCTAssertTrue(demand.isCancelled)
    }

    func testRunningDecodeObservesCancellationBeforePublishingPixels() {
        let queue = OperationQueue()
        let demand = PamImageDecodeWork()
        let decoding = expectation(description: "decode started")
        let discarded = expectation(description: "obsolete result discarded")
        let release = DispatchSemaphore(value: 0)
        demand.submit(on: queue) {
            decoding.fulfill()
            guard release.wait(timeout: .now() + 2) == .success else {
                XCTFail("test did not release decode")
                return
            }
            XCTAssertTrue(demand.isCancelled)
            discarded.fulfill()
        }
        wait(for: [decoding], timeout: 2)
        demand.cancel()
        release.signal()
        wait(for: [discarded], timeout: 2)
        queue.waitUntilAllOperationsAreFinished()
    }

    func testCompletedWorkReleasesCapturedPayloadWhileViewRemainsAlive() {
        final class Payload: @unchecked Sendable {
            let onRelease: () -> Void
            init(onRelease: @escaping () -> Void) { self.onRelease = onRelease }
            deinit { onRelease() }
        }
        let queue = OperationQueue()
        let demand = PamImageDecodeWork()
        weak var released: Payload?
        let release = expectation(description: "completed bytes released")
        autoreleasepool {
            let payload = Payload { release.fulfill() }
            released = payload
            demand.submit(on: queue) { _ = payload }
        }
        queue.waitUntilAllOperationsAreFinished()
        wait(for: [release], timeout: 2)
        XCTAssertNil(released, "completed image bytes must not be retained with the view")
        XCTAssertFalse(demand.isCancelled)
    }
}
