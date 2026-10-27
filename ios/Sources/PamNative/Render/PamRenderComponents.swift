import Foundation
import UIKit

enum PamMaterialElevation {
    static func apply(
        _ elevation: CGFloat,
        to layer: CALayer,
        bounds: CGRect,
        cornerRadius: CGFloat
    ) {
        guard elevation > 0 else {
            layer.shadowColor = nil
            layer.shadowOpacity = 0
            layer.shadowOffset = .zero
            layer.shadowRadius = 0
            layer.shadowPath = nil
            return
        }
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = Float(min(0.24, 0.10 + elevation * 0.008))
        layer.shadowOffset = CGSize(width: 0, height: max(1, elevation * 0.45))
        layer.shadowRadius = max(1, elevation * 0.72)
        layer.shadowPath = UIBezierPath(
            roundedRect: bounds,
            cornerRadius: max(0, cornerRadius)
        ).cgPath
    }
}

final class PamPressButton: UIButton, PamPointerEventsHost {
    static let minimumTouchTarget: CGFloat = 44
    /// RN `pressRetentionOffset` default (top/left/right 20, bottom 30).
    static let defaultPressRetention = UIEdgeInsets(top: 20, left: 20, bottom: 30, right: 20)

    var pamPressedOpacity: CGFloat = 0.72
    var pamPressedScale: CGFloat = 1
    var pamHitSlop = UIEdgeInsets.zero
    /// Extra distance the finger may travel outside the touch target before
    /// the press is cancelled (properties 237-240). Only grows the bounds
    /// while a touch is already tracking, so hit testing is unaffected.
    var pamPressRetention = PamPressButton.defaultPressRetention
    var pamPointerEvents = PamPointerEvents.auto
    var pamStateStyles: [Int: [Int: Any]] = [:]

    private var restingAlpha: CGFloat?
    private var restingTransform: CGAffineTransform?
    private var restingBackgroundColor: UIColor?
    private var restingTitleColor: UIColor?
    private var restingBorderColor: CGColor?
    private var restingShadowColor: CGColor?
    private var restingShadowOpacity: Float?
    private var restingShadowOffset: CGSize?
    private var restingShadowRadius: CGFloat?
    private var restingShadowPath: CGPath?

    override init(frame: CGRect) {
        super.init(frame: frame)
        focusEffect = UIFocusHaloEffect()
        installHoverState()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        focusEffect = UIFocusHaloEffect()
        installHoverState()
    }

    override var canBecomeFocused: Bool {
        isEnabled && !isHidden && alpha > 0.01
    }

    /// RN Pressable is `accessible` and, without its own label, announces the
    /// labels of its content (`RCTRecursiveAccessibilityLabel`), like a
    /// focusable Android ViewGroup reading its children. A press target that
    /// only wraps labelled content (a gesture detector around a button) must
    /// not become an unnamed element that hides that content's label.
    override var accessibilityLabel: String? {
        get {
            if let own = super.accessibilityLabel, !own.isEmpty { return own }
            return Self.contentAccessibilityLabel(of: self)
        }
        set { super.accessibilityLabel = newValue }
    }

    static func contentAccessibilityLabel(of view: UIView) -> String? {
        var parts: [String] = []
        for child in view.subviews where !child.isHidden && child.alpha > 0.01 && !child.accessibilityElementsHidden {
            if let label = child.accessibilityLabel, !label.isEmpty {
                parts.append(label)
            } else if let text = (child as? UILabel)?.text, !text.isEmpty {
                parts.append(text)
            } else if let nested = contentAccessibilityLabel(of: child) {
                parts.append(nested)
            }
        }
        return parts.isEmpty ? nil : parts.joined(separator: ", ")
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        guard isEnabled, isUserInteractionEnabled, !isHidden, alpha > 0.01 else {
            return false
        }
        return touchBounds(retaining: isTracking).contains(point)
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        PamPointerEvents.hitTest(self, mode: pamPointerEvents) {
            super.hitTest(point, with: event)
        }
    }

    /// Touch target: bounds grown to 44pt / hitSlop, plus the press retention
    /// offset while a press is in flight (RN `pressRetentionOffset`).
    func touchBounds(retaining: Bool) -> CGRect {
        let horizontal = max(0, Self.minimumTouchTarget - bounds.width) / 2
        let vertical = max(0, Self.minimumTouchTarget - bounds.height) / 2
        let retention = retaining ? pamPressRetention : .zero
        let insets = UIEdgeInsets(
            top: -(max(vertical, pamHitSlop.top) + max(0, retention.top)),
            left: -(max(horizontal, pamHitSlop.left) + max(0, retention.left)),
            bottom: -(max(vertical, pamHitSlop.bottom) + max(0, retention.bottom)),
            right: -(max(horizontal, pamHitSlop.right) + max(0, retention.right))
        )
        return bounds.inset(by: insets)
    }

    override func continueTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        let keep = super.continueTracking(touch, with: event)
        // UIControl keeps tracking with its own ~70pt tolerance; a press that
        // leaves the retention bounds is cancelled, like RN/Android.
        guard touchBounds(retaining: true).contains(touch.location(in: self)) else {
            cancelTracking(with: event)
            isHighlighted = false
            return false
        }
        return keep
    }

    override var isHighlighted: Bool {
        didSet {
            guard isHighlighted != oldValue else { return }
            if isHighlighted {
                restingAlpha = alpha
                restingTransform = transform
                applyPamState(1, active: true)
                // Pressed-state styles (translate/scale) compose with pressScale.
                let pressedBase = transform
                if pamStateStyles[1] != nil {
                    transform = restingTransform ?? .identity
                }
                if PamMotionPolicy.isReduced {
                    layer.removeAllAnimations()
                    alpha = pamPressedOpacity
                    transform = pressedBase.scaledBy(x: pamPressedScale, y: pamPressedScale)
                    return
                }
                UIView.animate(
                    withDuration: 0.07,
                    delay: 0,
                    options: [.beginFromCurrentState, .allowUserInteraction]
                ) {
                    self.alpha = self.pamPressedOpacity
                    self.transform = pressedBase.scaledBy(x: self.pamPressedScale, y: self.pamPressedScale)
                }
            } else {
                let alpha = restingAlpha ?? self.alpha
                let transform = restingTransform ?? self.transform
                if PamMotionPolicy.isReduced {
                    layer.removeAllAnimations()
                    self.alpha = alpha
                    self.transform = transform
                    applyPamState(1, active: false)
                    restingAlpha = nil
                    restingTransform = nil
                    return
                }
                UIView.animate(
                    withDuration: 0.11,
                    delay: 0,
                    options: [.beginFromCurrentState, .allowUserInteraction]
                ) {
                    self.alpha = alpha
                    self.transform = transform
                }
                applyPamState(1, active: false)
                restingAlpha = nil
                restingTransform = nil
            }
        }
    }

    override func didUpdateFocus(
        in context: UIFocusUpdateContext,
        with coordinator: UIFocusAnimationCoordinator
    ) {
        super.didUpdateFocus(in: context, with: coordinator)
        coordinator.addCoordinatedAnimations { [weak self] in
            guard let self else { return }
            self.applyPamState(2, active: context.nextFocusedView === self)
        }
    }

    private func installHoverState() {
        if #available(iOS 13.0, *) {
            addGestureRecognizer(UIHoverGestureRecognizer(target: self, action: #selector(hoverChanged(_:))))
        }
    }

    @available(iOS 13.0, *)
    @objc private func hoverChanged(_ recognizer: UIHoverGestureRecognizer) {
        applyPamState(3, active: recognizer.state == .began || recognizer.state == .changed)
    }

    private func applyPamState(_ state: Int, active: Bool) {
        guard let styles = pamStateStyles[state] else { return }
        if !active {
            alpha = restingAlpha ?? 1
            transform = restingTransform ?? .identity
            backgroundColor = restingBackgroundColor
            setTitleColor(restingTitleColor, for: .normal)
            layer.borderColor = restingBorderColor
            layer.shadowColor = restingShadowColor
            layer.shadowOpacity = restingShadowOpacity ?? 0
            layer.shadowOffset = restingShadowOffset ?? .zero
            layer.shadowRadius = restingShadowRadius ?? 0
            layer.shadowPath = restingShadowPath
            restingAlpha = nil
            restingTransform = nil
            restingBackgroundColor = nil
            restingTitleColor = nil
            restingBorderColor = nil
            restingShadowColor = nil
            restingShadowOpacity = nil
            restingShadowOffset = nil
            restingShadowRadius = nil
            restingShadowPath = nil
            return
        }
        if restingAlpha == nil { restingAlpha = alpha }
        if restingTransform == nil { restingTransform = transform }
        restingBackgroundColor = backgroundColor
        restingTitleColor = titleColor(for: .normal)
        restingBorderColor = layer.borderColor
        restingShadowColor = layer.shadowColor
        restingShadowOpacity = layer.shadowOpacity
        restingShadowOffset = layer.shadowOffset
        restingShadowRadius = layer.shadowRadius
        restingShadowPath = layer.shadowPath
        if let opacity = styles[38] as? NSNumber { alpha = CGFloat(truncating: opacity) }
        let x = (styles[74] as? NSNumber).map(CGFloat.init(truncating:)) ?? 1
        let y = (styles[75] as? NSNumber).map(CGFloat.init(truncating:)) ?? 1
        // Pressed `transform: translate…` is authored in points (RN translateY: 1.1).
        let dx = (styles[72] as? NSNumber).map(CGFloat.init(truncating:)) ?? 0
        let dy = (styles[73] as? NSNumber).map(CGFloat.init(truncating:)) ?? 0
        transform = transform
            .concatenating(CGAffineTransform(translationX: dx, y: dy))
            .scaledBy(x: x, y: y)
        if let color = styles[10] as? NSNumber {
            backgroundColor = UIColor(argb: color.int64Value)
        }
        if let color = styles[11] as? NSNumber {
            setTitleColor(UIColor(argb: color.int64Value), for: .normal)
        }
        if let color = styles[37] as? NSNumber {
            layer.borderColor = UIColor(argb: color.int64Value).cgColor
        }
        if let elevation = styles[56] as? NSNumber {
            PamMaterialElevation.apply(
                CGFloat(truncating: elevation),
                to: layer,
                bounds: bounds,
                cornerRadius: layer.cornerRadius
            )
        }
    }
}

final class PamAnchoredScrollView: UIScrollView {
    var anchorToEnd = false {
        didSet {
            if anchorToEnd && !oldValue {
                initialEndAnchorApplied = false
                setNeedsLayout()
            }
        }
    }
    var maintainVisibleContentPosition = false
    var autoScrollToEndThreshold: CGFloat = 24
    var horizontal = false
    var pamPagingEnabled = false
    var pamSnapInterval: CGFloat = 0
    var scrollTargetTestId = ""
    var scrollTargetOffset: CGFloat = -1
    var scrollTargetAlignment = 1
    /// RN `showsVerticalScrollIndicator` / `showsHorizontalScrollIndicator`
    /// (one flag, resolved against the axis like Android).
    var pamShowsScrollIndicator = true
    /// RN `contentOffset`: requested per axis and applied once the content
    /// is measured (Android `requestOffsetApplication`), so an offset set in
    /// the same commit as the content is not clamped to an empty view.
    var pamRequestedContentOffsetX: CGFloat? {
        didSet { if pamRequestedContentOffsetX != nil { setNeedsLayout() } }
    }
    var pamRequestedContentOffsetY: CGFloat? {
        didSet { if pamRequestedContentOffsetY != nil { setNeedsLayout() } }
    }

    private var initialEndAnchorApplied = false
    private var previousMaxOffset: CGFloat = 0
    /// Sticky header children (`stickyHeader`).
    let pamSticky = PamStickyRegistry()

    /// RN `keyboardShouldPersistTaps`; nil when absent or `always`.
    var pamKeyboardTaps: PamKeyboardTapController?

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        if !pamSticky.isEmpty, isUserInteractionEnabled, !isHidden, alpha > 0.01,
           self.point(inside: point, with: event),
           let pinned = pamSticky.hitTest(point, in: self, with: event) {
            return pamKeyboardTaps?.capture(pinned, in: self) ?? pinned
        }
        let hit = super.hitTest(point, with: event)
        guard let taps = pamKeyboardTaps else { return hit }
        return taps.capture(hit, in: self)
    }

    var primaryPageExtent: CGFloat {
        if pamSnapInterval > 0 {
            return pamSnapInterval
        }
        guard pamPagingEnabled else { return 0 }
        return horizontal
            ? max(0, bounds.width - adjustedContentInset.left - adjustedContentInset.right)
            : max(0, bounds.height - adjustedContentInset.top - adjustedContentInset.bottom)
    }

    static func onePageTarget(
        start: CGFloat,
        position: CGFloat,
        velocity: CGFloat,
        extent: CGFloat,
        maximum: CGFloat
    ) -> CGFloat {
        guard extent > 0 else { return min(max(0, position), maximum) }
        let startPage = (start / extent).rounded()
        let displacement = position - startPage * extent
        let direction: CGFloat
        if velocity > 350 {
            direction = 1
        } else if velocity < -350 {
            direction = -1
        } else if displacement > extent * 0.18 {
            direction = 1
        } else if displacement < -extent * 0.18 {
            direction = -1
        } else {
            direction = 0
        }
        return min(max(0, (startPage + direction) * extent), maximum)
    }

    override func layoutSubviews() {
        PamImageResidency.requestSweep()
        let oldMax = previousMaxOffset
        let previousOffset = primaryOffset
        let distanceFromEnd = max(0, oldMax - previousOffset)
        super.layoutSubviews()

        let newMax = primaryMaxOffset
        previousMaxOffset = newMax
        if anchorToEnd && !initialEndAnchorApplied {
            initialEndAnchorApplied = true
            setPrimaryOffset(newMax)
        } else if anchorToEnd,
                  newMax != oldMax,
                  distanceFromEnd <= autoScrollToEndThreshold {
            setPrimaryOffset(newMax)
        } else if maintainVisibleContentPosition, newMax > oldMax {
            setPrimaryOffset(previousOffset + newMax - oldMax)
        }
        applyRequestedContentOffset()
        if !horizontal {
            pamSticky.update(offset: contentOffset.y + adjustedContentInset.top, host: self)
        }
    }

    /// Applies a requested `contentOffset` once the content extent along
    /// that axis is known; a request before measurement waits for layout.
    private func applyRequestedContentOffset() {
        guard bounds.width > 0, bounds.height > 0, !isTracking, !isDragging else { return }
        var target = contentOffset
        var changed = false
        if let x = pamRequestedContentOffsetX, contentSize.width > 0 {
            let maximum = max(0, contentSize.width - bounds.width + adjustedContentInset.right)
            target.x = min(maximum, max(-adjustedContentInset.left, x))
            pamRequestedContentOffsetX = nil
            changed = true
        }
        if let y = pamRequestedContentOffsetY, contentSize.height > 0 {
            let maximum = max(0, contentSize.height - bounds.height + adjustedContentInset.bottom)
            target.y = min(maximum, max(-adjustedContentInset.top, y))
            pamRequestedContentOffsetY = nil
            changed = true
        }
        guard changed, target != contentOffset else { return }
        pamStopDeceleration()
        setContentOffset(target, animated: false)
    }

    private var primaryOffset: CGFloat {
        horizontal ? contentOffset.x : contentOffset.y
    }

    private var primaryMaxOffset: CGFloat {
        if horizontal {
            return max(0, contentSize.width - bounds.width + adjustedContentInset.right)
        }
        return max(0, contentSize.height - bounds.height + adjustedContentInset.bottom)
    }

    private func setPrimaryOffset(_ value: CGFloat) {
        if horizontal {
            contentOffset.x = min(
                primaryMaxOffset,
                max(-adjustedContentInset.left, value)
            )
        } else {
            contentOffset.y = min(
                primaryMaxOffset,
                max(-adjustedContentInset.top, value)
            )
        }
    }

    /// `scrollAnimated`: requests glide to their target.
    var scrollAnimated = false

    private func scrollToRequested(_ value: CGFloat) {
        pamStopDeceleration()
        guard scrollAnimated else {
            setPrimaryOffset(value)
            return
        }
        let bounded = horizontal
            ? min(primaryMaxOffset, max(-adjustedContentInset.left, value))
            : min(primaryMaxOffset, max(-adjustedContentInset.top, value))
        setContentOffset(
            horizontal ? CGPoint(x: bounded, y: contentOffset.y) : CGPoint(x: contentOffset.x, y: bounded),
            animated: true
        )
    }

    func requestScroll() {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.window != nil else { return }
            if !self.scrollTargetTestId.isEmpty {
                guard let target = self.findDescendant(testId: self.scrollTargetTestId) else {
                    return
                }
                let frame = target.convert(target.bounds, to: self)
                let targetStart = self.horizontal
                    ? frame.minX + self.contentOffset.x
                    : frame.minY + self.contentOffset.y
                let targetExtent = self.horizontal ? frame.width : frame.height
                let viewportExtent = self.horizontal
                    ? self.bounds.width - self.adjustedContentInset.left - self.adjustedContentInset.right
                    : self.bounds.height - self.adjustedContentInset.top - self.adjustedContentInset.bottom
                let available = max(0, viewportExtent - targetExtent)
                let adjustment: CGFloat
                switch self.scrollTargetAlignment {
                case 2: adjustment = available / 2
                case 3: adjustment = available
                default: adjustment = 0
                }
                let offset = max(0, targetStart - adjustment)
                self.scrollToRequested(offset)
                return
            }
            if self.scrollTargetOffset >= 0 {
                self.scrollToRequested(self.scrollTargetOffset)
            } else {
                self.scrollToRequested(self.primaryMaxOffset)
            }
        }
    }

    private func findDescendant(testId: String) -> UIView? {
        var pending = subviews
        while let view = pending.popLast() {
            if view.accessibilityIdentifier == testId {
                return view
            }
            pending.append(contentsOf: view.subviews)
        }
        return nil
    }
}

