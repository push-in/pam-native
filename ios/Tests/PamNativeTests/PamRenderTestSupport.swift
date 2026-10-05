import UIKit
import XCTest
@testable import PamNative

/// Shared helpers for the renderer parity tests (mirrors the Android
/// instrumented tests' `render(...)` + bitmap sampling).
@MainActor
enum PamRenderTestSupport {
    static func render(
        _ properties: [Int: PropValue],
        kind: NodeKind,
        frame: Frame = Frame(x: 20, y: 20, width: 200, height: 100),
        dispatch: @escaping (Int64, Int, Data) -> Void = { _, _, _ in }
    ) -> (host: UIView, renderer: PamRenderer, view: UIView) {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 360, height: 720))
        let renderer = PamRenderer(hostView: host, dispatchEvent: dispatch)
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: kind, properties: properties)),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 360, height: 720)),
            .layout(id: 2, frame: frame),
            .setRoot(1),
        ]])
        let view = host.viewWithTag(2)!
        return (host, renderer, view)
    }

    /// Renders `view` (layers included) at 1 px per point.
    static func snapshot(_ view: UIView) -> CGImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let image = UIGraphicsImageRenderer(bounds: view.bounds, format: format).image { context in
            view.layer.render(in: context.cgContext)
        }
        return image.cgImage!
    }

    /// ARGB of the pixel at (x, y).
    static func pixel(_ image: CGImage, _ x: Int, _ y: Int) -> UInt32 {
        var data = [UInt8](repeating: 0, count: 4)
        let space = CGColorSpaceCreateDeviceRGB()
        let context = CGContext(
            data: &data,
            width: 1,
            height: 1,
            bitsPerComponent: 8,
            bytesPerRow: 4,
            space: space,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!
        context.draw(image, in: CGRect(x: -x, y: y - image.height + 1, width: image.width, height: image.height))
        return UInt32(data[3]) << 24 | UInt32(data[0]) << 16 | UInt32(data[1]) << 8 | UInt32(data[2])
    }

    static func assertColor(
        _ actual: UInt32,
        _ expected: UInt32,
        tolerance: Int = 12,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        for shift in [24, 16, 8, 0] {
            let a = Int((actual >> UInt32(shift)) & 0xFF)
            let b = Int((expected >> UInt32(shift)) & 0xFF)
            XCTAssertLessThanOrEqual(
                abs(a - b),
                tolerance,
                String(format: "color %08X != %08X", actual, expected),
                file: file,
                line: line
            )
        }
    }
}
