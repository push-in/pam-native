import UIKit

private final class PamRouteViewController: UIViewController, UISearchResultsUpdating {
    private let routeView: UIView
    var onSearchChange: ((String) -> Void)?

    init(routeView: UIView) {
        self.routeView = routeView
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        view = routeView
    }

    func updateSearchResults(for searchController: UISearchController) {
        onSearchChange?(searchController.searchBar.text ?? "")
    }
}

final class PamNavigationHost: UIView, UIGestureRecognizerDelegate, UIAdaptivePresentationControllerDelegate {
    var operation = 1
    var transition = 1
    var duration: TimeInterval = 0.24
    var navigationOrientation = 1 {
        didSet { applyPlatformScreenBehavior() }
    }
    var autoHideHomeIndicator = false {
        didSet { applyPlatformScreenBehavior() }
    }
    var screenTitle = "" { didSet { applyControllerChrome() } }
    var headerShown = false { didSet { applyControllerChrome() } }
    var headerTransparent = false { didSet { applyControllerChrome() } }
    var headerBackgroundColor: UIColor? { didSet { applyControllerChrome() } }
    var headerTintColor: UIColor? { didSet { applyControllerChrome() } }
    var headerShadowVisible = true { didSet { applyControllerChrome() } }
    var headerLargeTitleEnabled = false { didSet { applyControllerChrome() } }
    var headerSearchEnabled = false { didSet { applyControllerChrome() } }
    var headerSearchPlaceholder = "Search" { didSet { applyControllerChrome() } }
    var onSearchChange: ((String) -> Void)? { didSet { applyControllerChrome() } }
    var screenPresentation = 1
    var sheetDetents: [CGFloat] = [1]
    var sheetInitialDetentIndex = 1
    var sheetGrabberVisible = false
    var sheetCornerRadius: CGFloat = 0
    var sheetExpandsWhenScrolledToEdge = true
    private var revision: Int64 = 0
    private var gestureEnabled = true
    private var gestureEdgeWidth: CGFloat = 24
    private var gestureThreshold: CGFloat = 0.35
    private var gestureDirection = 1
    private var fullScreenGestureEnabled = false
    private var onGesturePop: (() -> Void)?
    private var onTransitionEnd: (() -> Void)?
    private var onGestureStart: (() -> Void)?
    private var onGestureEnd: (() -> Void)?
    private var onGestureCancel: (() -> Void)?
    private var interactivePopCommitted = false
    private var routeViews: [UIView] = []
    private var routeControllers: [ObjectIdentifier: PamRouteViewController] = [:]
    private weak var containerController: UIViewController?
    private var navigationController: UINavigationController?
    private var presentedNavigationController: UINavigationController?
    private struct SharedElementConfig: Decodable {
        var durationMs = 500
        var easing = 2
        var resizeMode = 1
        var crossFade = true
        var damping = 0.82
        var stiffness = 220.0
        var mass = 1.0
    }
    private struct SharedElementAnimation {
        let snapshot: UIView
        let targetSnapshot: UIView?
        let source: UIView
        let destination: UIView
        let startFrame: CGRect
        let endFrame: CGRect
        let startRadius: CGFloat
        let endRadius: CGFloat
        let config: SharedElementConfig
    }
    private var sharedElements: [SharedElementAnimation] = []
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

    override func didMoveToWindow() {
        super.didMoveToWindow()
        ensureControllerHierarchy()
        applyPlatformScreenBehavior()
    }

    func insert(_ view: UIView, index: Int) {
        if !routeViews.contains(where: { $0 === view }) {
            routeViews.insert(view, at: min(max(index, 0), routeViews.count))
            routeControllers[ObjectIdentifier(view)] = PamRouteViewController(routeView: view)
        }
        if navigationController == nil, view.superview !== self {
            insertSubview(view, at: min(max(index, 0), subviews.count))
        }
        view.frame = bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        showOnlyTop()
    }

