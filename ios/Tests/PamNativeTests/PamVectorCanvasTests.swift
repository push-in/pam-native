import UIKit
import XCTest
@testable import PamNative

final class PamVectorCanvasTests: XCTestCase {
    /// The canvas is transparent where it draws nothing (Android's canvas
    /// View), so a chart with a few bars shows the card behind it instead of
    /// a black rectangle.
    func testUndrawnAreaIsTransparent() throws {
        let canvas = PamVectorCanvas(frame: CGRect(x: 0, y: 0, width: 40, height: 20))
        XCTAssertFalse(canvas.isOpaque)
        canvas.setCommands(#"[{"kind":1,"x":0,"y":10,"width":10,"height":10,"color":4278255360}]"#)
        canvas.layer.displayIfNeeded()
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let image = UIGraphicsImageRenderer(size: canvas.bounds.size, format: format).image { context in
            UIColor.white.setFill()
            context.fill(canvas.bounds)
            canvas.layer.render(in: context.cgContext)
        }
        let cg = try XCTUnwrap(image.cgImage)
        var pixel = [UInt8](repeating: 0, count: 4)
        let space = CGColorSpaceCreateDeviceRGB()
        func rgba(_ x: Int, _ y: Int) -> [UInt8] {
            let ctx = CGContext(data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            ctx.draw(cg, in: CGRect(x: -x, y: y - cg.height + 1, width: cg.width, height: cg.height))
            return pixel
        }
        XCTAssertEqual(Array(rgba(30, 2).prefix(3)), [255, 255, 255], "undrawn area shows what is behind")
        XCTAssertEqual(Array(rgba(5, 15).prefix(3)), [0, 255, 0], "drawn bar keeps its colour")
    }
}
