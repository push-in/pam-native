import XCTest
import UIKit
@testable import PamNative

final class PamTextScalePolicyTests: XCTestCase {
    func testLargeAccessibilityScaleHonorsOptOutAndMaximumMultiplier() {
        let base = UIFont.systemFont(ofSize: 16)
        let large = UITraitCollection(
            preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge
        )

        let unbounded = PamTextScalePolicy.font(
            baseFont: base,
            allowsScaling: true,
            maximumMultiplier: 0,
            compatibleWith: large
        )
        let capped = PamTextScalePolicy.font(
            baseFont: base,
            allowsScaling: true,
            maximumMultiplier: 1.5,
            compatibleWith: large
        )
        let invalidLowCap = PamTextScalePolicy.font(
            baseFont: base,
            allowsScaling: true,
            maximumMultiplier: 0.5,
            compatibleWith: large
        )
        let optedOut = PamTextScalePolicy.font(
            baseFont: base,
            allowsScaling: false,
            maximumMultiplier: 3,
            compatibleWith: large
        )

        XCTAssertGreaterThan(unbounded.pointSize, base.pointSize)
        XCTAssertGreaterThanOrEqual(capped.pointSize, base.pointSize)
        XCTAssertLessThanOrEqual(capped.pointSize, 24)
        XCTAssertEqual(invalidLowCap.pointSize, base.pointSize, accuracy: 0.000_1)
        XCTAssertEqual(optedOut.pointSize, base.pointSize, accuracy: 0.000_1)
    }
}
