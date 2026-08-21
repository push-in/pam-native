import UIKit

enum PamTextScalePolicy {
    static func font(
        baseFont: UIFont,
        allowsScaling: Bool,
        maximumMultiplier: Double,
        compatibleWith traits: UITraitCollection
    ) -> UIFont {
        guard allowsScaling else { return baseFont }
        let metrics = UIFontMetrics(forTextStyle: .body)
        guard maximumMultiplier > 0 else {
            return metrics.scaledFont(for: baseFont, compatibleWith: traits)
        }
        return metrics.scaledFont(
            for: baseFont,
            maximumPointSize: baseFont.pointSize * CGFloat(max(1, maximumMultiplier)),
            compatibleWith: traits
        )
    }
}
