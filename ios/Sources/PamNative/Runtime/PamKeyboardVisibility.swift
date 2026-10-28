import Foundation

/// A settled keyboard change published to PHP (`Keyboard::metrics()`).
struct PamKeyboardFrame: Equatable {
    var visible: Bool
    /// Visible keyboard height in points from the window bottom (0 when hidden).
    var height: Float
    /// The keyboard animation length, 0 when unknown.
    var durationMs: Int

    /// `EventKind.keyboardVisibility` payload: `<visible>;<height>;<durationMs>`.
    var payload: Data {
        let rounded = visible ? (max(0, height) * 10).rounded() / 10 : 0
        return Data("\(visible ? 1 : 0);\(String(format: "%.1f", rounded));\(max(0, durationMs))".utf8)
    }
}

/// Main-thread-only reducer of root/dialog insets to settled keyboard changes:
/// visibility transitions and settled height changes, never animation frames.
final class PamKeyboardVisibility {
    private var surfaces: Set<Int64> = []
    private var visible = false
    private var height: Float = 0
    private var durationMs = 0
    private var publishedHeight: Float = 0
    private var active = true
    var onChange: ((PamKeyboardFrame) -> Void)?

    func update(surface: Int64, bottom: Float) {
        guard active else { return }
        if bottom > 0 { surfaces.insert(surface) } else { surfaces.remove(surface) }
        publish(!surfaces.isEmpty)
    }

    /// The settled keyboard frame of a `keyboardWillChangeFrame`/`WillHide`
    /// notification (one per change on iOS, so no settling delay is needed).
    func update(surface: Int64, visible keyboardVisible: Bool, height newHeight: Float, durationMs newDuration: Int) {
        guard active else { return }
        height = keyboardVisible && newHeight.isFinite ? max(0, newHeight) : 0
        durationMs = max(0, newDuration)
        if keyboardVisible { surfaces.insert(surface) } else { surfaces.remove(surface) }
        let next = !surfaces.isEmpty
        if next != visible {
            publish(next)
        } else if visible, abs(currentHeight - publishedHeight) >= 0.5 {
            emit()
        }
    }

    func setActive(_ value: Bool) {
        active = value
        if !value {
            surfaces.removeAll()
            height = 0
            publish(false)
        }
    }

    static func guideIsVisible(_ frame: CGRect, in bounds: CGRect, safeBottom: CGFloat) -> Bool {
        let overlap = frame.intersection(bounds)
        return !overlap.isNull && overlap.width > 0 && overlap.height > 0 &&
            overlap.minY < bounds.maxY - max(0, safeBottom) - 0.5
    }

    func publishCurrent() { emit() }

    private var currentHeight: Float { visible ? height : 0 }

    private func publish(_ value: Bool) {
        guard value != visible else { return }
        visible = value
        emit()
    }

    private func emit() {
        publishedHeight = currentHeight
        onChange?(PamKeyboardFrame(visible: visible, height: publishedHeight, durationMs: durationMs))
    }
}
