import UIKit
import XCTest
@testable import PamNative

/// Decode-at-display-size and memory-sized caches (1.26.0), mirroring Android
/// `NativeImageDecodePlanTest`. Uncompiled on Linux — needs Mac validation.
final class PamImageDownsamplingTests: XCTestCase {
    func testPortraitPhotoCoversASquareCell() throws {
        // 1080x1350 photo in a 120x120 pt cell at 3x: the shorter edge must
        // reach 360 px, so the longest edge is 450 px.
        let edge = try XCTUnwrap(PamImageDownsampling.coverMaxPixelSize(
            sourceSize: CGSize(width: 1_080, height: 1_350),
            targetSize: CGSize(width: 120, height: 120),
            scale: 3,
            multiplier: 1
        ))
        XCTAssertEqual(edge, 450)
    }

    func testSourceNearTheViewSizeIsNotResampled() {
        XCTAssertNil(PamImageDownsampling.coverMaxPixelSize(
            sourceSize: CGSize(width: 1_100, height: 1_950),
            targetSize: CGSize(width: 360, height: 640),
            scale: 3,
            multiplier: 1
        ))
        XCTAssertNil(PamImageDownsampling.coverMaxPixelSize(
            sourceSize: CGSize(width: 200, height: 100),
            targetSize: CGSize(width: 360, height: 360),
            scale: 3,
            multiplier: 1
        ))
    }

    func testMultiplierEnlargesTheTarget() throws {
        let edge = try XCTUnwrap(PamImageDownsampling.coverMaxPixelSize(
            sourceSize: CGSize(width: 2_000, height: 2_000),
            targetSize: CGSize(width: 100, height: 100),
            scale: 3,
            multiplier: 2
        ))
        XCTAssertEqual(edge, 600)
    }

    func testDownsampledImageCoversTheView() throws {
        let renderer = UIGraphicsImageRenderer(
            size: CGSize(width: 1_080, height: 1_350),
            format: {
                let format = UIGraphicsImageRendererFormat()
                format.scale = 1
                return format
            }()
        )
        let data = try XCTUnwrap(renderer.jpegData(withCompressionQuality: 0.8) { context in
            UIColor.orange.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 1_080, height: 1_350))
        })
        let image = try XCTUnwrap(PamImageDownsampling.image(
            data: data,
            targetSize: CGSize(width: 120, height: 120),
            scale: 3
        ))
        let cgImage = try XCTUnwrap(image.cgImage)
        XCTAssertGreaterThanOrEqual(cgImage.width, 360)
        XCTAssertGreaterThanOrEqual(cgImage.height, 360)
        XCTAssertLessThanOrEqual(cgImage.height, 451)
    }

    func testCachesFollowPhysicalMemory() {
        let gib: UInt64 = 1_024 * 1_024 * 1_024
        XCTAssertEqual(PamImageDownsampling.memoryCacheBytes(physicalMemory: 2 * gib), 64 * 1_024 * 1_024)
        XCTAssertEqual(PamImageDownsampling.memoryCacheBytes(physicalMemory: 6 * gib), 96 * 1_024 * 1_024)
        XCTAssertEqual(PamImageDownsampling.memoryCacheBytes(physicalMemory: gib / 4), 16 * 1_024 * 1_024)
        XCTAssertEqual(PamImageDownsampling.inlineCacheBytes(physicalMemory: 2 * gib), 4 * 1_024 * 1_024)
        XCTAssertEqual(PamImageDownsampling.inlineCacheBytes(physicalMemory: 8 * gib), 8 * 1_024 * 1_024)
    }
}
