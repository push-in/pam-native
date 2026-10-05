import CoreText
import UIKit
import XCTest
@testable import PamNative

/// CSS paint properties compiled by the PHP style compiler reach iOS views
/// (mirrors Android `PamCssPaintInstrumentedTest`). Uncompiled on Linux —
/// needs Mac validation.
@MainActor
final class PamCssPaintTests: XCTestCase {
    func testPerSideBorderColorsPaintEachEdge() {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.backgroundColor: .integer(0xFFFF_FFFF),
            PamConstants.borderTopWidth: .decimal(8),
            PamConstants.borderBottomWidth: .decimal(8),
            PamConstants.borderColor: .integer(0xFF00_FF00),
            PamConstants.borderTopColor: .integer(0xFFFF_0000),
            PamConstants.borderBottomColor: .integer(0xFF00_00FF),
        ], kind: .column)
        defer { renderer.close() }
        let image = PamRenderTestSupport.snapshot(view)
        PamRenderTestSupport.assertColor(PamRenderTestSupport.pixel(image, 100, 2), 0xFFFF_0000)
        PamRenderTestSupport.assertColor(PamRenderTestSupport.pixel(image, 100, 97), 0xFF00_00FF)
        PamRenderTestSupport.assertColor(PamRenderTestSupport.pixel(image, 100, 50), 0xFFFF_FFFF)
    }

    func testTextShadowFontFeaturesAndJustifyApply() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.text: .text("0123456789 justified text that wraps across lines"),
            PamConstants.textShadowOffsetX: .decimal(1),
            PamConstants.textShadowOffsetY: .decimal(2),
            PamConstants.textShadowRadius: .decimal(3),
            PamConstants.textShadowColor: .integer(0x8000_0000),
            PamConstants.fontFeatureSettings: .text("'tnum' 1"),
            PamConstants.textAlign: .integer(4),
        ], kind: .text)
        defer { renderer.close() }
        let text = try XCTUnwrap(view as? PamTextView)
        let content = try XCTUnwrap(text.pamContent)
        XCTAssertEqual(content.options.shadowColor, 0x8000_0000)
        XCTAssertEqual(content.options.shadowOffset, CGSize(width: 1, height: 2))
        XCTAssertEqual(content.options.shadowRadius, 3)
        XCTAssertEqual(content.options.alignment, 4)
        XCTAssertEqual(content.style.fontFeatures, "'tnum' 1")
        let font = PamTextLayout.baseFont(content.style)
        let settings = CTFontCopyAttribute(font as CTFont, kCTFontFeatureSettingsAttribute) as? [[String: Any]]
        XCTAssertEqual(settings?.first?[kCTFontOpenTypeFeatureTag as String] as? String, "tnum")
        XCTAssertEqual(PamFontFeatures.parse("\"tnum\" on, 'liga' 0, \"smcp\"").map { $0.tag }, ["tnum", "liga", "smcp"])
        XCTAssertEqual(PamFontFeatures.parse("'liga' off").first?.value, 0)
    }

    func testTransformOriginAndPercentTranslationUseTheViewBox() {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.backgroundColor: .integer(0xFF10_1010),
            PamConstants.transformOriginX: .decimal(0),
            PamConstants.transformOriginY: .decimal(100),
            PamConstants.translationYPercent: .decimal(-50),
            PamConstants.rotation: .decimal(10),
        ], kind: .column)
        defer { renderer.close() }
        // The bottom-left corner is the rotation pivot: after the -50% translate
        // it sits half a box higher than its layout position.
        let pivot = view.convert(CGPoint(x: 0, y: view.bounds.height), to: view.superview)
        XCTAssertEqual(pivot.x, 20, accuracy: 0.5)
        XCTAssertEqual(pivot.y, 20 + 100 - 50, accuracy: 0.5)
        XCTAssertGreaterThan(atan2(view.transform.b, view.transform.a) * 180 / .pi, 9)
        XCTAssertEqual(view.bounds.size, CGSize(width: 200, height: 100))
    }

    func testTransformSpecComposesScaleRotateAndTranslateAroundOrigin() {
        var spec = PamTransformSpec()
        spec.scaleX = 2
        spec.scaleY = 2
        spec.originXPercent = 0
        spec.originYPercent = 0
        let transform = spec.affine(size: CGSize(width: 100, height: 40))
        // The top-left corner (-50, -20 from the center) is fixed.
        let corner = CGPoint(x: -50, y: -20).applying(transform)
        XCTAssertEqual(corner.x, -50, accuracy: 0.001)
        XCTAssertEqual(corner.y, -20, accuracy: 0.001)
        XCTAssertTrue(PamTransformSpec().isIdentity)
    }
}
