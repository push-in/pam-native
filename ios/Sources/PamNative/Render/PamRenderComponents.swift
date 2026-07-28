import Foundation
import UIKit

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
    private let arcLayer = CAShapeLayer()
    private var animating = false

    override var intrinsicContentSize: CGSize {
        CGSize(width: 32, height: 32)
    }

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
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        arcLayer.frame = bounds
        let inset = max(2, arcLayer.lineWidth / 2)
        arcLayer.path = UIBezierPath(
            ovalIn: bounds.insetBy(dx: inset, dy: inset)
        ).cgPath
    }

    override func tintColorDidChange() {
        super.tintColorDidChange()
        arcLayer.strokeColor = tintColor.cgColor
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil {
            arcLayer.removeAllAnimations()
        } else if animating {
            installAnimations()
        }
    }

    func startAnimating() {
        guard !animating else { return }
        animating = true
        isHidden = false
        installAnimations()
    }

    func stopAnimating() {
        animating = false
        arcLayer.removeAllAnimations()
        isHidden = true
    }

    private func installAnimations() {
        guard window != nil else { return }
        arcLayer.removeAllAnimations()
        if UIAccessibility.isReduceMotionEnabled {
            arcLayer.strokeStart = 0.08
            arcLayer.strokeEnd = 0.82
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

final class PamInputField: UITextField, UITextFieldDelegate {
    private static let maxKeyBytes = 64

    var onSelectionChange: ((Int, Int) -> Void)?
    var onContentSizeChange: ((Int, Int) -> Void)?
    var onKeyPress: ((String) -> Void)?
    var onInputEndEditing: ((String) -> Void)?

    private var contentSizeScheduled = false
    private var lastContentWidth = -1
    private var lastContentHeight = -1
    private var syncingText = false

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

    func setInputCallbacks(
        selection: ((Int, Int) -> Void)? = nil,
        contentSize: ((Int, Int) -> Void)? = nil,
        key: ((String) -> Void)? = nil,
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
    }

    override func deleteBackward() {
        onKeyPress?("Backspace")
        super.deleteBackward()
    }

    override func insertText(_ text: String) {
        if text.count > 0 {
            let key = text == "\n" ? "Enter" : String(text.prefix(Self.maxKeyBytes))
            if !key.isEmpty {
                onKeyPress?(key)
            }
        }
        super.insertText(text)
    }

    func textField(_ textField: UITextField, shouldChangeCharactersIn range: NSRange, replacementString string: String) -> Bool {
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
        return true
    }

    func textFieldDidChangeSelection(_ textField: UITextField) {
        guard textField === self else { return }
        let rangeStart = offset(from: beginningOfDocument, to: selectedTextRange?.start ?? beginningOfDocument)
        let rangeEnd = offset(from: beginningOfDocument, to: selectedTextRange?.end ?? beginningOfDocument)
        onSelectionChange?(rangeStart, rangeEnd)
        scheduleContentSizeUpdate()
    }

    func textFieldDidEndEditing(_ textField: UITextField) {
        onInputEndEditing?(text ?? "")
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        if let value = textField.text {
            onInputEndEditing?(value)
        }
        return true
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
        guard animated else {
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

final class PamRefreshContainer: UIView {
    private enum HolderStyle {
        static let defaultThreshold: CGFloat = 64
        static let defaultIndicatorSize: Int = 1
    }

    private let contentHost = UIView()
    private let indicatorContainer = UIView()
    private let indicator = UIActivityIndicatorView(style: .medium)

    private weak var scrollView: UIScrollView?
    private var scrollObserver: NSKeyValueObservation?
    private var dragging = false
    private var refreshing = false
    private(set) var colors: [Int] = []

    private var refreshCallback: (() -> Void)?
    private var progressBackgroundColor: Int?
    private var progressOffset: CGFloat = 0
    private var indicatorSize: Int = HolderStyle.defaultIndicatorSize

    var isRefreshControlEnabled = true

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = false
        indicatorContainer.backgroundColor = .systemBackground
        indicatorContainer.layer.cornerRadius = 18
        indicatorContainer.addSubview(indicator)
        indicator.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            indicator.centerXAnchor.constraint(equalTo: indicatorContainer.centerXAnchor),
            indicator.centerYAnchor.constraint(equalTo: indicatorContainer.centerYAnchor),
        ])
        addSubview(contentHost)
        addSubview(indicatorContainer)
        updateIndicatorLayout()
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
        refreshing = value
        isUserInteractionEnabled = !value
        if value {
            indicator.startAnimating()
            indicatorContainer.alpha = 1
            indicatorContainer.isHidden = false
            indicatorContainer.transform = .identity
        } else {
            indicator.stopAnimating()
            indicatorContainer.isHidden = true
            indicatorContainer.transform = .identity
        }
        if !value {
            dragging = false
        }
    }

    func setColors(_ encoded: String?) {
        colors = encoded?
            .split(separator: ",")
            .compactMap { Int($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
            ?? [UIColor.systemGray.cgColor.components?.first.flatMap { Int($0 * 255) } ?? 0]
        indicator.color = UIColor(argb: Int64(colors.first ?? 0xFF888888))
    }

    func setProgressBackgroundColor(_ color: Int?) {
        progressBackgroundColor = color
        indicatorContainer.backgroundColor = color.map { UIColor(argb: Int64($0)) }
    }

    func setProgressViewOffset(_ offset: Float) {
        progressOffset = CGFloat(offset)
        setNeedsLayout()
    }

    func setIndicatorSize(_ size: Int) {
        indicatorSize = size
        updateIndicatorLayout()
    }

    func setOnRefresh(_ action: (() -> Void)?) {
        refreshCallback = action
    }

    override var isHidden: Bool {
        didSet {
            if !isHidden {
                dragging = false
            }
        }
    }

    var isEnabled = true {
        didSet {
            if !isEnabled && !refreshing {
                setRefreshing(false)
            }
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        contentHost.frame = bounds
        contentHost.subviews.forEach { view in
            view.frame = bounds
        }
        updateIndicatorLayout()
        updateIndicatorVisibility()
    }

    private func bind(scrollView: UIScrollView) {
        if self.scrollView === scrollView {
            return
        }
        unbind()
        self.scrollView = scrollView
        scrollObserver = scrollView.observe(\.contentOffset, options: [.initial, .new], changeHandler: { [weak self] _, _ in
            self?.handleScrollOffset()
        })
        scrollView.showsVerticalScrollIndicator = true
        scrollView.alwaysBounceVertical = true
    }

    private func unbind() {
        scrollObserver?.invalidate()
        scrollObserver = nil
        scrollView = nil
    }

    private func handleScrollOffset() {
        guard isRefreshControlEnabled, let scrollView, let refreshCallback else { return }
        let topInset = scrollView.adjustedContentInset.top
        let pulled = max(0, -(scrollView.contentOffset.y + topInset))
        if scrollView.isDragging {
            if pulled > 0 {
                dragging = true
                updateIndicatorProgress(pulled / HolderStyle.defaultThreshold)
            }
            return
        }

        guard dragging else {
            return
        }
        dragging = false
        if pulled >= HolderStyle.defaultThreshold {
            if !refreshing {
                setRefreshing(true)
                refreshCallback()
            }
        } else {
            setRefreshing(false)
        }
    }

    private func updateIndicatorProgress(_ progress: CGFloat) {
        let clamped = min(1, max(0, progress))
        indicatorContainer.alpha = clamped
        indicatorContainer.transform = CGAffineTransform(translationX: 0, y: -(clamped * -progressOffset))
        if clamped >= 0.99 {
            indicator.startAnimating()
        }
    }

    private func updateIndicatorVisibility() {
        indicatorContainer.isHidden = !refreshing && indicatorContainer.alpha == 0
    }

    private func updateIndicatorLayout() {
        let size = CGFloat(indicatorSize == 2 ? 44 : 32)
        indicatorContainer.frame = CGRect(
            x: (bounds.width - size) / 2,
            y: progressOffset + 8,
            width: size,
            height: size,
        )
        indicatorContainer.layer.cornerRadius = size / 2
        indicator.transform = .identity
        indicator.frame = indicator.bounds
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
    }

    private enum Orientation {
        static let portrait = 1
        static let landscape = 2
    }

    private let backdropView = UIControl()
    private let contentHost = UIView()
    private let contentClip = UIView()
    private let sheetHandle = UIView()
    private let orientationObserver = NotificationCenter.default
    private var contentHeightConstraint: NSLayoutConstraint!

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
            contentHost.bottomAnchor.constraint(equalTo: contentClip.bottomAnchor),
            contentHeightConstraint,
            sheetHandle.widthAnchor.constraint(equalToConstant: 36),
            sheetHandle.heightAnchor.constraint(equalToConstant: 4),
            sheetHandle.centerXAnchor.constraint(equalTo: contentHost.centerXAnchor),
            sheetHandle.topAnchor.constraint(equalTo: contentHost.topAnchor, constant: 10),
        ])

        isUserInteractionEnabled = true
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    deinit {
        if let token = orientationToken {
            orientationObserver.removeObserver(token)
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
        visibilityGeneration += 1
        desiredVisible = value
        scheduleUpdate()
    }

    func setPresentation(_ value: Int) {
        presentation = value
        updatePresentationLayout()
    }

    func setAnimationType(_ value: Int) {
        animationType = value
    }

    func setBackdropColor(_ color: Int) {
        backdropColor = UIColor(argb: Int64(color))
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
    func setBottomSheetKeyboardBehavior(_: Int) {}

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
        isHidden = false
        currentlyVisible = true
        accessibilityViewIsModal = true
        backdropView.alpha = 0
        contentClip.alpha = 0
        contentClip.transform = presentation == Presentation.sheet ? CGAffineTransform(translationX: 0, y: bounds.height * 0.25) : .identity
        applyBackdropColor()
        let animationDuration: TimeInterval = 0.225

        switch UIAccessibility.isReduceMotionEnabled ? Animation.none : animationType {
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
        default:
            backdropView.alpha = 1
            contentClip.alpha = 1
            contentClip.transform = .identity
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

        let completion = {
            guard generation == self.visibilityGeneration, !self.desiredVisible else {
                self.present()
                return
            }
            self.currentlyVisible = false
            self.isHidden = true
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

        switch UIAccessibility.isReduceMotionEnabled ? Animation.none : animationType {
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
        default:
            completion()
        }
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
        return location.y <= handleBottom || pan.velocity(in: self).y > 0
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        presentation == Presentation.sheet
    }

    private func settleBottomSheet(translation: CGFloat, velocity: CGFloat) {
        let height = max(bounds.height, 1)
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
            contentHeightConstraint = contentHost.heightAnchor.constraint(
                equalTo: contentClip.heightAnchor,
                multiplier: bottomSheetSnapPoints[bottomSheetIndex]
            )
            contentHeightConstraint.isActive = true
            contentHost.layer.cornerRadius = bottomSheetCornerRadius
            contentHost.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
            contentHost.clipsToBounds = true
            sheetHandle.isHidden = !bottomSheetHandleVisible
            contentClip.bringSubviewToFront(sheetHandle)
        } else {
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

final class ImageLoadContext {
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
