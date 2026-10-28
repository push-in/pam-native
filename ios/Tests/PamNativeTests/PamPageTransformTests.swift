import AVFoundation
import QuartzCore
import UIKit
import XCTest
@testable import PamNative

/// Mirrors android/app/src/test/.../PamPageTransformTest.kt and the
/// start-buffer cases of PamForwardBufferTest.kt (uncompiled — needs Mac validation).
final class PamPageTransformTests: XCTestCase {
    func testFrontFaceIsFlat() {
        let face = PamCubeFace.at(0)
        XCTAssertEqual(face.angleDegrees, 0)
        XCTAssertEqual(face.shade, 0)
        XCTAssertTrue(CATransform3DIsIdentity(face.transform(width: 390)))
    }

    func testLeftFaceTurnsAroundItsRightEdge() {
        let face = PamCubeFace.at(-0.5)
        XCTAssertEqual(face.angleDegrees, -45, accuracy: 1e-4)
        XCTAssertEqual(face.pivotFraction, 1)
        XCTAssertEqual(face.shade, 0.275, accuracy: 1e-4)
        // The shared (right) edge stays where it was: x = +width/2 from the centre.
        let edge = project(CGPoint(x: 195, y: 0), face.transform(width: 390))
        XCTAssertEqual(edge.x, 195, accuracy: 1e-3)
        // The far (left) edge moves back, so it projects closer to the centre.
        XCTAssertGreaterThan(project(CGPoint(x: -195, y: 0), face.transform(width: 390)).x, -195)
    }

    func testRightFaceTurnsAroundItsLeftEdge() {
        let face = PamCubeFace.at(0.25)
        XCTAssertEqual(face.angleDegrees, 22.5, accuracy: 1e-4)
        XCTAssertEqual(face.pivotFraction, 0)
        XCTAssertEqual(project(CGPoint(x: -195, y: 0), face.transform(width: 390)).x, -195, accuracy: 1e-3)
    }

    func testPositionsBeyondOneFaceAreClamped() {
        XCTAssertEqual(PamCubeFace.at(3).angleDegrees, 90)
        XCTAssertEqual(PamCubeFace.at(-7).shade, 0.55, accuracy: 1e-4)
    }

    func testTransformerOnlyTouchesVisiblePagesAndResets() {
        let scroll = UIScrollView(frame: CGRect(x: 0, y: 0, width: 100, height: 200))
        let row = UIView(frame: CGRect(x: 0, y: 0, width: 300, height: 200))
        row.tag = 1
        scroll.addSubview(row)
        let pages = (0..<3).map { index -> UIView in
            let page = UIView(frame: CGRect(x: CGFloat(index) * 100, y: 0, width: 100, height: 200))
            page.tag = 10 + index
            row.addSubview(page)
            return page
        }
        scroll.contentSize = row.bounds.size
        let transformer = PamPageTransformer()
        transformer.setKind(PamPageTransformKind.cube.rawValue, on: scroll)
        scroll.contentOffset = CGPoint(x: 50, y: 0)
        transformer.apply(to: scroll)
        XCTAssertFalse(CATransform3DIsIdentity(pages[0].layer.transform))
        XCTAssertFalse(CATransform3DIsIdentity(pages[1].layer.transform))
        XCTAssertTrue(CATransform3DIsIdentity(pages[2].layer.transform))
        transformer.setKind(PamPageTransformKind.none.rawValue, on: scroll)
        XCTAssertTrue(pages.allSatisfy { CATransform3DIsIdentity($0.layer.transform) })
        XCTAssertTrue(pages.allSatisfy { ($0.layer.sublayers ?? []).isEmpty })
    }

    func testOffsetGlideUsesTheReactNativeTimingCurve() {
        XCTAssertEqual(pamEaseInOutQuad(0), 0)
        XCTAssertEqual(pamEaseInOutQuad(0.25), 0.125, accuracy: 1e-9)
        XCTAssertEqual(pamEaseInOutQuad(0.5), 0.5, accuracy: 1e-9)
        XCTAssertEqual(pamEaseInOutQuad(1.5), 1)
    }

    func testRequestedOffsetGlidesAfterTheFirstApplication() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 100, height: 200))
        let scroll = PamAnchoredScrollView(frame: window.bounds)
        scroll.horizontal = true
        scroll.contentSize = CGSize(width: 300, height: 200)
        window.addSubview(scroll)
        scroll.pamOffsetAnimationMs = 280
        scroll.pamRequestedContentOffsetX = 100
        scroll.layoutIfNeeded()
        XCTAssertEqual(scroll.contentOffset.x, 100, "the first offset jumps (mount)")
        scroll.pamRequestedContentOffsetX = 200
        scroll.layoutIfNeeded()
        XCTAssertLessThan(scroll.contentOffset.x, 200, "a later offset glides")
        let expectation = expectation(description: "glide ends")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { expectation.fulfill() }
        wait(for: [expectation], timeout: 2)
        XCTAssertEqual(scroll.contentOffset.x, 200, accuracy: 0.5)
    }

    func testBufferedAheadReadsTheRangeAroundThePlayhead() {
        let ranges = [
            CMTimeRange(start: CMTime(seconds: 0, preferredTimescale: 600), duration: CMTime(seconds: 0.3, preferredTimescale: 600)),
            CMTimeRange(start: CMTime(seconds: 2, preferredTimescale: 600), duration: CMTime(seconds: 1, preferredTimescale: 600)),
        ]
        XCTAssertEqual(pamBufferedAhead(ranges, at: 0), 0.3, accuracy: 1e-3)
        XCTAssertEqual(pamBufferedAhead(ranges, at: 2.5), 0.5, accuracy: 1e-3)
        XCTAssertEqual(pamBufferedAhead(ranges, at: 1), 0)
        XCTAssertEqual(pamBufferedAhead([], at: .nan), 0)
    }

    private func project(_ point: CGPoint, _ t: CATransform3D) -> CGPoint {
        let x = point.x * t.m11 + point.y * t.m21 + t.m41
        let y = point.x * t.m12 + point.y * t.m22 + t.m42
        let w = point.x * t.m14 + point.y * t.m24 + t.m44
        return CGPoint(x: x / w, y: y / w)
    }
}
