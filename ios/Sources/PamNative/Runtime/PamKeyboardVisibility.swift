import Foundation

/// Main-thread-only reducer of root/dialog insets to binary visibility changes.
final class PamKeyboardVisibility {
    private var surfaces: Set<Int64> = []
    private var visible = false
    private var active = true
    var onChange: ((Bool) -> Void)?

    func update(surface: Int64, bottom: Float) {
        guard active else { return }
        if bottom > 0 { surfaces.insert(surface) } else { surfaces.remove(surface) }
        publish(!surfaces.isEmpty)
    }

    func setActive(_ value: Bool) {
        active = value
        if !value {
            surfaces.removeAll()
            publish(false)
        }
    }

    static func guideIsVisible(_ frame: CGRect, in bounds: CGRect, safeBottom: CGFloat) -> Bool {
        let overlap = frame.intersection(bounds)
        return !overlap.isNull && overlap.width > 0 && overlap.height > 0 &&
            overlap.minY < bounds.maxY - max(0, safeBottom) - 0.5
    }

    func publishCurrent() { onChange?(visible) }

    private func publish(_ value: Bool) {
        guard value != visible else { return }
        visible = value
        onChange?(value)
    }
}