final class PamVirtualListView: UIScrollView {
    var onViewportChange: (() -> Void)?

    /// In a window and not hidden by any ancestor (a prewarmed route is).
    var pamIsShown: Bool {
        guard window != nil else { return false }
        var view: UIView? = self
        while let current = view {
            if current.isHidden || current.alpha < 0.01 { return false }
            view = current.superview
        }
        return true
    }
    /// RN `pagingEnabled` for lists: one item per page.
    var pamPagingEnabled = false {
        didSet { decelerationRate = pamPagingEnabled ? .fast : .normal }
    }
    /// Sorted item start offsets along the primary axis.
    var pamItemStarts: [CGFloat] = []

    /// Snapped item when paging; otherwise the first fully visible item.
    func pageIndex() -> Int {
        guard !pamItemStarts.isEmpty else { return 0 }
        let offset = horizontal ? contentOffset.x : contentOffset.y
        if pamPagingEnabled {
            return PamDragMath.nearest(pamItemStarts.map(Double.init), Double(offset))
        }
        return pamItemStarts.firstIndex { $0 >= offset - 0.5 }
            ?? max(0, (pamItemStarts.lastIndex { $0 <= offset } ?? 0))
    }

    /// One item per gesture from the item nearest the gesture start, moved
    /// by velocity (> 350 pt/s) or 18% of the item extent.
    static func itemPageTarget(
        starts: [CGFloat],
        start: CGFloat,
        position: CGFloat,
        velocity: CGFloat,
        maximum: CGFloat
    ) -> CGFloat {
        guard !starts.isEmpty else { return min(max(0, position), maximum) }
        let origin = PamDragMath.nearest(starts.map(Double.init), Double(start))
        let extent = origin + 1 < starts.count
            ? starts[origin + 1] - starts[origin]
            : (origin > 0 ? starts[origin] - starts[origin - 1] : max(1, maximum))
        let displacement = position - starts[origin]
        var index = origin
        if velocity > 350 || (abs(velocity) <= 350 && displacement > extent * 0.18) {
            index = min(origin + 1, starts.count - 1)
        } else if velocity < -350 || (abs(velocity) <= 350 && displacement < -extent * 0.18) {
            index = max(origin - 1, 0)
        }
        return min(max(0, starts[index]), maximum)
    }
    /// Sticky header cells (`stickyHeader`).
    let pamSticky = PamStickyRegistry()

    /// RN `keyboardShouldPersistTaps`; nil when absent or `always`.
    var pamKeyboardTaps: PamKeyboardTapController?

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        if !pamSticky.isEmpty, isUserInteractionEnabled, !isHidden, alpha > 0.01,
           self.point(inside: point, with: event),
           let pinned = pamSticky.hitTest(point, in: self, with: event) {
            return pamKeyboardTaps?.capture(pinned, in: self) ?? pinned
        }
        let hit = super.hitTest(point, with: event)
        guard let taps = pamKeyboardTaps else { return hit }
        return taps.capture(hit, in: self)
    }
    private var anchorOffset: CGFloat = 0
    private var anchorMaximum: CGFloat = -1
    private(set) var scrollVelocity: CGFloat = 0
    private var previousOffset: CGFloat = 0
    private var previousTimestamp = CACurrentMediaTime()
    private var notifyingViewport = false

    var horizontal = false

    /// RN `showsVerticalScrollIndicator` / `showsHorizontalScrollIndicator`
    /// (one flag, resolved against the axis like Android).
    var pamShowsScrollIndicator = true

    /// RN `inverted`: the list is mirrored along its axis and every cell is
    /// mirrored back (the React Native approach), so the engine keeps laying
    /// the rows out from the start while the first row sits at the end.
    var pamInverted = false {
        didSet {
            guard pamInverted != oldValue || transform != pamInvertedTransform else { return }
            transform = pamInvertedTransform
        }
    }

    /// Mirror transform for the list and its cells along the list axis.
    var pamInvertedTransform: CGAffineTransform {
        guard pamInverted else { return .identity }
        return horizontal ? CGAffineTransform(scaleX: -1, y: 1) : CGAffineTransform(scaleX: 1, y: -1)
    }

    /// RN `initialScrollIndex`: applied once, on the first sync that knows
    /// the item starts (Android `applyInitialPosition`); an explicit scroll
    /// request of the same commit wins.
    var pamInitialScrollIndex = 0
    var pamInitialScrollIndexApplied = false

    /// RN `windowSize`-like item prefetch (`ListPrefetch`): minimum number of
    /// items kept mounted beyond the viewport on each side. The windowing
    /// floors the velocity-driven overscan at this many average items.
    var pamPrefetchItems = 0
    /// RN `removeClippedSubviews`: the windowing always unmounts cells that
    /// leave the overscan (engine-driven); the flag is kept for parity and
    /// only widens the retained window when false, like Android's cache.
    var pamRemoveClippedSubviews = true

    /// Lands exactly on the target: deceleration in progress is stopped
    /// first (it otherwise kept moving after the jump). `animated` glides.
    func scrollToLogicalOffset(_ value: CGFloat, animated: Bool = false) {
        let requested = max(0, value)
        let maximum = horizontal
            ? max(0, contentSize.width - bounds.width + adjustedContentInset.right)
            : max(0, contentSize.height - bounds.height + adjustedContentInset.bottom)
        let offset = min(requested, maximum)
        pamStopDeceleration()
        let target = horizontal
            ? CGPoint(x: offset, y: contentOffset.y)
            : CGPoint(x: contentOffset.x, y: offset)
        guard animated, !PamMotionPolicy.isReduced else {
            setContentOffset(target, animated: false)
            return
        }
        // A previous UIScrollView animation stopped immediately above can be
        // coalesced with a new `setContentOffset(animated:)` in the same run
        // loop, leaving the list at the old offset. An explicit interruptible
        // UIView animation always starts from the presentation state.
        UIView.animate(
            withDuration: 0.25,
            delay: 0,
            options: [.beginFromCurrentState, .allowUserInteraction, .curveEaseOut]
        ) {
            self.contentOffset = target
        }
    }

    var adaptiveOverscan: CGFloat {
        let viewport = horizontal ? bounds.width : bounds.height
        let velocityPages = abs(scrollVelocity) / max(viewport, 1)
        let overscan = viewport * min(max(1.5 + velocityPages * 0.35, 1.5), 6)
        guard pamPrefetchItems > 0, pamItemStarts.count > 1 else { return overscan }
        let averageExtent = (pamItemStarts[pamItemStarts.count - 1] - pamItemStarts[0])
            / CGFloat(pamItemStarts.count - 1)
        let prefetch = CGFloat(pamPrefetchItems) * max(1, averageExtent)
        let retained = pamRemoveClippedSubviews ? prefetch : prefetch * 2
        return min(max(overscan, retained), viewport * 6)
    }

    var primaryMaximumOffset: CGFloat {
        horizontal
            ? max(0, contentSize.width - bounds.width + adjustedContentInset.right)
            : max(0, contentSize.height - bounds.height + adjustedContentInset.bottom)
    }

    /// A list resting at its end stays there when its cells are re-measured
    /// or its viewport shrinks (chat timelines).
    func maintainEndAnchor() {
        let maximum = primaryMaximumOffset
        let offset = horizontal ? contentOffset.x : contentOffset.y
        defer {
            anchorMaximum = maximum
            anchorOffset = horizontal ? contentOffset.x : contentOffset.y
        }
        guard anchorMaximum > 0, maximum != anchorMaximum,
              anchorOffset >= anchorMaximum - 1,
              !isTracking, !isDragging, !isDecelerating else { return }
        if abs(offset - maximum) > 0.5 {
            if horizontal {
                contentOffset.x = maximum
            } else {
                contentOffset.y = maximum
            }
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        PamImageResidency.requestSweep()
        maintainEndAnchor()
        if !horizontal {
            pamSticky.update(offset: contentOffset.y + adjustedContentInset.top, host: self)
        }
        let now = CACurrentMediaTime()
        let offset = horizontal ? contentOffset.x : contentOffset.y
        let elapsed = max(now - previousTimestamp, 1.0 / 240.0)
        scrollVelocity = (offset - previousOffset) / elapsed
        previousOffset = offset
        previousTimestamp = now
        guard !notifyingViewport else { return }
        notifyingViewport = true
        onViewportChange?()
        notifyingViewport = false
    }
}

enum PamVirtualWindow {
    static func visibleIds(
        frames: [(Int64, CGRect)],
        viewport: CGRect,
        horizontal: Bool,
        overscan: CGFloat,
        velocity: CGFloat
    ) -> Set<Int64> {
        let before = velocity < 0 ? overscan * 1.5 : overscan
        let after = velocity > 0 ? overscan * 1.5 : overscan
        let window = horizontal
            ? viewport.insetBy(dx: 0, dy: -overscan).insetBy(
                dx: -(before + after) / 2,
                dy: 0
            ).offsetBy(dx: (after - before) / 2, dy: 0)
            : viewport.insetBy(dx: -overscan, dy: 0).insetBy(
                dx: 0,
                dy: -(before + after) / 2
            ).offsetBy(dx: 0, dy: (after - before) / 2)
        let windowStart = horizontal ? window.minX : window.minY
        let windowEnd = horizontal ? window.maxX : window.maxY
        var lower = 0
        var upper = frames.count
        while lower < upper {
            let middle = lower + (upper - lower) / 2
            let frameEnd = horizontal ? frames[middle].1.maxX : frames[middle].1.maxY
            if frameEnd <= windowStart {
                lower = middle + 1
            } else {
                upper = middle
            }
        }
        var visible = Set<Int64>()
        var index = lower
        while index < frames.count {
            let candidate = frames[index]
            let frameStart = horizontal ? candidate.1.minX : candidate.1.minY
            if frameStart >= windowEnd { break }
            if candidate.1.intersects(window) {
                visible.insert(candidate.0)
            }
            index += 1
        }
        return visible
    }
}

final class PamVuetifySwitch: UIControl {
    private let trackLayer = CALayer()
    private let thumbLayer = CALayer()

    var trackOffColor = UIColor(white: 0.55, alpha: 0.60) {
        didSet { updateAppearance(animated: false) }
    }

    var trackOnColor = UIColor.systemBlue {
        didSet { updateAppearance(animated: false) }
    }

    var thumbColor = UIColor.white {
        didSet { updateAppearance(animated: false) }
    }

    var isOn = false {
        didSet {
            guard oldValue != isOn else { return }
            updateAppearance(animated: window != nil)
            accessibilityValue = isOn ? "On" : "Off"
        }
    }

    override var isEnabled: Bool {
        didSet {
            alpha = isEnabled ? 1.0 : 0.38
            accessibilityTraits = isEnabled ? [.button] : [.button, .notEnabled]
        }
    }

    override var intrinsicContentSize: CGSize {
        CGSize(width: 40, height: 40)
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = true
        accessibilityTraits = [.button]
        accessibilityValue = "Off"
        layer.addSublayer(trackLayer)
        layer.addSublayer(thumbLayer)
        trackLayer.cornerRadius = 7
        thumbLayer.cornerRadius = 10
        thumbLayer.shadowColor = UIColor.black.cgColor
        thumbLayer.shadowOpacity = 0.24
        thumbLayer.shadowRadius = 2
        thumbLayer.shadowOffset = CGSize(width: 0, height: 1)
        addTarget(self, action: #selector(toggle), for: .touchUpInside)
        updateAppearance(animated: false)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        updateFrames(animated: false)
    }

    @objc private func toggle() {
        guard isEnabled else { return }
        isOn.toggle()
        sendActions(for: .valueChanged)
    }

    private func updateAppearance(animated: Bool) {
        let changes = {
            self.trackLayer.backgroundColor = (
                self.isOn ? self.trackOnColor : self.trackOffColor
            ).cgColor
            self.thumbLayer.backgroundColor = self.thumbColor.cgColor
            self.updateFrames(animated: false)
        }
        if animated {
            CATransaction.begin()
            CATransaction.setAnimationDuration(0.20)
            CATransaction.setAnimationTimingFunction(
                CAMediaTimingFunction(name: .easeInEaseOut)
            )
            changes()
            CATransaction.commit()
        } else {
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            changes()
            CATransaction.commit()
        }
    }

    private func updateFrames(animated _: Bool) {
        let centerY = bounds.midY
        let trackFrame = CGRect(
            x: bounds.midX - 18,
            y: centerY - 7,
            width: 36,
            height: 14
        )
        trackLayer.frame = trackFrame
        let thumbCenterX = isOn ? trackFrame.maxX - 10 : trackFrame.minX + 10
        thumbLayer.frame = CGRect(
            x: thumbCenterX - 10,
            y: centerY - 10,
            width: 20,
            height: 20
        )
    }
}

final class PamVuetifySpinner: UIView {
    static let defaultSize: CGFloat = 20

