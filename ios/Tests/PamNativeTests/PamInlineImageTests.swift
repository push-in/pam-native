import UIKit
import XCTest
@testable import PamNative

/// Inline `data:image/*` icon masks and `tintColor` (1.19.1), mirroring
/// Android `InFlightImageLoadsTest` and `NativeImagePriorityTest`.
/// Uncompiled on Linux — needs Mac validation.
@MainActor
final class PamInlineImageTests: XCTestCase {
    /// 2×2 opaque black PNG.
    private let mask = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEElEQVR4nGNgYGD4D8UQBgAd9AP9yOH2qAAAAABJRU5ErkJggg=="

    func testInlineSourcePaintsInTheCommitThatAssignsIt() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.source: .text(mask),
        ], kind: .image)
        defer { renderer.close() }
        let imageView = try XCTUnwrap(view as? PamImageView)
        XCTAssertNotNil(imageView.image, "a data URI is decoded synchronously, never left blank")
    }

    func testTintColorPaintsTheMaskInThatColor() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.source: .text(mask),
            PamConstants.tintColor: .integer(0xFFFF_D23F),
        ], kind: .image)
        defer { renderer.close() }
        let imageView = try XCTUnwrap(view as? PamImageView)
        XCTAssertEqual(imageView.image?.renderingMode, .alwaysTemplate)
        let pixel = PamRenderTestSupport.pixel(PamRenderTestSupport.snapshot(imageView), 100, 50)
        PamRenderTestSupport.assertColor(pixel, 0xFFFF_D23F, tolerance: 8)
    }

    func testDecodingAcceptsBase64AndRejectsOtherMediaTypes() {
        XCTAssertNotNil(PamInlineImages.decode(mask))
        XCTAssertNil(PamInlineImages.decode("data:text/plain;base64,AAAA"))
        XCTAssertNil(PamInlineImages.image("https://cdn.example.test/icon.png"))
    }
}