    func removeRoute(_ view: UIView) {
        routeViews.removeAll { $0 === view }
        routeControllers.removeValue(forKey: ObjectIdentifier(view))
        view.removeFromSuperview()
        if let navigationController {
            let controllers = routeViews.compactMap { routeControllers[ObjectIdentifier($0)] }
            navigationController.setViewControllers(controllers, animated: false)
        }
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
        direction: Int = 1,
        fullScreen: Bool = false,
        onPop: (() -> Void)?,
        onTransitionEnd: (() -> Void)?,
        onGestureStart: (() -> Void)?,
        onGestureEnd: (() -> Void)?,
        onGestureCancel: (() -> Void)?
    ) {
        gestureEnabled = enabled
        gestureEdgeWidth = min(max(edgeWidth, 8), 160)
        gestureThreshold = min(max(threshold, 0.1), 0.9)
        gestureDirection = min(max(direction, 1), 2)
        fullScreenGestureEnabled = fullScreen
        onGesturePop = onPop
        self.onTransitionEnd = onTransitionEnd
        self.onGestureStart = onGestureStart
        self.onGestureEnd = onGestureEnd
        self.onGestureCancel = onGestureCancel
        edgeGesture.isEnabled = enabled
    }

    override func gestureRecognizerShouldBegin(
        _ gestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        guard gestureEnabled, routeViews.count >= 2,
              let pan = gestureRecognizer as? UIPanGestureRecognizer else { return false }
        let point = pan.location(in: self)
        let rtl = effectiveUserInterfaceLayoutDirection == .rightToLeft
        let atLeadingEdge = fullScreenGestureEnabled || gestureDirection == 2 || (rtl
            ? point.x >= bounds.width - gestureEdgeWidth
            : point.x <= gestureEdgeWidth)
        let velocity = pan.velocity(in: self)
        let towardBack = gestureDirection == 2 ? velocity.y > 0 : (rtl ? velocity.x < 0 : velocity.x > 0)
        let dominant = gestureDirection == 2 ? abs(velocity.y) > abs(velocity.x) : abs(velocity.x) > abs(velocity.y)
        return atLeadingEdge && towardBack && dominant
    }

    private func runTransition() {
        if navigationController != nil {
            runControllerTransition()
            return
        }
        guard let incoming = incomingView() else { return }
        let outgoing = outgoingView()
        if interactivePopCommitted && operation == 3 {
            interactivePopCommitted = false
            finish(incoming: incoming, outgoing: outgoing)
            return
        }
        routeViews.forEach { $0.isHidden = $0 !== incoming && $0 !== outgoing }
        incoming.isHidden = false
        outgoing?.isHidden = false
        layoutIfNeeded()
        prepareSharedElements(incoming: incoming, outgoing: outgoing)
        let kind = transition == 1 ? 2 : transition
        if UIAccessibility.isReduceMotionEnabled || duration == 0 || kind == 8 {
            finish(incoming: incoming, outgoing: outgoing)
            return
        }
        applyProgress(0, incoming: incoming, outgoing: outgoing, kind: kind)
        let sharedDuration = sharedElements.map { Double($0.config.durationMs) / 1000 }.max() ?? 0
        UIView.animate(
            withDuration: min(max(max(duration, sharedDuration), 0), 2),
            delay: 0,
            options: [.curveLinear, .beginFromCurrentState]
        ) {
            self.applyProgress(1, incoming: incoming, outgoing: outgoing, kind: kind)
        } completion: { _ in
            self.finish(incoming: incoming, outgoing: outgoing)
        }
    }