    private let arcLayer = CAShapeLayer()
    /// True while the indeterminate animations are (or should be) installed.
    private var running = false
    private var requestedVisible = true
    private var animating = true
    private var hidesWhenStopped = true
    private var size = PamVuetifySpinner.defaultSize
    private var customColor: UIColor?

    override var intrinsicContentSize: CGSize {
        CGSize(width: size, height: size)
    }

    /// Mirrors Android `PamActivityIndicator`: stroke colour, 20/36pt size
    /// variants and the `animating` / `hidesWhenStopped` pair.
    var pamIsAnimating: Bool { animating }
    var pamHidesWhenStopped: Bool { hidesWhenStopped }
    var pamSize: CGFloat { size }
    var pamStrokeColor: CGColor? { arcLayer.strokeColor }
    var pamIsRunning: Bool { running }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = true
        accessibilityTraits = [.updatesFrequently]
        accessibilityLabel = "Loading"
        arcLayer.fillColor = UIColor.clear.cgColor
        arcLayer.strokeColor = tintColor.cgColor
        arcLayer.lineCap = .round
        arcLayer.lineWidth = 3
        layer.addSublayer(arcLayer)
        applyState()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        arcLayer.frame = bounds
        let inset = max(2, arcLayer.lineWidth / 2)
        let rect = CGRect(
            x: (bounds.width - size) / 2,
            y: (bounds.height - size) / 2,
            width: size,
            height: size
        )
        arcLayer.path = UIBezierPath(
            ovalIn: rect.insetBy(dx: inset, dy: inset)
        ).cgPath
    }

    override func tintColorDidChange() {
        super.tintColorDidChange()
        guard customColor == nil else { return }
        arcLayer.strokeColor = tintColor.cgColor
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        // Off-screen spinners never keep Core Animation busy.
        if window == nil {
            arcLayer.removeAllAnimations()
        } else if running {
            installAnimations()
        }
    }

    /// `visible` (property 57).
    func setRequestedVisible(_ value: Bool) {
        requestedVisible = value
        applyState()
    }

    /// `animating` (property 189).
    func setAnimating(_ value: Bool) {
        animating = value
        applyState()
    }

    /// `hidesWhenStopped` (property 190).
    func setHidesWhenStopped(_ value: Bool) {
        hidesWhenStopped = value
        applyState()
    }

    /// `size` in points (property 191): 20 (small) / 36 (large) or custom.
    func setSize(_ value: CGFloat) {
        size = max(1, value)
        invalidateIntrinsicContentSize()
        setNeedsLayout()
    }

    /// `color` (property 53); nil restores the tint colour.
    func setColor(_ color: UIColor?) {
        customColor = color
        arcLayer.strokeColor = (color ?? tintColor).cgColor
    }

    private func applyState() {
        let shouldRun = animating && requestedVisible
        if shouldRun {
            if !running {
                running = true
                installAnimations()
            }
        } else if running || arcLayer.animationKeys()?.isEmpty == false {
            running = false
            arcLayer.removeAllAnimations()
            showStaticArc()
        }
        isHidden = !(requestedVisible && (animating || !hidesWhenStopped))
    }

    private func showStaticArc() {
        arcLayer.strokeStart = 0.08
        arcLayer.strokeEnd = 0.82
    }

    private func installAnimations() {
        guard window != nil else { return }
        arcLayer.removeAllAnimations()
        if PamMotionPolicy.isReduced {
            showStaticArc()
            return
        }

        let rotation = CABasicAnimation(keyPath: "transform.rotation")
        rotation.fromValue = 0
        rotation.toValue = CGFloat.pi * 2
        rotation.duration = 1.4
        rotation.repeatCount = .infinity
        rotation.timingFunction = CAMediaTimingFunction(name: .linear)

        let strokeEnd = CAKeyframeAnimation(keyPath: "strokeEnd")
        strokeEnd.values = [0.08, 0.82, 0.98]
        strokeEnd.keyTimes = [0, 0.55, 1]
        strokeEnd.duration = 1.4
        strokeEnd.repeatCount = .infinity
        strokeEnd.timingFunctions = [
            CAMediaTimingFunction(name: .easeInEaseOut),
            CAMediaTimingFunction(name: .easeInEaseOut),
        ]

        let strokeStart = CAKeyframeAnimation(keyPath: "strokeStart")
        strokeStart.values = [0, 0.04, 0.76]
        strokeStart.keyTimes = [0, 0.45, 1]
        strokeStart.duration = 1.4
        strokeStart.repeatCount = .infinity
        strokeStart.timingFunctions = [
            CAMediaTimingFunction(name: .easeInEaseOut),
            CAMediaTimingFunction(name: .easeInEaseOut),
        ]

        arcLayer.add(rotation, forKey: "pam.rotation")
        arcLayer.add(strokeEnd, forKey: "pam.strokeEnd")
        arcLayer.add(strokeStart, forKey: "pam.strokeStart")
    }
}

private extension UIView {
    /// Retries `autoFocus` on the first input under this view that asked for it.
    @discardableResult
    func pamRequestAutomaticFocus() -> Bool {
        if let input = self as? (any PamTextInputHost), input.autoFocusRequested {
            input.requestAutomaticFocus()
            return true
        }
        for child in subviews where child.pamRequestAutomaticFocus() {
            return true
        }
        return false
    }

    func pamFirstResponder() -> UIView? {
        if isFirstResponder {
            return self
        }
        for child in subviews {
            if let responder = child.pamFirstResponder() {
                return responder
            }
        }
        return nil
    }

    func pamFirstAccessibleView() -> UIView? {
        if (
            !isHidden
            && alpha > 0.01
            && isUserInteractionEnabled
            && (isAccessibilityElement || canBecomeFirstResponder)
        ) {
            return self
        }
        for child in subviews {
            if let accessible = child.pamFirstAccessibleView() {
                return accessible
            }
        }
        return nil
    }
}

final class PamSafeAreaView: UIView {
    var onSafeAreaInsetsDidChange: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        clipsToBounds = true
    }

    override func safeAreaInsetsDidChange() {
        super.safeAreaInsetsDidChange()
        onSafeAreaInsetsDidChange?()
    }
}

enum PamInputSubmitBehavior: Int64 {
    case submit = 1, blurAndSubmit, newline
}

/// Vertical text placement inside an Input (`InputTextAlignVertical`).
enum PamInputVerticalAlignment: Int64 {
    case auto = 1, top, center, bottom
}

/// Everything the renderer configures on an Input host, whether it is the
/// single-line `PamInputField` (UITextField) or the multiline
/// `PamMultilineInputField` (UITextView). The UIKit members listed here are
/// satisfied by the superclasses; the rest is implemented by both hosts.
protocol PamTextInputHost: UIView {
    var submitBehavior: PamInputSubmitBehavior { get set }
    var maximumLength: Int? { get set }
    var isInputEditable: Bool { get set }
    var autoFocusRequested: Bool { get set }
    var placeholderText: String? { get set }
    var placeholderColor: UIColor? { get set }
    var caretHidden: Bool { get set }
    var contextMenuHidden: Bool { get set }
    var selectTextOnFocus: Bool { get set }
    var verticalAlignment: PamInputVerticalAlignment { get set }
    var onInputEndEditing: ((String) -> Void)? { get set }
    var suppressTextChangeEvents: Bool { get }
    var inputText: String { get }

    func requestAutomaticFocus()
    func setTextFromRenderer(_ value: String)
    func setFormattedTextFromRenderer(_ value: String)
    func recordDispatchedValue(_ value: String)
    func setInputCallbacks(
        selection: ((Int, Int) -> Void)?,
        contentSize: ((Int, Int) -> Void)?,
        key: ((String) -> Void)?
    )
    func configureInputFormat(
        format: Int,
        pattern: String,
        placeholder: String,
        prefix: String,
        suffix: String,
        decimalDigits: Int,
        locale: String
    )
    func syncFontCache()
    func scheduleContentSizeUpdate()

    // UIKit (UITextField / UITextView)
    var font: UIFont? { get set }
    var textAlignment: NSTextAlignment { get set }
    var adjustsFontForContentSizeCategory: Bool { get set }
    var keyboardType: UIKeyboardType { get set }
    var returnKeyType: UIReturnKeyType { get set }
    var autocorrectionType: UITextAutocorrectionType { get set }
    var autocapitalizationType: UITextAutocapitalizationType { get set }
    var spellCheckingType: UITextSpellCheckingType { get set }
    var isSecureTextEntry: Bool { get set }
    var inputView: UIView? { get set }
    var selectedTextRange: UITextRange? { get set }
    var markedTextRange: UITextRange? { get }
    var beginningOfDocument: UITextPosition { get }
    var endOfDocument: UITextPosition { get }
    func position(from position: UITextPosition, offset: Int) -> UITextPosition?
    func textRange(from fromPosition: UITextPosition, to toPosition: UITextPosition) -> UITextRange?
    func offset(from: UITextPosition, to toPosition: UITextPosition) -> Int
}

extension PamTextInputHost {
    func setInputCallbacks() {
        setInputCallbacks(selection: nil, contentSize: nil, key: nil)
    }
}

final class PamInputField: UITextField, UITextFieldDelegate, PamTextInputHost {
    var submitBehavior: PamInputSubmitBehavior = .blurAndSubmit
    var maximumLength: Int?
    var isInputEditable = true
    var inputText: String { text ?? "" }
    var caretHidden = false {
        didSet { if caretHidden != oldValue { setNeedsLayout() } }
    }
    var contextMenuHidden = false
    var selectTextOnFocus = false
    var verticalAlignment: PamInputVerticalAlignment = .auto {
        didSet {
            contentVerticalAlignment = switch verticalAlignment {
            case .top: .top
            case .bottom: .bottom
            case .auto, .center: .center
            }
        }
    }
    /// `placeholder` + `placeholderColor` rendered through
    /// `attributedPlaceholder` (the theme authors a muted color per scheme;
    /// UIKit's default ignores it).
    var placeholderText: String? {
        didSet { refreshPlaceholder() }
    }
    var placeholderColor: UIColor? {
        didSet { refreshPlaceholder() }
    }

    override var font: UIFont? {
        didSet { if placeholderColor != nil { refreshPlaceholder() } }
    }

