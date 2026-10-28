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

    // A route presented as a full-screen modal owns the status bar; it
    // reports the resolved StatusBar stack like the root controller.
    override var preferredStatusBarStyle: UIStatusBarStyle {
        PamStatusBarCoordinator.shared.config.style
    }

    override var prefersStatusBarHidden: Bool {
        PamStatusBarCoordinator.shared.config.hidden
    }

    override var preferredStatusBarUpdateAnimation: UIStatusBarAnimation {
        PamStatusBarCoordinator.shared.config.updateAnimation
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
    var screenPresentation = 1 {
        didSet { syncControllerStack() }
    }
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
    /// Retains the custom fade of a presented transparent modal.
    private var modalFade: PamSharedFadeTransition?
    /// A Fade transition with a shared element only fades the top route.
    private var sharedFadeOnlyTop = false
    /// Bumped per transition; a finish from an older one only resets its
    /// transforms and never overrides the newer visibility.
    private var transitionGeneration = 0
    private var routeViews: [UIView] = []
    private var routeControllers: [ObjectIdentifier: PamRouteViewController] = [:]
    private weak var containerController: UIViewController?
    private var navigationController: UINavigationController?
    private var presentedNavigationController: UINavigationController?
    private lazy var sharedElements = PamSharedElementFlights(host: self)
    private lazy var edgeGesture = UIPanGestureRecognizer(
        target: self,
        action: #selector(handleEdgePan(_:))
    )

    /// Edge pops use a screen-edge recognizer like UIKit's own
    /// `interactivePopGestureRecognizer`: scroll views let it win, while a
    /// plain pan inside scrollable content never gets the touch. The plain
    /// pan stays for `fullScreenGestureEnabled` and vertical (direction 2).
    private(set) lazy var screenEdgeGesture: UIScreenEdgePanGestureRecognizer = {
        let recognizer = UIScreenEdgePanGestureRecognizer(
            target: self,
            action: #selector(handleEdgePan(_:))
        )
        recognizer.edges = .left
        return recognizer
    }()

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true
        edgeGesture.delegate = self
        edgeGesture.maximumNumberOfTouches = 1
        edgeGesture.isEnabled = false
        addGestureRecognizer(edgeGesture)
        screenEdgeGesture.delegate = self
        screenEdgeGesture.maximumNumberOfTouches = 1
        addGestureRecognizer(screenEdgeGesture)
    }

    /// Which recognizer handles the pop for the current configuration.
    private var usesScreenEdgeGesture: Bool {
        gestureDirection == 1 && !fullScreenGestureEnabled
    }

    private func syncGestureRecognizers() {
        screenEdgeGesture.edges = effectiveUserInterfaceLayoutDirection == .rightToLeft ? .right : .left
        screenEdgeGesture.isEnabled = gestureEnabled && usesScreenEdgeGesture
        edgeGesture.isEnabled = gestureEnabled && !usesScreenEdgeGesture
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        syncGestureRecognizers()
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
        if let current = routeViews.firstIndex(where: { $0 === view }) {
            // A move (a keep-alive route parked below the stack or coming
            // back on top): the route keeps its controller and view state,
            // only its order changes, which decides incoming/outgoing.
            routeViews.remove(at: current)
            routeViews.insert(view, at: min(max(index, 0), routeViews.count))
        } else {
            routeViews.insert(view, at: min(max(index, 0), routeViews.count))
            routeControllers[ObjectIdentifier(view)] = PamRouteViewController(routeView: view)
        }
        if navigationController == nil, view.superview !== self {
            insertSubview(view, at: min(max(index, 0), subviews.count))
        }
        view.frame = bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        showOnlyTop()
        syncControllerStack()
    }

    /// Moves a route without removing it from the window, so touches in
    /// progress on other routes are not cancelled and its views stay as they
    /// are (a screen mounted ahead by Navigator::prewarm() becoming the top).
    func reorderRoute(_ view: UIView, index: Int) {
        guard let current = routeViews.firstIndex(where: { $0 === view }) else {
            insert(view, index: index)
            return
        }
        routeViews.remove(at: current)
        routeViews.insert(view, at: min(max(index, 0), routeViews.count))
        if navigationController == nil, view.superview === self {
            insertSubview(view, at: min(max(index, 0), max(subviews.count - 1, 0)))
        }
        showOnlyTop()
        syncControllerStack()
    }

    func removeRoute(_ view: UIView) {
        routeViews.removeAll { $0 === view }
        routeControllers.removeValue(forKey: ObjectIdentifier(view))
        view.removeFromSuperview()
        syncControllerStack()
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
        syncGestureRecognizers()
    }

    override func gestureRecognizerShouldBegin(
        _ gestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        guard gestureEnabled, routeViews.count >= 2,
              let pan = gestureRecognizer as? UIPanGestureRecognizer else { return false }
        let point = pan.location(in: self)
        let rtl = effectiveUserInterfaceLayoutDirection == .rightToLeft
        let atLeadingEdge = pan === screenEdgeGesture || fullScreenGestureEnabled || gestureDirection == 2 || (rtl
            ? point.x >= bounds.width - gestureEdgeWidth
            : point.x <= gestureEdgeWidth)
        // Direction from the motion so far: velocity when UIKit has one,
        // otherwise the translation (slow or synthesized drags report ~0).
        let velocity = pan.velocity(in: self)
        let translation = pan.translation(in: self)
        let motion = (abs(velocity.x) + abs(velocity.y)) > 1 ? velocity : translation
        let towardBack = gestureDirection == 2 ? motion.y > 0 : (rtl ? motion.x < 0 : motion.x > 0)
        let dominant = gestureDirection == 2 ? abs(motion.y) >= abs(motion.x) : abs(motion.x) >= abs(motion.y)
        return atLeadingEdge && towardBack && dominant
    }

    /// Scrollable content under the route waits for the pop gesture, as
    /// UIKit does for its own screen-edge recognizer.
    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        guard gestureRecognizer === screenEdgeGesture || gestureRecognizer === edgeGesture else { return false }
        return otherGestureRecognizer.view is UIScrollView
    }

    private func runTransition() {
        transitionGeneration += 1
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
        routeViews.forEach { setRouteHidden($0, $0 !== incoming && $0 !== outgoing) }
        setRouteHidden(incoming, false)
        if let outgoing { setRouteHidden(outgoing, false) }
        layoutIfNeeded()
        prepareSharedElements(incoming: incoming, outgoing: outgoing, popping: operation == 3)
        let kind = transition == 1 ? 2 : transition
        sharedFadeOnlyTop = sharedElements.count > 0
        if PamMotionPolicy.isReduced || duration == 0 || kind == 8 {
            finish(incoming: incoming, outgoing: outgoing)
            return
        }
        applyProgress(0, incoming: incoming, outgoing: outgoing, kind: kind)
        // The outgoing screen only fades/slides: rasterize it once instead
        // of compositing its translucent subtree offscreen every frame.
        let outgoingRasterized = outgoing?.layer.shouldRasterize ?? true
        if let outgoing, !outgoingRasterized {
            outgoing.layer.rasterizationScale = window?.screen.scale ?? UIScreen.main.scale
            outgoing.layer.shouldRasterize = true
        }
        let sharedDuration = sharedElements.duration
        let generation = transitionGeneration
        if sharedElements.count > 0 {
            // The shared elements set the pace and the curve; the routes move
            // in the same animator (a spring in, an ease-in home).
            let animator = sharedElements.animator(duration: min(max(duration, 0), 2)) {
                self.applyProgress(1, incoming: incoming, outgoing: outgoing, kind: kind)
            }
            animator.addCompletion { _ in
                if !outgoingRasterized { outgoing?.layer.shouldRasterize = false }
                self.finish(incoming: incoming, outgoing: outgoing, generation: generation)
            }
            animator.startAnimation()
            return
        }
        UIView.animate(
            withDuration: min(max(max(duration, sharedDuration), 0), 2),
            delay: 0,
            options: [.curveLinear, .beginFromCurrentState]
        ) {
            self.applyProgress(1, incoming: incoming, outgoing: outgoing, kind: kind)
        } completion: { _ in
            if !outgoingRasterized { outgoing?.layer.shouldRasterize = false }
            self.finish(incoming: incoming, outgoing: outgoing, generation: generation)
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
            if !sharedFadeOnlyTop {
                incoming.alpha = progress
                outgoing?.alpha = 1 - progress
            } else if popping {
                // With a shared element only the top route fades: the
                // screen below stays put while its media flies home.
                outgoing?.alpha = 1 - progress
            } else {
                incoming.alpha = progress
            }
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
    }

    private func prepareSharedElements(incoming: UIView, outgoing: UIView?, popping: Bool = true) {
        sharedElements.clear()
        guard !PamMotionPolicy.isReduced, duration > 0, let outgoing else { return }
        sharedElements.capture(outgoing: outgoing, defaultDuration: duration, popping: popping)
        sharedElements.attach(incoming: incoming)
    }

    private func applySharedElementProgress(_ progress: CGFloat) {
        sharedElements.apply(progress)
    }

    private func clearSharedElements() {
        sharedElements.clear()
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
            setRouteHidden(incoming, false)
            setRouteHidden(outgoing, false)
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
                    self.setRouteHidden(outgoing, true)
                    self.interactivePopCommitted = true
                    self.onGestureEnd?()
                    self.onGesturePop?()
                } else {
                    self.clearSharedElements()
                    self.setRouteHidden(incoming, true)
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

    private func finish(incoming: UIView, outgoing: UIView?, generation: Int? = nil) {
        if let generation, generation != transitionGeneration {
            // A newer transition owns visibility now; only undo this one's
            // in-flight transforms.
            [incoming, outgoing].compactMap { $0 }.forEach {
                $0.alpha = 1
                $0.transform = .identity
            }
            return
        }
        sharedElements.finish()
        sharedFadeOnlyTop = false
        incoming.alpha = 1
        incoming.transform = .identity
        setRouteHidden(incoming, false)
        outgoing?.alpha = 1
        outgoing?.transform = .identity
        if let outgoing { setRouteHidden(outgoing, true) }
        onTransitionEnd?()
        PamStatusBarCoordinator.invalidate()
    }

    /// Retained routes stay attached; hiding them must retire their editor too.
    /// endEditing on this subtree cannot resign an editor in the destination.
    private func setRouteHidden(_ view: UIView, _ hidden: Bool) {
        if hidden { view.endEditing(true) }
        guard view.isHidden != hidden else { return }
        view.isHidden = hidden
        PamRouteCoverage.notifyChanged()
    }

    private func showOnlyTop() {
        for (index, view) in routeViews.enumerated() {
            setRouteHidden(view, index != routeViews.count - 1)
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
        syncControllerStack()
    }

    private func controllerStack() -> [UIViewController] {
        routeViews.compactMap { routeControllers[ObjectIdentifier($0)] }
    }

    /// Route mutations can arrive after this host is already attached. Keep
    /// UIKit's stack in lockstep immediately; waiting for a later navigation
    /// revision leaves the initial or prewarmed route outside any window.
    private func syncControllerStack() {
        guard let navigationController else { return }
        let controllers = controllerStack()
        let baseControllers = screenPresentation == 1 || controllers.count <= 1
            ? controllers
            : Array(controllers.dropLast())
        navigationController.setViewControllers(baseControllers, animated: false)
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
            if let fade = presented.transitioningDelegate as? PamSharedFadeTransition,
               !PamMotionPolicy.isReduced, let outgoing {
                // Measured while the modal is still on screen: the flight
                // home starts where the element is (dragged, zoomed, paged).
                sharedElements.capture(outgoing: outgoing, defaultDuration: duration, popping: true)
                fade.incoming = incoming
            }
            presented.dismiss(animated: !PamMotionPolicy.isReduced) { [weak self] in
                self?.presentedNavigationController = nil
                self?.modalFade = nil
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
            if transition == 5, [5, 6].contains(screenPresentation), !PamMotionPolicy.isReduced {
                // A transparent modal that fades: a custom transition fades it
                // over the screen below and flies its shared elements.
                let fade = PamSharedFadeTransition(host: self, duration: min(max(duration, 0), 2))
                fade.incoming = incoming
                modal.transitioningDelegate = fade
                modal.modalPresentationStyle = .custom
                modalFade = fade
                if let outgoing {
                    sharedElements.capture(outgoing: outgoing, defaultDuration: duration, popping: false)
                }
            } else {
                modalFade = nil
                configurePresentation(modal)
            }
            presentedNavigationController = modal
            applyControllerChrome()
            // Present from the host's owning controller. The embedded
            // navigation controller is a child used only for route stacking;
            // asking that child to present can be ignored by UIKit when its
            // containment transition has only just completed.
            let presenter = containerController ?? navigation
            presenter.present(modal, animated: !PamMotionPolicy.isReduced) { [weak self] in
                self?.finish(incoming: incoming, outgoing: outgoing)
            }
            return
        }
        let finalControllers = operation == 3 && allControllers.count > 1
            ? Array(allControllers.dropLast())
            : allControllers
        let kind = transition == 1 ? 2 : transition
        let animated = !PamMotionPolicy.isReduced && duration > 0 && kind != 8
        // Sources are measured and copied while still in the window (a video
        // frame needs it) and hidden before the cross-fade captures them.
        sharedElements.clear()
        if animated, let outgoing {
            sharedElements.capture(outgoing: outgoing, defaultDuration: duration, popping: operation == 3)
        }
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
            sharedElements.attach(incoming: incoming)
            sharedElements.animate(duration: min(max(duration, 0), 2))
        }
        if animated {
            let sharedDuration = sharedElements.duration
            let generation = transitionGeneration
            DispatchQueue.main.asyncAfter(deadline: .now() + min(max(max(duration, sharedDuration), 0), 2)) { [weak self] in
                self?.finish(incoming: incoming, outgoing: outgoing, generation: generation)
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

    /// Pairs the captured sources with the laid-out incoming route (a
    /// presented modal attaches its own view first).
    func attachSharedElements(incoming: UIView) {
        sharedElements.attach(incoming: incoming)
    }

    func sharedElementAnimator(duration: TimeInterval, alongside: @escaping () -> Void) -> UIViewPropertyAnimator {
        sharedElements.animator(duration: duration, alongside: alongside)
    }

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


/// Fade presentation for a transparent modal route (`TransparentModal` +
/// `Fade`) that flies its shared elements: the modal fades in over the
/// screen below, which stays visible, and fades out on dismiss while its
/// elements fly home. UIKit's own transitions would slide the modal and skip
/// the host's flights.
final class PamSharedFadeTransition: NSObject, UIViewControllerTransitioningDelegate, UIViewControllerAnimatedTransitioning {
    private weak var host: PamNavigationHost?
    private let duration: TimeInterval
    private var presenting = true
    weak var incoming: UIView?

    init(host: PamNavigationHost, duration: TimeInterval) {
        self.host = host
        self.duration = duration
    }

    func animationController(
        forPresented presented: UIViewController,
        presenting: UIViewController,
        source: UIViewController
    ) -> UIViewControllerAnimatedTransitioning? {
        self.presenting = true
        return self
    }

    func animationController(forDismissed dismissed: UIViewController) -> UIViewControllerAnimatedTransitioning? {
        presenting = false
        return self
    }

    func presentationController(
        forPresented presented: UIViewController,
        presenting: UIViewController?,
        source: UIViewController
    ) -> UIPresentationController? {
        PamOverPresentation(presentedViewController: presented, presenting: presenting)
    }

    func transitionDuration(using transitionContext: UIViewControllerContextTransitioning?) -> TimeInterval {
        duration
    }

    func animateTransition(using context: UIViewControllerContextTransitioning) {
        let key: UITransitionContextViewKey = presenting ? .to : .from
        guard let view = context.view(forKey: key) else {
            context.completeTransition(!context.transitionWasCancelled)
            return
        }
        if presenting {
            if let controller = context.viewController(forKey: .to) {
                view.frame = context.finalFrame(for: controller)
            }
            context.containerView.addSubview(view)
            view.alpha = 0
            view.layoutIfNeeded()
        }
        if let incoming { host?.attachSharedElements(incoming: incoming) }
        let presenting = presenting
        let animator = host?.sharedElementAnimator(duration: duration) {
            view.alpha = presenting ? 1 : 0
        } ?? UIViewPropertyAnimator(duration: duration, curve: .easeInOut) {
            view.alpha = presenting ? 1 : 0
        }
        animator.addCompletion { _ in
            if !presenting { view.removeFromSuperview() }
            context.completeTransition(!context.transitionWasCancelled)
        }
        animator.startAnimation()
    }
}

/// Keeps the presenting screen in the window under a transparent modal.
private final class PamOverPresentation: UIPresentationController {
    override var shouldRemovePresentersView: Bool { false }
}