    private func applyProgress(
        _ progress: CGFloat,
        incoming: UIView,
        outgoing: UIView?,
        kind: Int
    ) {
        let width = max(bounds.width, 1)
        let height = max(bounds.height, 1)
        let semanticSign: CGFloat = effectiveUserInterfaceLayoutDirection == .rightToLeft ? -1 : 1
        let popping = operation == 3
        let direction: CGFloat = kind == 3 ? -1 : semanticSign
        incoming.alpha = 1
        incoming.transform = .identity
        incoming.layer.transform = CATransform3DIdentity
        outgoing?.alpha = 1
        outgoing?.transform = .identity
        outgoing?.layer.transform = CATransform3DIdentity

        switch kind {
        case 2, 3:
            if popping {
                incoming.transform = CGAffineTransform(
                    translationX: -direction * width * 0.28 * (1 - progress), y: 0
                )
                outgoing?.transform = CGAffineTransform(
                    translationX: direction * width * progress, y: 0
                )
            } else {
                incoming.transform = CGAffineTransform(
                    translationX: direction * width * (1 - progress), y: 0
                )
                outgoing?.transform = CGAffineTransform(
                    translationX: -direction * width * 0.28 * progress, y: 0
                )
            }
            outgoing?.alpha = 1 - progress * 0.18
        case 4:
            if popping {
                outgoing?.transform = CGAffineTransform(translationX: 0, y: height * progress)
            } else {
                incoming.transform = CGAffineTransform(translationX: 0, y: height * (1 - progress))
            }
            outgoing?.alpha = 1 - progress * 0.12
        case 5:
            incoming.alpha = progress
            outgoing?.alpha = 1 - progress
        case 6:
            incoming.alpha = progress
            incoming.transform = CGAffineTransform(translationX: 0, y: height * 0.08 * (1 - progress))
        case 7:
            incoming.alpha = progress
            let scale = 0.94 + 0.06 * progress
            incoming.transform = CGAffineTransform(scaleX: scale, y: scale)
        case 9:
            if popping {
                outgoing?.transform = CGAffineTransform(translationX: 0, y: -height * progress)
            } else {
                incoming.transform = CGAffineTransform(translationX: 0, y: -height * (1 - progress))
            }
            outgoing?.alpha = 1 - progress * 0.12
        case 10:
            let sign = popping ? -semanticSign : semanticSign
            incoming.alpha = progress
            incoming.transform = CGAffineTransform(
                translationX: sign * width * 0.12 * (1 - progress), y: 0
            )
            outgoing?.alpha = 1 - progress
            outgoing?.transform = CGAffineTransform(
                translationX: -sign * width * 0.08 * progress, y: 0
            )
        case 11:
            let sign: CGFloat = popping ? -1 : 1
            incoming.alpha = progress
            incoming.transform = CGAffineTransform(
                translationX: 0, y: sign * height * 0.08 * (1 - progress)
            )
            outgoing?.alpha = 1 - progress
            outgoing?.transform = CGAffineTransform(
                translationX: 0, y: -sign * height * 0.05 * progress
            )
        case 12:
            incoming.alpha = progress
            incoming.layer.transform = CATransform3DMakeRotation(-.pi / 2 * (1 - progress), 0, 1, 0)
            outgoing?.layer.transform = CATransform3DMakeRotation(.pi / 2 * progress, 0, 1, 0)
        case 13:
            if popping {
                outgoing?.transform = CGAffineTransform(translationX: direction * width * progress, y: 0)
            } else {
                incoming.transform = CGAffineTransform(translationX: direction * width * (1 - progress), y: 0)
            }
        default:
            break
        }
        applySharedElementProgress(progress)
    }

    private func prepareSharedElements(
        incoming: UIView,
        outgoing: UIView?,
        sourceFrames: [String: CGRect] = [:]
    ) {
        clearSharedElements()
        guard !UIAccessibility.isReduceMotionEnabled, duration > 0,
              let outgoing else { return }
        let sources = sharedElementViews(in: outgoing)
        let destinations = sharedElementViews(in: incoming)
        for (tag, source) in sources.prefix(16) {
            guard let destination = destinations[tag],
                  source.bounds.width > 0, source.bounds.height > 0,
                  destination.bounds.width > 0, destination.bounds.height > 0,
                  let snapshot = source.snapshotView(afterScreenUpdates: false) else { continue }
            let config = sharedElementConfig(for: destination)
                ?? sharedElementConfig(for: source)
                ?? SharedElementConfig(durationMs: Int(duration * 1000))
            let start = sourceFrames[tag] ?? source.convert(source.bounds, to: self)
            let end = destination.convert(destination.bounds, to: self)
            snapshot.frame = start
            snapshot.layer.masksToBounds = true
            snapshot.layer.cornerRadius = source.layer.cornerRadius
            let targetSnapshot = config.crossFade
                ? destination.snapshotView(afterScreenUpdates: true)
                : nil
            source.isHidden = true
            destination.isHidden = true
            addSubview(snapshot)
            targetSnapshot?.frame = start
            targetSnapshot?.alpha = 0
            targetSnapshot?.layer.masksToBounds = true
            targetSnapshot?.layer.cornerRadius = source.layer.cornerRadius
            if let targetSnapshot { addSubview(targetSnapshot) }
            sharedElements.append(SharedElementAnimation(
                snapshot: snapshot,
                targetSnapshot: targetSnapshot,
                source: source,
                destination: destination,
                startFrame: start,
                endFrame: end,
                startRadius: source.layer.cornerRadius,
                endRadius: destination.layer.cornerRadius,
                config: config
            ))
        }
    }

