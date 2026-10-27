import XCTest
@testable import PamNative

@MainActor
final class PamImageResidencyTests: XCTestCase {
    func testImagesWithinOneScreenOfTheWindowAreHeld() {
        let window = CGRect(x: 0, y: 0, width: 400, height: 800)
        XCTAssertTrue(PamImageResidency.isNearScreen(CGRect(x: 0, y: 100, width: 400, height: 500), window: window))
        XCTAssertTrue(PamImageResidency.isNearScreen(CGRect(x: 0, y: 1_500, width: 400, height: 500), window: window), "next screen: prefetched")
        XCTAssertTrue(PamImageResidency.isNearScreen(CGRect(x: 0, y: -1_200, width: 400, height: 500), window: window), "previous screen")
        XCTAssertFalse(PamImageResidency.isNearScreen(CGRect(x: 0, y: 1_700, width: 400, height: 500), window: window), "two screens below")
        XCTAssertFalse(PamImageResidency.isNearScreen(CGRect(x: 900, y: 0, width: 400, height: 500), window: window), "album page two widths away")
        XCTAssertFalse(PamImageResidency.isNearScreen(.zero, window: window))
    }

    func testHiddenAncestorsReleaseButTransparencyDoesNot() {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 400, height: 800))
        window.isHidden = false
        defer { window.isHidden = true }
        let tab = UIView(frame: window.bounds)
        window.addSubview(tab)
        let image = UIImageView(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        tab.addSubview(image)
        XCTAssertTrue(PamImageResidency.shouldHold(image))

        image.alpha = 0
        XCTAssertTrue(PamImageResidency.shouldHold(image), "an image fading in keeps its pixels")
        image.alpha = 1

        tab.isHidden = true
        XCTAssertFalse(PamImageResidency.shouldHold(image), "inactive tab")
        tab.isHidden = false

        image.frame.origin.y = 3_000
        XCTAssertFalse(PamImageResidency.shouldHold(image), "far below the screen")

        image.removeFromSuperview()
        XCTAssertFalse(PamImageResidency.shouldHold(image), "out of the window")
    }

    func testDecodedCacheSharesOneBitmapPerIdentityAndPixelSize() {
        let cache = PamDecodedImageCache(costLimit: 8 * 1_024 * 1_024)
        let key = PamDecodedImageCache.key(identity: "photo", targetSize: CGSize(width: 100, height: 50), scale: 3, multiplier: 1)
        XCTAssertEqual(key, "photo|300x150|1.0")
        XCTAssertNotEqual(key, PamDecodedImageCache.key(identity: "photo", targetSize: CGSize(width: 100, height: 50), scale: 3, multiplier: 0.1))
        XCTAssertNotEqual(key, PamDecodedImageCache.key(identity: "photo", targetSize: CGSize(width: 50, height: 50), scale: 3, multiplier: 1))

        let image = UIGraphicsImageRenderer(size: CGSize(width: 30, height: 15)).image { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 30, height: 15))
        }
        cache.store(image, forKey: key)
        XCTAssertTrue(cache.image(forKey: key) === image)
        XCTAssertGreaterThan(PamDecodedImageCache.cost(image), 0)

        cache.removeAll()
        XCTAssertNil(cache.image(forKey: key))
    }

    func testDecodedBudgetIsHalfTheMediaBudget() {
        let gib: UInt64 = 1_024 * 1_024 * 1_024
        XCTAssertEqual(PamDecodedImageCache.defaultCostLimit(physicalMemory: 6 * gib), 48 * 1_024 * 1_024)
        XCTAssertEqual(PamDecodedImageCache.defaultCostLimit(physicalMemory: gib / 4), 8 * 1_024 * 1_024)
    }
}
