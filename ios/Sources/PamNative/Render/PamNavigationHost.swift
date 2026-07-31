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

final class PamNavigationHost: UIView, UIGestureRecognizerDelegate {
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
    private var revision: Int64 = 0
    private var gestureEnabled = true
    private var gestureEdgeWidth: CGFloat = 24
    private var gestureThreshold: CGFloat = 0.35
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
    private struct SharedElementAnimation {
        let snapshot: UIView
        let source: UIView
        let destination: UIView
        let startFrame: CGRect
        let endFrame: CGRect
        let startRadius: CGFloat
        let endRadius: CGFloat
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
        onPop: (() -> Void)?,
        onTransitionEnd: (() -> Void)?,
        onGestureStart: (() -> Void)?,
        onGestureEnd: (() -> Void)?,
        onGestureCancel: (() -> Void)?
    ) {
        gestureEnabled = enabled
        gestureEdgeWidth = min(max(edgeWidth, 8), 160)
        gestureThreshold = min(max(threshold, 0.1), 0.9)
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
        guard navigationController == nil, gestureEnabled, routeViews.count >= 2,
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
        UIView.animate(
            withDuration: min(max(duration, 0), 2),
            delay: 0,
            options: [.curveEaseOut, .beginFromCurrentState]
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
        outgoing?.alpha = 1
        outgoing?.transform = .identity

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
        default:
            break
        }
        applySharedElementProgress(progress)
    }

    private func prepareSharedElements(incoming: UIView, outgoing: UIView?) {
        clearSharedElements()
        guard !UIAccessibility.isReduceMotionEnabled, duration > 0,
              let outgoing else { return }
        let sources = sharedElementViews(in: outgoing)
        let destinations = sharedElementViews(in: incoming)
        for (tag, source) in sources {
            guard let destination = destinations[tag],
                  source.bounds.width > 0, source.bounds.height > 0,
                  destination.bounds.width > 0, destination.bounds.height > 0,
                  let snapshot = source.snapshotView(afterScreenUpdates: false) else { continue }
            let start = source.convert(source.bounds, to: self)
            let end = destination.convert(destination.bounds, to: self)
            snapshot.frame = start
            snapshot.layer.masksToBounds = true
            snapshot.layer.cornerRadius = source.layer.cornerRadius
            source.isHidden = true
            destination.isHidden = true
            addSubview(snapshot)
            sharedElements.append(SharedElementAnimation(
                snapshot: snapshot,
                source: source,
                destination: destination,
                startFrame: start,
                endFrame: end,
                startRadius: source.layer.cornerRadius,
                endRadius: destination.layer.cornerRadius
            ))
        }
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

    private func applySharedElementProgress(_ progress: CGFloat) {
        for item in sharedElements {
            item.snapshot.frame = CGRect(
                x: item.startFrame.minX + (item.endFrame.minX - item.startFrame.minX) * progress,
                y: item.startFrame.minY + (item.endFrame.minY - item.startFrame.minY) * progress,
                width: item.startFrame.width + (item.endFrame.width - item.startFrame.width) * progress,
                height: item.startFrame.height + (item.endFrame.height - item.startFrame.height) * progress
            )
            item.snapshot.layer.cornerRadius = item.startRadius + (item.endRadius - item.startRadius) * progress
        }
    }

    private func clearSharedElements() {
        for item in sharedElements {
            item.source.isHidden = false
            item.destination.isHidden = false
            item.snapshot.removeFromSuperview()
        }
        sharedElements.removeAll(keepingCapacity: true)
    }

    @objc private func handleEdgePan(_ gesture: UIPanGestureRecognizer) {
        guard gestureEnabled, routeViews.count >= 2 else { return }
        let incoming = routeViews[routeViews.count - 2]
        let outgoing = routeViews[routeViews.count - 1]
        let rtl = effectiveUserInterfaceLayoutDirection == .rightToLeft
        let raw = gesture.translation(in: self).x
        let distance = max(0, rtl ? -raw : raw)
        let progress = min(distance / max(bounds.width, 1), 1)
        let sign: CGFloat = rtl ? -1 : 1
        switch gesture.state {
        case .began:
            onGestureStart?()
            incoming.isHidden = false
            outgoing.isHidden = false
            fallthrough
        case .changed:
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
                    self.interactivePopCommitted = true
                    self.onGestureEnd?()
                    self.onGesturePop?()
                } else {
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
        let finalControllers = operation == 3 && allControllers.count > 1
            ? Array(allControllers.dropLast())
            : allControllers
        let kind = transition == 1 ? 2 : transition
        let animated = !UIAccessibility.isReduceMotionEnabled && duration > 0 && kind != 8
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
        if animated {
            DispatchQueue.main.asyncAfter(deadline: .now() + min(max(duration, 0), 2)) { [weak self] in
                self?.finish(incoming: incoming, outgoing: outgoing)
            }
        } else {
            finish(incoming: incoming, outgoing: outgoing)
        }
    }

    internal var routeControllerCount: Int { routeControllers.count }
    internal var usesNativeNavigationController: Bool { navigationController != nil }

    private func applyControllerChrome() {
        guard let navigation = navigationController else { return }
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