    /// Dynamic Type changes the field's font without going through the
    /// setter: the colored placeholder is rebuilt with the scaled font.
    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if placeholderColor != nil,
           previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            DispatchQueue.main.async { [weak self] in self?.refreshPlaceholder() }
        }
    }

    private func refreshPlaceholder() {
        guard let placeholderText, !placeholderText.isEmpty else {
            attributedPlaceholder = nil
            placeholder = nil
            return
        }
        guard let placeholderColor else {
            placeholder = placeholderText
            return
        }
        var attributes: [NSAttributedString.Key: Any] = [.foregroundColor: placeholderColor]
        if let font { attributes[.font] = font }
        attributedPlaceholder = NSAttributedString(string: placeholderText, attributes: attributes)
    }

    /// Authored padding and border widths (Yoga padding box): text,
    /// placeholder and caret stay inside them, like Android's leaf padding.
    var contentInsets: UIEdgeInsets = .zero {
        didSet {
            guard contentInsets != oldValue else { return }
            setNeedsLayout()
            scheduleContentSizeUpdate()
        }
    }

    override func textRect(forBounds bounds: CGRect) -> CGRect {
        super.textRect(forBounds: bounds.inset(by: contentInsets))
    }

    override func editingRect(forBounds bounds: CGRect) -> CGRect {
        super.editingRect(forBounds: bounds.inset(by: contentInsets))
    }

    /// UIKit derives the placeholder rect from `textRect(forBounds:)`, which
    /// already applies `contentInsets`: insetting here too indented the
    /// placeholder by the padding twice (the typed text only once).
    override func placeholderRect(forBounds bounds: CGRect) -> CGRect {
        textRect(forBounds: bounds)
    }

    override func caretRect(for position: UITextPosition) -> CGRect {
        caretHidden ? .zero : super.caretRect(for: position)
    }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        contextMenuHidden ? false : super.canPerformAction(action, withSender: sender)
    }

    func textFieldDidBeginEditing(_ textField: UITextField) {
        guard selectTextOnFocus else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self, self.isFirstResponder else { return }
            self.selectedTextRange = self.textRange(from: self.beginningOfDocument, to: self.endOfDocument)
        }
    }
    var autoFocusRequested = false {
        didSet {
            if !autoFocusRequested { automaticFocusApplied = false }
            scheduleAutomaticFocus()
        }
    }
    private var automaticFocusApplied = false
    private var automaticFocusScheduled = false

    /// Retries `autoFocus` once a hidden ancestor (a presenting Modal or
    /// BottomSheet) became visible.
    func requestAutomaticFocus() {
        scheduleAutomaticFocus()
    }

    private func scheduleAutomaticFocus() {
        guard autoFocusRequested, !automaticFocusApplied, !automaticFocusScheduled else { return }
        automaticFocusScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.automaticFocusScheduled = false
            guard self.autoFocusRequested, !self.automaticFocusApplied,
                  self.window != nil, self.isEnabled else { return }
            var ancestor: UIView? = self
            while let view = ancestor {
                guard !view.isHidden, view.alpha > 0.01, view.isUserInteractionEnabled else { return }
                ancestor = view.superview
            }
            self.automaticFocusApplied = self.becomeFirstResponder()
        }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        scheduleAutomaticFocus()
    }
    private static let maxKeyBytes = 64

    var onSelectionChange: ((Int, Int) -> Void)?
    var onContentSizeChange: ((Int, Int) -> Void)?
    var onKeyPress: ((String) -> Void)?
    var onInputEndEditing: ((String) -> Void)?

    private var contentSizeScheduled = false
    private var lastContentWidth = -1
    private var lastContentHeight = -1
    private var syncingText = false
    private var inputFormat = 1
    private var inputFormatPattern = ""
    private var inputFormatPlaceholder: Character = "#"
    private var inputFormatPrefix = ""
    private var inputFormatSuffix = ""
    private var inputFormatDecimalDigits = 2
    private var inputFormatLocale: Locale?

    func configureInputFormat(
        format: Int,
        pattern: String,
        placeholder: String,
        prefix: String,
        suffix: String,
        decimalDigits: Int,
        locale: String
    ) {
        inputFormat = min(3, max(1, format))
        inputFormatPattern = String(pattern.prefix(128))
        inputFormatPlaceholder = placeholder.first ?? "#"
        inputFormatPrefix = String(prefix.prefix(16))
        inputFormatSuffix = String(suffix.prefix(16))
        inputFormatDecimalDigits = min(6, max(0, decimalDigits))
        let localeIdentifier = locale.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "_")
        inputFormatLocale = localeIdentifier.isEmpty ? nil : Locale(identifier: localeIdentifier)
        let formatted = formatInput(text ?? "")
        if formatted != text { setTextFromRenderer(formatted) }
    }

    override var text: String! {
        didSet {
            let value = text ?? ""
            if !syncingText {
                syncFontCache()
            }
            scheduleContentSizeUpdate()
            _ = value
        }
    }

    func setTextFromRenderer(_ value: String) {
        syncingText = true
        text = value
        syncingText = false
        syncFontCache()
        scheduleContentSizeUpdate()
    }

    func setFormattedTextFromRenderer(_ value: String) {
        let formatted = formatInput(value)
        let current = text ?? ""
        // A controlled value rendered from an older change event must not
        // overwrite newer keyboard text (React Native's mostRecentEventCount).
        if isFirstResponder && PamInputEcho.isStale(
            &inFlightValues,
            value: formatted,
            current: current,
            now: ProcessInfo.processInfo.systemUptime
        ) {
            return
        }
        if formatted == current { return }
        setTextFromRenderer(formatted)
    }

    /// Values dispatched to PHP whose echo has not been rendered yet.
    private var inFlightValues: [(String, TimeInterval)] = []

    func recordDispatchedValue(_ value: String) {
        PamInputEcho.record(&inFlightValues, value: value, now: ProcessInfo.processInfo.systemUptime)
    }

    func setInputCallbacks(
        selection: ((Int, Int) -> Void)?,
        contentSize: ((Int, Int) -> Void)?,
        key: ((String) -> Void)?
    ) {
        onSelectionChange = selection
        onContentSizeChange = contentSize
        onKeyPress = key
        if contentSize != nil {
            scheduleContentSizeUpdate()
        }
    }

    var suppressTextChangeEvents: Bool {
        syncingText
    }

    private var cachedFont: UIFont = .systemFont(ofSize: UIFont.systemFontSize)

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        borderStyle = .none
        autocorrectionType = .default
        translatesAutoresizingMaskIntoConstraints = true
        syncFontCache()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        delegate = self
        borderStyle = .none
        syncFontCache()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        syncFontCache()
        scheduleContentSizeUpdate()
        scheduleAutomaticFocus()
    }

    override func deleteBackward() {
        guard isInputEditable else { return }
        onKeyPress?("Backspace")
        super.deleteBackward()
    }

    override func unmarkText() {
        super.unmarkText()
        enforceCommittedLength()
    }

    override func setMarkedText(_ markedText: String?, selectedRange: NSRange) {
        guard isInputEditable else { return }
        super.setMarkedText(markedText, selectedRange: selectedRange)
    }

    private func enforceCommittedLength() {
        guard isInputEditable, markedTextRange == nil, let limit = maximumLength,
              let current = text, current.utf16.count > limit else { return }
        var bounded = ""
        var remaining = limit
        for character in current {
            let length = String(character).utf16.count
            guard length <= remaining else { break }
            bounded.append(character)
            remaining -= length
        }
        let cursor = selectedTextRange.map { offset(from: beginningOfDocument, to: $0.start) }
        setTextFromRenderer(bounded)
        if let position = position(from: beginningOfDocument, offset: min(cursor ?? bounded.utf16.count, bounded.utf16.count)) {
            selectedTextRange = textRange(from: position, to: position)
        }
        sendActions(for: .editingChanged)
    }

    override func insertText(_ text: String) {
        guard isInputEditable else { return }
        if text.count > 0 {
            let key = text == "\n" ? "Enter" : String(text.prefix(Self.maxKeyBytes))
            if !key.isEmpty {
                onKeyPress?(key)
            }
        }
        super.insertText(text)
    }

    func textField(_ textField: UITextField, shouldChangeCharactersIn range: NSRange, replacementString string: String) -> Bool {
        guard isInputEditable else { return false }
        if let limit = maximumLength, textField.markedTextRange == nil,
           let current = textField.text, let editRange = Range(range, in: current) {
            let retainedLength = current.utf16.count - range.length
            let available = max(0, limit - retainedLength)
            if string.utf16.count > available {
                var accepted = ""
                var remaining = available
                for character in string {
                    let length = String(character).utf16.count
                    guard length <= remaining else { break }
                    accepted.append(character)
                    remaining -= length
                }
                guard !accepted.isEmpty else { return false }
                let proposed = current.replacingCharacters(in: editRange, with: accepted)
                setTextFromRenderer(formatInput(proposed))
                if let cursor = position(from: beginningOfDocument, offset: range.location + accepted.utf16.count) {
                    selectedTextRange = textRange(from: cursor, to: cursor)
                }
                sendActions(for: .editingChanged)
                return false
            }
        }
        if string.isEmpty && range.length > 0 {
            onKeyPress?("Backspace")
        } else if !string.isEmpty {
            if string == "\n" {
                onKeyPress?("Enter")
            } else {
                let key = String(string.prefix(Self.maxKeyBytes))
                if !key.isEmpty {
                    onKeyPress?(key)
                }
            }
        }
        guard inputFormat != 1,
              let current = textField.text,
              let swiftRange = Range(range, in: current) else { return true }
        var proposed = current.replacingCharacters(in: swiftRange, with: string)
        if string.isEmpty,
           proposed.filter(\.isNumber).count == current.filter(\.isNumber).count {
            proposed = removingDigit(beforeUTF16Offset: range.location, from: proposed)
        }
        let formatted = formatInput(proposed)
        setTextFromRenderer(formatted)
        let cursor = cursorAfterDigits(
            in: formatted,
            count: proposed.prefix(range.location + string.utf16.count).filter(\.isNumber).count
        )
        if let position = position(from: beginningOfDocument, offset: cursor) {
            selectedTextRange = textRange(from: position, to: position)
        }
        sendActions(for: .editingChanged)
        return false
    }

    private func formatInput(_ value: String) -> String {
        switch inputFormat {
        case 2: return formatPattern(value)
        case 3: return formatCurrency(value)
        default: return value
        }
    }

    private func formatPattern(_ value: String) -> String {
        let digits = value.filter(\.isNumber)
        guard !digits.isEmpty, !inputFormatPattern.isEmpty else {
            return digits.isEmpty ? "" : value
        }
        var result = ""
        var index = digits.startIndex
        for character in inputFormatPattern {
            if character == inputFormatPlaceholder {
                guard index < digits.endIndex else { break }
                result.append(digits[index])
                index = digits.index(after: index)
            } else if index == digits.startIndex || index < digits.endIndex {
                result.append(character)
            }
        }
        return result
    }

    private func formatCurrency(_ value: String) -> String {
        let digits = String(value.filter(\.isNumber).prefix(36))
        guard !digits.isEmpty, let integer = Decimal(string: digits) else { return "" }
        var divisor = Decimal(1)
        for _ in 0..<inputFormatDecimalDigits { divisor *= 10 }
        let formatter = NumberFormatter()
        formatter.locale = inputFormatLocale ?? .current
        formatter.numberStyle = .decimal
        formatter.usesGroupingSeparator = true
        formatter.minimumFractionDigits = inputFormatDecimalDigits
        formatter.maximumFractionDigits = inputFormatDecimalDigits
        return inputFormatPrefix + (formatter.string(from: NSDecimalNumber(decimal: integer / divisor)) ?? digits) + inputFormatSuffix
    }

    private func cursorAfterDigits(in value: String, count: Int) -> Int {
        guard count > 0 else { return 0 }
        var seen = 0
        for (offset, character) in value.enumerated() where character.isNumber {
            seen += 1
            if seen >= count { return offset + 1 }
        }
        return value.count
    }

    private func removingDigit(beforeUTF16Offset offset: Int, from value: String) -> String {
        var characters = Array(value)
        let prefix = String(
            decoding: value.utf16.prefix(max(0, offset)),
            as: UTF16.self
        )
        var digit = prefix.filter(\.isNumber).count - 1
        guard digit >= 0 else { return value }
        for index in characters.indices where characters[index].isNumber {
            if digit == 0 {
                characters.remove(at: index)
                return String(characters)
            }
            digit -= 1
        }
        return value
    }

    func textFieldDidChangeSelection(_ textField: UITextField) {
        guard textField === self else { return }
        let rangeStart = offset(from: beginningOfDocument, to: selectedTextRange?.start ?? beginningOfDocument)
        let rangeEnd = offset(from: beginningOfDocument, to: selectedTextRange?.end ?? beginningOfDocument)
        onSelectionChange?(rangeStart, rangeEnd)
        scheduleContentSizeUpdate()
    }

    func textFieldDidEndEditing(_ textField: UITextField) {
        enforceCommittedLength()
        onInputEndEditing?(text ?? "")
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        guard isInputEditable, submitBehavior != .newline else { return false }
        enforceCommittedLength()
        sendActions(for: .primaryActionTriggered)
        if submitBehavior == .blurAndSubmit { resignFirstResponder() }
        // We dispatched explicitly; UIKit must not dispatch another action.
        return false
    }

    func syncFontCache() {
        if let fieldFont = font {
            cachedFont = fieldFont
        } else {
            cachedFont = .systemFont(ofSize: UIFont.systemFontSize)
        }
    }

    func scheduleContentSizeUpdate() {
        guard !contentSizeScheduled else {
            return
        }
        contentSizeScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.contentSizeScheduled = false
            let rect = self.textRect(forBounds: self.bounds)
            let leftWidth = self.leftView?.frame.width ?? 0
            let rightWidth = self.rightView?.frame.width ?? 0
            let width = Int((rect.width + leftWidth + rightWidth).rounded())
            let height = Int((cachedFont.lineHeight + (rect.height > 0 ? rect.height : self.bounds.height)).rounded())
            if width == self.lastContentWidth && height == self.lastContentHeight {
                return
            }
            self.lastContentWidth = max(0, width)
            self.lastContentHeight = max(0, height)
            self.onContentSizeChange?(self.lastContentWidth, self.lastContentHeight)
        }
    }
}

/// `Input::multiline()` host: a UITextView with the same renderer contract
/// as `PamInputField` (controlled value, echo reconciliation, `maxLength`,
/// key/selection/content-size callbacks, `autoFocus`) plus the multiline
/// traits (`minLines`, `textAlignVertical`, `scrollEnabled`). Without an
/// authored height it grows with its content like a React Native
/// multiline TextInput.
final class PamMultilineInputField: UITextView, UITextViewDelegate, PamTextInputHost {
    var submitBehavior: PamInputSubmitBehavior = .newline
    var maximumLength: Int?
    var isInputEditable = true
    var inputText: String { text ?? "" }
    var caretHidden = false {
        didSet { if caretHidden != oldValue { setNeedsLayout() } }
    }
    var contextMenuHidden = false
    var selectTextOnFocus = false
    var verticalAlignment: PamInputVerticalAlignment = .auto {
        didSet { if verticalAlignment != oldValue { setNeedsLayout() } }
    }
    /// Authored padding and border widths, added to the vertical-alignment
    /// container inset (Android leaf padding).
    var contentInsets: UIEdgeInsets = .zero {
        didSet { if contentInsets != oldValue { setNeedsLayout() } }
    }
    /// `minLines`: the smallest number of text lines the box keeps room for.
    var minimumLines = 1 {
        didSet { if minimumLines != oldValue { setNeedsLayout() } }
    }
    /// No authored `height`: the frame follows the content height.
    var growsWithContent = true {
        didSet { if growsWithContent != oldValue { setNeedsLayout() } }
    }
    var autoFocusRequested = false {
        didSet {
            if !autoFocusRequested { automaticFocusApplied = false }
            scheduleAutomaticFocus()
        }
    }
    var placeholderText: String? {
        didSet {
            placeholderLabel.text = placeholderText
            refreshPlaceholder()
        }
    }
    var placeholderColor: UIColor? {
        didSet { refreshPlaceholder() }
    }

    /// Event hooks consumed by the renderer's `EventBridge`.
    var onTextChanged: ((String) -> Void)?
    var onEditingBegan: (() -> Void)?
    var onEditingEnded: (() -> Void)?
    var onSubmit: (() -> Void)?
    /// Blur or submit: a coalesced change (`syncMode`) must be flushed first.
    var onSyncFlush: (() -> Void)?
    var onSelectionChange: ((Int, Int) -> Void)?
    var onContentSizeChange: ((Int, Int) -> Void)?
    var onKeyPress: ((String) -> Void)?
    var onInputEndEditing: ((String) -> Void)?

    let placeholderLabel = UILabel()

    private static let maxKeyBytes = 64
    private var automaticFocusApplied = false
    private var automaticFocusScheduled = false
    private var contentSizeScheduled = false
    private var lastContentWidth = -1
    private var lastContentHeight = -1
    private var syncingText = false
    private var growing = false
    private var cachedFont: UIFont = .systemFont(ofSize: UIFont.systemFontSize)
    private var inFlightValues: [(String, TimeInterval)] = []

    override init(frame: CGRect, textContainer: NSTextContainer?) {
        super.init(frame: frame, textContainer: textContainer)
        configure()
    }

    convenience init() {
        self.init(frame: .zero, textContainer: nil)
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    private func configure() {
        delegate = self
        backgroundColor = .clear
        // Same text origin as the single-line field (no UIKit padding).
        textContainerInset = .zero
        textContainer.lineFragmentPadding = 0
        isScrollEnabled = true
        alwaysBounceVertical = false
        showsHorizontalScrollIndicator = false
        translatesAutoresizingMaskIntoConstraints = true
        font = .systemFont(ofSize: UIFont.systemFontSize)
        placeholderLabel.numberOfLines = 0
        placeholderLabel.isUserInteractionEnabled = false
        placeholderLabel.backgroundColor = .clear
        placeholderLabel.isHidden = true
        addSubview(placeholderLabel)
        syncFontCache()
        refreshPlaceholder()
    }

    // MARK: Automatic focus

    func requestAutomaticFocus() {
        scheduleAutomaticFocus()
    }

    private func scheduleAutomaticFocus() {
        guard autoFocusRequested, !automaticFocusApplied, !automaticFocusScheduled else { return }
        automaticFocusScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.automaticFocusScheduled = false
            guard self.autoFocusRequested, !self.automaticFocusApplied,
                  self.window != nil, self.isEditable else { return }
            var ancestor: UIView? = self
            while let view = ancestor {
                guard !view.isHidden, view.alpha > 0.01, view.isUserInteractionEnabled else { return }
                ancestor = view.superview
            }
            self.automaticFocusApplied = self.becomeFirstResponder()
        }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        scheduleAutomaticFocus()
    }

