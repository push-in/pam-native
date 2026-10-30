import UIKit

/// One player under the visible-autoplay policy, as measured after a frame
/// (Android `PamAutoplayCandidate`).
struct PamAutoplayCandidate: Equatable {
    /// Share of the player's area on screen (0...1).
    var fraction: CGFloat
    /// `autoPlayVisible`: share needed to start.
    var threshold: CGFloat
    /// Every scrolling list holding the player is at rest.
    var idle: Bool
    /// Players with the same non-nil scope (list + group) play one at a time.
    var scope: String?
    /// On-screen top of the visible part (topmost wins a group).
    var top: CGFloat
    /// The player currently holds the policy's grant.
    var active: Bool
}

/// Visible-autoplay decision (pure; Android `PamAutoplayPolicy`). A player
/// starts only at rest with at least its threshold on screen; a playing one
/// keeps playing, also while the list moves, until it falls below
/// `threshold - hysteresis`. Within a scope one player plays: the one already
/// playing while it still meets its threshold, else the topmost that does,
/// else the one already playing while it stays above its floor.
enum PamAutoplayPolicy {
    static let hysteresis: CGFloat = 0.2
    private static let epsilon: CGFloat = 0.001

    static func floor(_ threshold: CGFloat) -> CGFloat { max(0.01, threshold - hysteresis) }

    private static func meets(_ candidate: PamAutoplayCandidate) -> Bool {
        candidate.fraction + epsilon >= candidate.threshold
    }

    private static func keeps(_ candidate: PamAutoplayCandidate) -> Bool {
        candidate.active && candidate.fraction > 0 && candidate.fraction + epsilon >= floor(candidate.threshold)
    }

    static func choose(_ candidates: [PamAutoplayCandidate]) -> [Bool] {
        var chosen = [Bool](repeating: false, count: candidates.count)
        var scopes: [String: [Int]] = [:]
        var order: [String] = []
        for (index, candidate) in candidates.enumerated() {
            guard let scope = candidate.scope else {
                chosen[index] = keeps(candidate) || (candidate.idle && meets(candidate))
                continue
            }
            if scopes[scope] == nil { order.append(scope) }
            scopes[scope, default: []].append(index)
        }
        for scope in order {
            let members = scopes[scope] ?? []
            let topmost: ([Int]) -> Int? = { list in
                list.min { candidates[$0].top < candidates[$1].top }
            }
            let kept = topmost(members.filter { keeps(candidates[$0]) })
            let idle = members.allSatisfy { candidates[$0].idle }
            let winner: Int?
            if !idle {
                winner = kept
            } else if let kept, meets(candidates[kept]) {
                winner = kept
            } else {
                winner = topmost(members.filter { meets(candidates[$0]) }) ?? kept
            }
            if let winner { chosen[winner] = true }
        }
        return chosen
    }
}

/// Main-thread coordinator of the media views that wait for the screen:
/// players with `autoPlayVisible` (granted or withdrawn by
/// `PamAutoplayPolicy`) and list videos whose player is prepared once they
/// show on screen outside a fling. Scrolls, layouts and coverage changes
/// schedule one evaluation (coalesced); while a list still moves and a view
/// waits, it checks again every 100 ms until the lists rest. No PHP round-trip.
enum PamMediaVisibility {
    private static let autoplay = NSHashTable<PamMediaView>.weakObjects()
    private static let awaiting = NSHashTable<PamMediaView>.weakObjects()
    private static var scheduled = false
    private static var retryScheduled = false

    static var isEmpty: Bool { autoplay.count == 0 && awaiting.count == 0 }

    static func join(_ view: PamMediaView) {
        autoplay.add(view)
        schedule()
    }

    static func leave(_ view: PamMediaView) {
        autoplay.remove(view)
    }

    static func awaitScreen(_ view: PamMediaView) {
        awaiting.add(view)
        schedule()
    }

    static func stopAwaiting(_ view: PamMediaView) {
        awaiting.remove(view)
    }

    /// Members (tests, diagnostics).
    static var autoplayCount: Int { autoplay.allObjects.count }

    static func schedule() {
        guard !scheduled, !isEmpty else { return }
        scheduled = true
        DispatchQueue.main.async {
            scheduled = false
            evaluate()
        }
    }

    /// Runs the evaluation now (tests).
    static func evaluateNow() {
        evaluate()
    }

    private static func evaluate() {
        var waitingOnMotion = false
        for view in awaiting.allObjects {
            if view.screenReadyToPrepare() {
                awaiting.remove(view)
                view.prepareAfterScreen()
            } else if view.window != nil {
                waitingOnMotion = true
            }
        }
        let views = autoplay.allObjects.filter { $0.window != nil && $0.autoplayThreshold > 0 }
        if !views.isEmpty {
            let idles = views.map(listsIdle)
            if views.contains(where: \.autoplayGranted) || idles.contains(true) {
                let candidates = views.enumerated().map { index, view -> PamAutoplayCandidate in
                    let idle = idles[index]
                    let visible = idle || view.autoplayGranted ? view.visibleRect() : nil
                    let fraction = visible.map { rect -> CGFloat in
                        let area = view.bounds.width * view.bounds.height
                        return area > 0 ? (rect.width * rect.height) / area : 0
                    } ?? 0
                    return PamAutoplayCandidate(
                        fraction: fraction,
                        threshold: view.autoplayThreshold,
                        idle: idle,
                        scope: view.autoplayGroupName.isEmpty ? nil : "\(view.autoplayGroupName)#\(nearestList(view))",
                        top: fraction > 0 ? (visible?.minY ?? .greatestFiniteMagnitude) : .greatestFiniteMagnitude,
                        active: view.autoplayGranted
                    )
                }
                let chosen = PamAutoplayPolicy.choose(candidates)
                // Withdraw first, so two players of one group never play together.
                for (index, view) in views.enumerated() where !chosen[index] { view.grantAutoplay(false) }
                for (index, view) in views.enumerated() where chosen[index] { view.grantAutoplay(true) }
            }
            if idles.contains(false) { waitingOnMotion = true }
        }
        // A list still moving: decide again once it rests (UIScrollView has
        // no "rested" callback without a delegate the app may own).
        if waitingOnMotion, !retryScheduled {
            retryScheduled = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                retryScheduled = false
                schedule()
            }
        }
    }

    /// Every virtual list holding the view is not dragged nor flinging.
    static func listsIdle(_ view: UIView) -> Bool {
        var parent = view.superview
        while let current = parent {
            if let list = current as? PamVirtualListView,
               list.isTracking || list.isDragging || list.isDecelerating {
                return false
            }
            parent = current.superview
        }
        return true
    }

    private static func nearestList(_ view: UIView) -> Int {
        var parent = view.superview
        while let current = parent {
            if current is PamVirtualListView { return ObjectIdentifier(current).hashValue }
            parent = current.superview
        }
        return 0
    }
}

extension UIView {
    /// The part of the view inside its window, clipped by every clipping
    /// ancestor, in window coordinates; nil when nothing shows.
    func pamVisibleRectInWindow() -> CGRect? {
        guard let window, bounds.width > 0, bounds.height > 0 else { return nil }
        var rect = convert(bounds, to: window)
        var current: UIView? = self
        while let view = current {
            if view.isHidden || view.alpha < 0.01 { return nil }
            if view !== self, view.clipsToBounds || view is UIScrollView {
                rect = rect.intersection(view.convert(view.bounds, to: window))
                if rect.isNull || rect.isEmpty { return nil }
            }
            current = view.superview
        }
        rect = rect.intersection(window.bounds)
        return rect.isNull || rect.isEmpty ? nil : rect
    }
}