    private func sharedElementConfig(for view: UIView) -> SharedElementConfig? {
        guard let encoded = view.layer.value(forKey: "pamSharedTransitionConfig") as? String,
              let data = encoded.data(using: .utf8),
              var config = try? JSONDecoder().decode(SharedElementConfig.self, from: data) else {
            return nil
        }
        config.durationMs = min(max(config.durationMs, 0), 2_000)
        config.easing = min(max(config.easing, 1), 3)
        config.resizeMode = min(max(config.resizeMode, 1), 3)
        config.damping = min(max(config.damping, 0.01), 1)
        config.stiffness = min(max(config.stiffness, 1), 1_000)
        config.mass = min(max(config.mass, 0.1), 10)
        return config
    }

    private func sharedElementViews(in root: UIView) -> [String: UIView] {
        var result: [String: UIView] = [:]
        func visit(_ view: UIView) {
            if let tag = view.layer.value(forKey: "pamSharedTransitionTag") as? String,
               !tag.isEmpty, result[tag] == nil {
                result[tag] = view
            }
            view.subviews.forEach(visit)
        }
        visit(root)
        return result
    }

    private func sharedElementFrames(in root: UIView?) -> [String: CGRect] {
        guard let root else { return [:] }
        return sharedElementViews(in: root).mapValues { $0.convert($0.bounds, to: self) }
    }

    private func applySharedElementProgress(_ progress: CGFloat) {
        for item in sharedElements {
            let adjusted = sharedElementProgress(progress, config: item.config)
            let endFrame = item.config.resizeMode == 3
                ? CGRect(origin: item.endFrame.origin, size: item.startFrame.size)
                : item.endFrame
            let frame = CGRect(
                x: item.startFrame.minX + (endFrame.minX - item.startFrame.minX) * adjusted,
                y: item.startFrame.minY + (endFrame.minY - item.startFrame.minY) * adjusted,
                width: item.startFrame.width + (endFrame.width - item.startFrame.width) * adjusted,
                height: item.startFrame.height + (endFrame.height - item.startFrame.height) * adjusted
            )
            item.snapshot.frame = frame
            item.targetSnapshot?.frame = frame
            let radius = item.startRadius + (item.endRadius - item.startRadius) * adjusted
            item.snapshot.layer.cornerRadius = radius
            item.targetSnapshot?.layer.cornerRadius = radius
            if let target = item.targetSnapshot {
                item.snapshot.alpha = 1 - adjusted
                target.alpha = adjusted
            }
        }
    }

    private func sharedElementProgress(
        _ progress: CGFloat,
        config: SharedElementConfig
    ) -> CGFloat {
        switch config.easing {
        case 1:
            return progress
        case 3:
            let damping = CGFloat(config.damping)
            let frequency = CGFloat(sqrt(config.stiffness / config.mass) * 0.12)
            return min(max(1 - exp(-damping * 7 * progress) * cos(frequency * progress), 0), 1.08)
        default:
            return progress * progress * (3 - 2 * progress)
        }
    }

    private func clearSharedElements() {
        for item in sharedElements {
            item.source.isHidden = false
            item.destination.isHidden = false
            item.snapshot.removeFromSuperview()
            item.targetSnapshot?.removeFromSuperview()
        }
        sharedElements.removeAll(keepingCapacity: true)
    }

