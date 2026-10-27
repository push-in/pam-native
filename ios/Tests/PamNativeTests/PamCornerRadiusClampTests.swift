import UIKit
import XCTest
@testable import PamNative

/// borderRadius 999 on a clipping 44pt view (a circular icon button) is
/// clamped to half its side, like CSS and Android, so its children stay visible.
@MainActor
final class PamCornerRadiusClampTests: XCTestCase {
    func testOversizedRadiusOfAClippingViewIsHalfItsShorterSide() {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.borderRadius: .decimal(999),
            PamConstants.overflow: .integer(2),
            PamConstants.backgroundColor: .integer(0xFF00_0000),
        ], kind: .view, frame: Frame(x: 0, y: 0, width: 44, height: 40))
        defer { renderer.close() }
        XCTAssertEqual(view.layer.cornerRadius, 20, accuracy: 0.001)
        XCTAssertTrue(view.layer.masksToBounds)
    }

    func testRadiusWithinHalfTheSideIsKept() {
        XCTAssertEqual(PamViewEffects.clampedRadius(12, size: CGSize(width: 44, height: 44)), 12)
        XCTAssertEqual(PamViewEffects.clampedRadius(999, size: CGSize(width: 44, height: 44)), 22)
        XCTAssertEqual(PamViewEffects.clampedRadius(999, size: .zero), 999)
    }
}
