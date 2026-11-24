import Foundation
import UIKit

enum PamMotionPolicy {
    static var reduceMotionOverride: Bool?

    static var isReduced: Bool {
        reduceMotionOverride ?? UIAccessibility.isReduceMotionEnabled
    }

    /// Only used by an active animation. The display link is invalidated by
    /// its owner when it finishes; this does not request a permanent refresh.
    static func animationFrameRate(maximum: Int, lowPower: Bool) -> Int {
        max(1, min(maximum, lowPower ? 60 : 120))
    }

    static func configureAnimationLink(_ link: CADisplayLink, screen: UIScreen?) {
        #if os(tvOS)
        let lowPower = false
        #else
        let lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
        #endif
        let rate = animationFrameRate(
            maximum: (screen ?? UIScreen.main).maximumFramesPerSecond,
            lowPower: lowPower
        )
        if #available(iOS 15.0, *) {
            link.preferredFrameRateRange = CAFrameRateRange(
                minimum: Float(min(60, rate)), maximum: Float(rate), preferred: Float(rate)
            )
        } else {
            link.preferredFramesPerSecond = rate
        }
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
