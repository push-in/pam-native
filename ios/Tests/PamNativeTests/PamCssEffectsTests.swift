import UIKit
import XCTest
@testable import PamNative

/// CSS visual effects (1.3.0) on iOS (mirrors Android
/// `PamCssEffectsInstrumentedTest`). Uncompiled on Linux — needs Mac validation.
@MainActor
final class PamCssEffectsTests: XCTestCase {
    private let redToBlue = #"[{"t":1,"m":1,"a":90,"s":[[4294901760,0,1],[4278190335,1,1]]}]"#

    func testLinearGradientStopsInterpolateLikeBrowsers() {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.backgroundGradient: .text(redToBlue),
        ], kind: .column)
        defer { renderer.close() }
        let image = PamRenderTestSupport.snapshot(view)
        PamRenderTestSupport.assertColor(PamRenderTestSupport.pixel(image, 1, 50), 0xFFFF_0000, tolerance: 16)
        PamRenderTestSupport.assertColor(PamRenderTestSupport.pixel(image, 198, 50), 0xFF00_00FF, tolerance: 16)
        PamRenderTestSupport.assertColor(PamRenderTestSupport.pixel(image, 100, 50), 0xFF80_007F, tolerance: 20)
    }

    func testTransparentStopsFadeWithoutDarkening() {
        // white → transparent: the midpoint stays white at half alpha (premultiplied).
        let middle = PamGradientLayer.premultipliedLerp(0xFFFF_FFFF, 0x0000_0000, 0.5)
        let parts = PamARGB.components(middle)
        XCTAssertEqual(parts.a, 0.5, accuracy: 0.01)
        XCTAssertEqual(parts.r, 1, accuracy: 0.01)
        let stops = PamGradientLayer.premultiplied([(0xFFFF_FFFF, 0), (0x0000_0000, 1)])
        XCTAssertEqual(stops.last?.0, 0x00FF_FFFF, "transparent end keeps the white channels")
    }

    func testHardStopsPositionsAndRadiusClipping() {
        let hard = #"[{"t":1,"m":1,"a":90,"s":[[4294901760,0,1],[4294901760,0.5,1],[4278190335,0.5,1],[4278190335,1,1]]}]"#
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.backgroundGradient: .text(hard),
            PamConstants.borderRadius: .decimal(40),
        ], kind: .column)
        defer { renderer.close() }
        let image = PamRenderTestSupport.snapshot(view)
        PamRenderTestSupport.assertColor(PamRenderTestSupport.pixel(image, 90, 50), 0xFFFF_0000, tolerance: 8)
        PamRenderTestSupport.assertColor(PamRenderTestSupport.pixel(image, 110, 50), 0xFF00_00FF, tolerance: 8)
        // Outside the rounded corner nothing is painted.
        XCTAssertEqual(PamRenderTestSupport.pixel(image, 1, 1) >> 24, 0)
    }

    func testRadialGradientResolvesFarthestCornerAndRepeatingTiles() {
        let layers = PamGradientLayer.parse(#"[{"t":2,"e":0,"z":4,"r":1,"s":[[4294901760,0,2],[4278190335,10,2]]}]"#)
        XCTAssertEqual(layers.count, 1)
        let geometry = layers[0].radialGeometry(width: 200, height: 100)
        XCTAssertEqual(geometry.rx, hypot(100, 50), accuracy: 0.01)
        let tiled = PamGradientLayer.tiled(
            stops: layers[0].resolvedStops(lineLength: geometry.rx),
            first: 0,
            span: 10 / geometry.rx,
            from: 0,
            to: 1
        )
        XCTAssertGreaterThan(tiled.stops.count, 20)
        XCTAssertEqual(tiled.from, 0, accuracy: 0.0001)
    }

    func testMultipleOuterShadowsAndInsetShadowPaint() {
        let shadows = #"[[0,4,8,0,1073741824,0],[0,0,0,2,4294901760,0],[0,0,6,0,2147483648,1]]"#
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.backgroundColor: .integer(0xFFFF_FFFF),
            PamConstants.borderRadius: .decimal(12),
            PamConstants.boxShadows: .text(shadows),
        ], kind: .column)
        defer { renderer.close() }
        let parsed = PamBoxShadow.parse(shadows)
        XCTAssertEqual(parsed.count, 3)
        XCTAssertTrue(parsed[2].inset)
        let shadowLayers = view.layer.sublayers?.flatMap { $0.sublayers ?? [] }.filter { $0.shadowPath != nil } ?? []
        XCTAssertEqual(shadowLayers.count, 3, "two outer + one inset shadow layer")
        XCTAssertNil(view.layer.shadowPath, "CSS shadows never use the view's own layer shadow")
    }

    func testClippedViewKeepsItsOuterShadowAsASibling() {
        let (host, renderer, view) = PamRenderTestSupport.render([
            PamConstants.overflow: .integer(2),
            PamConstants.borderRadius: .decimal(12),
            PamConstants.boxShadows: .text("[[0,4,8,0,1073741824,0]]"),
        ], kind: .column)
        defer { renderer.close() }
        XCTAssertTrue(view.layer.masksToBounds)
        let parent = try! XCTUnwrap(host.viewWithTag(1))
        let sublayers = parent.layer.sublayers ?? []
        let viewIndex = try! XCTUnwrap(sublayers.firstIndex { $0 === view.layer })
        XCTAssertGreaterThan(viewIndex, 0)
        XCTAssertNotNil(sublayers[viewIndex - 1].sublayers?.first?.shadowPath)
    }

    func testColorMatrixFilterRendersGrayscale() throws {
        let grayscale = "0.2126,0.7152,0.0722,0,0,0.2126,0.7152,0.0722,0,0,0.2126,0.7152,0.0722,0,0,0,0,0,1,0"
        let matrix = try XCTUnwrap(PamImageFilter.colorMatrix(grayscale))
        let red = UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).image { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        }
        let filtered = PamImageFilter(blurSigma: 0, matrix: matrix).apply(to: red, displayScale: 2)
        let pixel = PamRenderTestSupport.pixel(try XCTUnwrap(filtered.cgImage), 1, 1)
        let r = (pixel >> 16) & 0xFF
        let g = (pixel >> 8) & 0xFF
        XCTAssertLessThanOrEqual(abs(Int(r) - Int(g)), 6)
    }

    func testImageBlurRadiusBlursTheBitmapWithOpaqueEdges() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.blurRadius: .decimal(4),
        ], kind: .image)
        defer { renderer.close() }
        let imageView = try XCTUnwrap(view as? PamImageView)
        let checker = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 40)).image { context in
            UIColor.black.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 40, height: 40))
            UIColor.white.setFill()
            context.fill(CGRect(x: 20, y: 0, width: 20, height: 40))
        }
        imageView.image = checker
        let output = try XCTUnwrap(imageView.image?.cgImage)
        XCTAssertEqual(PamRenderTestSupport.pixel(output, 0, 0) >> 24, 0xFF, "edges stay opaque")
        let edge = (PamRenderTestSupport.pixel(output, output.width / 2, output.height / 2) >> 16) & 0xFF
        XCTAssertGreaterThan(edge, 40)
        XCTAssertLessThan(edge, 215)
        XCTAssertTrue(imageView.originalImage === checker)
    }

    func testShimmerSweepsAHighlightOverTheBackground() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.backgroundColor: .integer(0xFFE0_E0E0),
            PamConstants.shimmerEnabled: .flag(true),
            PamConstants.shimmerDurationMs: .integer(900),
        ], kind: .view)
        defer { renderer.close() }
        let shimmer = try XCTUnwrap(view.layer.sublayers?.first { $0 is PamShimmerLayer })
        XCTAssertEqual(shimmer.frame, view.bounds)
        let strip = try XCTUnwrap(shimmer.sublayers?.first as? CAGradientLayer)
        if !PamMotionPolicy.isReduced {
            let sweep = try XCTUnwrap(strip.animation(forKey: "pam.shimmer") as? CABasicAnimation)
            XCTAssertEqual(sweep.duration, 0.9, accuracy: 0.001)
        }
        renderer.commit([[.update(id: 2, key: PamConstants.shimmerEnabled, value: .flag(false))]])
        XCTAssertNil(view.layer.sublayers?.first { $0 is PamShimmerLayer })
    }

    func testBackdropFilterInsertsABlurBehindTheChildren() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.backgroundColor: .integer(0x8000_0000),
            PamConstants.backdropBlurRadius: .decimal(12),
        ], kind: .view)
        defer { renderer.close() }
        let backdrop = try XCTUnwrap(view.subviews.first { $0 is PamBackdropView } as? PamBackdropView)
        XCTAssertEqual(backdrop.tint.map(PamARGB.from), 0x8000_0000)
        renderer.commit([[
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view, properties: [:])),
            .layout(id: 3, frame: Frame(x: 20, y: 20, width: 10, height: 10)),
        ]])
        let child = try XCTUnwrap(view.viewWithTag(3))
        XCTAssertGreaterThan(view.subviews.firstIndex(of: child)!, view.subviews.firstIndex(of: backdrop)!)
    }
}
