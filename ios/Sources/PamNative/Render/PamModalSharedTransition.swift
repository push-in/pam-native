import UIKit

/// Shared elements between a `<Modal>` and what is on screen under it (the
/// screen, or a Modal below), Android `PamModalSharedTransition`. The same
/// `shared` tag contract as routes: when the Modal opens, every tag on screen
/// below that the Modal also shows flies into it; when it closes (hidden or
/// unmounted), the Modal's tags fly home to the same tag below (the instance
/// they came from while it is on screen). While anything flies, the Modal
/// fades in step with the flights instead of its own animation.
///
/// The flights draw in the window overlay of [PamSharedElementFlights],
/// above the Modal, in the coordinates of the window root that holds both.
final class PamModalSharedTransition {
    private var flights: PamSharedElementFlights?
    private weak var flightsHost: UIView?
    private var animator: UIViewPropertyAnimator?
    private var shade: UIView?
    private var pendingEnd: ((Bool) -> Void)?

    /// Transitions of unmounted Modals stay alive until their flights land.
    private static var detached: [ObjectIdentifier: PamModalSharedTransition] = [:]

    var running: Bool { animator != nil }

    var activeCount: Int { running ? flights?.count ?? 0 : 0 }

    private func flights(for host: UIView) -> PamSharedElementFlights {
        if let flights, flightsHost === host { return flights }
        flights?.clear()
        let created = PamSharedElementFlights(host: host)
        flights = created
        flightsHost = host
        return created
    }

    /// First frame of [modal] (laid out at rest): pairs the tags on screen in
    /// [below] (which holds the Modal too) with the Modal's. True when
    /// something flies; [fade] then gets the Modal's opacity per frame
    /// (inside the animator, so Core Animation interpolates it).
    func enter(
        modal: UIView,
        below: UIView,
        defaultDuration: TimeInterval,
        fade: @escaping (CGFloat) -> Void,
        onEnd: @escaping () -> Void
    ) -> Bool {
        cancel()
        guard Self.hasTag(modal) else { return false }
        let flights = flights(for: below)
        if let touch = PamSharedElementFlights.lastWindowTouch {
            flights.touched(below.convert(touch, from: nil))
        }
        flights.excluded = modal
        flights.capture(outgoing: below, defaultDuration: defaultDuration, popping: false)
        flights.excluded = nil
        flights.attach(incoming: modal)
        guard flights.count > 0 else {
            flights.clear()
            return false
        }
        let floor = flights.fadeFloor
        fade(modalSharedEnterAlpha(floor, 0))
        run(flights, fade: { fade(modalSharedEnterAlpha(floor, 1)) }) { _ in onEnd() }
        return true
    }

    /// The Modal closes: its tags fly home into [below] while it fades from
    /// [from]. [shadeColor] covers the window while the Modal's own views
    /// are already gone (an unmounted Modal), fading with it. [onEnd] runs
    /// once, false when cut short. False when nothing flies (the caller runs
    /// its own exit).
    func exit(
        modal: UIView,
        below: UIView,
        defaultDuration: TimeInterval,
        shadeColor: UIColor?,
        fade: @escaping () -> Void,
        onEnd: @escaping (Bool) -> Void
    ) -> Bool {
        cancel()
        guard Self.hasTag(modal) else { return false }
        let flights = flights(for: below)
        if let touch = PamSharedElementFlights.lastWindowTouch {
            flights.touched(below.convert(touch, from: nil))
        }
        flights.excluded = nil
        flights.capture(outgoing: modal, defaultDuration: defaultDuration, popping: true)
        flights.excluded = modal
        flights.attach(incoming: below)
        flights.excluded = nil
        guard flights.count > 0 else {
            flights.clear()
            return false
        }
        if let shadeColor {
            let cover = UIView(frame: below.convert(below.bounds, to: nil))
            cover.backgroundColor = shadeColor
            cover.isUserInteractionEnabled = false
            flights.addUnderlay(cover)
            cover.frame = cover.superview.map { below.convert(below.bounds, to: $0) } ?? cover.frame
            shade = cover
        }
        pendingEnd = onEnd
        run(flights, fade: { [weak self] in
            fade()
            self?.shade?.alpha = 0
        }) { [weak self] completed in
            guard let self else { return }
            self.removeShade()
            let end = self.pendingEnd
            self.pendingEnd = nil
            end?(completed)
        }
        return true
    }

