import Foundation

/// Reduces the root and presented-dialog insets to binary visibility changes.
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

    func publishCurrent() { onChange?(visible) }

    private func publish(_ value: Bool) {
        guard value != visible else { return }
        visible = value
        onChange?(value)
    }
}