    // MARK: Controlled value

    override var text: String! {
        didSet {
            if !syncingText { syncFontCache() }
            refreshPlaceholder()
            scheduleContentSizeUpdate()
            setNeedsLayout()
        }
    }

    override var font: UIFont? {
        didSet {
            syncFontCache()
            placeholderLabel.font = font
            setNeedsLayout()
        }
    }

    override var textAlignment: NSTextAlignment {
        didSet { placeholderLabel.textAlignment = textAlignment }
    }

    func setTextFromRenderer(_ value: String) {
        syncingText = true
        text = value
        syncingText = false
        syncFontCache()
        scheduleContentSizeUpdate()
    }

    func setFormattedTextFromRenderer(_ value: String) {
        let current = text ?? ""
        // A controlled value rendered from an older change event must not
        // overwrite newer keyboard text (React Native's mostRecentEventCount).
        if isFirstResponder && PamInputEcho.isStale(
            &inFlightValues,
            value: value,
            current: current,
            now: ProcessInfo.processInfo.systemUptime
        ) {
            return
        }
        if value == current { return }
        setTextFromRenderer(value)
    }

    func recordDispatchedValue(_ value: String) {
        PamInputEcho.record(&inFlightValues, value: value, now: ProcessInfo.processInfo.systemUptime)
    }

    /// Masks and currency formats are single-line concerns (Android applies
    /// them to any EditText but the engine only authors them on one-line
    /// inputs); a multiline box keeps the raw text.
    func configureInputFormat(
        format: Int,
        pattern: String,
        placeholder: String,
        prefix: String,
        suffix: String,
        decimalDigits: Int,
        locale: String
    ) {}

    func setInputCallbacks(
        selection: ((Int, Int) -> Void)?,
        contentSize: ((Int, Int) -> Void)?,
        key: ((String) -> Void)?
    ) {
        onSelectionChange = selection
        onContentSizeChange = contentSize
        onKeyPress = key
        if contentSize != nil {
            scheduleContentSizeUpdate()
        }
    }

    var suppressTextChangeEvents: Bool {
        syncingText
    }

    func syncFontCache() {
        cachedFont = font ?? .systemFont(ofSize: UIFont.systemFontSize)
    }

    // MARK: Placeholder

    private func refreshPlaceholder() {
        placeholderLabel.textColor = placeholderColor ?? .placeholderText
        placeholderLabel.font = font ?? cachedFont
        placeholderLabel.textAlignment = textAlignment
        let empty = (text ?? "").isEmpty
        placeholderLabel.isHidden = !empty || (placeholderText ?? "").isEmpty
        if !placeholderLabel.isHidden { setNeedsLayout() }
    }

    private func layoutPlaceholder() {
        guard !placeholderLabel.isHidden else { return }
        let inset = textContainerInset
        let width = max(0, bounds.width - inset.left - inset.right)
        let height = placeholderLabel.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
        placeholderLabel.frame = CGRect(x: inset.left, y: inset.top, width: width, height: height)
    }

    // MARK: Layout (growth, minLines, vertical alignment)

    /// Text height for the current width, without the container insets.
    private func textHeight() -> CGFloat {
        let width = bounds.width > 0 ? bounds.width : (window?.bounds.width ?? 320)
        let fitted = sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
        return max(0, fitted - textContainerInset.top - textContainerInset.bottom)
    }

    /// Height the box wants: `max(minLines, content)` lines (RN auto-grow).
    func preferredHeight() -> CGFloat {
        let minimum = CGFloat(max(1, minimumLines)) * cachedFont.lineHeight
        return max(minimum, textHeight()).rounded(.up)
    }

    private func applyVerticalAlignment() {
        let free = max(0, bounds.height - contentInsets.top - contentInsets.bottom - textHeight())
        let top: CGFloat = switch verticalAlignment {
        case .center: (free / 2).rounded(.down)
        case .bottom: free.rounded(.down)
        case .auto, .top: 0
        }
        let inset = UIEdgeInsets(
            top: contentInsets.top + top,
            left: contentInsets.left,
            bottom: contentInsets.bottom,
            right: contentInsets.right
        )
        if textContainerInset != inset { textContainerInset = inset }
    }

    private func growIfNeeded() {
        guard growsWithContent, !growing, bounds.width > 0 else { return }
        let target = preferredHeight()
        guard target > bounds.height + 0.5 else { return }
        growing = true
        var frame = self.frame
        frame.size.height = target
        self.frame = frame
        growing = false
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        syncFontCache()
        growIfNeeded()
        applyVerticalAlignment()
        layoutPlaceholder()
        scheduleContentSizeUpdate()
        scheduleAutomaticFocus()
    }

    func scheduleContentSizeUpdate() {
        guard !contentSizeScheduled else { return }
        contentSizeScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.contentSizeScheduled = false
            let width = Int(self.bounds.width.rounded())
            let height = Int(self.preferredHeight().rounded())
            if width == self.lastContentWidth && height == self.lastContentHeight {
                return
            }
            self.lastContentWidth = max(0, width)
            self.lastContentHeight = max(0, height)
            self.onContentSizeChange?(self.lastContentWidth, self.lastContentHeight)
        }
    }

    // MARK: Caret / context menu

    override func caretRect(for position: UITextPosition) -> CGRect {
        caretHidden ? .zero : super.caretRect(for: position)
    }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        contextMenuHidden ? false : super.canPerformAction(action, withSender: sender)
    }

    // MARK: Editing

    override func deleteBackward() {
        guard isInputEditable else { return }
        onKeyPress?("Backspace")
        super.deleteBackward()
    }

    override func insertText(_ text: String) {
        guard isInputEditable else { return }
        super.insertText(text)
    }

    override func setMarkedText(_ markedText: String?, selectedRange: NSRange) {
        guard isInputEditable else { return }
        super.setMarkedText(markedText, selectedRange: selectedRange)
    }

    override func unmarkText() {
        super.unmarkText()
        enforceCommittedLength()
    }

    private func enforceCommittedLength() {
        guard isInputEditable, markedTextRange == nil, let limit = maximumLength,
              let current = text, current.utf16.count > limit else { return }
        var bounded = ""
        var remaining = limit
        for character in current {
            let length = String(character).utf16.count
            guard length <= remaining else { break }
            bounded.append(character)
            remaining -= length
        }
        let cursor = selectedTextRange.map { offset(from: beginningOfDocument, to: $0.start) }
        setTextFromRenderer(bounded)
        if let position = position(from: beginningOfDocument, offset: min(cursor ?? bounded.utf16.count, bounded.utf16.count)) {
            selectedTextRange = textRange(from: position, to: position)
        }
        notifyTextChanged()
    }

    private func notifyTextChanged() {
        refreshPlaceholder()
        scheduleContentSizeUpdate()
        setNeedsLayout()
        guard !syncingText else { return }
        onTextChanged?(text ?? "")
    }

    func textView(_ textView: UITextView, shouldChangeTextIn range: NSRange, replacementText replacement: String) -> Bool {
        guard isInputEditable else { return false }
        if replacement == "\n", submitBehavior != .newline {
            onKeyPress?("Enter")
            enforceCommittedLength()
            onSyncFlush?()
            onSubmit?()
            if submitBehavior == .blurAndSubmit { resignFirstResponder() }
            return false
        }
        if let limit = maximumLength, textView.markedTextRange == nil,
           let current = textView.text, let editRange = Range(range, in: current) {
            let retainedLength = current.utf16.count - range.length
            let available = max(0, limit - retainedLength)
            if replacement.utf16.count > available {
                var accepted = ""
                var remaining = available
                for character in replacement {
                    let length = String(character).utf16.count
                    guard length <= remaining else { break }
                    accepted.append(character)
                    remaining -= length
                }
                guard !accepted.isEmpty else { return false }
                setTextFromRenderer(current.replacingCharacters(in: editRange, with: accepted))
                if let cursor = position(from: beginningOfDocument, offset: range.location + accepted.utf16.count) {
                    selectedTextRange = textRange(from: cursor, to: cursor)
                }
                notifyTextChanged()
                return false
            }
        }
        if replacement.isEmpty && range.length > 0 {
            onKeyPress?("Backspace")
        } else if !replacement.isEmpty {
            if replacement == "\n" {
                onKeyPress?("Enter")
            } else {
                let key = String(replacement.prefix(Self.maxKeyBytes))
                if !key.isEmpty {
                    onKeyPress?(key)
                }
            }
        }
        return true
    }

    func textViewDidChange(_ textView: UITextView) {
        guard textView === self else { return }
        if markedTextRange == nil, let limit = maximumLength, (text ?? "").utf16.count > limit {
            // `enforceCommittedLength` re-dispatches the bounded text.
            enforceCommittedLength()
            return
        }
        notifyTextChanged()
    }

    func textViewDidChangeSelection(_ textView: UITextView) {
        guard textView === self else { return }
        let rangeStart = offset(from: beginningOfDocument, to: selectedTextRange?.start ?? beginningOfDocument)
        let rangeEnd = offset(from: beginningOfDocument, to: selectedTextRange?.end ?? beginningOfDocument)
        onSelectionChange?(rangeStart, rangeEnd)
    }

    func textViewDidBeginEditing(_ textView: UITextView) {
        guard textView === self else { return }
        onEditingBegan?()
        guard selectTextOnFocus else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self, self.isFirstResponder else { return }
            self.selectedTextRange = self.textRange(from: self.beginningOfDocument, to: self.endOfDocument)
        }
    }

    func textViewDidEndEditing(_ textView: UITextView) {
        guard textView === self else { return }
        enforceCommittedLength()
        onSyncFlush?()
        onInputEndEditing?(text ?? "")
        onEditingEnded?()
    }
}

final class PamDrawerLayout: UIView, UIGestureRecognizerDelegate {
    private let contentHost = UIView()
    private let overlayView = UIView()
    private let drawerHost = UIView()
    private var open = false
    private var drawerType = 1
    private var drawerPosition = 1
    private var preferredDrawerWidth: CGFloat = 256
    private var swipeEnabled = true
    private var swipeEdgeWidth: CGFloat = 32
    private var swipeMinDistance: CGFloat = 56
    private var keyboardDismissMode = 1
    private var permanentBreakpoint: CGFloat = 840
    private var hideStatusBarOnOpen = false
    private var statusBarAnimation = 1
    private var downX: CGFloat = 0
    private var gestureStartProgress: CGFloat = 0
    private var gestureInProgress = false

    private var onOpen: (() -> Void)?
    private var onClose: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = false
        addSubview(contentHost)
        overlayView.backgroundColor = UIColor(argb: 0x33000000)
        overlayView.alpha = 0
        overlayView.isUserInteractionEnabled = false
        addSubview(overlayView)
        addSubview(drawerHost)
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        pan.cancelsTouchesInView = false
        pan.delegate = self
        addGestureRecognizer(pan)
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        tap.cancelsTouchesInView = false
        addGestureRecognizer(tap)
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    func insert(_ view: UIView, index: Int) {
        if index <= 0 {
            if view.superview != contentHost {
                contentHost.addSubview(view)
            }
        } else {
            if view.superview != drawerHost {
                drawerHost.addSubview(view)
            }
        }
        setNeedsLayout()
        layoutIfNeeded()
        updateDrawer(animated: false)
    }

    func setOpen(_ value: Bool, animated: Bool = true) {
        guard open != value else {
            updateDrawer(animated: animated)
            return
        }
        open = value
        updateDrawer(animated: animated)
        if open {
            onOpen?()
        } else {
            onClose?()
        }
    }

    func setCallbacks(opened: (() -> Void)?, closed: (() -> Void)?) {
        onOpen = opened
        onClose = closed
    }

    func setDrawerType(_ value: Int) {
        drawerType = min(4, max(1, value))
        setNeedsLayout()
    }

    func setDrawerPosition(_ value: Int) {
        drawerPosition = min(3, max(1, value))
        setNeedsLayout()
    }

    func setDrawerWidth(_ value: CGFloat) {
        preferredDrawerWidth = min(640, max(200, value))
        setNeedsLayout()
    }

    func setOverlayColor(_ value: Int) {
        overlayView.backgroundColor = UIColor(argb: Int64(value))
    }

    func setSwipeEnabled(_ value: Bool) {
        swipeEnabled = value
    }

    func setSwipeEdgeWidth(_ value: CGFloat) {
        swipeEdgeWidth = min(256, max(0, value))
    }

    func setSwipeMinDistance(_ value: CGFloat) {
        swipeMinDistance = min(512, max(1, value))
    }

    func setKeyboardDismissMode(_ value: Int) {
        keyboardDismissMode = min(2, max(1, value))
    }

    func setPermanentBreakpoint(_ value: CGFloat) {
        permanentBreakpoint = max(0, value)
        setNeedsLayout()
    }

    func setHideStatusBarOnOpen(_ value: Bool) {
        hideStatusBarOnOpen = value
    }

    func setStatusBarAnimation(_ value: Int) {
        statusBarAnimation = min(3, max(1, value))
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let type = resolvedType()
        applyProgress(type == 4 || open ? 1 : 0)
    }

