import UIKit
import XCTest
@testable import PamNative

/// A photo whose view is created in the same batch as its `source` decodes at
/// the view's laid-out size, not at its full resolution.
@MainActor
final class PamSizelessImageDecodeTests: XCTestCase {
    private var fileURL: URL!

    override func setUpWithError() throws {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pam-files", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("sizeless-photo-test.png")
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let photo = UIGraphicsImageRenderer(size: CGSize(width: 1_600, height: 1_600), format: format).image { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 1_600, height: 1_600))
        }
        try XCTUnwrap(photo.pngData()).write(to: fileURL)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: fileURL)
    }

    func testPhotoCreatedWithItsLayoutDecodesAtTheViewSize() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.source: .text("pam-file:///sizeless-photo-test.png"),
        ], kind: .image, frame: Frame(x: 0, y: 0, width: 100, height: 100))
        defer { renderer.close() }
        let image = try XCTUnwrap((view as? PamImageView)?.image)
        let covering = 100 * UIScreen.main.scale
        XCTAssertLessThanOrEqual(image.size.width * image.scale, ceil(covering) + 1)
        XCTAssertGreaterThanOrEqual(image.size.width * image.scale, floor(covering) - 1)
    }
}
