import UIKit

struct PamPanHold {
    private var beganAt: TimeInterval?

    mutating func begin(at time: TimeInterval) {
        if beganAt == nil { beganAt = time }
    }

    func permits(at time: TimeInterval, duration: TimeInterval) -> Bool {
        duration <= 0 || (beganAt.map { time - $0 >= duration } ?? false)
    }

    mutating func reset() { beganAt = nil }
}

/// Early motion remains available to ancestor scrolling; zero preserves normal UIKit pan.
final class PamHeldPanGestureRecognizer: UIPanGestureRecognizer {
    var minimumHoldDuration: TimeInterval = 0
    private var hold = PamPanHold()

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        if let time = touches.first?.timestamp { hold.begin(at: time) }
        super.touchesBegan(touches, with: event)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        if let time = touches.first?.timestamp,
           !hold.permits(at: time, duration: minimumHoldDuration) { return }
        super.touchesMoved(touches, with: event)
    }

    override func reset() {
        hold.reset()
        super.reset()
    }
}