    @objc private func handleEdgePan(_ gesture: UIPanGestureRecognizer) {
        guard gestureEnabled, routeViews.count >= 2 else { return }
        let incoming = routeViews[routeViews.count - 2]
        let outgoing = routeViews[routeViews.count - 1]
        let rtl = effectiveUserInterfaceLayoutDirection == .rightToLeft
        let translation = gesture.translation(in: self)
        let raw = gestureDirection == 2 ? translation.y : translation.x
        let distance = max(0, gestureDirection == 2 ? raw : (rtl ? -raw : raw))
        let extent = gestureDirection == 2 ? bounds.height : bounds.width
        let progress = min(distance / max(extent, 1), 1)
        let sign: CGFloat = rtl ? -1 : 1
        switch gesture.state {
        case .began:
            onGestureStart?()
            incoming.isHidden = false
            outgoing.isHidden = false
            prepareSharedElements(incoming: incoming, outgoing: outgoing)
            fallthrough
        case .changed:
            incoming.isHidden = false
            outgoing.isHidden = false
            outgoing.transform = gestureDirection == 2
                ? CGAffineTransform(translationX: 0, y: bounds.height * progress)
                : CGAffineTransform(translationX: sign * bounds.width * progress, y: 0)
            incoming.transform = gestureDirection == 2
                ? CGAffineTransform(translationX: 0, y: -bounds.height * 0.12 * (1 - progress))
                : CGAffineTransform(translationX: -sign * bounds.width * 0.28 * (1 - progress), y: 0)
            incoming.alpha = 0.82 + 0.18 * progress
            applySharedElementProgress(progress)
        case .ended, .cancelled:
            let rawVelocity = gestureDirection == 2 ? gesture.velocity(in: self).y : gesture.velocity(in: self).x
            let velocity = gestureDirection == 2 ? rawVelocity : rawVelocity * (rtl ? -1 : 1)
            let complete = gesture.state == .ended &&
                (progress >= gestureThreshold || velocity >= 700)
            UIView.animate(
                withDuration: 0.18,
                delay: 0,
                options: [.curveEaseOut, .beginFromCurrentState]
            ) {
                outgoing.transform = complete
                    ? (self.gestureDirection == 2
                        ? CGAffineTransform(translationX: 0, y: self.bounds.height)
                        : CGAffineTransform(translationX: sign * self.bounds.width, y: 0))
                    : .identity
                incoming.transform = complete
                    ? .identity
                    : CGAffineTransform(translationX: -sign * self.bounds.width * 0.28, y: 0)
                incoming.alpha = complete ? 1 : 0.82
                self.applySharedElementProgress(complete ? 1 : 0)
            } completion: { _ in
                incoming.transform = .identity
                outgoing.transform = .identity
                incoming.alpha = 1
                if complete {
                    outgoing.isHidden = true
                    self.interactivePopCommitted = true
                    self.onGestureEnd?()
                    self.onGesturePop?()
                } else {
                    self.clearSharedElements()
                    incoming.isHidden = true
                    self.onGestureCancel?()
                }
            }
        default:
            break
        }
    }

    private func incomingView() -> UIView? {
        switch operation {
        case 2, 4: return routeViews.last
        case 3: return routeViews.count > 1 ? routeViews[routeViews.count - 2] : routeViews.last
        default: return routeViews.last
        }
    }

    private func outgoingView() -> UIView? {
        guard routeViews.count > 1 else { return nil }
        return operation == 3 ? routeViews.last : routeViews[routeViews.count - 2]
    }

    private func finish(incoming: UIView, outgoing: UIView?) {
        clearSharedElements()
        incoming.alpha = 1
        incoming.transform = .identity
        incoming.isHidden = false
        outgoing?.alpha = 1
        outgoing?.transform = .identity
        outgoing?.isHidden = true
        onTransitionEnd?()
    }

    private func showOnlyTop() {
        for (index, view) in routeViews.enumerated() {
            view.isHidden = index != routeViews.count - 1
        }
    }

    private func ensureControllerHierarchy() {
        guard window != nil, navigationController == nil,
              let parent = nearestViewController() else { return }
        let navigation = UINavigationController()
        navigation.setNavigationBarHidden(true, animated: false)
        navigation.interactivePopGestureRecognizer?.isEnabled = false
        parent.addChild(navigation)
        navigation.view.frame = bounds
        navigation.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(navigation.view)
        navigation.didMove(toParent: parent)
        containerController = parent
        navigationController = navigation
        let controllers = routeViews.compactMap { routeControllers[ObjectIdentifier($0)] }
        navigation.setViewControllers(controllers, animated: false)
        applyControllerChrome()
    }

    private func nearestViewController() -> UIViewController? {
        var responder: UIResponder? = self
        while let current = responder {
            if let controller = current as? UIViewController { return controller }
            responder = current.next
        }
        return nil
    }

