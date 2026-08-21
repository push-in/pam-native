import Foundation
import UIKit

enum PamMotionPolicy {
    static var reduceMotionOverride: Bool?

    static var isReduced: Bool {
        reduceMotionOverride ?? UIAccessibility.isReduceMotionEnabled
    }

    static func applyTerminalKeyframe(_ frame: [String: Any], to view: UIView) {
        view.layer.removeAllAnimations()
        view.alpha = CGFloat((frame["opacity"] as? NSNumber)?.doubleValue ?? 1)
        let x = CGFloat((frame["translationX"] as? NSNumber)?.doubleValue ?? 0)
        let y = CGFloat((frame["translationY"] as? NSNumber)?.doubleValue ?? 0)
        let scaleX = CGFloat((frame["scaleX"] as? NSNumber)?.doubleValue ?? 1)
        let scaleY = CGFloat((frame["scaleY"] as? NSNumber)?.doubleValue ?? 1)
        let rotation = CGFloat((frame["rotation"] as? NSNumber)?.doubleValue ?? 0)
        view.transform = CGAffineTransform(translationX: x, y: y)
            .scaledBy(x: scaleX, y: scaleY)
            .rotated(by: rotation * .pi / 180)
    }
}
