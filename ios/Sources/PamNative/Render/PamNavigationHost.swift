import UIKit

final class PamNavigationHost: UIView, UIGestureRecognizerDelegate {
    var operation = 1
    var transition = 1
    var duration: TimeInterval = 0.24
    private var revision: Int64 = 0
    private var gestureEnabled = true
    private var gestureEdgeWidth: CGFloat = 24
    private var gestureThreshold: CGFloat = 0.35
    private var onGesturePop: (() -> Void)?
    private lazy var edgeGesture = UIPanGestureRecognizer(
        target: self,
        action: #selector(handleEdgePan(_:))
    )

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true
        edgeGesture.delegate = self
        edgeGesture.maximumNumberOfTouches = 1
        addGestureRecognizer(edgeGesture)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func insert(_ view: UIView, index: Int) {
        if view.superview !== self {
            insertSubview(view, at: min(max(index, 0), subviews.count))
        }
        view.frame = bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        showOnlyTop()
    }

    func navigate(_ value: Int64) {
        guard revision != value else { return }
        revision = value
        runTransition()
    }

    func setGestureNavigation(
        enabled: Bool,
        edgeWidth: CGFloat,
        threshold: CGFloat,
        onPop: (() -> Void)?
    ) {
        gestureEnabled = enabled
        gestureEdgeWidth = min(max(edgeWidth, 8), 160)
        gestureThreshold = min(max(threshold, 0.1), 0.9)
        onGesturePop = onPop
        edgeGesture.isEnabled = enabled
    }

    override func gestureRecognizerShouldBegin(
        _ gestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        guard gestureEnabled, subviews.count >= 2,
              let pan = gestureRecognizer as? UIPanGestureRecognizer else { return false }
        let point = pan.location(in: self)
        let rtl = effectiveUserInterfaceLayoutDirection == .rightToLeft
        let atLeadingEdge = rtl
            ? point.x >= bounds.width - gestureEdgeWidth
            : point.x <= gestureEdgeWidth
        let velocity = pan.velocity(in: self)
        let towardBack = rtl ? velocity.x < 0 : velocity.x > 0
        return atLeadingEdge && towardBack && abs(velocity.x) > abs(velocity.y)
    }

    private func runTransition() {
        guard let incoming = incomingView() else { return }
        let outgoing = outgoingView()
        subviews.forEach { $0.isHidden = $0 !== incoming && $0 !== outgoing }
        let sign: CGFloat = effectiveUserInterfaceLayoutDirection == .rightToLeft ? -1 : 1
        let popping = operation == 3
        incoming.isHidden = false
        outgoing?.isHidden = false
        if UIAccessibility.isReduceMotionEnabled || duration == 0 || transition == 8 {
            finish(incoming: incoming, outgoing: outgoing)
            return
        }
        if transition == 5 {
            incoming.alpha = 0
        } else if popping {
            incoming.transform = CGAffineTransform(translationX: -sign * bounds.width * 0.28, y: 0)
        } else {
            incoming.transform = CGAffineTransform(translationX: sign * bounds.width, y: 0)
        }
        UIView.animate(
            withDuration: min(max(duration, 0), 2),
            delay: 0,
            options: [.curveEaseOut, .beginFromCurrentState]
        ) {
            incoming.alpha = 1
            incoming.transform = .identity
            if self.transition == 5 {
                outgoing?.alpha = 0
            } else {
                outgoing?.transform = CGAffineTransform(
                    translationX: popping ? sign * self.bounds.width : -sign * self.bounds.width * 0.28,
                    y: 0
                )
            }
        } completion: { _ in
            self.finish(incoming: incoming, outgoing: outgoing)
        }
    }

    @objc private func handleEdgePan(_ gesture: UIPanGestureRecognizer) {
        guard gestureEnabled, subviews.count >= 2 else { return }
        let incoming = subviews[subviews.count - 2]
        let outgoing = subviews[subviews.count - 1]
        let rtl = effectiveUserInterfaceLayoutDirection == .rightToLeft
        let raw = gesture.translation(in: self).x
        let distance = max(0, rtl ? -raw : raw)
        let progress = min(distance / max(bounds.width, 1), 1)
        let sign: CGFloat = rtl ? -1 : 1
        switch gesture.state {
        case .began, .changed:
            incoming.isHidden = false
            outgoing.isHidden = false
            outgoing.transform = CGAffineTransform(translationX: sign * bounds.width * progress, y: 0)
            incoming.transform = CGAffineTransform(
                translationX: -sign * bounds.width * 0.28 * (1 - progress),
                y: 0
            )
            incoming.alpha = 0.82 + 0.18 * progress
        case .ended, .cancelled:
            let velocity = gesture.velocity(in: self).x * (rtl ? -1 : 1)
            let complete = gesture.state == .ended &&
                (progress >= gestureThreshold || velocity >= 700)
            UIView.animate(
                withDuration: 0.18,
                delay: 0,
                options: [.curveEaseOut, .beginFromCurrentState]
            ) {
                outgoing.transform = complete
                    ? CGAffineTransform(translationX: sign * self.bounds.width, y: 0)
                    : .identity
                incoming.transform = complete
                    ? .identity
                    : CGAffineTransform(translationX: -sign * self.bounds.width * 0.28, y: 0)
                incoming.alpha = complete ? 1 : 0.82
            } completion: { _ in
                incoming.transform = .identity
                outgoing.transform = .identity
                incoming.alpha = 1
                if complete {
                    outgoing.isHidden = true
                    self.onGesturePop?()
                } else {
                    incoming.isHidden = true
                }
            }
        default:
            break
        }
    }

    private func incomingView() -> UIView? {
        switch operation {
        case 2, 4: return subviews.last
        case 3: return subviews.count > 1 ? subviews[subviews.count - 2] : subviews.last
        default: return subviews.last
        }
    }

    private func outgoingView() -> UIView? {
        guard subviews.count > 1 else { return nil }
        return operation == 3 ? subviews.last : subviews[subviews.count - 2]
    }

    private func finish(incoming: UIView, outgoing: UIView?) {
        incoming.alpha = 1
        incoming.transform = .identity
        incoming.isHidden = false
        outgoing?.alpha = 1
        outgoing?.transform = .identity
        outgoing?.isHidden = true
    }

    private func showOnlyTop() {
        for (index, view) in subviews.enumerated() {
            view.isHidden = index != subviews.count - 1
        }
    }
}
