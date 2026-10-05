import UIKit

/// CSS transform composition: translation (points + % of the own box),
/// rotation and scale around `transform-origin` (% of the own box).
struct PamTransformSpec: Equatable {
    var translateX: CGFloat = 0
    var translateY: CGFloat = 0
    var translateXPercent: CGFloat = 0
    var translateYPercent: CGFloat = 0
    var scaleX: CGFloat = 1
    var scaleY: CGFloat = 1
    var rotationDegrees: CGFloat = 0
    var originXPercent: CGFloat = 50
    var originYPercent: CGFloat = 50

    var isIdentity: Bool {
        translateX == 0 && translateY == 0 && translateXPercent == 0 && translateYPercent == 0
            && scaleX == 1 && scaleY == 1 && rotationDegrees == 0
    }

    /// Transform for `UIView.transform` (applied around the view center).
    func affine(size: CGSize) -> CGAffineTransform {
        let dx = size.width * (originXPercent / 100 - 0.5)
        let dy = size.height * (originYPercent / 100 - 0.5)
        let tx = translateX + size.width * translateXPercent / 100
        let ty = translateY + size.height * translateYPercent / 100
        return CGAffineTransform(translationX: -dx, y: -dy)
            .concatenating(CGAffineTransform(scaleX: scaleX, y: scaleY))
            .concatenating(CGAffineTransform(rotationAngle: rotationDegrees * .pi / 180))
            .concatenating(CGAffineTransform(translationX: dx, y: dy))
            .concatenating(CGAffineTransform(translationX: tx, y: ty))
    }
}

/// React Native sticky headers (`stickyHeaderIndices`): a sticky child stays
/// pinned to the top of the viewport after it scrolls past it until the next
/// sticky child pushes it away.
enum PamStickyHeaders {
    /// `frames` are the unpinned content frames, `offset` the scroll position
    /// of the viewport top. Returns the vertical shift for each frame.
    static func shifts(frames: [CGRect], offset: CGFloat) -> [CGFloat] {
        let order = frames.indices.sorted { frames[$0].minY < frames[$1].minY }
        var result = [CGFloat](repeating: 0, count: frames.count)
        for (position, index) in order.enumerated() {
            let frame = frames[index]
            guard offset > frame.minY else { continue }
            let nextTop = position + 1 < order.count ? frames[order[position + 1]].minY : .greatestFiniteMagnitude
            result[index] = min(offset - frame.minY, max(0, nextTop - frame.minY - frame.height))
        }
        return result
    }
}

/// Sticky children of one scroll host, positioned through `center` so their
/// own transforms keep working.
final class PamStickyRegistry {
    private var entries: [ObjectIdentifier: (view: UIView, frame: CGRect)] = [:]

    var isEmpty: Bool { entries.isEmpty }

    func set(_ view: UIView, frame: CGRect?) {
        let key = ObjectIdentifier(view)
        if let frame {
            entries[key] = (view, frame)
            view.layer.zPosition = 1
        } else if let previous = entries.removeValue(forKey: key) {
            previous.view.layer.zPosition = 0
            previous.view.center = CGPoint(x: previous.frame.midX, y: previous.frame.midY)
        }
    }

    func contains(_ view: UIView) -> Bool {
        entries[ObjectIdentifier(view)] != nil
    }

    func update(offset: CGFloat, host: UIView) {
        guard !entries.isEmpty else { return }
        entries = entries.filter { $0.value.view.superview === host }
        let ordered = Array(entries.values)
        let shifts = PamStickyHeaders.shifts(frames: ordered.map { $0.frame }, offset: offset)
        for (index, entry) in ordered.enumerated() {
            let center = CGPoint(x: entry.frame.midX, y: entry.frame.midY + shifts[index])
            if entry.view.center != center { entry.view.center = center }
        }
    }
}

/// `<ScrollView keyboardInset>`: insets the content bottom by the part of the
/// software keyboard that overlaps the scroll view.
final class PamKeyboardInsetObserver {
    private weak var scrollView: UIScrollView?
    private var tokens: [NSObjectProtocol] = []
    private var applied: CGFloat = 0

    init(scrollView: UIScrollView) {
        self.scrollView = scrollView
        let center = NotificationCenter.default
        tokens.append(center.addObserver(
            forName: UIResponder.keyboardWillChangeFrameNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            self?.keyboardChanged(notification, hiding: false)
        })
        tokens.append(center.addObserver(
            forName: UIResponder.keyboardWillHideNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            self?.keyboardChanged(notification, hiding: true)
        })
    }

    deinit {
        tokens.forEach { NotificationCenter.default.removeObserver($0) }
        reset()
    }

    func reset() {
        guard let scrollView, applied != 0 else { return }
        scrollView.contentInset.bottom -= applied
        scrollView.verticalScrollIndicatorInsets.bottom -= applied
        applied = 0
    }

    static func overlap(keyboard: CGRect, viewInWindow: CGRect) -> CGFloat {
        guard !keyboard.isEmpty else { return 0 }
        return max(0, viewInWindow.maxY - max(keyboard.minY, viewInWindow.minY))
    }

    private func keyboardChanged(_ notification: Notification, hiding: Bool) {
        guard let scrollView, let window = scrollView.window else { return }
        let end = (notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue ?? .zero
        let keyboard = window.convert(end, from: window.screen.coordinateSpace)
        let frame = scrollView.convert(scrollView.bounds, to: window)
        let next = hiding ? 0 : Self.overlap(keyboard: keyboard, viewInWindow: frame)
        guard next != applied else { return }
        let duration = (notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber)?.doubleValue ?? 0.25
        let delta = next - applied
        applied = next
        UIView.animate(withDuration: PamMotionPolicy.isReduced ? 0 : duration) {
            scrollView.contentInset.bottom += delta
            scrollView.verticalScrollIndicatorInsets.bottom += delta
        }
    }
}

/// `on:layout` (React Native onLayout): frame relative to the parent,
/// reported on mount and on change, coalesced to one event per node per frame.
final class PamLayoutEvents {
    private var pending: [Int64] = []
    private var pendingSet = Set<Int64>()
    private var last: [Int64: CGRect] = [:]
    private var scheduled = false
    private let resolve: (Int64) -> CGRect?
    private let dispatch: (Int64, Data) -> Void

    init(resolve: @escaping (Int64) -> CGRect?, dispatch: @escaping (Int64, Data) -> Void) {
        self.resolve = resolve
        self.dispatch = dispatch
    }

    func queue(_ id: Int64) {
        guard pendingSet.insert(id).inserted else { return }
        pending.append(id)
        guard !scheduled else { return }
        scheduled = true
        DispatchQueue.main.async { [weak self] in self?.flush() }
    }

    func forget(_ id: Int64) {
        last[id] = nil
        pendingSet.remove(id)
    }

    func flush() {
        scheduled = false
        let ids = pending
        pending.removeAll()
        pendingSet.removeAll()
        for id in ids {
            guard let frame = resolve(id), last[id] != frame else { continue }
            last[id] = frame
            dispatch(id, Self.payload(frame))
        }
    }

    static func payload(_ frame: CGRect) -> Data {
        (try? WireMap.encode([
            "x": .decimal(Double(frame.minX)),
            "y": .decimal(Double(frame.minY)),
            "width": .decimal(Double(frame.width)),
            "height": .decimal(Double(frame.height)),
        ])) ?? Data()
    }
}