    private func runControllerTransition() {
        guard let navigation = navigationController,
              let incoming = incomingView() else { return }
        let outgoing = outgoingView()
        let allControllers = routeViews.compactMap { routeControllers[ObjectIdentifier($0)] }
        if interactivePopCommitted && operation == 3 {
            interactivePopCommitted = false
            navigation.setViewControllers(allControllers, animated: false)
            finish(incoming: incoming, outgoing: outgoing)
            applyControllerChrome()
            return
        }
        if operation == 3, let presented = presentedNavigationController {
            presented.dismiss(animated: !UIAccessibility.isReduceMotionEnabled) { [weak self] in
                self?.presentedNavigationController = nil
                self?.finish(incoming: incoming, outgoing: outgoing)
                self?.applyControllerChrome()
            }
            return
        }
        if operation != 3, screenPresentation != 1,
           let controller = allControllers.last {
            let baseControllers = Array(allControllers.dropLast())
            navigation.setViewControllers(baseControllers, animated: false)
            let modal = UINavigationController(rootViewController: controller)
            configurePresentation(modal)
            presentedNavigationController = modal
            applyControllerChrome()
            navigation.present(modal, animated: !UIAccessibility.isReduceMotionEnabled) { [weak self] in
                self?.finish(incoming: incoming, outgoing: outgoing)
            }
            return
        }
        let finalControllers = operation == 3 && allControllers.count > 1
            ? Array(allControllers.dropLast())
            : allControllers
        let kind = transition == 1 ? 2 : transition
        let animated = !UIAccessibility.isReduceMotionEnabled && duration > 0 && kind != 8
        let sharedSourceFrames = animated ? sharedElementFrames(in: outgoing) : [:]
        if animated {
            let animation = CATransition()
            animation.duration = min(max(duration, 0), 2)
            animation.timingFunction = CAMediaTimingFunction(name: .easeOut)
            switch kind {
            case 5, 6, 7, 10, 11:
                animation.type = .fade
            case 4:
                animation.type = .push
                animation.subtype = operation == 3 ? .fromTop : .fromBottom
            case 9:
                animation.type = .push
                animation.subtype = operation == 3 ? .fromBottom : .fromTop
            case 12:
                animation.type = .init(rawValue: "oglFlip")
                animation.subtype = operation == 3 ? .fromLeft : .fromRight
            default:
                animation.type = .push
                let rtl = effectiveUserInterfaceLayoutDirection == .rightToLeft
                animation.subtype = operation == 3
                    ? (rtl ? .fromLeft : .fromRight)
                    : (rtl ? .fromRight : .fromLeft)
            }
            navigation.view.layer.add(animation, forKey: "pam.navigation.controller")
        }
        navigation.setViewControllers(finalControllers, animated: false)
        navigation.view.layoutIfNeeded()
        if animated {
            prepareSharedElements(
                incoming: incoming,
                outgoing: outgoing,
                sourceFrames: sharedSourceFrames
            )
            applySharedElementProgress(0)
            let sharedDuration = sharedElements.map { Double($0.config.durationMs) / 1000 }.max() ?? 0
            UIView.animate(
                withDuration: min(max(max(duration, sharedDuration), 0), 2),
                delay: 0,
                options: [.curveLinear, .beginFromCurrentState]
            ) { [weak self] in
                self?.applySharedElementProgress(1)
            }
        }
        if animated {
            let sharedDuration = sharedElements.map { Double($0.config.durationMs) / 1000 }.max() ?? 0
            DispatchQueue.main.asyncAfter(deadline: .now() + min(max(max(duration, sharedDuration), 0), 2)) { [weak self] in
                self?.finish(incoming: incoming, outgoing: outgoing)
            }
        } else {
            finish(incoming: incoming, outgoing: outgoing)
        }
    }

    internal var routeControllerCount: Int { routeControllers.count }
    internal var usesNativeNavigationController: Bool { navigationController != nil }
    internal var usesNativeModalController: Bool { presentedNavigationController != nil }
    internal var activeSheetDetentCount: Int {
        presentedNavigationController?.sheetPresentationController?.detents.count ?? 0
    }
    internal var activeSharedElementCount: Int { sharedElements.count }