    /// Keeps this transition (and its flights) alive after its Modal left.
    func detach() {
        guard running else { return }
        Self.detached[ObjectIdentifier(self)] = self
    }

    private func run(
        _ flights: PamSharedElementFlights,
        fade: @escaping () -> Void,
        onEnd: @escaping (Bool) -> Void
    ) {
        let started = flights.animator(duration: flights.duration, alongside: fade)
        animator = started
        started.addCompletion { [weak self] position in
            guard let self else { return }
            Self.detached[ObjectIdentifier(self)] = nil
            guard self.animator === started else { return }
            self.animator = nil
            flights.finish()
            onEnd(position == .end)
        }
        started.startAnimation()
    }

    /// Lands every flight at once; a pending exit ends (not completed).
    func cancel() {
        guard let running = animator else { return }
        animator = nil
        running.stopAnimation(true)
        flights?.finish()
        removeShade()
        Self.detached[ObjectIdentifier(self)] = nil
        if let end = pendingEnd {
            pendingEnd = nil
            end(false)
        }
    }

    func release() {
        cancel()
        flights?.clear()
        flights = nil
    }

    private func removeShade() {
        shade?.removeFromSuperview()
        shade = nil
    }

    /// Whether any view under [root] carries a shared tag (stops at the first).
    static func hasTag(_ root: UIView) -> Bool {
        if let tag = root.layer.value(forKey: "pamSharedTransitionTag") as? String, !tag.isEmpty {
            return true
        }
        return root.subviews.contains(where: hasTag)
    }

    static let minimumCoverage: CGFloat = 0.9

    /// The color that covers [root]: the topmost view (down to three levels)
    /// filling at least 90% of it with a solid background, with that view's
    /// opacity folded in. Nil when nothing does.
    static func coverColor(_ root: UIView, skip: UIView? = nil) -> UIColor? {
        let area = root.bounds.width * root.bounds.height
        guard area > 0 else { return nil }
        var layers: [PamModalCoverLayer] = []
        func visit(_ group: UIView, depth: Int, alpha: CGFloat) {
            for child in group.subviews.reversed() {
                if child === skip || child.isHidden { continue }
                let opacity = alpha * child.alpha
                let frame = child.convert(child.bounds, to: root).intersection(root.bounds)
                let coverage = frame.isNull ? 0 : min(1, max(0, frame.width * frame.height / area))
                if coverage < minimumCoverage { continue }
                // A view's children draw over its own background.
                if depth < 3 { visit(child, depth: depth + 1, alpha: opacity) }
                if let color = child.backgroundColor {
                    layers.append(PamModalCoverLayer(coverage: coverage, color: color, opacity: opacity))
                }
            }
        }
        visit(root, depth: 1, alpha: root.alpha)
        return modalCoverColor(layers)
    }
}

/// A full-window candidate for the cover of an unmounted Modal.
struct PamModalCoverLayer {
    let coverage: CGFloat
    let color: UIColor
    let opacity: CGFloat
}

/// The topmost [layers] entry (top first) that covers the window and is not
/// transparent, its opacity folded into alpha.
func modalCoverColor(_ layers: [PamModalCoverLayer]) -> UIColor? {
    for layer in layers {
        guard layer.coverage >= PamModalSharedTransition.minimumCoverage else { continue }
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        guard layer.color.getRed(&red, green: &green, blue: &blue, alpha: &alpha) else { continue }
        let folded = (alpha * 255 * min(1, max(0, layer.opacity))).rounded(.down)
        guard folded > 0 else { continue }
        return UIColor(red: red, green: green, blue: blue, alpha: min(255, folded) / 255)
    }
    return nil
}

/// Opacity of an opening Modal at [eased] flight progress, from [floor].
func modalSharedEnterAlpha(_ floor: CGFloat, _ eased: CGFloat) -> CGFloat {
    let start = min(1, max(0, floor))
    return min(1, max(0, start + (1 - start) * min(1, max(0, eased))))
}

/// Opacity of a closing Modal at [eased] flight progress, from [from].
func modalSharedExitAlpha(_ from: CGFloat, _ eased: CGFloat) -> CGFloat {
    min(1, max(0, min(1, max(0, from)) * (1 - min(1, max(0, eased)))))
}
