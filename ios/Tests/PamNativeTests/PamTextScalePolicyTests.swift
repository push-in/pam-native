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

    /// Larger Text below the accessibility sizes reaches the app (React
    /// Native `RCTFontSizeMultiplier`); `UIFontMetrics.scaledValue(for: 1)`
    /// rounded XL/XXL/XXXL to 1.
    func testDynamicTypeCategoriesUseReactNativeMultipliers() {
        XCTAssertEqual(PamTextScale.systemMultiplier(.large), 1)
        XCTAssertEqual(PamTextScale.systemMultiplier(.extraLarge), 1.118, accuracy: 0.0001)
        XCTAssertEqual(PamTextScale.systemMultiplier(.extraExtraExtraLarge), 1.353, accuracy: 0.0001)
        XCTAssertEqual(PamTextScale.systemMultiplier(.small), 0.882, accuracy: 0.0001)
        XCTAssertEqual(PamTextScale.systemMultiplier(.accessibilityMedium), 1.786, accuracy: 0.0001)
        let traits = UITraitCollection(preferredContentSizeCategory: .extraLarge)
        XCTAssertEqual(PamTextScale.systemMultiplier(traits: traits), 1.118, accuracy: 0.0001)
        XCTAssertGreaterThan(PamRuntime.deviceTextScale(traits: traits), 1.0)
    }
}