    private func applyControllerChrome() {
        guard let navigation = presentedNavigationController ?? navigationController else { return }
        navigation.setNavigationBarHidden(!headerShown, animated: false)
        navigation.navigationBar.prefersLargeTitles = headerLargeTitleEnabled
        navigation.navigationBar.tintColor = headerTintColor
        let appearance = UINavigationBarAppearance()
        if headerTransparent { appearance.configureWithTransparentBackground() }
        else { appearance.configureWithOpaqueBackground() }
        appearance.backgroundColor = headerBackgroundColor
        if !headerShadowVisible { appearance.shadowColor = .clear }
        navigation.navigationBar.standardAppearance = appearance
        navigation.navigationBar.scrollEdgeAppearance = appearance

        guard let controller = navigation.topViewController as? PamRouteViewController else { return }
        controller.title = screenTitle
        controller.navigationItem.largeTitleDisplayMode = headerLargeTitleEnabled ? .always : .never
        controller.onSearchChange = onSearchChange
        if headerSearchEnabled {
            let search = controller.navigationItem.searchController ?? UISearchController(searchResultsController: nil)
            search.searchResultsUpdater = controller
            search.obscuresBackgroundDuringPresentation = false
            search.searchBar.placeholder = headerSearchPlaceholder
            controller.navigationItem.searchController = search
            controller.navigationItem.hidesSearchBarWhenScrolling = headerLargeTitleEnabled
        } else {
            controller.navigationItem.searchController = nil
        }
    }

    private func configurePresentation(_ modal: UINavigationController) {
        switch screenPresentation {
        case 3: modal.modalPresentationStyle = .currentContext
        case 4: modal.modalPresentationStyle = .fullScreen
        case 5:
            modal.modalPresentationStyle = .overFullScreen
            modal.view.backgroundColor = .clear
        case 6:
            modal.modalPresentationStyle = .overCurrentContext
            modal.view.backgroundColor = .clear
        default: modal.modalPresentationStyle = .pageSheet
        }
        modal.presentationController?.delegate = self
        guard screenPresentation == 7, let sheet = modal.sheetPresentationController else { return }
        if #available(iOS 16.0, *) {
            sheet.detents = sheetDetents.prefix(3).enumerated().map { index, fraction in
                .custom(identifier: .init("pam.detent.\(index + 1)")) { context in
                    context.maximumDetentValue * min(max(fraction, 0.05), 1)
                }
            }
        } else {
            sheet.detents = sheetDetents.prefix(2).map { $0 <= 0.5 ? .medium() : .large() }
        }
        if #available(iOS 16.0, *), !sheet.detents.isEmpty {
            let index = min(max(sheetInitialDetentIndex - 1, 0), sheet.detents.count - 1)
            sheet.selectedDetentIdentifier = sheet.detents[index].identifier
        } else if let fraction = sheetDetents.dropFirst(max(sheetInitialDetentIndex - 1, 0)).first {
            sheet.selectedDetentIdentifier = fraction <= 0.5 ? .medium : .large
        }
        sheet.prefersGrabberVisible = sheetGrabberVisible
        sheet.preferredCornerRadius = sheetCornerRadius > 0 ? sheetCornerRadius : nil
        sheet.prefersScrollingExpandsWhenScrolledToEdge = sheetExpandsWhenScrolledToEdge
    }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        guard presentedNavigationController != nil else { return }
        presentedNavigationController = nil
        onGestureEnd?()
        onGesturePop?()
    }

    private func applyPlatformScreenBehavior() {
        if #available(iOS 16.0, *), navigationOrientation != 1,
           let scene = window?.windowScene {
            let mask: UIInterfaceOrientationMask = switch navigationOrientation {
            case 2: .all
            case 3: [.portrait, .portraitUpsideDown]
            case 4: .portrait
            case 5: .portraitUpsideDown
            case 6: .landscape
            case 7: .landscapeLeft
            case 8: .landscapeRight
            default: .all
            }
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask))
        }
        var responder: UIResponder? = self
        while let current = responder {
            if let controller = current as? UIViewController {
                controller.setNeedsUpdateOfHomeIndicatorAutoHidden()
                break
            }
            responder = current.next
        }
        NotificationCenter.default.post(
            name: Notification.Name("PamNativeHomeIndicatorAutoHide"),
            object: self,
            userInfo: ["hidden": autoHideHomeIndicator]
        )
    }
}
