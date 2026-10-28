import UIKit
import XCTest
@testable import PamNative

/// Releasing a media view whose poster is not decoded yet (a loops page
/// recycled while its video played) must not start a poster load from
/// deinit: the load captures `[weak self]`, and forming a weak reference to a
/// deallocating object aborts the process.
@MainActor
final class PamMediaViewTeardownTests: XCTestCase {
    func testDeallocatingWithAPendingPosterDoesNotReloadIt() {
        weak var released: PamMediaView?
        autoreleasepool {
            let media = PamMediaView(frame: CGRect(x: 0, y: 0, width: 200, height: 356))
            media.setThumbnail("https://127.0.0.1:9/poster.jpg", cacheKey: nil, policy: 1, maxAgeMs: 0)
            media.setThumbnail(nil, cacheKey: nil, policy: 1, maxAgeMs: 0)
            media.setThumbnail("https://127.0.0.1:9/poster-2.jpg", cacheKey: nil, policy: 1, maxAgeMs: 0)
            released = media
        }
        XCTAssertNil(released, "the view deallocated without aborting")
    }
}
