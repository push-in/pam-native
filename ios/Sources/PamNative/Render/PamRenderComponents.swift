import Foundation
import UIKit

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
        borderStyle = .roundedRect
        autocorrectionType = .default
        translatesAutoresizingMaskIntoConstraints = true
        syncFontCache()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        delegate = self
        borderStyle = .roundedRect
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

final class PamDrawerLayout: UIView {
    private let contentHost = UIView()
    private let drawerHost = UIView()
    private var open = false
    private var downX: CGFloat = 0
    private var gestureInProgress = false

    private var onOpen: (() -> Void)?
    private var onClose: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = false
        addSubview(contentHost)
        addSubview(drawerHost)
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        pan.cancelsTouchesInView = false
        addGestureRecognizer(pan)
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
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

    override func layoutSubviews() {
        super.layoutSubviews()
        contentHost.frame = bounds
        let drawerWidth = min(bounds.width * 0.86, 320)
        drawerHost.frame = CGRect(x: open ? 0 : -drawerWidth, y: 0, width: drawerWidth, height: bounds.height)
    }

    @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
        guard gesture.state == .ended else { return }
        if !open && gestureInProgress {
            return
        }
        let point = gesture.location(in: self)
        if point.x >= bounds.width - 2 { }
    }

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        switch gesture.state {
        case .began:
            downX = gesture.location(in: self).x
            gestureInProgress = true
        case .changed:
            guard gestureInProgress else { return }
            let point = gesture.location(in: self)
            let deltaX = point.x - downX
            let drawerWidth = drawerHost.frame.width
            let clamped = max(-drawerWidth, min(0, (open ? 0 : -drawerWidth) + deltaX * 0.62))
            drawerHost.frame.origin.x = clamped
        case .ended, .cancelled:
            let translation = gesture.translation(in: self).x
            gestureInProgress = false
            if open {
                if translation <= -56 {
                    setOpen(false, animated: true)
                } else {
                    setOpen(true, animated: true)
                }
            } else {
                if translation >= 56 {
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

    private func updateDrawer(animated: Bool) {
        let drawerWidth = drawerHost.frame.width
        let target = open ? 0 : -drawerWidth
        guard animated else {
            drawerHost.frame.origin.x = target
            return
        }
        UIView.animate(
            withDuration: 0.18,
            delay: 0,
            options: .curveEaseOut,
        ) {
            self.drawerHost.frame.origin.x = target
        }
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
        let size = indicatorSize == 2 ? 44 : 32
        indicatorContainer.frame = CGRect(
            x: (bounds.width - size) / 2,
            y: progressOffset + 8,
            width: CGFloat(size),
            height: CGFloat(size),
        )
        indicatorContainer.layer.cornerRadius = CGFloat(size / 2)
        indicator.transform = .identity
        indicator.frame = indicator.bounds
    }
}

final class PamModalHost: UIView {
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
    private let orientationObserver = NotificationCenter.default

    private var showScheduled = false
    private var desiredVisible = true
    private var currentlyVisible = false
    private var presentation = Presentation.dialog
    private var animationType = Animation.none
    private var backdropColor = UIColor.white
    private var transparent = false
    private var allowSwipeDismissal = false
    private var onRequestClose: (() -> Void)?
    private var onShow: (() -> Void)?
    private var onDismiss: (() -> Void)?
    private var onOrientationChange: ((Int) -> Void)?
    private var orientationToken: NSObjectProtocol?
    private var lastOrientation: Int?

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

        addSubview(backdropView)
        addSubview(contentClip)

        let pan = UIPanGestureRecognizer(target: self, action: #selector(onModalPan(_:)))
        pan.maximumNumberOfTouches = 1
        contentClip.addGestureRecognizer(pan)

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
            contentHost.topAnchor.constraint(equalTo: contentClip.topAnchor),
            contentHost.bottomAnchor.constraint(equalTo: contentClip.bottomAnchor),
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
        guard !currentlyVisible else { return }
        isHidden = false
        currentlyVisible = true
        backdropView.alpha = 0
        contentClip.alpha = 0
        contentClip.transform = presentation == Presentation.sheet ? CGAffineTransform(translationX: 0, y: bounds.height * 0.25) : .identity
        applyBackdropColor()
        let animationDuration: TimeInterval = 0.22

        switch animationType {
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
        let animationDuration: TimeInterval = 0.16

        let completion = {
            self.currentlyVisible = false
            self.isHidden = true
            if notify {
                self.onDismiss?()
            }
            if self.onOrientationChange != nil {
                self.lastOrientation = nil
            }
            self.removeOrientationObserver()
        }

        switch animationType {
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
        if let onRequestClose {
            onRequestClose()
            return
        }
        desiredVisible = false
        dismiss(notify: true)
    }

    @objc private func onBackdropTap() {
        requestClose()
    }

    @objc private func onModalPan(_ gesture: UIPanGestureRecognizer) {
        guard allowSwipeDismissal, currentlyVisible else { return }
        if gesture.state == .ended || gesture.state == .cancelled || gesture.state == .failed {
            let velocity = gesture.velocity(in: self)
            if velocity.y > 560 {
                requestClose()
            }
        }
    }

    private func applyBackdropColor() {
        backdropView.backgroundColor = transparent ? .clear : backdropColor.withAlphaComponent(0.55)
    }

    private func updatePresentationLayout() {
        if presentation == Presentation.sheet {
            contentHost.layer.cornerRadius = 14
            contentHost.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
            contentHost.clipsToBounds = true
        } else {
            contentHost.layer.cornerRadius = 0
            contentHost.clipsToBounds = false
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
    weak var task: URLSessionDownloadTask?
    var onStart: ((ImageLoadContext) -> Void)?
    var onProgress: ((ImageLoadContext) -> Void)?
    var onSuccess: ((ImageLoadContext, Data) -> Void)?
    var onError: ((ImageLoadContext, String) -> Void)?
    var onEnd: ((ImageLoadContext) -> Void)?
    var progressLoaded: Int64 = 0
    var progressTotal: Int64 = 0
    var progressScheduled = false

    init(nodeId: Int64, generation: Int, source: String, imageView: UIImageView) {
        self.nodeId = nodeId
        self.generation = generation
        self.source = source
        self.imageView = imageView
    }
}

final class ImageLoadSessionDelegate: NSObject, URLSessionDownloadDelegate {
    weak var renderer: PamRenderer?
    private var active = [Int: ImageLoadContext]()
    private let lock = NSLock()

    func register(_ context: ImageLoadContext, for task: URLSessionDownloadTask) {
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
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64,
    ) {
        guard let context = lookup(task: downloadTask) else {
            return
        }
        context.progressLoaded = totalBytesWritten
        context.progressTotal = max(0, totalBytesExpectedToWrite)
        context.onProgress?(context)
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL,
    ) {
        guard let context = lookup(task: downloadTask) else {
            return
        }
        do {
            let data = try Data(contentsOf: location)
            context.onSuccess?(context, data)
        } catch {
            context.onError?(context, error.localizedDescription)
        }
        context.onEnd?(context)
        unregister(taskIdentifier: downloadTask.taskIdentifier)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let downloadTask = task as? URLSessionDownloadTask,
              let context = lookup(task: downloadTask) else {
            return
        }
        if let error {
            let nsError = error as NSError
            if nsError.domain != NSURLErrorDomain || nsError.code != NSURLErrorCancelled {
                context.onError?(context, error.localizedDescription)
            }
            context.onEnd?(context)
        }
        unregister(taskIdentifier: downloadTask.taskIdentifier)
    }

    private func lookup(task: URLSessionDownloadTask) -> ImageLoadContext? {
        lock.lock()
        defer { lock.unlock() }
        return active[task.taskIdentifier]
    }
}
