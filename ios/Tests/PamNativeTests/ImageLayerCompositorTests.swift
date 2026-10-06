import UIKit
import XCTest
@testable import PamNative

final class ImageLayerCompositorTests: XCTestCase {
    func testBitmapOverlayPreservesOutputSizeAndAcceptsPrivateResolver() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let source = UIGraphicsImageRenderer(size: CGSize(width: 100, height: 100), format: format).image { _ in
            UIColor.white.setFill()
            UIRectFill(CGRect(x: 0, y: 0, width: 100, height: 100))
        }
        let overlay = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 20), format: format).image { _ in
            UIColor.red.setFill()
            UIRectFill(CGRect(x: 0, y: 0, width: 40, height: 20))
        }
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".png")
        defer { try? FileManager.default.removeItem(at: path) }
        try XCTUnwrap(overlay.pngData()).write(to: path)
        let output = try ImageLayerCompositor.compose(source, encoded: "[{\"path\":\"layer.png\",\"width\":0.4,\"x\":0.2,\"y\":0.3}]") { value in
            XCTAssertEqual(value, "layer.png")
            return path
        }
        XCTAssertEqual(output.size, source.size)
        XCTAssertNotEqual(output.pngData(), source.pngData())
        XCTAssertThrowsError(try ImageLayerCompositor.compose(source, encoded: "[{\"path\":\"missing.png\"}]") { _ in
            path.appendingPathExtension("missing")
        })
    }
}
