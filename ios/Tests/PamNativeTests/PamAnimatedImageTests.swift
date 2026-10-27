import ImageIO
import UIKit
import UniformTypeIdentifiers
import XCTest
@testable import PamNative

/// Animated GIF/WebP (emoji, GIF panel, WebP stickers): Android plays them
/// with `AnimatedImageDrawable`; iOS used to show only the first frame.
final class PamAnimatedImageTests: XCTestCase {
    /// 16×16 animated WebP: red, green, blue at 100 ms, looping (ffmpeg libwebp_anim).
    static let webp = "UklGRrQAAABXRUJQVlA4WAoAAAACAAAADwAADwAAQU5JTQYAAAD/////AABBTk1GKAAAAAAAAAAAAA8AAA8AAGQAAAJWUDhMDwAAAC8PwAMABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAA8AAA8AAGQAAABWUDhMDwAAAC8PwAMAB9D+iP4HIqL/AQBBTk1GKAAAAAAAAAAAAA8AAA8AAGQAAABWUDhMDwAAAC8PwAMABxBR//4HIqL/AQA="

    /// Animated GIF with one solid color per frame.
    static func gif(colors: [UIColor], delays: [Double], size: CGSize = CGSize(width: 240, height: 240)) -> Data {
        let data = NSMutableData()
        let destination = CGImageDestinationCreateWithData(
            data, UTType.gif.identifier as CFString, colors.count, nil
        )!
        CGImageDestinationSetProperties(destination, [
            kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFLoopCount: 0],
        ] as CFDictionary)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        for (color, delay) in zip(colors, delays) {
            let frame = UIGraphicsImageRenderer(size: size, format: format).image { context in
                color.setFill()
                context.fill(CGRect(origin: .zero, size: size))
            }
            CGImageDestinationAddImage(destination, frame.cgImage!, [
                kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFDelayTime: delay],
            ] as CFDictionary)
        }
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }

    func testGifDecodesEveryFrameWithItsDelays() throws {
        let data = Self.gif(colors: [.red, .green, .blue], delays: [0.1, 0.2, 0])
        let image = try XCTUnwrap(PamAnimatedImage.image(data: data, targetSize: .zero))
        let animation = try XCTUnwrap(image.pamAnimation)
        XCTAssertEqual(animation.frameCount, 3)
        XCTAssertEqual(animation.delays[0], 0.1, accuracy: 0.001)
        XCTAssertEqual(animation.delays[1], 0.2, accuracy: 0.001)
        XCTAssertEqual(animation.delays[2], PamAnimatedImage.defaultDelay, accuracy: 0.001,
                       "a 0 ms delay plays at 100 ms like browsers and Android")
        XCTAssertNotNil(animation.decodeFrame(2))
    }

    func testAnimatedWebPDecodesEveryFrame() throws {
        let data = try XCTUnwrap(Data(base64Encoded: Self.webp))
        let image = try XCTUnwrap(PamAnimatedImage.image(data: data, targetSize: .zero))
        let animation = try XCTUnwrap(image.pamAnimation)
        XCTAssertEqual(animation.frameCount, 3)
        XCTAssertEqual(animation.duration, 0.3, accuracy: 0.01)
        let first = try XCTUnwrap(animation.decodeFrame(0))
        let last = try XCTUnwrap(animation.decodeFrame(2))
        XCTAssertNotEqual(Self.center(first), Self.center(last), "frames really differ")
    }

    func testFramesDecodeAtTheCoveringViewSizeNotTheSourceSize() throws {
        // 240 px source in a 20 pt emoji at 3x: 60 px frames.
        let data = Self.gif(colors: [.red, .green], delays: [0.1, 0.1])
        let image = try XCTUnwrap(PamAnimatedImage.image(
            data: data, targetSize: CGSize(width: 20, height: 20), scale: 3
        ))
        let animation = try XCTUnwrap(image.pamAnimation)
        XCTAssertEqual(animation.pixelSize, CGSize(width: 60, height: 60))
        XCTAssertEqual(try XCTUnwrap(animation.decodeFrame(1)).width, 60)
    }

    func testStaticImagesAreNotAnimated() throws {
        let data = Self.gif(colors: [.red], delays: [0.1])
        XCTAssertNil(PamAnimatedImage.image(data: data, targetSize: .zero))
        XCTAssertNil(PamImageDownsampling.image(data: data, targetSize: CGSize(width: 10, height: 10))?.pamAnimation)
    }

    func testDownsamplingPathReturnsTheAnimation() throws {
        let data = Self.gif(colors: [.red, .green], delays: [0.1, 0.1])
        let image = try XCTUnwrap(PamImageDownsampling.image(data: data, targetSize: CGSize(width: 40, height: 40)))
        XCTAssertEqual(image.pamAnimation?.frameCount, 2)
    }

    func testFrameIndexFollowsElapsedTimeAndLoops() {
        let delays = [0.1, 0.2, 0.1]
        XCTAssertEqual(PamAnimatedImage.frameIndex(elapsed: 0, delays: delays), 0)
        XCTAssertEqual(PamAnimatedImage.frameIndex(elapsed: 0.05, delays: delays), 0)
        XCTAssertEqual(PamAnimatedImage.frameIndex(elapsed: 0.15, delays: delays), 1)
        XCTAssertEqual(PamAnimatedImage.frameIndex(elapsed: 0.35, delays: delays), 2)
        XCTAssertEqual(PamAnimatedImage.frameIndex(elapsed: 0.45, delays: delays), 0, "loops forever")
        XCTAssertEqual(PamAnimatedImage.normalizedDelay(0.005), 0.1)
        XCTAssertEqual(PamAnimatedImage.normalizedDelay(nil), 0.1)
        XCTAssertEqual(PamAnimatedImage.normalizedDelay(0.04), 0.04)
    }

    @MainActor
    func testImageViewAnimatesOnlyWhileVisible() throws {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 200, height: 200))
        window.isHidden = false
        defer { window.isHidden = true }
        let view = PamImageView(frame: CGRect(x: 0, y: 0, width: 40, height: 40))
        let data = Self.gif(colors: [.red, .green, .blue], delays: [0.1, 0.1, 0.1])
        view.image = PamImageDownsampling.image(data: data, targetSize: view.bounds.size)
        XCTAssertFalse(view.isAnimatingImage, "not in a window: no clock")
        window.addSubview(view)
        XCTAssertTrue(view.isAnimatingImage)
        XCTAssertTrue(PamAnimatedImageClock.shared.isRunning)
        let first = view.image?.cgImage
        // Frame 1 decodes off the main thread; let it land.
        PamAnimatedImageClock.shared.step(by: 0.15)
        waitUntil { view.animationFrameIndex != 0 }
        XCTAssertNotEqual(view.animationFrameIndex, 0)
        XCTAssertFalse(view.image?.cgImage === first, "the displayed bitmap changed")

        // Hidden ancestor (covered route / inactive tab): time stands still.
        let host = UIView(frame: window.bounds)
        window.addSubview(host)
        host.addSubview(view)
        host.isHidden = true
        XCTAssertFalse(view.isAnimationVisible)
        let frozen = view.animationFrameIndex
        let frozenElapsed = view.animationElapsed
        PamAnimatedImageClock.shared.step(by: 0.1)
        RunLoop.main.run(until: Date().addingTimeInterval(0.2))
        XCTAssertEqual(view.animationFrameIndex, frozen)
        XCTAssertEqual(view.animationElapsed, frozenElapsed, "no time passes while hidden")

        // Scrolled out of the window: not visible either.
        host.isHidden = false
        view.frame.origin = CGPoint(x: 0, y: 1_000)
        XCTAssertFalse(view.isAnimationVisible)
        view.frame.origin = .zero
        XCTAssertTrue(view.isAnimationVisible)

        // Detached: unregistered, and the shared clock stops with no views.
        view.removeFromSuperview()
        XCTAssertFalse(view.isAnimatingImage)
        PamAnimatedImageClock.shared.step(by: 0)
        XCTAssertFalse(PamAnimatedImageClock.shared.isRunning)
    }

    @MainActor
    func testStaticImageStopsAPreviousAnimation() throws {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        window.isHidden = false
        defer { window.isHidden = true }
        let view = PamImageView(frame: CGRect(x: 0, y: 0, width: 40, height: 40))
        window.addSubview(view)
        view.image = PamAnimatedImage.image(
            data: Self.gif(colors: [.red, .green], delays: [0.1, 0.1]), targetSize: .zero
        )
        XCTAssertTrue(view.isAnimatingImage)
        view.image = UIImage(data: Self.gif(colors: [.red], delays: [0.1]))
        XCTAssertFalse(view.isAnimatingImage)
        view.removeFromSuperview()
    }

    func testInlineDataURIKeepsTheAnimation() throws {
        let image = try XCTUnwrap(PamInlineImages.image("data:image/webp;base64," + Self.webp))
        XCTAssertEqual(image.pamAnimation?.frameCount, 3)
    }

    private func waitUntil(timeout: TimeInterval = 2, _ condition: () -> Bool) {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() && Date() < deadline {
            RunLoop.main.run(until: Date().addingTimeInterval(0.01))
        }
    }

    static func center(_ image: CGImage) -> [UInt8] {
        var pixel = [UInt8](repeating: 0, count: 4)
        let context = CGContext(
            data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!
        context.draw(image, in: CGRect(x: -CGFloat(image.width) / 2, y: -CGFloat(image.height) / 2,
                                       width: CGFloat(image.width), height: CGFloat(image.height)))
        return pixel
    }
}