    @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
        guard gesture.state == .ended else { return }
        guard open && resolvedType() != 4 else { return }
        let point = gesture.location(in: self)
        if !drawerHost.frame.contains(point) {
            setOpen(false, animated: true)
        }
    }

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        guard swipeEnabled && resolvedType() != 4 else { return }
        switch gesture.state {
        case .began:
            downX = gesture.location(in: self).x
            gestureStartProgress = open ? 1 : 0
            let edgeEligible = isRight
                ? downX >= bounds.width - swipeEdgeWidth
                : downX <= swipeEdgeWidth
            gestureInProgress = open || edgeEligible
            if gestureInProgress && keyboardDismissMode == 1 {
                endEditing(true)
            }
        case .changed:
            guard gestureInProgress else { return }
            let point = gesture.location(in: self)
            let deltaX = point.x - downX
            let directed = isRight ? -deltaX : deltaX
            let width = max(1, min(bounds.width, preferredDrawerWidth))
            applyProgress(max(0, min(1, gestureStartProgress + directed / width)))
        case .ended, .cancelled:
            let translation = gesture.translation(in: self).x
            gestureInProgress = false
            let directed = isRight ? -translation : translation
            if open {
                if directed <= -swipeMinDistance {
                    setOpen(false, animated: true)
                } else {
                    setOpen(true, animated: true)
                }
            } else {
                if directed >= swipeMinDistance {
                    setOpen(true, animated: true)
                } else {
                    setOpen(false, animated: true)
                }
            }
            gestureInProgress = false
        default:
            break
        }
    }

    override func gestureRecognizerShouldBegin(
        _ gestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        guard swipeEnabled && resolvedType() != 4 else { return false }
        guard let pan = gestureRecognizer as? UIPanGestureRecognizer else {
            return true
        }
        let point = pan.location(in: self)
        if open && drawerHost.frame.contains(point) {
            return false
        }
        if !open {
            let edgeEligible = isRight
                ? point.x >= bounds.width - swipeEdgeWidth
                : point.x <= swipeEdgeWidth
            guard edgeEligible else { return false }
        }
        let velocity = pan.velocity(in: self)
        return abs(velocity.x) > abs(velocity.y)
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        gestureRecognizer is UIPanGestureRecognizer
            || otherGestureRecognizer is UIPanGestureRecognizer
    }

    private func updateDrawer(animated: Bool) {
        if resolvedType() == 4 {
            open = true
        }
        guard animated, !PamMotionPolicy.isReduced else {
            setNeedsLayout()
            layoutIfNeeded()
            return
        }
        UIView.animate(
            withDuration: 0.20,
            delay: 0,
            options: .curveEaseOut,
        ) {
            self.setNeedsLayout()
            self.layoutIfNeeded()
        }
    }

    private func applyProgress(_ rawProgress: CGFloat) {
        let type = resolvedType()
        let progress = max(0, min(1, rawProgress))
        let width = min(bounds.width, preferredDrawerWidth)
        let direction: CGFloat = isRight ? -1 : 1
        let openX = isRight ? bounds.width - width : 0
        let closedX = isRight ? bounds.width : -width
        let drawerStart: CGFloat
        switch type {
        case 2, 4:
            drawerStart = openX
        case 3:
            drawerStart = openX + (closedX - openX) * 0.35
        default:
            drawerStart = closedX
        }
        let drawerX = drawerStart + (openX - drawerStart) * progress
        drawerHost.frame = CGRect(
            x: drawerX,
            y: 0,
            width: width,
            height: bounds.height
        )
        let contentOffset = type == 4
            ? direction * width
            : ((type == 2 || type == 3) ? direction * width * progress : 0)
        contentHost.frame = bounds.offsetBy(dx: contentOffset, dy: 0)
        overlayView.frame = bounds
        overlayView.alpha = type == 4 ? 0 : progress
        overlayView.isUserInteractionEnabled = progress > 0
    }

    private var isRight: Bool {
        drawerPosition == 3 ||
            (drawerPosition == 1 && effectiveUserInterfaceLayoutDirection == .rightToLeft)
    }

    private func resolvedType() -> Int {
        permanentBreakpoint > 0 && bounds.width >= permanentBreakpoint ? 4 : drawerType
    }
}

/// RN `RefreshControl` on iOS: a native `UIRefreshControl` installed on the
/// hosted scroll view (ScrollView or list). `alwaysBounceVertical` keeps a
/// short or empty list pullable. `refreshing` is controlled: after a pull the
/// handler's value wins, so `refreshing=false` ends the spinner even when the
/// handler never flipped it (RN re-applies the controlled value).
final class PamRefreshContainer: UIView {
    private enum Sync {
        static let controlledDelay: TimeInterval = 0.6
    }

    private let contentHost = UIView()
    private let control = UIRefreshControl()
    private weak var scrollView: UIScrollView?
    private var refreshCallback: (() -> Void)?
    private var controlled = false
    private var userTriggered = false
    private var progressOffset: CGFloat = 0
    private(set) var colors: [Int] = []
    private var tint: Int?
    private var offsetObservation: NSKeyValueObservation?

    var isEnabled = true {
        didSet { installControl() }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        addSubview(contentHost)
        control.addTarget(self, action: #selector(onValueChanged), for: .valueChanged)
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        fatalError("init(coder:) has not been implemented")
    }

    func insert(_ view: UIView, index: Int) {
        contentHost.subviews.forEach { subview in
            if subview !== view {
                subview.removeFromSuperview()
            }
        }
        contentHost.addSubview(view)
        view.frame = bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        if let hostScroll = view as? UIScrollView {
            bind(scrollView: hostScroll)
        }
        setNeedsLayout()
    }

    func setRefreshing(_ value: Bool) {
        controlled = value
        userTriggered = false
        NSObject.cancelPreviousPerformRequests(withTarget: self, selector: #selector(syncControlled), object: nil)
        apply(value)
    }

    /// RN `colors` (Android); iOS uses the first one when no `tintColor`.
    func setColors(_ encoded: String?) {
        colors = encoded?
            .split(separator: ",")
            .compactMap { Int($0.trimmingCharacters(in: .whitespacesAndNewlines)) } ?? []
        applyTint()
    }

    /// RN `tintColor`: the iOS spinner color.
    func setTint(_ color: Int?) {
        tint = color
        applyTint()
    }

    private func applyTint() {
        control.tintColor = (tint ?? colors.first).map { UIColor(argb: Int64($0)) }
    }

    func setProgressBackgroundColor(_ color: Int?) {
        control.backgroundColor = color.map { UIColor(argb: Int64($0)) }
    }

    /// RN `progressViewOffset`: moves the spinner down, e.g. below a
    /// translucent header.
    func setProgressViewOffset(_ offset: Float) {
        progressOffset = CGFloat(offset)
        control.bounds = CGRect(
            x: control.bounds.origin.x,
            y: -progressOffset,
            width: control.bounds.width,
            height: control.bounds.height
        )
    }

    /// Android-only (`size`); UIRefreshControl has a single size.
    func setIndicatorSize(_ size: Int) {}

    func setOnRefresh(_ action: (() -> Void)?) {
        refreshCallback = action
        installControl()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        contentHost.frame = bounds
        contentHost.subviews.forEach { view in
            view.frame = bounds
        }
        stopIdleSpinner()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        // UIRefreshControl cannot begin refreshing off-window.
        if window != nil, controlled != control.isRefreshing {
            apply(controlled)
        }
        stopIdleSpinner()
    }

    /// UIKit's refresh control can leave its spinner rotating forever while
    /// it is hidden and not refreshing (seen after a list settles at the top
    /// or a refresh ends off screen). That infinite Core Animation keeps the
    /// render server drawing (battery) and the app never reports idle to UI
    /// tests. Outside a pull and a refresh, repeating animations of the
    /// control are dropped; UIKit adds them again on the next pull.
    func stopIdleSpinner() {
        guard !control.isRefreshing, let scrollView,
              !scrollView.isTracking, !scrollView.isDragging,
              scrollView.contentOffset.y >= -scrollView.adjustedContentInset.top - 0.5 else { return }
        Self.removeRepeatingAnimations(control.layer)
    }

    static func removeRepeatingAnimations(_ layer: CALayer) {
        for key in layer.animationKeys() ?? [] {
            guard let animation = layer.animation(forKey: key),
                  animation.repeatCount >= Float.greatestFiniteMagnitude || animation.repeatDuration >= .greatestFiniteMagnitude
            else { continue }
            layer.removeAnimation(forKey: key)
        }
        layer.sublayers?.forEach { removeRepeatingAnimations($0) }
    }

    private func bind(scrollView: UIScrollView) {
        guard self.scrollView !== scrollView else { return }
        if self.scrollView?.refreshControl === control {
            self.scrollView?.refreshControl = nil
        }
        self.scrollView = scrollView
        // A short or empty list must still be pullable.
        scrollView.alwaysBounceVertical = true
        // Scrolling settles a spinner left running (see `stopIdleSpinner`);
        // the check only walks the control's few layers.
        offsetObservation = scrollView.observe(\.contentOffset, options: []) { [weak self] _, _ in
            self?.stopIdleSpinner()
        }
        installControl()
    }

    private func installControl() {
        guard let scrollView else { return }
        let wanted = isEnabled && refreshCallback != nil
        if wanted, scrollView.refreshControl !== control {
            scrollView.refreshControl = control
            if controlled { apply(true) }
        } else if !wanted, scrollView.refreshControl === control, !control.isRefreshing {
            // RN `enabled=false`: no new pulls; a running refresh still ends
            // through `refreshing=false`.
            scrollView.refreshControl = nil
        }
    }

    private func apply(_ value: Bool) {
        if value, !control.isRefreshing {
            control.beginRefreshing()
            // RN: a programmatic refresh at the top reveals the spinner.
            if !userTriggered, let scrollView, scrollView.refreshControl === control,
               scrollView.contentOffset.y <= -scrollView.adjustedContentInset.top {
                scrollView.setContentOffset(
                    CGPoint(x: scrollView.contentOffset.x, y: scrollView.contentOffset.y - control.frame.height),
                    animated: true
                )
            }
        } else if !value, control.isRefreshing {
            control.endRefreshing()
            installControl()
            // endRefreshing animates the inset back; settle the spinner after it.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                self?.stopIdleSpinner()
            }
        }
    }

    @objc private func onValueChanged() {
        guard let refreshCallback else {
            control.endRefreshing()
            return
        }
        userTriggered = true
        refreshCallback()
        // A handler that leaves `refreshing` unchanged sends no update: fall
        // back to the controlled value after the round trip.
        NSObject.cancelPreviousPerformRequests(withTarget: self, selector: #selector(syncControlled), object: nil)
        perform(#selector(syncControlled), with: nil, afterDelay: Sync.controlledDelay)
    }

    @objc private func syncControlled() {
        guard userTriggered else { return }
        userTriggered = false
        apply(controlled)
    }
}

/// Full-window container of a modal's content. As a bottom sheet the content
/// only covers the snap height, so a touch on the empty part above it falls
/// through to the backdrop (tap to close, like the Android scrim and
/// gorhom's `pressBehavior="close"`) instead of being swallowed here.
final class PamModalContentClip: UIView {
    var passesEmptyTouches = false

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let hit = super.hitTest(point, with: event)
        return passesEmptyTouches && hit === self ? nil : hit
    }
}

/// Reports actual Auto Layout viewport changes once; no display link or polling.
private final class PamModalContentViewport: UIView {
    var onBoundsChange: (() -> Void)?
    private var previousSize = CGSize.zero

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.size != previousSize else { return }
        previousSize = bounds.size
        onBoundsChange?()
    }
}

final class PamModalHost: UIView, UIGestureRecognizerDelegate {
    private enum Presentation {
        static let dialog = 2
        static let sheet = 3
    }

    private enum Animation {
        static let none = 1
        static let slide = 2
        static let fade = 3
        static let slideFade = 4
    }

    /// `slide-fade` (RN sheet modals): the backdrop fades while the content
    /// slides from fully below the screen on its own curve.
    private enum SlideFade {
        static let enter: TimeInterval = 0.32
        static let exit: TimeInterval = 0.22
        static let backdropShare: TimeInterval = 0.7
    }

    private var slideFadeAnimator: UIViewPropertyAnimator?

    private enum Orientation {
        static let portrait = 1
        static let landscape = 2
    }

    private let backdropView = UIControl()
    private let contentHost = PamModalContentViewport()
    private let contentClip = PamModalContentClip()
    private let sheetHandle = UIView()
    private let orientationObserver = NotificationCenter.default
    private var contentHeightConstraint: NSLayoutConstraint!
    private var contentBottomConstraint: NSLayoutConstraint!
    private var keyboardTokens: [NSObjectProtocol] = []
    /// How far the sheet sits above its resting position so its bottom edge
    /// rests on the keyboard (@gorhom/bottom-sheet `interactive`).
    private var sheetKeyboardLift: CGFloat = 0
    /// Keyboard overlap (points from the modal's bottom edge) its content
    /// avoids itself (KeyboardAvoidingView resize/padding, laid out by the
    /// engine), with the keyboard's animation duration and curve.
    var onSurfaceKeyboardInset: ((CGFloat, TimeInterval, UInt) -> Void)?
    private var surfaceKeyboardInset: CGFloat = 0
    /// BottomSheetKeyboardBehavior.Contain (4): the sheet never moves; its
    /// content avoids the keyboard through the published surface inset.
    private var bottomSheetContainsKeyboard = false

    private var showScheduled = false
    private var desiredVisible = true
    private var currentlyVisible = false
    private var visibilityGeneration = 0
    private var presentation = Presentation.dialog
    private var animationType = Animation.none
    private var backdropColor = UIColor.black.withAlphaComponent(0.32)
    private var transparent = false
    private var allowSwipeDismissal = false
    private var bottomSheetSnapPoints: [CGFloat] = [0.5, 0.9]
    private var bottomSheetIndex = 0
    private var bottomSheetDismissible = true
    private var bottomSheetBackdropDismiss = true
    private var bottomSheetHandleVisible = true
    private var bottomSheetDragEnabled = true
    private var bottomSheetCornerRadius: CGFloat = 20
    private var onBottomSheetChange: ((Int, CGFloat) -> Void)?
    private var onBottomSheetDismiss: (() -> Void)?
    private var onRequestClose: (() -> Void)?
    private var onShow: (() -> Void)?
    private var onDismiss: (() -> Void)?
    private var onOrientationChange: ((Int) -> Void)?
    private var orientationToken: NSObjectProtocol?
    private var lastOrientation: Int?
    private weak var previousFocus: UIView?

    override var canBecomeFirstResponder: Bool {
        true
    }

    override var keyCommands: [UIKeyCommand]? {
        guard currentlyVisible else { return nil }
        return [
            UIKeyCommand(
                input: UIKeyCommand.inputEscape,
                modifierFlags: [],
                action: #selector(onEscape)
            ),
        ]
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isHidden = true
        clipsToBounds = false

        backdropView.backgroundColor = backdropColor
        backdropView.addTarget(self, action: #selector(onBackdropTap), for: .touchUpInside)
        backdropView.translatesAutoresizingMaskIntoConstraints = false
        contentClip.translatesAutoresizingMaskIntoConstraints = false
        contentClip.backgroundColor = .clear
        contentClip.addSubview(contentHost)
        contentHost.translatesAutoresizingMaskIntoConstraints = false
        sheetHandle.translatesAutoresizingMaskIntoConstraints = false
        sheetHandle.backgroundColor = UIColor.secondaryLabel.withAlphaComponent(0.45)
        sheetHandle.layer.cornerRadius = 2
        contentClip.addSubview(sheetHandle)

        addSubview(backdropView)
        addSubview(contentClip)

        let pan = UIPanGestureRecognizer(target: self, action: #selector(onModalPan(_:)))
        pan.maximumNumberOfTouches = 1
        pan.cancelsTouchesInView = false
        pan.delegate = self
        contentClip.addGestureRecognizer(pan)

        contentHeightConstraint = contentHost.heightAnchor.constraint(equalTo: contentClip.heightAnchor)
        contentBottomConstraint = contentHost.bottomAnchor.constraint(equalTo: contentClip.bottomAnchor)
        NSLayoutConstraint.activate([
            backdropView.leadingAnchor.constraint(equalTo: leadingAnchor),
            backdropView.trailingAnchor.constraint(equalTo: trailingAnchor),
            backdropView.topAnchor.constraint(equalTo: topAnchor),
            backdropView.bottomAnchor.constraint(equalTo: bottomAnchor),

            contentClip.leadingAnchor.constraint(equalTo: leadingAnchor),
            contentClip.trailingAnchor.constraint(equalTo: trailingAnchor),
            contentClip.topAnchor.constraint(equalTo: topAnchor),
            contentClip.bottomAnchor.constraint(equalTo: bottomAnchor),

            contentHost.leadingAnchor.constraint(equalTo: contentClip.leadingAnchor),
            contentHost.trailingAnchor.constraint(equalTo: contentClip.trailingAnchor),
            contentBottomConstraint,
            contentHeightConstraint,
            sheetHandle.widthAnchor.constraint(equalToConstant: 36),
            sheetHandle.heightAnchor.constraint(equalToConstant: 4),
            sheetHandle.centerXAnchor.constraint(equalTo: contentHost.centerXAnchor),
            sheetHandle.topAnchor.constraint(equalTo: contentHost.topAnchor, constant: 10),
        ])

        isUserInteractionEnabled = true
        observeKeyboard()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    deinit {
        if let token = orientationToken {
            orientationObserver.removeObserver(token)
        }
        for token in keyboardTokens {
            NotificationCenter.default.removeObserver(token)
        }
    }

    private func observeKeyboard() {
        let center = NotificationCenter.default
        keyboardTokens.append(center.addObserver(
            forName: UIResponder.keyboardWillChangeFrameNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            self?.sheetKeyboardChanged(notification, hiding: false)
        })
        keyboardTokens.append(center.addObserver(
            forName: UIResponder.keyboardWillHideNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            self?.sheetKeyboardChanged(notification, hiding: true)
        })
    }

    /// A presented sheet rides on the keyboard with the keyboard's own
    /// duration and curve, so its focused field and actions stay visible.
    private func sheetKeyboardChanged(_ notification: Notification, hiding: Bool) {
        guard let window else { return }
        let info = notification.userInfo
        let end = (info?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue ?? .zero
        let keyboard = window.convert(end, from: window.screen.coordinateSpace)
        let frame = convert(bounds, to: window)
        let isSheet = presentation == Presentation.sheet
        let containedSheet = isSheet && bottomSheetContainsKeyboard
        let overlap = hiding || !currentlyVisible ? 0
            : PamKeyboardInsetObserver.overlap(keyboard: keyboard, viewInWindow: frame)
        let lift = !isSheet || containedSheet ? 0 : Self.boundedSheetKeyboardLift(
            requested: overlap,
            availableHeight: max(1, bounds.height - safeAreaInsets.top),
            sheetHeight: sheetHeight
        )
        // A tall sheet cannot rise beyond the safe top. Its own keyboard
        // avoider consumes the remaining overlap; short sheets still ride
        // fully above the keyboard as before.
        publishSurfaceKeyboard(isSheet ? max(0, overlap - lift) : Self.surfaceKeyboardInset(
            keyboardInModal: convert(end, from: window.screen.coordinateSpace),
            modalBounds: bounds,
            hiding: hiding || !currentlyVisible
        ), info: info)
        guard abs(lift - sheetKeyboardLift) > 0.5 else { return }
        sheetKeyboardLift = lift
        contentBottomConstraint.constant = -lift
        let duration = (info?[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber)?.doubleValue ?? 0.25
        let curve = (info?[UIResponder.keyboardAnimationCurveUserInfoKey] as? NSNumber)?.uintValue ?? 7
        UIView.animate(
            withDuration: duration,
            delay: 0,
            options: [UIView.AnimationOptions(rawValue: curve << 16), .beginFromCurrentState, .allowUserInteraction]
        ) {
            self.layoutIfNeeded()
        }
    }

    static func boundedSheetKeyboardLift(requested: CGFloat, availableHeight: CGFloat, sheetHeight: CGFloat) -> CGFloat {
        min(max(0, requested), max(0, availableHeight - sheetHeight))
    }

    private func resetSheetKeyboardLift() {
        sheetKeyboardLift = 0
        contentBottomConstraint.constant = 0
        publishSurfaceKeyboard(0, info: nil)
    }

    /// The keyboard overlap of a full-screen or dialog modal in its own
    /// coordinate space: points between the keyboard's top edge and the
    /// modal's bottom edge. Sheets ride on the keyboard instead (0).
    static func surfaceKeyboardInset(keyboardInModal: CGRect, modalBounds: CGRect, hiding: Bool) -> CGFloat {
        guard !hiding, !keyboardInModal.isEmpty, keyboardInModal.minY < modalBounds.maxY else { return 0 }
        return max(0, modalBounds.maxY - max(keyboardInModal.minY, modalBounds.minY))
    }

    private func publishSurfaceKeyboard(_ inset: CGFloat, info: [AnyHashable: Any]?) {
        guard abs(inset - surfaceKeyboardInset) > 0.5 else { return }
        surfaceKeyboardInset = inset
        let duration = (info?[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber)?.doubleValue ?? 0
        let curve = (info?[UIResponder.keyboardAnimationCurveUserInfoKey] as? NSNumber)?.uintValue ?? 7
        onSurfaceKeyboardInset?(inset, duration, curve)
    }

    /// Sheet geometry belongs to its native detent, never the engine window frame.
    var sheetContentBounds: CGRect? {
        presentation == Presentation.sheet ? contentHost.bounds : nil
    }

    var onSheetViewportChange: (() -> Void)? {
        didSet {
            contentHost.onBoundsChange = { [weak self] in
                guard let self, self.presentation == Presentation.sheet else { return }
                self.onSheetViewportChange?()
            }
        }
    }

    func insert(_ view: UIView, index _: Int) {
        if view.superview !== contentHost {
            contentHost.subviews.forEach { $0.removeFromSuperview() }
            contentHost.addSubview(view)
        }
        view.frame = contentHost.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        updatePresentationLayout()
    }

    func setVisible(_ value: Bool) {
        // A backdrop tap already hid the modal before the app answered its
        // close request with `visible = false`: repeating the hide must not
        // restart (and then undo) the dismissal that is running.
        if !value && !desiredVisible { return }
        visibilityGeneration += 1
        let changed = desiredVisible != value
        desiredVisible = value
        scheduleUpdate()
        if changed {
            PamStatusBarCoordinator.invalidate()
        }
    }

    /// Open or opening: its content's StatusBar is on the stack (a closing
    /// modal hands the bar back as its dismissal starts, like RN).
    var showsContent: Bool {
        desiredVisible
    }

    func setPresentation(_ value: Int) {
        presentation = value
        updatePresentationLayout()
    }

    func setAnimationType(_ value: Int) {
        animationType = value
    }

    func setBackdropColor(_ color: Int) {
        backdropColor = UIColor(argb: Int64(truncatingIfNeeded: UInt32(truncatingIfNeeded: color)))
        applyBackdropColor()
    }

    func setTransparent(_ value: Bool) {
        transparent = value
        applyBackdropColor()
    }

    func setAllowSwipeDismissal(_ value: Bool) {
        allowSwipeDismissal = value
    }

    func setBottomSheetSnapPoints(_ values: [CGFloat]) {
        guard !values.isEmpty else { return }
        bottomSheetSnapPoints = Array(values.map { min(max($0, 0.05), 1) }.sorted().prefix(16))
        bottomSheetIndex = min(bottomSheetIndex, bottomSheetSnapPoints.count - 1)
        updatePresentationLayout()
    }

    func setBottomSheetIndex(_ value: Int) {
        bottomSheetIndex = min(max(value, 0), bottomSheetSnapPoints.count - 1)
        updatePresentationLayout()
    }

    func setBottomSheetDismissible(_ value: Bool) { bottomSheetDismissible = value }
    func setBottomSheetBackdropDismiss(_ value: Bool) { bottomSheetBackdropDismiss = value }
    func setBottomSheetDragEnabled(_ value: Bool) { bottomSheetDragEnabled = value }
    /// Interactive, Extend and FillParent keep the sheet above the keyboard on
    /// iOS; Contain (4) keeps it in place and lets its content avoid the IME.
    func setBottomSheetKeyboardBehavior(_ value: Int) {
        bottomSheetContainsKeyboard = value == 4
    }

    /// Whether the keyboard belongs to an input inside a presented modal or
    /// sheet: the screen below must not avoid that keyboard.
    static func hostsFirstResponder(in window: UIWindow) -> Bool {
        var view = window.pamFirstResponder()
        while let current = view {
            if let host = current as? PamModalHost { return host.currentlyVisible }
            view = current.superview
        }
        return false
    }

    func setBottomSheetHandleVisible(_ value: Bool) {
        bottomSheetHandleVisible = value
        updatePresentationLayout()
    }

    func setBottomSheetCornerRadius(_ value: CGFloat) {
        bottomSheetCornerRadius = min(max(value, 0), 128)
        updatePresentationLayout()
    }

    func setBottomSheetCallbacks(
        onChange: ((Int, CGFloat) -> Void)?,
        onDismiss: (() -> Void)?,
    ) {
        onBottomSheetChange = onChange
        onBottomSheetDismiss = onDismiss
    }

    func setHardwareAccelerated(_: Bool) {}
    func setNavigationBarTranslucent(_: Bool) {}
    func setStatusBarTranslucent(_: Bool) {}

    func setCallbacks(
        onRequestClose: (() -> Void)?,
        onShow: (() -> Void)?,
        onDismiss: (() -> Void)?,
        onOrientationChange: ((Int) -> Void)?,
    ) {
        self.onRequestClose = onRequestClose
        self.onShow = onShow
        self.onDismiss = onDismiss
        self.onOrientationChange = onOrientationChange
        if onOrientationChange != nil {
            dispatchOrientation(force: currentlyVisible)
        }
    }

    private func scheduleUpdate() {
        guard !showScheduled else { return }
        showScheduled = true
        DispatchQueue.main.async { [weak self] in
            self?.showScheduled = false
            self?.applyVisibility()
        }
    }

    private func applyVisibility() {
        if desiredVisible {
            present()
        } else {
            dismiss(notify: true)
        }
    }

    private func present() {
        backdropView.layer.removeAllAnimations()
        contentClip.layer.removeAllAnimations()
        if currentlyVisible {
            isHidden = false
            accessibilityViewIsModal = true
            backdropView.alpha = 1
            contentClip.alpha = 1
            contentClip.transform = .identity
            return
        }
        previousFocus = window?.pamFirstResponder()
        // The newest presented modal draws above earlier ones and the routes.
        superview?.bringSubviewToFront(self)
        isHidden = false
        currentlyVisible = true
        accessibilityViewIsModal = true
        backdropView.alpha = 0
        contentClip.alpha = 0
        contentClip.transform = presentation == Presentation.sheet ? CGAffineTransform(translationX: 0, y: bounds.height * 0.25) : .identity
        applyBackdropColor()
        let animationDuration: TimeInterval = 0.225
        let animated = !PamMotionPolicy.isReduced && animationType != Animation.none

        if presentation == Presentation.sheet && animated {
            // @gorhom/bottom-sheet: the backdrop fades while the sheet (and its
            // handle) slides by its own height, independently.
            layoutIfNeeded()
            contentClip.alpha = 1
            contentClip.transform = .identity
            let distance = sheetTravel
            contentHost.transform = CGAffineTransform(translationX: 0, y: distance)
            sheetHandle.transform = contentHost.transform
            UIView.animate(withDuration: 0.25, delay: 0, options: [.curveLinear, .allowUserInteraction]) {
                self.backdropView.alpha = 1
            }
            UIView.animate(withDuration: 0.25, delay: 0, options: [.curveEaseOut, .allowUserInteraction]) {
                self.contentHost.transform = .identity
                self.sheetHandle.transform = .identity
            }
            finishPresent()
            return
        }
        if animated && animationType == Animation.slideFade {
            // slide-fade: the backdrop fades on its own curve while the content
            // slides from fully below the screen.
            contentClip.alpha = 1
            contentClip.transform = CGAffineTransform(translationX: 0, y: max(bounds.height, 1))
            UIView.animate(withDuration: 0.32 * 0.7, delay: 0, options: [.curveLinear]) {
                self.backdropView.alpha = 1
            }
            UIView.animate(withDuration: 0.32, delay: 0, options: [.curveEaseOut, .allowUserInteraction]) {
                self.contentClip.transform = .identity
            }
            finishPresent()
            return
        }

        switch PamMotionPolicy.isReduced ? Animation.none : animationType {
        case Animation.slide:
            if presentation == Presentation.sheet {
                contentClip.transform = CGAffineTransform(translationX: 0, y: bounds.height * 0.35)
            } else {
                contentClip.transform = CGAffineTransform(translationX: 0, y: bounds.height * 0.12)
            }
            UIView.animate(withDuration: animationDuration) {
                self.backdropView.alpha = 1
                self.contentClip.alpha = 1
                self.contentClip.transform = self.presentation == Presentation.sheet
                    ? .identity
                    : .identity
            }
        case Animation.fade:
            UIView.animate(withDuration: animationDuration) {
                self.backdropView.alpha = 1
                self.contentClip.alpha = 1
            }
        case Animation.slideFade:
            slideFadeAnimator?.stopAnimation(true)
            layoutIfNeeded()
            contentClip.alpha = 1
            contentClip.transform = CGAffineTransform(translationX: 0, y: slideFadeDistance())
            UIView.animate(
                withDuration: SlideFade.enter * SlideFade.backdropShare,
                delay: 0,
                options: [.curveLinear, .allowUserInteraction]
            ) {
                self.backdropView.alpha = 1
            }
            let animator = UIViewPropertyAnimator(
                duration: SlideFade.enter,
                controlPoint1: CGPoint(x: 0.215, y: 0.61),
                controlPoint2: CGPoint(x: 0.355, y: 1)
            ) {
                self.contentClip.transform = .identity
            }
            animator.addCompletion { [weak self] _ in self?.slideFadeAnimator = nil }
            slideFadeAnimator = animator
            animator.startAnimation()
        default:
            backdropView.alpha = 1
            contentClip.alpha = 1
            contentClip.transform = .identity
        }
        finishPresent()
    }

    /// Distance a sheet travels when it slides in or out.
    private var sheetTravel: CGFloat {
        max(contentHost.bounds.height, sheetHeight) + 24
    }

    /// Sheet height: percentage snap points resolve against the container
    /// minus the top safe-area inset (@gorhom/bottom-sheet).
    private var sheetHeight: CGFloat {
        let available = max(1, bounds.height - safeAreaInsets.top)
        return available * bottomSheetSnapPoints[bottomSheetIndex]
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if presentation == Presentation.sheet,
           abs(contentHeightConstraint.constant - sheetHeight) > 0.5 {
            contentHeightConstraint.constant = sheetHeight
        }
    }

    override func safeAreaInsetsDidChange() {
        super.safeAreaInsetsDidChange()
        setNeedsLayout()
    }

    private func finishPresent() {
        // `autoFocus` inside the sheet was skipped while the host was hidden;
        // focus it (and so open the keyboard) once the entrance settled.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            guard let self, self.currentlyVisible else { return }
            self.contentHost.pamRequestAutomaticFocus()
        }
        onShow?()
        becomeFirstResponder()
        let initialFocus = contentHost.pamFirstAccessibleView() ?? contentHost
        UIAccessibility.post(notification: .screenChanged, argument: initialFocus)
        if onOrientationChange != nil {
            dispatchOrientation(force: true)
        }
        ensureOrientationObserver()
    }

    private func dismiss(notify: Bool) {
        guard currentlyVisible else {
            currentlyVisible = false
            isHidden = true
            return
        }
        let animationDuration: TimeInterval = 0.125
        let generation = visibilityGeneration
        // An input focused inside the sheet kept the keyboard up after the
        // sheet closed (a hidden view stays first responder). Android's
        // dialog hides the IME with its window.
        endEditing(true)

        let completion = {
            guard !self.desiredVisible else {
                self.present()
                return
            }
            // A newer dismissal owns the teardown.
            guard generation == self.visibilityGeneration else { return }
            self.currentlyVisible = false
            self.isHidden = true
            self.resetSheetKeyboardLift()
            self.accessibilityViewIsModal = false
            self.resignFirstResponder()
            if notify {
                self.onDismiss?()
            }
            if self.onOrientationChange != nil {
                self.lastOrientation = nil
            }
            self.removeOrientationObserver()
            if let previousFocus = self.previousFocus, previousFocus.window != nil {
                previousFocus.becomeFirstResponder()
                UIAccessibility.post(
                    notification: .screenChanged,
                    argument: previousFocus
                )
            } else {
                UIAccessibility.post(notification: .screenChanged, argument: nil)
            }
            self.previousFocus = nil
        }

        let animated = !PamMotionPolicy.isReduced && animationType != Animation.none
        if presentation == Presentation.sheet && animated {
            let distance = sheetTravel
            UIView.animate(withDuration: 0.2, delay: 0, options: [.curveLinear]) {
                self.backdropView.alpha = 0
            }
            UIView.animate(withDuration: 0.2, delay: 0, options: [.curveEaseIn], animations: {
                self.contentHost.transform = CGAffineTransform(translationX: 0, y: distance)
                self.sheetHandle.transform = self.contentHost.transform
            }, completion: { _ in
                self.contentHost.transform = .identity
                self.sheetHandle.transform = .identity
                completion()
            })
            return
        }
        if animated && animationType == Animation.slideFade {
            UIView.animate(withDuration: 0.22 * 0.7, delay: 0, options: [.curveLinear]) {
                self.backdropView.alpha = 0
            }
            UIView.animate(withDuration: 0.22, delay: 0, options: [.curveEaseIn], animations: {
                self.contentClip.transform = CGAffineTransform(translationX: 0, y: max(self.bounds.height, 1))
            }, completion: { _ in
                completion()
            })
            return
        }

        switch PamMotionPolicy.isReduced ? Animation.none : animationType {
        case Animation.slide:
            UIView.animate(withDuration: animationDuration, animations: {
                self.backdropView.alpha = 0
                self.contentClip.alpha = 0
                self.contentClip.transform = self.presentation == Presentation.sheet
                    ? CGAffineTransform(translationX: 0, y: self.bounds.height * 0.35)
                    : CGAffineTransform(translationX: 0, y: self.bounds.height * 0.12)
            }, completion: { _ in
                completion()
            })
        case Animation.fade:
            UIView.animate(withDuration: animationDuration, animations: {
                self.backdropView.alpha = 0
                self.contentClip.alpha = 0
            }, completion: { _ in
                completion()
            })
        case Animation.slideFade:
            slideFadeAnimator?.stopAnimation(true)
            UIView.animate(
                withDuration: SlideFade.exit * SlideFade.backdropShare,
                delay: SlideFade.exit * (1 - SlideFade.backdropShare),
                options: [.curveLinear]
            ) {
                self.backdropView.alpha = 0
            }
            let distance = slideFadeDistance()
            let animator = UIViewPropertyAnimator(
                duration: SlideFade.exit,
                controlPoint1: CGPoint(x: 0.55, y: 0.085),
                controlPoint2: CGPoint(x: 0.68, y: 0.53)
            ) {
                self.contentClip.transform = CGAffineTransform(translationX: 0, y: distance)
            }
            animator.addCompletion { [weak self] _ in
                guard let self else { return }
                self.slideFadeAnimator = nil
                self.contentClip.transform = .identity
                completion()
            }
            slideFadeAnimator = animator
            animator.startAnimation()
        default:
            completion()
        }
    }

    /// Travel that hides the content fully below the screen (at least half
    /// the screen, like Android).
    private func slideFadeDistance() -> CGFloat {
        let untransformedTop = contentClip.center.y - contentClip.bounds.height / 2
        return max(bounds.height - untransformedTop, bounds.height / 2)
    }

    func requestClose() {
        guard presentation != Presentation.sheet || bottomSheetDismissible else { return }
        setVisible(false)
        if let onRequestClose {
            onRequestClose()
            return
        }
    }

    @objc private func onBackdropTap() {
        guard presentation != Presentation.sheet || bottomSheetBackdropDismiss else { return }
        requestClose()
    }

    @objc private func onEscape() {
        requestClose()
    }

    @objc private func onModalPan(_ gesture: UIPanGestureRecognizer) {
        guard currentlyVisible else { return }
        if presentation == Presentation.sheet, bottomSheetDragEnabled {
            let translation = gesture.translation(in: self).y
            switch gesture.state {
            case .changed:
                let minimum = -(bounds.height - contentHost.bounds.height)
                let bounded = max(translation, minimum)
                contentHost.transform = CGAffineTransform(translationX: 0, y: bounded)
                sheetHandle.transform = contentHost.transform
            case .ended:
                settleBottomSheet(
                    translation: translation,
                    velocity: gesture.velocity(in: self).y
                )
            case .cancelled, .failed:
                resetBottomSheetTransform()
            default:
                break
            }
            return
        }
        if allowSwipeDismissal,
           gesture.state == .ended || gesture.state == .cancelled || gesture.state == .failed,
           gesture.velocity(in: self).y > 560 {
            requestClose()
        }
    }

    override func gestureRecognizerShouldBegin(
        _ gestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        guard presentation == Presentation.sheet,
              let pan = gestureRecognizer as? UIPanGestureRecognizer else {
            return allowSwipeDismissal
        }
        let location = pan.location(in: self)
        let handleBottom = contentHost.frame.minY + 48
        if location.y <= handleBottom { return true }
        guard pan.velocity(in: self).y > 0 else { return false }
        // gorhom/react-native-bottom-sheet: a downward drag on a nested
        // scrollable moves the sheet only once that scrollable is at its top.
        var candidate = hitTest(location, with: nil)
        while let view = candidate, view !== self {
            if let scroll = view as? UIScrollView,
               scroll.isScrollEnabled,
               scroll.contentOffset.y > -scroll.adjustedContentInset.top + 0.5 {
                return false
            }
            candidate = view.superview
        }
        return true
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        presentation == Presentation.sheet
    }

    private func settleBottomSheet(translation: CGFloat, velocity: CGFloat) {
        let height = max(bounds.height - safeAreaInsets.top, 1)
        let current = bottomSheetSnapPoints[bottomSheetIndex]
        let projected = current - (translation + velocity * 0.12) / height
        if bottomSheetDismissible,
           bottomSheetIndex == 0,
           projected < bottomSheetSnapPoints[0] * 0.55 {
            onBottomSheetDismiss?()
            requestClose()
            return
        }
        bottomSheetIndex = bottomSheetSnapPoints.indices.min {
            abs(bottomSheetSnapPoints[$0] - projected) <
                abs(bottomSheetSnapPoints[$1] - projected)
        } ?? bottomSheetIndex
        updatePresentationLayout()
        resetBottomSheetTransform()
        onBottomSheetChange?(bottomSheetIndex, bottomSheetSnapPoints[bottomSheetIndex])
    }

    private func resetBottomSheetTransform() {
        guard !PamMotionPolicy.isReduced else {
            contentHost.layer.removeAllAnimations()
            sheetHandle.layer.removeAllAnimations()
            contentHost.transform = .identity
            sheetHandle.transform = .identity
            return
        }
        UIView.animate(withDuration: 0.18) {
            self.contentHost.transform = .identity
            self.sheetHandle.transform = .identity
        }
    }

    private func applyBackdropColor() {
        backdropView.backgroundColor = transparent ? .clear : backdropColor
    }

    private func updatePresentationLayout() {
        if presentation == Presentation.sheet {
            contentHeightConstraint.isActive = false
            contentHeightConstraint = contentHost.heightAnchor.constraint(equalToConstant: sheetHeight)
            contentHeightConstraint.isActive = true
            contentHost.layer.cornerRadius = bottomSheetCornerRadius
            contentHost.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
            contentHost.clipsToBounds = true
            sheetHandle.isHidden = !bottomSheetHandleVisible
            contentClip.bringSubviewToFront(sheetHandle)
            contentClip.passesEmptyTouches = true
        } else {
            contentClip.passesEmptyTouches = false
            contentHeightConstraint.isActive = false
            contentHeightConstraint = contentHost.heightAnchor.constraint(equalTo: contentClip.heightAnchor)
            contentHeightConstraint.isActive = true
            contentHost.layer.cornerRadius = 0
            contentHost.clipsToBounds = false
            sheetHandle.isHidden = true
        }
    }

    private func ensureOrientationObserver() {
        guard orientationToken == nil else { return }
        orientationToken = orientationObserver.addObserver(
            forName: UIDevice.orientationDidChangeNotification,
            object: nil,
            queue: .main,
        ) { [weak self] _ in
            self?.dispatchOrientation(force: false)
        }
        dispatchOrientation(force: true)
    }

    private func removeOrientationObserver() {
        if let token = orientationToken {
            orientationObserver.removeObserver(token)
            orientationToken = nil
        }
    }

    private func dispatchOrientation(force: Bool) {
        let orientationValue = currentOrientation()
        if !force, let lastOrientation, lastOrientation == orientationValue {
            return
        }
        lastOrientation = orientationValue
        onOrientationChange?(orientationValue)
    }

    private func currentOrientation() -> Int {
        if #available(iOS 13.0, *),
           let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            let interface = scene.interfaceOrientation
            if interface.isLandscape {
                return Orientation.landscape
            }
            return Orientation.portrait
        }

        return UIDevice.current.orientation.isLandscape ? Orientation.landscape : Orientation.portrait
    }
}

final class ImageLoadContext: @unchecked Sendable {
    let nodeId: Int64
    let generation: Int
    let source: String
    weak var imageView: UIImageView?
    weak var task: URLSessionTask?
    let progressive: Bool
    var receivedData = Data()
    var onStart: ((ImageLoadContext) -> Void)?
    var onProgress: ((ImageLoadContext) -> Void)?
    var onSuccess: ((ImageLoadContext, Data) -> Void)?
    var onPartial: ((ImageLoadContext, Data) -> Void)?
    var onError: ((ImageLoadContext, String) -> Void)?
    var onEnd: ((ImageLoadContext) -> Void)?
    var progressLoaded: Int64 = 0
    var progressTotal: Int64 = 0
    var progressScheduled = false

    init(
        nodeId: Int64,
        generation: Int,
        source: String,
        imageView: UIImageView,
        progressive: Bool
    ) {
        self.nodeId = nodeId
        self.generation = generation
        self.source = source
        self.imageView = imageView
        self.progressive = progressive
    }
}

final class ImageLoadSessionDelegate: NSObject, URLSessionDataDelegate {
    weak var renderer: PamRenderer?
    private var active = [Int: ImageLoadContext]()
    private let lock = NSLock()

    func register(_ context: ImageLoadContext, for task: URLSessionTask) {
        lock.lock()
        active[task.taskIdentifier] = context
        lock.unlock()
        context.task = task
    }

    func unregister(taskIdentifier: Int) {
        lock.lock()
        active[taskIdentifier] = nil
        lock.unlock()
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard let context = lookup(task: dataTask) else {
            completionHandler(.cancel)
            return
        }
        if let response = response as? HTTPURLResponse,
           !(200...299).contains(response.statusCode) {
            context.onError?(context, "Image request failed with HTTP \(response.statusCode)")
            completionHandler(.cancel)
            return
        }
        context.progressTotal = max(0, response.expectedContentLength)
        completionHandler(.allow)
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive data: Data
    ) {
        guard let context = lookup(task: dataTask) else { return }
        context.receivedData.append(data)
        context.progressLoaded = Int64(context.receivedData.count)
        context.onProgress?(context)
        if context.progressive { context.onPartial?(context, context.receivedData) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let context = lookup(task: task) else { return }
        if let error {
            let nsError = error as NSError
            if nsError.domain != NSURLErrorDomain || nsError.code != NSURLErrorCancelled {
                context.onError?(context, error.localizedDescription)
            }
        } else {
            context.onSuccess?(context, context.receivedData)
        }
        context.onEnd?(context)
        unregister(taskIdentifier: task.taskIdentifier)
    }

    private func lookup(task: URLSessionTask) -> ImageLoadContext? {
        lock.lock()
        defer { lock.unlock() }
        return active[task.taskIdentifier]
    }
}


/// Controlled-input echo reconciliation shared with Android
/// (`isStaleInputEcho`): PHP renders change events in order, so a rendered
/// value equal to one still in flight is that event's echo; when the field
/// already holds newer text it is stale. Any other value is authored.
enum PamInputEcho {
    static let window: TimeInterval = 3
    static let limit = 64

    static func record(_ inFlight: inout [(String, TimeInterval)], value: String, now: TimeInterval) {
        if inFlight.last?.0 == value { return }
        inFlight.append((value, now))
        if inFlight.count > limit { inFlight.removeFirst(inFlight.count - limit) }
    }

    static func isStale(
        _ inFlight: inout [(String, TimeInterval)],
        value: String,
        current: String,
        now: TimeInterval
    ) -> Bool {
        while let first = inFlight.first, now - first.1 > window { inFlight.removeFirst() }
        if value == current {
            inFlight.removeAll()
            return false
        }
        guard let index = inFlight.firstIndex(where: { $0.0 == value }) else {
            inFlight.removeAll()
            return false
        }
        inFlight.removeFirst(index + 1)
        return true
    }
}

extension UIScrollView {
    /// Stops a fling/deceleration in progress: setting the current offset
    /// without animation is UIKit's documented way to halt scrolling.
    func pamStopDeceleration() {
        // A finger on the view owns it; otherwise halt deceleration and any
        // running setContentOffset animation.
        guard !isTracking else { return }
        setContentOffset(contentOffset, animated: false)
    }
}
