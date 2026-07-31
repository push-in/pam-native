import Foundation
import ImageIO
import UIKit

public final class PamRenderer {
    private let host: UIView
    private let dispatchEvent: (Int64, Int, Data) -> Void
    private let nativeViews = NativeViewRegistry()
    private let imageSession: URLSession
    private let imageSessionDelegate: ImageLoadSessionDelegate
    private var imageLoadContexts: [Int: ImageLoadContext] = [:]

    private var views: [Int64: UIView] = [:]
    private var nodes: [Int64: NodeState] = [:]
    private var frames: [Int64: Frame] = [:]
    private var children: [Int64: [Int64]] = [:]
    private var eventBridges: [Int64: [Int: EventBridge]] = [:]
    private var interactionBridges: [Int64: PamInteractionBridge] = [:]
    private var animationDelegates: [Int64: PamAnimationDelegate] = [:]
    private var localModalActions: [Int64: UIAction.Identifier] = [:]
    private var borderLayers: [Int64: PamBorderLayers] = [:]
    private var rootId: Int64 = 0
    private var nextMountOrder: Int64 = 1
    private let maxEventBytes = 1024 * 1024

    public init(hostView: UIView, dispatchEvent: @escaping (Int64, Int, Data) -> Void) {
        self.host = hostView
        self.dispatchEvent = dispatchEvent
        let sessionDelegate = ImageLoadSessionDelegate()
        self.imageSessionDelegate = sessionDelegate
        self.imageSession = URLSession(
            configuration: .default,
            delegate: sessionDelegate,
            delegateQueue: OperationQueue.main,
        )
        sessionDelegate.renderer = self
    }

    public func commit(_ batches: [[Mutation]]) {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in
                self?.commit(batches)
            }
            return
        }

        guard !batches.isEmpty else {
            return
        }

        var dirtyLayouts = Set<Int64>()

        for batch in batches {
            for mutation in batch {
                switch mutation {
                case let .create(node):
                    create(node)
                case let .remove(id):
                    remove(id)
                case let .update(id, key, value):
                    update(id: id, key: key, value: value)
                case let .move(id, parent, index):
                    move(id: id, parent: parent, index: index)
                case let .layout(id, frame):
                    frames[id] = frame
                    dirtyLayouts.insert(id)
                case let .setRoot(id):
                    rootId = id
                }
            }
        }

        syncLocalModalTriggers()
        dirtyLayouts.forEach { id in
            applyLayout(id)
        }
    }

    public func trimMemory(_ critical: Bool) {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in
                self?.trimMemory(critical)
            }
            return
        }
        guard critical else { return }
        for node in nodes.values {
            cancelImageLoad(for: node)
        }
        PamMediaDiskCache.shared.trimMemory()
    }

    public func setApplicationActive(_ active: Bool) {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in self?.setApplicationActive(active) }
            return
        }
        for view in views.values {
            if let media = view as? PamMediaView {
                if active {
                    media.onHostResume()
                } else {
                    media.onHostPause()
                }
            }
        }
    }

    public func close() {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in
                self?.close()
            }
            return
        }

        for (_, bridgeByKind) in eventBridges {
            for (_, bridge) in bridgeByKind {
                bridge.detach()
            }
        }
        eventBridges.removeAll()
        interactionBridges.values.forEach { $0.detach() }
        interactionBridges.removeAll()
        animationDelegates.removeAll()
        localModalActions.removeAll()
        borderLayers.removeAll()

        for node in nodes.values {
            cancelImageLoad(for: node)
        }
        imageSession.invalidateAndCancel()
        imageLoadContexts.removeAll()

        for (nodeId, view) in views {
            nativeViews.release(view: view)
            view.removeFromSuperview()
            nodes[nodeId]?.childrenNeedRethrow = nil
        }

        nodes.removeAll()
        views.removeAll()
        frames.removeAll()
        children.removeAll()
        host.subviews.forEach { $0.removeFromSuperview() }
        host.layoutIfNeeded()
        nativeViews.close()

        rootId = 0
        nextMountOrder = 1
    }

    private func create(_ spec: NodeSpec) {
        guard nodes[spec.id] == nil else {
            return
        }

        let state = NodeState(
            id: spec.id,
            parent: spec.parent,
            index: spec.index,
            kind: spec.kind,
            properties: spec.properties,
            mountOrder: nextMountOrder,
            imageTask: nil,
            imageGeneration: 0,
            imageLoading: false,
            imageProgressLoaded: 0,
            imageProgressTotal: 0,
            imageProgressScheduled: false,
        )
        nextMountOrder += 1
        nodes[spec.id] = state
        addChild(to: state.parent, child: state.id)

        let view = createView(for: spec)
        views[spec.id] = view
        view.tag = Int(clamping: state.id)
        attach(view, parentId: state.parent, index: state.index)

        for (key, value) in state.properties {
            applyProperty(view: view, nodeId: state.id, key: key, value: value)
        }

        installEvents(for: state.id)
    }

    private func syncLocalModalTriggers() {
        let modalPrefix = "pam:local-modal:"
        let triggerPrefix = "pam:local-modal-trigger:"
        var modals: [String: PamModalHost] = [:]

        for (nodeId, state) in nodes where state.kind == .modal {
            guard
                let marker = state.properties[PamConstants.value]?.textOrNil(),
                marker.hasPrefix(modalPrefix),
                let modal = views[nodeId] as? PamModalHost
            else {
                continue
            }
            modals[String(marker.dropFirst(modalPrefix.count))] = modal
        }

        for (nodeId, view) in views {
            guard let button = view as? UIButton else { continue }
            if let identifier = localModalActions.removeValue(forKey: nodeId) {
                button.removeAction(identifiedBy: identifier, for: .touchUpInside)
            }
            guard
                let marker = nodes[nodeId]?
                    .properties[PamConstants.value]?.textOrNil(),
                marker.hasPrefix(triggerPrefix),
                let modal = modals[String(marker.dropFirst(triggerPrefix.count))]
            else {
                continue
            }
            let identifier = UIAction.Identifier(
                "dev.pam.local-modal.\(nodeId)"
            )
            button.addAction(
                UIAction(identifier: identifier) { [weak modal] _ in
                    modal?.setVisible(true)
                },
                for: .touchUpInside
            )
            localModalActions[nodeId] = identifier
        }
    }

    private func remove(_ id: Int64) {
        guard let state = nodes[id] else {
            return
        }
        let childIds = children[id] ?? []
        for childId in childIds {
            remove(childId)
        }

        eventBridges[id]?.forEach { _, bridge in
            bridge.detach()
        }
        eventBridges[id] = nil
        interactionBridges.removeValue(forKey: id)?.detach()
        animationDelegates[id] = nil
        localModalActions[id] = nil
        borderLayers[id]?.remove()
        borderLayers[id] = nil

        cancelImageLoad(for: state)

        if let view = views[id] {
            nativeViews.release(view: view)
            if let navigation = views[state.parent] as? PamNavigationHost {
                navigation.removeRoute(view)
            } else {
                view.removeFromSuperview()
            }
        }

        removeChild(from: state.parent, child: id)
        views[id] = nil
        nodes[id] = nil
        frames[id] = nil
        children[id] = nil
        imageLoadContexts = imageLoadContexts.filter { _, context in
            context.nodeId != id
        }

        if id == rootId {
            rootId = 0
        }
    }

    private func update(id: Int64, key: Int, value: PropValue?) {
        guard let state = nodes[id], let view = views[id] else {
            return
        }

        if let value {
            state.properties[key] = value
            applyProperty(view: view, nodeId: id, key: key, value: value)
        } else {
            state.properties.removeValue(forKey: key)
            resetProperty(view: view, nodeId: id, key: key, state: state)
            if key == PamConstants.source,
               let imageView = imageView(for: view) {
                cancelImageLoad(for: state)
                imageView.image = nil
                (view as? PamDrawingCanvas)?.imageDidChange()
            }
        }

        if isEventProperty(key) ||
            key == PamConstants.scrollHorizontal ||
            key == PamConstants.endReachedThreshold {
            installEvents(for: id)
        }

        if state.kind == .customView && key == PamConstants.hostProperties {
            nativeViews.update(
                view: view,
                properties: value?.propertiesOrNil() ?? [:],
            )
        }

        if key == PamConstants.source, let source = value?.textOrNil() {
            if let imageView = imageView(for: view) {
                loadImage(source, into: imageView, nodeId: id)
            }
        }
    }

    private func move(id: Int64, parent: Int64, index: Int) {
        guard let state = nodes[id], let view = views[id] else {
            return
        }

        view.removeFromSuperview()
        removeChild(from: state.parent, child: id)
        state.parent = parent
        state.index = index
        addChild(to: parent, child: id)
        attach(view, parentId: parent, index: index)
    }

    private func applyLayout(_ id: Int64) {
        guard let frame = frames[id], let state = nodes[id], let view = views[id] else {
            return
        }

        let effectiveParent = (state.parent == 0 ? rootId : state.parent)
        let parentFrame = frames[effectiveParent] ?? Frame(
            x: 0,
            y: 0,
            width: Float(host.bounds.width),
            height: Float(host.bounds.height),
        )

        var left = CGFloat(frame.x - parentFrame.x)
        var top = CGFloat(frame.y - parentFrame.y)
        var width = CGFloat(max(0, frame.width))
        var height = CGFloat(max(0, frame.height))

        if let parentState = nodes[effectiveParent],
           parentState.kind == .safeAreaView,
           Int(parentState.properties[PamConstants.safeAreaMode]?.integer() ?? 1) == 1,
           let parentView = views[effectiveParent] {
            let insets = parentView.safeAreaInsets

            if parentState.properties[PamConstants.safeAreaLeft]?.flag() ?? true {
                left += insets.left
                width -= insets.left
            }
            if parentState.properties[PamConstants.safeAreaRight]?.flag() ?? true {
                width -= insets.right
            }
            if parentState.properties[PamConstants.safeAreaTop]?.flag() ?? true {
                top += insets.top
                height -= insets.top
            }
            if parentState.properties[PamConstants.safeAreaBottom]?.flag() ?? true {
                height -= insets.bottom
            }
        }

        if let parentState = nodes[effectiveParent],
           let parentView = views[effectiveParent] {
            let horizontal = parentState.kind == .row
            let vertical = parentState.kind == .column
            if horizontal || vertical {
                let engineExtent = horizontal
                    ? CGFloat(parentFrame.width)
                    : CGFloat(parentFrame.height)
                let renderedExtent = horizontal
                    ? parentView.bounds.width
                    : parentView.bounds.height
                let viewportReduction = engineExtent - renderedExtent
                let siblings = (children[parentState.id] ?? [])
                    .compactMap { nodes[$0] }
                    .sorted { $0.index < $1.index }
                let totalGrow = siblings.reduce(CGFloat.zero) { result, sibling in
                    result + max(
                        0,
                        CGFloat(
                            sibling.properties[PamConstants.flexGrow]?.decimal() ?? 0
                        ),
                    )
                }
                if renderedExtent > 0, viewportReduction > 0, totalGrow > 0 {
                    var growBefore = CGFloat.zero
                    for sibling in siblings {
                        if sibling.id == state.id {
                            break
                        }
                        growBefore += max(
                            0,
                            CGFloat(
                                sibling.properties[PamConstants.flexGrow]?.decimal() ?? 0
                            ),
                        )
                    }
                    let ownGrow = max(
                        0,
                        CGFloat(state.properties[PamConstants.flexGrow]?.decimal() ?? 0),
                    )
                    let reductionBefore = (
                        viewportReduction * growBefore / totalGrow
                    ).rounded()
                    let reductionThrough = (
                        viewportReduction * (growBefore + ownGrow) / totalGrow
                    ).rounded()
                    let ownReduction = max(0, reductionThrough - reductionBefore)
                    if horizontal {
                        left -= reductionBefore
                        width -= ownReduction
                    } else {
                        top -= reductionBefore
                        height -= ownReduction
                    }
                }
            }
        }

        if state.kind == .safeAreaView,
           Int(state.properties[PamConstants.safeAreaMode]?.integer() ?? 1) == 2 {
            let insets = view.safeAreaInsets

            if state.properties[PamConstants.safeAreaLeft]?.flag() ?? true {
                left += insets.left
                width -= insets.left
            }
            if state.properties[PamConstants.safeAreaRight]?.flag() ?? true {
                width -= insets.right
            }
            if state.properties[PamConstants.safeAreaTop]?.flag() ?? true {
                top += insets.top
                height -= insets.top
            }
            if state.properties[PamConstants.safeAreaBottom]?.flag() ?? true {
                height -= insets.bottom
            }
        }

        let nextFrame = CGRect(
            x: left,
            y: top,
            width: max(0, width),
            height: max(0, height),
        )
        let layoutChanged = view.frame != nextFrame
        view.frame = nextFrame
        if layoutChanged {
            children[state.id]?.forEach { applyLayout($0) }
        }
        applyBorder(view: view, nodeId: id)
        applyBoxShadow(view: view, nodeId: id)
        applyTextAlignment(view: view, nodeId: id)
    }

    private func addChild(to parent: Int64, child: Int64) {
        var siblings = children[parent] ?? []
        if let existing = siblings.firstIndex(of: child) {
            siblings.remove(at: existing)
        }
        siblings.append(child)
        siblings.sort {
            (nodes[$0]?.index ?? Int.max) < (nodes[$1]?.index ?? Int.max)
        }
        children[parent] = siblings
    }

    private func removeChild(from parent: Int64, child: Int64) {
        guard var siblings = children[parent] else {
            return
        }
        siblings.removeAll { $0 == child }
        if siblings.isEmpty {
            children.removeValue(forKey: parent)
        } else {
            children[parent] = siblings
        }
    }

    private func attach(_ view: UIView, parentId: Int64, index: Int) {
        if parentId == 0 {
            let list = children[0] ?? []
            let target = min(max(index, 0), list.count)
            if target >= host.subviews.count {
                host.addSubview(view)
            } else {
                host.insertSubview(view, at: target)
            }
            return
        }

        guard let parent = views[parentId] else {
            host.addSubview(view)
            return
        }

        if let navigation = parent as? PamNavigationHost {
            navigation.insert(view, index: index)
            return
        }
        if let modal = parent as? PamModalHost {
            modal.insert(view, index: index)
            return
        }

        let target = min(max(index, 0), parent.subviews.count)
        if target >= parent.subviews.count {
            parent.addSubview(view)
        } else {
            parent.insertSubview(view, at: target)
        }
    }

    private func hostName(for state: NodeState) -> String {
        if let hostName = state.properties[PamConstants.hostName]?.textOrNil() {
            return hostName
        }
        return "com.pam.native.custom"
    }

    private func createView(for spec: NodeSpec) -> UIView {
        if spec.kind == .customView {
            return nativeViews.create(name: hostName(for: NodeState(
                id: spec.id,
                parent: spec.parent,
                index: spec.index,
                kind: spec.kind,
                properties: spec.properties,
                mountOrder: 0,
                imageTask: nil,
                imageGeneration: 0,
                imageLoading: false,
                imageProgressLoaded: 0,
                imageProgressTotal: 0,
                imageProgressScheduled: false,
            ))) { [weak self] kind, payload in
                self?.dispatchNativeViewEvent(nodeId: spec.id, kind: kind, payload: payload)
            }
        }

        switch spec.kind {
        case .safeAreaView:
            let view = PamSafeAreaView()
            view.onSafeAreaInsetsDidChange = { [weak self] in
                guard let self else { return }
                self.applyLayout(spec.id)
                self.children[spec.id]?.forEach { self.applyLayout($0) }
            }
            return view
        case .screen, .column, .row, .view, .keyboardAvoidingView, .inputAccessoryView:
            return UIView()
        case .pressable:
            return UIButton(type: .system)
        case .button:
            return UIButton(type: .system)
        case .text:
            return UILabel()
        case .input:
            let field = PamInputField()
            field.borderStyle = .none
            return field
        case .image, .imageBackground:
            return UIImageView()
        case .scroll:
            return PamAnchoredScrollView()
        case .list, .sectionList, .virtualList:
            return UIScrollView()
        case .spacer:
            return UIView()
        case .activityIndicator:
            let indicator = PamVuetifySpinner()
            indicator.startAnimating()
            return indicator
        case .toggle:
            return PamVuetifySwitch()
        case .modal:
            return PamModalHost()
        case .drawerLayout:
            return PamDrawerLayout()
        case .statusBar:
            return UIView()
        case .navigationHost:
            return PamNavigationHost()
        case .webView:
            return PamWebView()
        case .media:
            return PamMediaView()
        case .drawingCanvas:
            return PamDrawingCanvas()
        case .refreshControl:
            return PamRefreshContainer()
        case .customView:
            return UIView()
        }
    }

    private func installEvents(for nodeId: Int64) {
        guard let state = nodes[nodeId], let view = views[nodeId] else {
            return
        }

        let previous = eventBridges[nodeId] ?? [:]
        for (_, bridge) in previous {
            bridge.detach()
        }
        eventBridges[nodeId] = [:]

        let eventProperties = Set(state.properties.keys)

        if state.kind == .customView {
            return
        }

        if eventProperties.contains(PamConstants.onClickOutside) ||
            eventProperties.contains(PamConstants.onIntersect) ||
            eventProperties.contains(PamConstants.onMutate) ||
            eventProperties.contains(PamConstants.onResize) ||
            eventProperties.contains(PamConstants.onTouchStart) ||
            eventProperties.contains(PamConstants.onTouchMove) ||
            eventProperties.contains(PamConstants.onTouchEnd) {
            let bridge = EventBridge(
                nodeId: nodeId,
                kind: EventKind.clickOutside.rawValue,
                dispatchEvent: dispatchEvent
            )
            bridge.attachDirectives(
                to: view,
                host: host,
                clickOutside: eventProperties.contains(PamConstants.onClickOutside),
                intersect: eventProperties.contains(PamConstants.onIntersect),
                mutate: eventProperties.contains(PamConstants.onMutate),
                resize: eventProperties.contains(PamConstants.onResize),
                touchStart: eventProperties.contains(PamConstants.onTouchStart),
                touchMove: eventProperties.contains(PamConstants.onTouchMove),
                touchEnd: eventProperties.contains(PamConstants.onTouchEnd)
            )
            eventBridges[nodeId]?[EventKind.clickOutside.rawValue] = bridge
        }
        if let rippleValue = state.properties[PamConstants.rippleColor] {
            let bridge = EventBridge(
                nodeId: nodeId,
                kind: EventKind.press.rawValue,
                dispatchEvent: dispatchEvent
            )
            bridge.attachRipple(
                to: view,
                color: rippleValue.integerOrNil() ?? 0,
                alpha: state.properties[PamConstants.rippleAlpha]?.decimalOrNil() ?? 0.12,
                radius: state.properties[PamConstants.rippleRadius]?.decimalOrNil()
            )
            eventBridges[nodeId]?[PamConstants.rippleColor] = bridge
        }
        if let type = state.properties[PamConstants.gestureType]?.integerOrNil(),
           (1...6).contains(type),
           state.properties[PamConstants.gestureEnabled]?.boolOrNil() ?? true {
            let bridge = EventBridge(
                nodeId: nodeId,
                kind: EventKind.gestureUpdate.rawValue,
                dispatchEvent: dispatchEvent
            )
            bridge.attachSemanticGesture(
                to: view,
                type: Int(type),
                minimumPointers: Int(
                    state.properties[PamConstants.gestureMinPointers]?.integerOrNil() ?? 1
                ),
                maximumPointers: Int(
                    state.properties[PamConstants.gestureMaxPointers]?.integerOrNil() ?? 1
                ),
                direction: Int(
                    state.properties[PamConstants.gestureDirection]?.integerOrNil() ?? 1
                ),
                composition: Int(
                    state.properties[PamConstants.gestureComposition]?.integerOrNil() ?? 1
                ),
                minimumDistance:
                    state.properties[PamConstants.gestureMinDistance]?.decimalOrNil() ?? 12,
                minimumDuration: Double(
                    state.properties[PamConstants.gestureMinDurationMs]?.integerOrNil() ?? 0
                ) / 1_000,
                emitsBegin: eventProperties.contains(PamConstants.onGestureBegin),
                emitsUpdate: eventProperties.contains(PamConstants.onGestureUpdate),
                emitsEnd: eventProperties.contains(PamConstants.onGestureEnd),
                emitsCancel: eventProperties.contains(PamConstants.onGestureCancel),
                nativeTransform:
                    state.properties[PamConstants.gestureNativeTransform]?.boolOrNil() ?? false,
                nativeMinimumScale:
                    state.properties[PamConstants.gestureNativeMinScale]?.decimalOrNil() ?? 1,
                nativeMaximumScale:
                    state.properties[PamConstants.gestureNativeMaxScale]?.decimalOrNil() ?? 4
            )
            eventBridges[nodeId]?[EventKind.gestureUpdate.rawValue] = bridge
        }

        let inputField = view as? PamInputField

        if let button = view as? UIButton {
            if eventProperties.contains(PamConstants.onPress) {
                let bridge = EventBridge(nodeId: nodeId, kind: EventKind.press.rawValue, dispatchEvent: dispatchEvent)
                bridge.attachButtonPress(button)
                eventBridges[nodeId]?[EventKind.press.rawValue] = bridge
            }

            if eventProperties.contains(PamConstants.onLongPress) {
                let bridge = EventBridge(nodeId: nodeId, kind: EventKind.longPress.rawValue, dispatchEvent: dispatchEvent)
                bridge.attachLongPress(to: button)
                eventBridges[nodeId]?[EventKind.longPress.rawValue] = bridge
            }

            if eventProperties.contains(PamConstants.onPressIn) ||
                eventProperties.contains(PamConstants.onPressOut) ||
                eventProperties.contains(PamConstants.onPressMove) {
                let bridge = EventBridge(nodeId: nodeId, kind: EventKind.pressMove.rawValue, dispatchEvent: dispatchEvent)
                bridge.attachPressPointer(
                    to: button,
                    pressIn: eventProperties.contains(PamConstants.onPressIn) ? EventKind.pressIn.rawValue : nil,
                    pressOut: eventProperties.contains(PamConstants.onPressOut) ? EventKind.pressOut.rawValue : nil,
                    pressMove: eventProperties.contains(PamConstants.onPressMove) ? EventKind.pressMove.rawValue : nil,
                )
                eventBridges[nodeId]?[EventKind.pressMove.rawValue] = bridge
            }

            if eventProperties.contains(PamConstants.onSubmit) {
                let bridge = EventBridge(nodeId: nodeId, kind: EventKind.submit.rawValue, dispatchEvent: dispatchEvent)
                bridge.attachSubmit(button)
                eventBridges[nodeId]?[EventKind.submit.rawValue] = bridge
            }
            return
        }

        if eventProperties.contains(PamConstants.onPress), !state.kind.isContainer {
            let bridge = EventBridge(nodeId: nodeId, kind: EventKind.press.rawValue, dispatchEvent: dispatchEvent)
            bridge.attachPress(to: view)
            eventBridges[nodeId]?[EventKind.press.rawValue] = bridge
        }

        if eventProperties.contains(PamConstants.onLongPress) {
            let bridge = EventBridge(nodeId: nodeId, kind: EventKind.longPress.rawValue, dispatchEvent: dispatchEvent)
            bridge.attachLongPress(to: view)
            eventBridges[nodeId]?[EventKind.longPress.rawValue] = bridge
        }

        if eventProperties.contains(PamConstants.onPressIn) ||
            eventProperties.contains(PamConstants.onPressOut) ||
            eventProperties.contains(PamConstants.onPressMove) {
            let bridge = EventBridge(nodeId: nodeId, kind: EventKind.pressMove.rawValue, dispatchEvent: dispatchEvent)
            bridge.attachPressPointer(
                to: view,
                pressIn: eventProperties.contains(PamConstants.onPressIn) ? EventKind.pressIn.rawValue : nil,
                pressOut: eventProperties.contains(PamConstants.onPressOut) ? EventKind.pressOut.rawValue : nil,
                pressMove: eventProperties.contains(PamConstants.onPressMove) ? EventKind.pressMove.rawValue : nil,
            )
            eventBridges[nodeId]?[EventKind.pressMove.rawValue] = bridge
        }

        if eventProperties.contains(PamConstants.onToggle), let switchView = view as? PamVuetifySwitch {
            let bridge = EventBridge(nodeId: nodeId, kind: EventKind.toggle.rawValue, dispatchEvent: dispatchEvent)
            bridge.attachControlValueChanged(switchView)
            eventBridges[nodeId]?[EventKind.toggle.rawValue] = bridge
        }

        if eventProperties.contains(PamConstants.onChange), let field = inputField {
            let bridge = EventBridge(nodeId: nodeId, kind: EventKind.change.rawValue, dispatchEvent: dispatchEvent)
            bridge.attachTextField(field)
            eventBridges[nodeId]?[EventKind.change.rawValue] = bridge
        }

        if eventProperties.contains(PamConstants.onSubmit), let field = inputField {
            let bridge = EventBridge(nodeId: nodeId, kind: EventKind.submit.rawValue, dispatchEvent: dispatchEvent)
            bridge.attachSubmit(field)
            eventBridges[nodeId]?[EventKind.submit.rawValue] = bridge
        }

        if eventProperties.contains(PamConstants.onFocus), let field = inputField {
            let bridge = EventBridge(nodeId: nodeId, kind: EventKind.focus.rawValue, dispatchEvent: dispatchEvent)
            bridge.attachFocus(field)
            eventBridges[nodeId]?[EventKind.focus.rawValue] = bridge
        }

        if eventProperties.contains(PamConstants.onBlur), let field = inputField {
            let bridge = EventBridge(nodeId: nodeId, kind: EventKind.blur.rawValue, dispatchEvent: dispatchEvent)
            bridge.attachBlur(field)
            eventBridges[nodeId]?[EventKind.blur.rawValue] = bridge
        }

        let hasSelection = eventProperties.contains(PamConstants.onInputSelectionChange)
        let hasContentSize = eventProperties.contains(PamConstants.onInputContentSizeChange)
        let hasKey = eventProperties.contains(PamConstants.onInputKeyPress)
        let hasInputEndEditing = eventProperties.contains(PamConstants.onInputEndEditing)
        if (hasSelection || hasContentSize || hasKey || hasInputEndEditing), let field = inputField {
            let bridge = EventBridge(nodeId: nodeId, kind: EventKind.inputEndEditing.rawValue, dispatchEvent: dispatchEvent)
            bridge.attachInputCallbacks(
                field: field,
                hasSelection: hasSelection,
                hasContentSize: hasContentSize,
                hasKey: hasKey,
                hasEndEditing: hasInputEndEditing,
            )
            if hasSelection {
                eventBridges[nodeId]?[EventKind.inputSelectionChange.rawValue] = bridge
            }
            if hasContentSize {
                eventBridges[nodeId]?[EventKind.inputContentSizeChange.rawValue] = bridge
            }
            if hasKey {
                eventBridges[nodeId]?[EventKind.inputKeyPress.rawValue] = bridge
            }
            if hasInputEndEditing {
                eventBridges[nodeId]?[EventKind.inputEndEditing.rawValue] = bridge
            }
        }

        let hasScroll = eventProperties.contains(PamConstants.onScroll)
        let hasEndReached = eventProperties.contains(PamConstants.onEndReached)
        if (hasScroll || hasEndReached), let scroll = view as? UIScrollView {
            let bridge = EventBridge(nodeId: nodeId, kind: EventKind.scroll.rawValue, dispatchEvent: dispatchEvent)
            bridge.attachScrollEvents(
                scroll,
                isHorizontal: state.properties[PamConstants.scrollHorizontal]?.boolOrNil() ?? false,
                emitScroll: hasScroll,
                emitEndReached: hasEndReached,
                endReachedThreshold: max(
                    0,
                    state.properties[PamConstants.endReachedThreshold]?.decimalOrNil() ?? 0.5
                ),
            )
            if hasScroll {
                eventBridges[nodeId]?[EventKind.scroll.rawValue] = bridge
            }
            if hasEndReached {
                eventBridges[nodeId]?[EventKind.endReached.rawValue] = bridge
            }
        }

        if eventProperties.contains(PamConstants.onRefresh), let refresh = view as? PamRefreshContainer {
            let bridge = EventBridge(nodeId: nodeId, kind: EventKind.refresh.rawValue, dispatchEvent: dispatchEvent)
            bridge.attachRefresh(refresh)
            eventBridges[nodeId]?[EventKind.refresh.rawValue] = bridge
        }

        if eventProperties.contains(PamConstants.onDrawerOpen) || eventProperties.contains(PamConstants.onDrawerClose),
           let drawer = view as? PamDrawerLayout {
            let bridge = EventBridge(nodeId: nodeId, kind: EventKind.drawerOpen.rawValue, dispatchEvent: dispatchEvent)
            bridge.attachDrawerCallbacks(
                drawer,
                onOpen: eventProperties.contains(PamConstants.onDrawerOpen),
                onClose: eventProperties.contains(PamConstants.onDrawerClose),
            )
            eventBridges[nodeId]?[EventKind.drawerOpen.rawValue] = bridge
        }

        if let modal = view as? PamModalHost {
            let bridge = EventBridge(nodeId: nodeId, kind: EventKind.modalRequestClose.rawValue, dispatchEvent: dispatchEvent)
            bridge.attachModalCallbacks(
                modal,
                state: state,
                handleRequestClose:
                    eventProperties.contains(PamConstants.onModalRequestClose) ||
                    state.properties[PamConstants.onNativeEvent] != nil,
                handleShow: eventProperties.contains(PamConstants.onModalShow),
                handleDismiss: eventProperties.contains(PamConstants.onModalDismiss),
                handleOrientationChange: eventProperties.contains(PamConstants.onModalOrientationChange),
                handleBottomSheetChange: eventProperties.contains(PamConstants.onBottomSheetChange),
                handleBottomSheetDismiss: eventProperties.contains(PamConstants.onBottomSheetDismiss),
            )
            eventBridges[nodeId]?[EventKind.modalRequestClose.rawValue] = bridge
        }
        if let webView = view as? PamWebView {
            webView.onLoad = eventProperties.contains(PamConstants.onWebViewLoad) ? {
                [weak self] in self?.dispatchEvent(nodeId, EventKind.webViewLoad.rawValue, Data())
            } : nil
            webView.onError = eventProperties.contains(PamConstants.onWebViewError) ? {
                [weak self] message in
                let payload = (try? WireMap.encode(["message": .text(message)])) ?? Data()
                self?.dispatchEvent(nodeId, EventKind.webViewError.rawValue, payload)
            } : nil
            webView.onMessage = eventProperties.contains(PamConstants.onWebViewMessage) ? {
                [weak self] message in
                let payload = (try? WireMap.encode(["message": .text(message)])) ?? Data()
                self?.dispatchEvent(nodeId, EventKind.webViewMessage.rawValue, payload)
            } : nil
        }
        if let drawing = view as? PamDrawingCanvas {
            drawing.onDrawingChange = eventProperties.contains(PamConstants.onChange) ? {
                [weak self] value in
                self?.dispatchEvent(nodeId, EventKind.change.rawValue, Data(value.utf8))
            } : nil
        }
        if let media = view as? PamMediaView {
            media.onReady = eventProperties.contains(PamConstants.onMediaReady) ? {
                [weak self] in self?.dispatchEvent(nodeId, EventKind.mediaReady.rawValue, Data())
            } : nil
            media.onProgress = eventProperties.contains(PamConstants.onMediaProgress) ? {
                [weak self] current, duration in
                let payload = (try? WireMap.encode([
                    "currentTime": .decimal(current),
                    "duration": .decimal(duration),
                ])) ?? Data()
                self?.dispatchEvent(nodeId, EventKind.mediaProgress.rawValue, payload)
            } : nil
            media.onEnd = eventProperties.contains(PamConstants.onMediaEnd) ? {
                [weak self] in self?.dispatchEvent(nodeId, EventKind.mediaEnd.rawValue, Data())
            } : nil
            media.onError = eventProperties.contains(PamConstants.onMediaError) ? {
                [weak self] message in
                let payload = (try? WireMap.encode(["message": .text(message)])) ?? Data()
                self?.dispatchEvent(nodeId, EventKind.mediaError.rawValue, payload)
            } : nil
            media.onCacheHit = eventProperties.contains(PamConstants.onMediaCacheHit) ? {
                [weak self] key in self?.dispatchMediaCacheEvent(
                    nodeId: nodeId,
                    kind: .mediaCacheHit,
                    key: key,
                    disk: true
                )
            } : nil
            media.onCacheMiss = eventProperties.contains(PamConstants.onMediaCacheMiss) ? {
                [weak self] key in self?.dispatchMediaCacheEvent(
                    nodeId: nodeId,
                    kind: .mediaCacheMiss,
                    key: key
                )
            } : nil
            media.onCacheReady = eventProperties.contains(PamConstants.onMediaCacheReady) ? {
                [weak self] key, bytes in self?.dispatchMediaCacheEvent(
                    nodeId: nodeId,
                    kind: .mediaCacheReady,
                    key: key,
                    loaded: bytes,
                    total: bytes,
                    disk: true
                )
            } : nil
        }
        configureNativeInteraction(nodeId: nodeId, view: view)
        configureGestureNavigation(nodeId: nodeId, view: view)
    }

    private func dispatchNativeViewEvent(nodeId: Int64, kind: Int, payload: Data) {
        guard let state = nodes[nodeId] else { return }
        if kind == EventKind.native.rawValue {
            closeLocalModalAncestor(startingAt: state.parent)
        }
        guard let eventProperty = nativeEventProperty(kind), state.properties[eventProperty] != nil else {
            return
        }
        dispatchEvent(nodeId, kind, payload)
    }

    private func closeLocalModalAncestor(startingAt startId: Int64) {
        let modalPrefix = "pam:local-modal:"
        var currentId = startId
        var depth = 0
        while currentId != 0, depth < 64 {
            guard let state = nodes[currentId] else { return }
            if state.kind == .modal {
                if state.properties[PamConstants.value]?
                    .textOrNil()?.hasPrefix(modalPrefix) == true {
                    (views[currentId] as? PamModalHost)?.setVisible(false)
                }
                return
            }
            currentId = state.parent
            depth += 1
        }
    }

    private func isEventProperty(_ key: Int) -> Bool {
        eventKeys.contains(key)
    }

    private var eventKeys: Set<Int> {
        [
            PamConstants.onPress,
            PamConstants.onChange,
            PamConstants.onLongPress,
            PamConstants.onFocus,
            PamConstants.onBlur,
            PamConstants.onSubmit,
            PamConstants.onScroll,
            PamConstants.onRefresh,
            PamConstants.onToggle,
            PamConstants.onEndReached,
            PamConstants.onNativeEvent,
            PamConstants.onImageLoadStart,
            PamConstants.onImageProgress,
            PamConstants.onImageLoad,
            PamConstants.onImageError,
            PamConstants.onImageLoadEnd,
            PamConstants.onInputEndEditing,
            PamConstants.onInputSelectionChange,
            PamConstants.onInputContentSizeChange,
            PamConstants.onInputKeyPress,
            PamConstants.onPressIn,
            PamConstants.onPressOut,
            PamConstants.onPressMove,
            PamConstants.onModalRequestClose,
            PamConstants.onModalShow,
            PamConstants.onModalDismiss,
            PamConstants.onModalOrientationChange,
            PamConstants.onDrawerOpen,
            PamConstants.onDrawerClose,
            PamConstants.onClickOutside,
            PamConstants.onIntersect,
            PamConstants.onMutate,
            PamConstants.onResize,
            PamConstants.onTouchStart,
            PamConstants.onTouchMove,
            PamConstants.onTouchEnd,
            PamConstants.onGestureBegin,
            PamConstants.onGestureUpdate,
            PamConstants.onGestureEnd,
            PamConstants.onGestureCancel,
            PamConstants.onBottomSheetChange,
            PamConstants.onBottomSheetDismiss,
            PamConstants.onWebViewLoad,
            PamConstants.onWebViewError,
            PamConstants.onWebViewMessage,
            PamConstants.onMediaReady,
            PamConstants.onMediaProgress,
            PamConstants.onMediaEnd,
            PamConstants.onMediaError,
            PamConstants.onMediaCacheHit,
            PamConstants.onMediaCacheMiss,
            PamConstants.onMediaCacheProgress,
            PamConstants.onMediaCacheReady,
            PamConstants.onDragStart,
            PamConstants.onDragEnd,
            PamConstants.onDrop,
            PamConstants.onMenuAction,
            PamConstants.onNavigationGesturePop,
            PamConstants.onAnimationComplete,
        ].reduce(into: Set<Int>()) { $0.insert($1) }
    }

    private func nativeEventProperty(_ eventKind: Int) -> Int? {
        switch eventKind {
        case EventKind.press.rawValue:
            return PamConstants.onPress
        case EventKind.change.rawValue:
            return PamConstants.onChange
        case EventKind.longPress.rawValue:
            return PamConstants.onLongPress
        case EventKind.focus.rawValue:
            return PamConstants.onFocus
        case EventKind.blur.rawValue:
            return PamConstants.onBlur
        case EventKind.submit.rawValue:
            return PamConstants.onSubmit
        case EventKind.scroll.rawValue:
            return PamConstants.onScroll
        case EventKind.refresh.rawValue:
            return PamConstants.onRefresh
        case EventKind.toggle.rawValue:
            return PamConstants.onToggle
        case EventKind.endReached.rawValue:
            return PamConstants.onEndReached
        case EventKind.drawerOpen.rawValue:
            return PamConstants.onDrawerOpen
        case EventKind.drawerClose.rawValue:
            return PamConstants.onDrawerClose
        case EventKind.native.rawValue:
            return PamConstants.onNativeEvent
        case EventKind.imageLoadStart.rawValue:
            return PamConstants.onImageLoadStart
        case EventKind.imageProgress.rawValue:
            return PamConstants.onImageProgress
        case EventKind.imageLoad.rawValue:
            return PamConstants.onImageLoad
        case EventKind.imageError.rawValue:
            return PamConstants.onImageError
        case EventKind.imageLoadEnd.rawValue:
            return PamConstants.onImageLoadEnd
        case EventKind.inputEndEditing.rawValue:
            return PamConstants.onInputEndEditing
        case EventKind.inputSelectionChange.rawValue:
            return PamConstants.onInputSelectionChange
        case EventKind.inputContentSizeChange.rawValue:
            return PamConstants.onInputContentSizeChange
        case EventKind.inputKeyPress.rawValue:
            return PamConstants.onInputKeyPress
        case EventKind.pressIn.rawValue:
            return PamConstants.onPressIn
        case EventKind.pressOut.rawValue:
            return PamConstants.onPressOut
        case EventKind.pressMove.rawValue:
            return PamConstants.onPressMove
        case EventKind.modalRequestClose.rawValue:
            return PamConstants.onModalRequestClose
        case EventKind.modalShow.rawValue:
            return PamConstants.onModalShow
        case EventKind.modalDismiss.rawValue:
            return PamConstants.onModalDismiss
        case EventKind.modalOrientationChange.rawValue:
            return PamConstants.onModalOrientationChange
        case EventKind.bottomSheetChange.rawValue:
            return PamConstants.onBottomSheetChange
        case EventKind.bottomSheetDismiss.rawValue:
            return PamConstants.onBottomSheetDismiss
        case EventKind.webViewLoad.rawValue:
            return PamConstants.onWebViewLoad
        case EventKind.webViewError.rawValue:
            return PamConstants.onWebViewError
        case EventKind.webViewMessage.rawValue:
            return PamConstants.onWebViewMessage
        case EventKind.mediaReady.rawValue:
            return PamConstants.onMediaReady
        case EventKind.mediaProgress.rawValue:
            return PamConstants.onMediaProgress
        case EventKind.mediaEnd.rawValue:
            return PamConstants.onMediaEnd
        case EventKind.mediaError.rawValue:
            return PamConstants.onMediaError
        case EventKind.mediaCacheHit.rawValue:
            return PamConstants.onMediaCacheHit
        case EventKind.mediaCacheMiss.rawValue:
            return PamConstants.onMediaCacheMiss
        case EventKind.mediaCacheProgress.rawValue:
            return PamConstants.onMediaCacheProgress
        case EventKind.mediaCacheReady.rawValue:
            return PamConstants.onMediaCacheReady
        case EventKind.dragStart.rawValue:
            return PamConstants.onDragStart
        case EventKind.dragEnd.rawValue:
            return PamConstants.onDragEnd
        case EventKind.drop.rawValue:
            return PamConstants.onDrop
        case EventKind.menuAction.rawValue:
            return PamConstants.onMenuAction
        case EventKind.navigationGesturePop.rawValue:
            return PamConstants.onNavigationGesturePop
        case EventKind.animationComplete.rawValue:
            return PamConstants.onAnimationComplete
        case EventKind.clickOutside.rawValue:
            return PamConstants.onClickOutside
        case EventKind.intersect.rawValue:
            return PamConstants.onIntersect
        case EventKind.mutate.rawValue:
            return PamConstants.onMutate
        case EventKind.resize.rawValue:
            return PamConstants.onResize
        case EventKind.touchStart.rawValue:
            return PamConstants.onTouchStart
        case EventKind.touchMove.rawValue:
            return PamConstants.onTouchMove
        case EventKind.touchEnd.rawValue:
            return PamConstants.onTouchEnd
        case EventKind.gestureBegin.rawValue:
            return PamConstants.onGestureBegin
        case EventKind.gestureUpdate.rawValue:
            return PamConstants.onGestureUpdate
        case EventKind.gestureEnd.rawValue:
            return PamConstants.onGestureEnd
        case EventKind.gestureCancel.rawValue:
            return PamConstants.onGestureCancel
        default:
            return nil
        }
    }

    private func applyProperty(view: UIView, nodeId: Int64, key: Int, value: PropValue) {
        switch key {
        case PamConstants.text:
            if let textValue = value.textOrNil() {
                if let label = view as? UILabel {
                    label.text = textValue
                } else if let button = view as? UIButton {
                    button.setTitle(textValue, for: .normal)
                } else if let field = view as? UITextField {
                    field.text = textValue
                }
            }
        case PamConstants.value:
            if let textValue = value.textOrNil(), let drawing = view as? PamDrawingCanvas {
                drawing.setDrawing(textValue)
            } else if let textValue = value.textOrNil(), let field = view as? UITextField {
                field.text = textValue
            } else if let boolValue = value.boolOrNil(), let switchView = view as? PamVuetifySwitch {
                switchView.isOn = boolValue
            } else if let textValue = value.textOrNil(),
                      textValue.hasPrefix("pam:") {
                view.accessibilityIdentifier = textValue
            }
        case PamConstants.placeholder:
            if let textValue = value.textOrNil(), let field = view as? UITextField {
                field.placeholder = textValue
            }
        case PamConstants.source:
            if let imageView = imageView(for: view), let source = value.textOrNil() {
                loadImage(source, into: imageView, nodeId: nodeId)
            }
        case PamConstants.imageFit:
            let mode = Int(value.integerOrNil() ?? 1)
            if let imageView = imageView(for: view) {
                imageView.contentMode = switch mode {
                case 2: .scaleAspectFit
                case 3: .scaleToFill
                case 4, 5: .center
                default: .scaleAspectFill
                }
            }
            (view as? PamMediaView)?.setResizeMode(mode)
        case PamConstants.width:
            if let width = value.decimalOrNil() {
                var frame = view.frame
                frame.size.width = CGFloat(width)
                view.frame = frame
            }
        case PamConstants.height:
            if let height = value.decimalOrNil() {
                var frame = view.frame
                frame.size.height = CGFloat(height)
                view.frame = frame
            }
        case PamConstants.opacity:
            view.alpha = CGFloat(value.decimalOrZero())
        case PamConstants.translationX:
            view.transform.tx = CGFloat(value.decimalOrZero())
        case PamConstants.translationY:
            view.transform.ty = CGFloat(value.decimalOrZero())
        case PamConstants.scaleX:
            view.transform = view.transform.scaledBy(
                x: CGFloat(value.decimalOrZero()),
                y: 1
            )
        case PamConstants.scaleY:
            view.transform = view.transform.scaledBy(
                x: 1,
                y: CGFloat(value.decimalOrZero())
            )
        case PamConstants.rotation:
            view.transform = view.transform.rotated(
                by: CGFloat(value.decimalOrZero()) * .pi / 180
            )
        case PamConstants.animationKind:
            applyMotion(
                view: view,
                state: nodes[nodeId],
                kind: Int(value.integerOrNil() ?? 1)
            )
        case PamConstants.backgroundColor:
            if let color = value.integerOrNil() {
                view.backgroundColor = UIColor(argb: color)
            }
        case PamConstants.borderColor,
             PamConstants.borderWidth,
             PamConstants.borderLeftWidth,
             PamConstants.borderTopWidth,
             PamConstants.borderRightWidth,
             PamConstants.borderBottomWidth:
            applyBorder(view: view, nodeId: nodeId)
        case PamConstants.borderRadius:
            view.layer.cornerRadius = CGFloat(value.decimalOrZero())
            applyBoxShadow(view: view, nodeId: nodeId)
        case PamConstants.shadowOffsetX,
             PamConstants.shadowOffsetY,
             PamConstants.shadowBlurRadius,
             PamConstants.shadowSpreadRadius,
             PamConstants.shadowColor:
            applyBoxShadow(view: view, nodeId: nodeId)
        case PamConstants.overflow:
            view.layer.masksToBounds = value.integerOrNil() == 2
        case PamConstants.enabled:
            if let enabled = value.boolOrNil() {
                view.isUserInteractionEnabled = enabled
                if let control = view as? UIControl {
                    control.isEnabled = enabled
                }
            }
        case PamConstants.accessibilityLabel:
            view.accessibilityLabel = value.textOrNil()
            applyAccessibility(view: view, state: nodes[nodeId])
        case PamConstants.accessibilityHint:
            view.accessibilityHint = value.textOrNil()
        case PamConstants.accessibilityRole,
             PamConstants.accessible,
             PamConstants.accessibilityLiveRegion,
             PamConstants.accessibilityImportance,
             PamConstants.accessibilityExpanded,
             PamConstants.accessibilityBusy,
             PamConstants.accessibilityCheckedState,
             PamConstants.accessibilityValueMin,
             PamConstants.accessibilityValueMax,
             PamConstants.accessibilityValueNow,
             PamConstants.accessibilityValueText,
             PamConstants.selected,
             PamConstants.checked,
             PamConstants.loading:
            applyAccessibility(view: view, state: nodes[nodeId])
        case PamConstants.testId:
            view.accessibilityIdentifier = value.textOrNil()
        case PamConstants.sharedTransitionTag:
            view.layer.setValue(value.textOrNil(), forKey: "pamSharedTransitionTag")
        case PamConstants.textColor:
            if let color = value.integerOrNil() {
                if let label = view as? UILabel {
                    label.textColor = UIColor(argb: color)
                }
            }
        case PamConstants.textAlign:
            applyTextAlignment(view: view, nodeId: nodeId)
        case PamConstants.fontSize,
             PamConstants.fontWeight,
             PamConstants.fontStyle,
             PamConstants.fontFamily,
             PamConstants.textAllowFontScaling,
             PamConstants.textMaxFontSizeMultiplier,
             PamConstants.textAdjustsFontSizeToFit,
             PamConstants.textMinimumFontScale:
            applyTextSizing(view: view, state: nodes[nodeId])
        case PamConstants.visible:
            if let modal = view as? PamModalHost {
                modal.setVisible(value.boolOrNil() ?? true)
            } else if let indicator = view as? PamVuetifySpinner {
                if value.boolOrNil() ?? true {
                    indicator.startAnimating()
                } else {
                    indicator.stopAnimating()
                }
            } else {
                view.isHidden = !(value.boolOrNil() ?? true)
            }
        case PamConstants.navigationOperation:
            (view as? PamNavigationHost)?.operation = Int(value.integerOrNil() ?? 1)
        case PamConstants.navigationTransition:
            (view as? PamNavigationHost)?.transition = Int(value.integerOrNil() ?? 1)
        case PamConstants.navigationDurationMs:
            (view as? PamNavigationHost)?.duration =
                TimeInterval(value.integerOrNil() ?? 240) / 1_000
        case PamConstants.navigationOrientation:
            (view as? PamNavigationHost)?.navigationOrientation = Int(value.integerOrNil() ?? 1)
        case PamConstants.navigationAutoHideHomeIndicator:
            (view as? PamNavigationHost)?.autoHideHomeIndicator = value.boolOrNil() ?? false
        case PamConstants.navigationTitle:
            (view as? PamNavigationHost)?.screenTitle = value.textOrNil() ?? ""
        case PamConstants.navigationHeaderShown:
            (view as? PamNavigationHost)?.headerShown = value.boolOrNil() ?? false
        case PamConstants.navigationHeaderTransparent:
            (view as? PamNavigationHost)?.headerTransparent = value.boolOrNil() ?? false
        case PamConstants.navigationHeaderBackgroundColor:
            (view as? PamNavigationHost)?.headerBackgroundColor = value.integerOrNil().map(UIColor.init(argb:))
        case PamConstants.navigationHeaderTintColor:
            (view as? PamNavigationHost)?.headerTintColor = value.integerOrNil().map(UIColor.init(argb:))
        case PamConstants.navigationHeaderShadowVisible:
            (view as? PamNavigationHost)?.headerShadowVisible = value.boolOrNil() ?? true
        case PamConstants.navigationHeaderLargeTitleEnabled:
            (view as? PamNavigationHost)?.headerLargeTitleEnabled = value.boolOrNil() ?? false
        case PamConstants.navigationHeaderSearchEnabled,
             PamConstants.navigationHeaderSearchPlaceholder:
            configureNavigationChrome(nodeId: nodeId, view: view)
        case PamConstants.navigationRevision:
            (view as? PamNavigationHost)?.navigate(value.integerOrNil() ?? 0)
        case PamConstants.navigationGestureEnabled,
             PamConstants.navigationGestureEdgeWidth,
             PamConstants.navigationGestureThreshold:
            configureGestureNavigation(nodeId: nodeId, view: view)
        case PamConstants.switchTrackColorFalse:
            if let color = value.integerOrNil(), let switchView = view as? PamVuetifySwitch {
                switchView.trackOffColor = UIColor(argb: color)
            }
        case PamConstants.switchTrackColorTrue:
            if let color = value.integerOrNil(), let switchView = view as? PamVuetifySwitch {
                switchView.trackOnColor = UIColor(argb: color)
            }
        case PamConstants.switchThumbColor:
            if let color = value.integerOrNil(), let switchView = view as? PamVuetifySwitch {
                switchView.thumbColor = UIColor(argb: color)
            }
        case PamConstants.modalPresentation:
            (view as? PamModalHost)?.setPresentation(Int(value.integerOrNil() ?? 2))
        case PamConstants.bottomSheetSnapPoints:
            (view as? PamModalHost)?.setBottomSheetSnapPoints(
                decodeBottomSheetSnapPoints(value)
            )
        case PamConstants.bottomSheetIndex:
            (view as? PamModalHost)?.setBottomSheetIndex(Int(value.integerOrNil() ?? 0))
        case PamConstants.bottomSheetDismissible:
            (view as? PamModalHost)?.setBottomSheetDismissible(value.boolOrNil() ?? true)
        case PamConstants.bottomSheetBackdropDismiss:
            (view as? PamModalHost)?.setBottomSheetBackdropDismiss(value.boolOrNil() ?? true)
        case PamConstants.bottomSheetHandleVisible:
            (view as? PamModalHost)?.setBottomSheetHandleVisible(value.boolOrNil() ?? true)
        case PamConstants.bottomSheetDragEnabled:
            (view as? PamModalHost)?.setBottomSheetDragEnabled(value.boolOrNil() ?? true)
        case PamConstants.bottomSheetKeyboardBehavior:
            (view as? PamModalHost)?.setBottomSheetKeyboardBehavior(
                Int(value.integerOrNil() ?? 1)
            )
        case PamConstants.bottomSheetCornerRadius:
            (view as? PamModalHost)?.setBottomSheetCornerRadius(
                CGFloat(value.decimalOrZero())
            )
        case PamConstants.webViewSource:
            (view as? PamWebView)?.setSource(value.textOrNil() ?? "")
        case PamConstants.webViewJavaScriptEnabled:
            (view as? PamWebView)?.setJavaScriptEnabled(value.boolOrNil() ?? true)
        case PamConstants.webViewDomStorageEnabled:
            (view as? PamWebView)?.setDomStorageEnabled(value.boolOrNil() ?? true)
        case PamConstants.webViewUserAgent:
            (view as? PamWebView)?.setUserAgent(value.textOrNil() ?? "")
        case PamConstants.webViewInjectedJavaScript:
            (view as? PamWebView)?.setInjectedJavaScript(value.textOrNil() ?? "")
        case PamConstants.webViewAllowsInlineMedia:
            (view as? PamWebView)?.setAllowsInlineMedia(value.boolOrNil() ?? true)
        case PamConstants.webViewAllowedHosts:
            (view as? PamWebView)?.setAllowedHosts(value.textOrNil() ?? "")
        case PamConstants.mediaSource:
            if let media = view as? PamMediaView, let state = nodes[nodeId] {
                configureMediaCache(media, state: state)
            }
            (view as? PamMediaView)?.setSource(value.textOrNil() ?? "")
        case PamConstants.mediaType:
            break
        case PamConstants.mediaAutoPlay:
            (view as? PamMediaView)?.setAutoPlay(value.boolOrNil() ?? false)
        case PamConstants.mediaControls:
            (view as? PamMediaView)?.setControls(value.boolOrNil() ?? true)
        case PamConstants.mediaLoop:
            (view as? PamMediaView)?.setLoop(value.boolOrNil() ?? false)
        case PamConstants.mediaMuted:
            (view as? PamMediaView)?.setMuted(value.boolOrNil() ?? false)
        case PamConstants.mediaVolume:
            (view as? PamMediaView)?.setVolume(Float(value.decimalOrZero()))
        case PamConstants.mediaCurrentTime:
            (view as? PamMediaView)?.seek(value.decimalOrZero())
        case PamConstants.mediaPlaybackRate:
            (view as? PamMediaView)?.setPlaybackRate(Float(value.decimalOrZero()))
        case PamConstants.mediaCachePolicy,
             PamConstants.mediaCacheKey,
             PamConstants.mediaCacheMaxAgeMs,
             PamConstants.mediaCachePinOffline,
             PamConstants.mediaCacheStreaming,
             PamConstants.mediaCacheDownloadWhilePlaying,
             PamConstants.mediaCacheMaxBytes,
             PamConstants.mediaCacheChecksum:
            if let media = view as? PamMediaView, let state = nodes[nodeId] {
                configureMediaCache(media, state: state)
            }
        case PamConstants.mediaCacheTags,
             PamConstants.mediaCachePreloadSeconds,
             PamConstants.mediaThumbnailSource,
             PamConstants.mediaResizeWidth,
             PamConstants.mediaResizeHeight,
             PamConstants.mediaPriority:
            break
        case PamConstants.draggable,
             PamConstants.dragData,
             PamConstants.dropEnabled,
             PamConstants.contextMenuItems:
            configureNativeInteraction(nodeId: nodeId, view: view)
        case PamConstants.animationKeyframes,
             PamConstants.animationIterations,
             PamConstants.animationDelayMs,
             PamConstants.animationFillMode,
             PamConstants.animationPlayState,
             PamConstants.animationAutoReverse:
            configureKeyframeAnimation(nodeId: nodeId, view: view)
        case PamConstants.refreshing:
            (view as? PamRefreshContainer)?.setRefreshing(value.boolOrNil() ?? false)
        case PamConstants.refreshColors:
            (view as? PamRefreshContainer)?.setColors(value.textOrNil())
        case PamConstants.refreshProgressBackgroundColor:
            if let color = value.integerOrNil() {
                (view as? PamRefreshContainer)?.setProgressBackgroundColor(Int(color))
            }
        case PamConstants.refreshProgressViewOffset:
            (view as? PamRefreshContainer)?.setProgressViewOffset(Float(value.decimalOrZero()))
        case PamConstants.refreshIndicatorSize:
            (view as? PamRefreshContainer)?.setIndicatorSize(Int(value.integerOrNil() ?? 1))
        case PamConstants.scrollHorizontal:
            if let scroll = view as? UIScrollView {
                configureScrollView(scroll, horizontal: value.boolOrNil() ?? false)
            }
        case PamConstants.scrollAnchorToEnd:
            (view as? PamAnchoredScrollView)?.anchorToEnd = value.boolOrNil() ?? false
        case PamConstants.scrollMaintainVisibleContentPosition:
            (view as? PamAnchoredScrollView)?.maintainVisibleContentPosition =
                value.boolOrNil() ?? false
        case PamConstants.scrollAutoScrollToEndThreshold:
            (view as? PamAnchoredScrollView)?.autoScrollToEndThreshold =
                max(0, CGFloat(value.decimalOrZero()))
        case PamConstants.scrollTargetTestId:
            (view as? PamAnchoredScrollView)?.scrollTargetTestId =
                value.textOrNil() ?? ""
        case PamConstants.scrollTargetOffset:
            (view as? PamAnchoredScrollView)?.scrollTargetOffset =
                CGFloat(value.decimalOrZero())
        case PamConstants.drawingColor:
            (view as? PamDrawingCanvas)?.setBrushColor(value.integerOrNil() ?? Int64(UInt32.max))
        case PamConstants.drawingWidth:
            (view as? PamDrawingCanvas)?.setBrushWidth(CGFloat(value.decimalOrZero()))
        case PamConstants.drawingMode:
            (view as? PamDrawingCanvas)?.setDrawingMode(Int(value.integerOrNil() ?? 1))
        case PamConstants.drawingClearRequest:
            (view as? PamDrawingCanvas)?.setClearRequest(Int(value.integerOrNil() ?? 0))
        case PamConstants.drawingUndoRequest:
            (view as? PamDrawingCanvas)?.setUndoRequest(Int(value.integerOrNil() ?? 0))
        case PamConstants.scrollRequest:
            (view as? PamAnchoredScrollView)?.requestScroll()
        case PamConstants.drawerOpen:
            (view as? PamDrawerLayout)?.setOpen(value.boolOrNil() ?? false, animated: true)
        case PamConstants.drawerType:
            (view as? PamDrawerLayout)?.setDrawerType(Int(value.integerOrNil() ?? 1))
        case PamConstants.drawerPosition:
            (view as? PamDrawerLayout)?.setDrawerPosition(Int(value.integerOrNil() ?? 1))
        case PamConstants.drawerWidth:
            (view as? PamDrawerLayout)?.setDrawerWidth(CGFloat(value.decimalOrZero()))
        case PamConstants.drawerOverlayColor:
            if let color = value.integerOrNil() {
                (view as? PamDrawerLayout)?.setOverlayColor(Int(color))
            }
        case PamConstants.drawerSwipeEnabled:
            (view as? PamDrawerLayout)?.setSwipeEnabled(value.boolOrNil() ?? true)
        case PamConstants.drawerSwipeEdgeWidth:
            (view as? PamDrawerLayout)?.setSwipeEdgeWidth(CGFloat(value.decimalOrZero()))
        case PamConstants.drawerSwipeMinDistance:
            (view as? PamDrawerLayout)?.setSwipeMinDistance(CGFloat(value.decimalOrZero()))
        case PamConstants.drawerKeyboardDismissMode:
            (view as? PamDrawerLayout)?.setKeyboardDismissMode(Int(value.integerOrNil() ?? 1))
        case PamConstants.drawerHideStatusBarOnOpen:
            (view as? PamDrawerLayout)?.setHideStatusBarOnOpen(value.boolOrNil() ?? false)
        case PamConstants.drawerStatusBarAnimation:
            (view as? PamDrawerLayout)?.setStatusBarAnimation(Int(value.integerOrNil() ?? 1))
        case PamConstants.drawerPermanentBreakpoint:
            (view as? PamDrawerLayout)?.setPermanentBreakpoint(CGFloat(value.decimalOrZero()))
        case PamConstants.layoutDirection:
            view.semanticContentAttribute = value.integerOrNil() == 2
                ? .forceRightToLeft
                : .forceLeftToRight
        case PamConstants.gestureType,
             PamConstants.gestureEnabled,
             PamConstants.gestureMinPointers,
             PamConstants.gestureMaxPointers,
             PamConstants.gestureDirection,
             PamConstants.gestureComposition,
             PamConstants.gestureMinDistance,
             PamConstants.gestureMinDurationMs,
             PamConstants.gestureNativeTransform,
             PamConstants.gestureNativeMinScale,
             PamConstants.gestureNativeMaxScale:
            installEvents(for: nodeId)
        case PamConstants.gestureNativeResetKey:
            if let child = view.subviews.first {
                child.layer.removeAllAnimations()
                child.transform = .identity
            }
            installEvents(for: nodeId)
        case PamConstants.hostProperties:
            nativeViews.update(view: view, properties: value.propertiesOrNil() ?? [:])
        default:
            break
        }
    }

    private func resetProperty(view: UIView, nodeId: Int64, key: Int, state: NodeState) {
        switch key {
        case PamConstants.backgroundColor:
            view.backgroundColor = .clear
        case PamConstants.imageFit:
            imageView(for: view)?.contentMode = .scaleAspectFill
            (view as? PamMediaView)?.setResizeMode(1)
        case PamConstants.borderColor,
             PamConstants.borderWidth,
             PamConstants.borderLeftWidth,
             PamConstants.borderTopWidth,
             PamConstants.borderRightWidth,
             PamConstants.borderBottomWidth:
            applyBorder(view: view, nodeId: nodeId)
        case PamConstants.borderRadius:
            view.layer.cornerRadius = 0
            applyBoxShadow(view: view, nodeId: nodeId)
        case PamConstants.textAlign:
            applyTextAlignment(view: view, nodeId: nodeId)
        case PamConstants.shadowOffsetX,
             PamConstants.shadowOffsetY,
             PamConstants.shadowBlurRadius,
             PamConstants.shadowSpreadRadius,
             PamConstants.shadowColor:
            applyBoxShadow(view: view, nodeId: nodeId)
        case PamConstants.overflow:
            view.layer.masksToBounds = false
        case PamConstants.fontSize,
             PamConstants.fontWeight,
             PamConstants.fontStyle,
             PamConstants.fontFamily,
             PamConstants.textAllowFontScaling,
             PamConstants.textMaxFontSizeMultiplier,
             PamConstants.textAdjustsFontSizeToFit,
             PamConstants.textMinimumFontScale:
            applyTextSizing(view: view, state: state)
        case PamConstants.accessibilityLabel:
            view.accessibilityLabel = nil
            applyAccessibility(view: view, state: state)
        case PamConstants.accessibilityHint:
            view.accessibilityHint = nil
        case PamConstants.accessibilityRole,
             PamConstants.accessible,
             PamConstants.accessibilityLiveRegion,
             PamConstants.accessibilityImportance,
             PamConstants.accessibilityExpanded,
             PamConstants.accessibilityBusy,
             PamConstants.accessibilityCheckedState,
             PamConstants.accessibilityValueMin,
             PamConstants.accessibilityValueMax,
             PamConstants.accessibilityValueNow,
             PamConstants.accessibilityValueText,
             PamConstants.selected,
             PamConstants.checked,
             PamConstants.loading:
            applyAccessibility(view: view, state: state)
        case PamConstants.layoutDirection:
            view.semanticContentAttribute = .unspecified
        case PamConstants.visible:
            if let modal = view as? PamModalHost {
                modal.setVisible(true)
            } else if let indicator = view as? PamVuetifySpinner {
                indicator.startAnimating()
            } else {
                view.isHidden = false
            }
        case PamConstants.sharedTransitionTag:
            view.layer.setValue(nil, forKey: "pamSharedTransitionTag")
        case PamConstants.navigationOperation:
            (view as? PamNavigationHost)?.operation = 1
        case PamConstants.navigationTransition:
            (view as? PamNavigationHost)?.transition = 1
        case PamConstants.navigationDurationMs:
            (view as? PamNavigationHost)?.duration = 0.24
        case PamConstants.navigationOrientation:
            (view as? PamNavigationHost)?.navigationOrientation = 1
        case PamConstants.navigationAutoHideHomeIndicator:
            (view as? PamNavigationHost)?.autoHideHomeIndicator = false
        case PamConstants.navigationRevision:
            break
        case PamConstants.navigationGestureEnabled,
             PamConstants.navigationGestureEdgeWidth,
             PamConstants.navigationGestureThreshold:
            configureGestureNavigation(nodeId: nodeId, view: view)
        case PamConstants.modalPresentation:
            (view as? PamModalHost)?.setPresentation(2)
        case PamConstants.bottomSheetSnapPoints:
            (view as? PamModalHost)?.setBottomSheetSnapPoints([0.5, 0.9])
        case PamConstants.bottomSheetIndex:
            (view as? PamModalHost)?.setBottomSheetIndex(0)
        case PamConstants.bottomSheetDismissible:
            (view as? PamModalHost)?.setBottomSheetDismissible(true)
        case PamConstants.bottomSheetBackdropDismiss:
            (view as? PamModalHost)?.setBottomSheetBackdropDismiss(true)
        case PamConstants.bottomSheetHandleVisible:
            (view as? PamModalHost)?.setBottomSheetHandleVisible(true)
        case PamConstants.bottomSheetDragEnabled:
            (view as? PamModalHost)?.setBottomSheetDragEnabled(true)
        case PamConstants.bottomSheetKeyboardBehavior:
            (view as? PamModalHost)?.setBottomSheetKeyboardBehavior(1)
        case PamConstants.bottomSheetCornerRadius:
            (view as? PamModalHost)?.setBottomSheetCornerRadius(20)
        case PamConstants.webViewSource:
            (view as? PamWebView)?.setSource("")
        case PamConstants.webViewJavaScriptEnabled:
            (view as? PamWebView)?.setJavaScriptEnabled(true)
        case PamConstants.webViewDomStorageEnabled:
            (view as? PamWebView)?.setDomStorageEnabled(true)
        case PamConstants.webViewUserAgent:
            (view as? PamWebView)?.setUserAgent("")
        case PamConstants.webViewInjectedJavaScript:
            (view as? PamWebView)?.setInjectedJavaScript("")
        case PamConstants.webViewAllowsInlineMedia:
            (view as? PamWebView)?.setAllowsInlineMedia(true)
        case PamConstants.webViewAllowedHosts:
            (view as? PamWebView)?.setAllowedHosts("")
        case PamConstants.mediaSource:
            (view as? PamMediaView)?.setSource("")
        case PamConstants.mediaType:
            break
        case PamConstants.mediaAutoPlay:
            (view as? PamMediaView)?.setAutoPlay(false)
        case PamConstants.mediaControls:
            (view as? PamMediaView)?.setControls(true)
        case PamConstants.mediaLoop:
            (view as? PamMediaView)?.setLoop(false)
        case PamConstants.mediaMuted:
            (view as? PamMediaView)?.setMuted(false)
        case PamConstants.mediaVolume:
            (view as? PamMediaView)?.setVolume(1)
        case PamConstants.mediaCurrentTime:
            (view as? PamMediaView)?.seek(0)
        case PamConstants.mediaPlaybackRate:
            (view as? PamMediaView)?.setPlaybackRate(1)
        case PamConstants.mediaCachePolicy,
             PamConstants.mediaCacheKey,
             PamConstants.mediaCacheMaxAgeMs,
             PamConstants.mediaCachePinOffline,
             PamConstants.mediaCacheStreaming,
             PamConstants.mediaCacheDownloadWhilePlaying,
             PamConstants.mediaCacheMaxBytes,
             PamConstants.mediaCacheChecksum:
            if let media = view as? PamMediaView {
                configureMediaCache(media, state: state)
            }
        case PamConstants.mediaCacheTags,
             PamConstants.mediaCachePreloadSeconds,
             PamConstants.mediaThumbnailSource,
             PamConstants.mediaResizeWidth,
             PamConstants.mediaResizeHeight,
             PamConstants.mediaPriority:
            break
        case PamConstants.draggable,
             PamConstants.dragData,
             PamConstants.dropEnabled,
             PamConstants.contextMenuItems:
            configureNativeInteraction(nodeId: nodeId, view: view)
        case PamConstants.animationKeyframes,
             PamConstants.animationIterations,
             PamConstants.animationDelayMs,
             PamConstants.animationFillMode,
             PamConstants.animationPlayState,
             PamConstants.animationAutoReverse:
            view.layer.removeAnimation(forKey: "pam.keyframes")
            animationDelegates[nodeId] = nil
        case PamConstants.refreshing:
            (view as? PamRefreshContainer)?.setRefreshing(false)
        case PamConstants.refreshColors:
            (view as? PamRefreshContainer)?.setColors(nil)
        case PamConstants.refreshProgressBackgroundColor:
            (view as? PamRefreshContainer)?.setProgressBackgroundColor(nil)
        case PamConstants.refreshProgressViewOffset:
            (view as? PamRefreshContainer)?.setProgressViewOffset(0)
        case PamConstants.refreshIndicatorSize:
            (view as? PamRefreshContainer)?.setIndicatorSize(1)
        case PamConstants.scrollHorizontal:
            if let scroll = view as? UIScrollView {
                configureScrollView(scroll, horizontal: false)
            }
        case PamConstants.scrollAnchorToEnd:
            (view as? PamAnchoredScrollView)?.anchorToEnd = false
        case PamConstants.scrollMaintainVisibleContentPosition:
            (view as? PamAnchoredScrollView)?.maintainVisibleContentPosition = false
        case PamConstants.scrollAutoScrollToEndThreshold:
            (view as? PamAnchoredScrollView)?.autoScrollToEndThreshold = 24
        case PamConstants.scrollTargetTestId:
            (view as? PamAnchoredScrollView)?.scrollTargetTestId = ""
        case PamConstants.scrollTargetOffset:
            (view as? PamAnchoredScrollView)?.scrollTargetOffset = -1
        case PamConstants.drawingColor:
            (view as? PamDrawingCanvas)?.setBrushColor(Int64(UInt32.max))
        case PamConstants.drawingWidth:
            (view as? PamDrawingCanvas)?.setBrushWidth(6)
        case PamConstants.drawingMode:
            (view as? PamDrawingCanvas)?.setDrawingMode(1)
        case PamConstants.drawingClearRequest:
            (view as? PamDrawingCanvas)?.setClearRequest(0)
        case PamConstants.drawingUndoRequest:
            (view as? PamDrawingCanvas)?.setUndoRequest(0)
        case PamConstants.scrollRequest:
            break
        case PamConstants.drawerOpen:
            (view as? PamDrawerLayout)?.setOpen(false, animated: true)
        default:
            break
        }
    }

    private func applyBorder(view: UIView, nodeId: Int64) {
        guard let state = nodes[nodeId] else { return }
        let uniform = CGFloat(
            state.properties[PamConstants.borderWidth]?.decimalOrNil() ?? 0
        )
        func width(_ key: Int) -> CGFloat {
            CGFloat(state.properties[key]?.decimalOrNil() ?? Double(uniform))
        }

        let left = max(0, width(PamConstants.borderLeftWidth))
        let top = max(0, width(PamConstants.borderTopWidth))
        let right = max(0, width(PamConstants.borderRightWidth))
        let bottom = max(0, width(PamConstants.borderBottomWidth))
        let color = UIColor(
            argb: state.properties[PamConstants.borderColor]?.integerOrNil() ?? 0
        ).cgColor
        let directional = left != top || left != right || left != bottom

        if !directional {
            borderLayers[nodeId]?.remove()
            borderLayers[nodeId] = nil
            view.layer.borderWidth = left
            view.layer.borderColor = color
            return
        }

        view.layer.borderWidth = 0
        view.layer.borderColor = nil
        let layers = borderLayers[nodeId] ?? PamBorderLayers(host: view.layer)
        borderLayers[nodeId] = layers
        layers.update(
            bounds: view.bounds,
            left: left,
            top: top,
            right: right,
            bottom: bottom,
            color: color,
        )
    }

    private func applyBoxShadow(view: UIView, nodeId: Int64) {
        guard let state = nodes[nodeId],
              let colorValue = state.properties[PamConstants.shadowColor]?.integerOrNil()
        else {
            view.layer.shadowColor = nil
            view.layer.shadowOpacity = 0
            view.layer.shadowPath = nil
            return
        }

        let color = UIColor(argb: colorValue)
        let alpha = color.cgColor.alpha
        if alpha == 0 {
            view.layer.shadowColor = nil
            view.layer.shadowOpacity = 0
            view.layer.shadowPath = nil
            return
        }
        let x = CGFloat(
            state.properties[PamConstants.shadowOffsetX]?.decimalOrNil() ?? 0
        )
        let y = CGFloat(
            state.properties[PamConstants.shadowOffsetY]?.decimalOrNil() ?? 0
        )
        let blur = max(
            0,
            CGFloat(state.properties[PamConstants.shadowBlurRadius]?.decimalOrNil() ?? 0),
        )
        let spread = CGFloat(
            state.properties[PamConstants.shadowSpreadRadius]?.decimalOrNil() ?? 0
        )
        let cornerRadius = max(
            0,
            CGFloat(state.properties[PamConstants.borderRadius]?.decimalOrNil() ?? 0)
                + spread,
        )
        view.layer.shadowColor = color.cgColor
        view.layer.shadowOpacity = 1
        view.layer.shadowOffset = CGSize(width: x, height: y)
        view.layer.shadowRadius = blur / 2
        let shadowBounds = view.bounds.insetBy(dx: -spread, dy: -spread)
        view.layer.shadowPath = UIBezierPath(
            roundedRect: shadowBounds,
            cornerRadius: cornerRadius,
        ).cgPath
    }

    private func applyTextAlignment(view: UIView, nodeId: Int64) {
        guard let label = view as? UILabel, let state = nodes[nodeId] else { return }
        if let authored = state.properties[PamConstants.textAlign]?.integerOrNil() {
            label.textAlignment = switch authored {
            case 2: .center
            case 3: .right
            default: .left
            }
            return
        }
        guard let parent = nodes[state.parent] else {
            label.textAlignment = .left
            return
        }
        let hasAllocatedWidth =
            state.properties[PamConstants.width] != nil
            || state.properties[PamConstants.minWidth] != nil
            || (state.properties[PamConstants.flexGrow]?.decimalOrNil() ?? 0) > 0
        if hasAllocatedWidth {
            label.textAlignment = .left
            return
        }
        let defaultDirection: Int64 = parent.kind == .row ? 2 : 1
        let direction = parent.properties[PamConstants.flexDirection]?.integerOrNil()
            ?? defaultDirection
        let parentIsColumn = direction == 1 || direction == 3
        if parentIsColumn {
            let authoredSelf = state.properties[PamConstants.alignSelf]?.integerOrNil()
            let alignment = authoredSelf == 4 || authoredSelf == nil
                ? parent.properties[PamConstants.alignItems]?.integerOrNil() ?? 4
                : authoredSelf!
            label.textAlignment = switch alignment {
            case 2: .center
            case 3: .right
            default: .left
            }
        } else {
            let justification =
                parent.properties[PamConstants.justifyContent]?.integerOrNil() ?? 1
            label.textAlignment = switch justification {
            case 2: .center
            case 3: .right
            default: .left
            }
        }
    }

    private func applyTextSizing(view: UIView, state: NodeState?) {
        guard let state else { return }
        let baseSize = CGFloat(
            state.properties[PamConstants.fontSize]?.decimalOrNil() ?? 14
        )
        let numericWeight = Int(
            state.properties[PamConstants.fontWeight]?.integerOrNil() ?? 400
        )
        let weight: UIFont.Weight
        switch numericWeight {
        case 700...:
            weight = .bold
        case 600...:
            weight = .semibold
        case 500...:
            weight = .medium
        case ..<350:
            weight = .light
        default:
            weight = .regular
        }
        let family = state.properties[PamConstants.fontFamily]?.textOrNil()
        var baseFont = family.flatMap { UIFont(name: $0, size: baseSize) }
            ?? UIFont.systemFont(ofSize: baseSize, weight: weight)
        if state.properties[PamConstants.fontStyle]?.integerOrNil() == 2,
           let italicDescriptor = baseFont.fontDescriptor
            .withSymbolicTraits(.traitItalic) {
            baseFont = UIFont(descriptor: italicDescriptor, size: baseSize)
        }

        let allowsScaling =
            state.properties[PamConstants.textAllowFontScaling]?.boolOrNil() ?? true
        let maximumMultiplier =
            state.properties[PamConstants.textMaxFontSizeMultiplier]?.decimalOrNil() ?? 0
        let font: UIFont
        if allowsScaling {
            let metrics = UIFontMetrics(forTextStyle: .body)
            font = maximumMultiplier > 0
                ? metrics.scaledFont(
                    for: baseFont,
                    maximumPointSize: baseSize * CGFloat(maximumMultiplier)
                )
                : metrics.scaledFont(for: baseFont)
        } else {
            font = baseFont
        }

        let adjustsToFit =
            state.properties[PamConstants.textAdjustsFontSizeToFit]?.boolOrNil() ?? false
        let minimumScale = max(
            0,
            min(
                1,
                CGFloat(
                    state.properties[PamConstants.textMinimumFontScale]?.decimalOrNil() ?? 0
                )
            )
        )
        if let label = view as? UILabel {
            label.font = font
            label.adjustsFontForContentSizeCategory = allowsScaling
            label.adjustsFontSizeToFitWidth = adjustsToFit
            label.minimumScaleFactor = minimumScale
        } else if let button = view as? UIButton {
            button.titleLabel?.font = font
            button.titleLabel?.adjustsFontForContentSizeCategory = allowsScaling
            button.titleLabel?.adjustsFontSizeToFitWidth = adjustsToFit
            button.titleLabel?.minimumScaleFactor = minimumScale
        } else if let field = view as? UITextField {
            field.font = font
            field.adjustsFontForContentSizeCategory = allowsScaling
            field.adjustsFontSizeToFitWidth = adjustsToFit
            field.minimumFontSize = adjustsToFit ? baseSize * minimumScale : 0
        }
    }

    private func applyAccessibility(view: UIView, state: NodeState?) {
        guard let state else { return }
        let role = Int(
            state.properties[PamConstants.accessibilityRole]?.integerOrNil() ?? 1
        )
        let importance = Int(
            state.properties[PamConstants.accessibilityImportance]?.integerOrNil() ?? 1
        )
        let explicitlyAccessible =
            state.properties[PamConstants.accessible]?.boolOrNil()

        var traits: UIAccessibilityTraits = []
        switch role {
        case 2, 5, 8, 11, 19, 25, 29:
            traits.insert(.button)
        case 4:
            traits.insert(.image)
        case 6, 23:
            traits.insert(.adjustable)
        case 10:
            traits.insert(.header)
        case 13:
            traits.insert(.link)
        case 18, 28:
            traits.insert(.updatesFrequently)
        case 22:
            traits.insert(.searchField)
        case 27:
            traits.insert(.staticText)
        default:
            break
        }

        let selected =
            state.properties[PamConstants.selected]?.boolOrNil() == true
        let checkedState = Int(
            state.properties[PamConstants.accessibilityCheckedState]?.integerOrNil() ?? 0
        )
        if selected || checkedState == 2 {
            traits.insert(.selected)
        }
        if state.properties[PamConstants.enabled]?.boolOrNil() == false {
            traits.insert(.notEnabled)
        }
        if state.properties[PamConstants.accessibilityBusy]?.boolOrNil() == true ||
            state.properties[PamConstants.loading]?.boolOrNil() == true ||
            Int(state.properties[PamConstants.accessibilityLiveRegion]?.integerOrNil() ?? 1) != 1 {
            traits.insert(.updatesFrequently)
        }

        view.accessibilityTraits = traits
        view.isAccessibilityElement =
            explicitlyAccessible ??
            (role != 1 && role != 17 && role != 34 && importance != 3)
        view.accessibilityElementsHidden = importance == 4

        let explicitValue =
            state.properties[PamConstants.accessibilityValueText]?.textOrNil()
        let maximum =
            state.properties[PamConstants.accessibilityValueMax]?.decimalOrNil()
        let current =
            state.properties[PamConstants.accessibilityValueNow]?.decimalOrNil()
        var values: [String] = []
        if let explicitValue, !explicitValue.isEmpty {
            values.append(explicitValue)
        } else if let maximum, let current {
            values.append(
                "\(accessibilityNumber(current)) / \(accessibilityNumber(maximum))"
            )
        }
        if checkedState != 0 {
            values.append(
                checkedState == 2 ? "Checked" :
                    (checkedState == 3 ? "Mixed" : "Unchecked")
            )
        }
        if let expanded =
            state.properties[PamConstants.accessibilityExpanded]?.boolOrNil() {
            values.append(expanded ? "Expanded" : "Collapsed")
        }
        if state.properties[PamConstants.accessibilityBusy]?.boolOrNil() == true ||
            state.properties[PamConstants.loading]?.boolOrNil() == true {
            values.append("Loading")
        }
        view.accessibilityValue = values.isEmpty ? nil : values.joined(separator: ", ")
    }

    private func accessibilityNumber(_ value: Double) -> String {
        if value.rounded() == value {
            return String(Int64(value))
        }
        var text = String(format: "%.2f", value)
        while text.last == "0" {
            text.removeLast()
        }
        if text.last == "." {
            text.removeLast()
        }
        return text
    }

    private func configureScrollView(_ scroll: UIScrollView, horizontal: Bool) {
        (scroll as? PamAnchoredScrollView)?.horizontal = horizontal
        scroll.alwaysBounceVertical = !horizontal
        scroll.alwaysBounceHorizontal = horizontal
        scroll.showsHorizontalScrollIndicator = horizontal
        scroll.showsVerticalScrollIndicator = !horizontal
        scroll.isDirectionalLockEnabled = true
    }

    private func decodeBottomSheetSnapPoints(_ value: PropValue) -> [CGFloat] {
        guard let data = value.bytesOrNil(), data.count >= 10 else {
            return [0.5, 0.9]
        }
        let bytes = [UInt8](data)
        let count = Int(bytes[0]) | Int(bytes[1]) << 8
        guard count >= 1, count <= 16, bytes.count == 2 + count * 8 else {
            return [0.5, 0.9]
        }
        return (0..<count).map { index in
            let offset = 2 + index * 8
            var bits: UInt64 = 0
            for byteIndex in 0..<8 {
                bits |= UInt64(bytes[offset + byteIndex]) << UInt64(byteIndex * 8)
            }
            return CGFloat(Double(bitPattern: bits))
        }
    }

    private func configureNativeInteraction(nodeId: Int64, view: UIView) {
        interactionBridges.removeValue(forKey: nodeId)?.detach()
        guard let state = nodes[nodeId] else { return }
        let keys = [
            PamConstants.draggable,
            PamConstants.dragData,
            PamConstants.dropEnabled,
            PamConstants.contextMenuItems,
            PamConstants.onDragStart,
            PamConstants.onDragEnd,
            PamConstants.onDrop,
            PamConstants.onMenuAction,
        ]
        guard keys.contains(where: { state.properties[$0] != nil }) else { return }
        let menuItems: [PamInteractionBridge.MenuItem]
        if let data = state.properties[PamConstants.contextMenuItems]?.bytesOrNil(),
           let values = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
            menuItems = values.prefix(64).compactMap { item in
                guard let id = item["id"] as? String,
                      let title = item["title"] as? String else { return nil }
                return PamInteractionBridge.MenuItem(
                    id: id,
                    title: title,
                    destructive: item["destructive"] as? Bool ?? false,
                    disabled: item["disabled"] as? Bool ?? false
                )
            }
        } else {
            menuItems = []
        }
        let bridge = PamInteractionBridge(
            view: view,
            draggable: state.properties[PamConstants.draggable]?.boolOrNil() ?? false,
            dragData: state.properties[PamConstants.dragData]?.textOrNil() ?? "",
            dropEnabled: state.properties[PamConstants.dropEnabled]?.boolOrNil() ?? false,
            menuItems: menuItems,
            onDragStart: state.properties[PamConstants.onDragStart] != nil ? {
                [weak self] in self?.dispatchEvent(nodeId, EventKind.dragStart.rawValue, Data())
            } : nil,
            onDragEnd: state.properties[PamConstants.onDragEnd] != nil ? {
                [weak self] in self?.dispatchEvent(nodeId, EventKind.dragEnd.rawValue, Data())
            } : nil,
            onDrop: state.properties[PamConstants.onDrop] != nil ? { [weak self] data in
                let payload = (try? WireMap.encode(["data": .text(data)])) ?? Data()
                self?.dispatchEvent(nodeId, EventKind.drop.rawValue, payload)
            } : nil,
            onMenuAction: state.properties[PamConstants.onMenuAction] != nil ? {
                [weak self] id in
                let payload = (try? WireMap.encode(["id": .text(id)])) ?? Data()
                self?.dispatchEvent(nodeId, EventKind.menuAction.rawValue, payload)
            } : nil
        )
        interactionBridges[nodeId] = bridge
    }

    private func configureGestureNavigation(nodeId: Int64, view: UIView) {
        guard let navigation = view as? PamNavigationHost,
              let state = nodes[nodeId] else { return }
        navigation.setGestureNavigation(
            enabled: state.properties[PamConstants.navigationGestureEnabled]?.boolOrNil() ?? true,
            edgeWidth: CGFloat(
                state.properties[PamConstants.navigationGestureEdgeWidth]?.decimalOrNil() ?? 24
            ),
            threshold: CGFloat(
                state.properties[PamConstants.navigationGestureThreshold]?.decimalOrNil() ?? 0.35
            ),
            onPop: state.properties[PamConstants.onNavigationGesturePop] != nil ? {
                [weak self] in
                self?.dispatchEvent(
                    nodeId,
                    EventKind.navigationGesturePop.rawValue,
                    Data()
                )
            } : nil,
            onTransitionEnd: state.properties[PamConstants.onAnimationComplete] != nil ? {
                [weak self] in
                self?.dispatchEvent(nodeId, EventKind.animationComplete.rawValue, Data())
            } : nil,
            onGestureStart: state.properties[PamConstants.onGestureBegin] != nil ? {
                [weak self] in
                self?.dispatchEvent(nodeId, EventKind.gestureBegin.rawValue, Data())
            } : nil,
            onGestureEnd: state.properties[PamConstants.onGestureEnd] != nil ? {
                [weak self] in
                self?.dispatchEvent(nodeId, EventKind.gestureEnd.rawValue, Data())
            } : nil,
            onGestureCancel: state.properties[PamConstants.onGestureCancel] != nil ? {
                [weak self] in
                self?.dispatchEvent(nodeId, EventKind.gestureCancel.rawValue, Data())
            } : nil
        )
    }

    private func configureNavigationChrome(nodeId: Int64, view: UIView) {
        guard let navigation = view as? PamNavigationHost,
              let state = nodes[nodeId] else { return }
        navigation.headerSearchEnabled = state.properties[PamConstants.navigationHeaderSearchEnabled]?.boolOrNil() ?? false
        navigation.headerSearchPlaceholder = state.properties[PamConstants.navigationHeaderSearchPlaceholder]?.textOrNil() ?? "Search"
        navigation.onSearchChange = state.properties[PamConstants.onChange] != nil ? { [weak self] text in
            self?.dispatchEvent(nodeId, EventKind.change.rawValue, Data(text.utf8))
        } : nil
    }

    private func configureKeyframeAnimation(nodeId: Int64, view: UIView) {
        guard let state = nodes[nodeId],
              let data = state.properties[PamConstants.animationKeyframes]?.bytesOrNil(),
              let frames = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]],
              frames.count >= 2 else { return }
        let parentWidth = view.superview?.bounds.width ?? 0
        let translationReferenceWidth = parentWidth > 0 ? parentWidth : UIScreen.main.bounds.width
        let resolvedFrames = frames.map { frame -> [String: Any] in
            guard frame["translationX"] == nil,
                  let percent = (frame["translationXPercent"] as? NSNumber)?.doubleValue else {
                return frame
            }
            var resolved = frame
            resolved["translationX"] = percent * Double(translationReferenceWidth) / 100
            return resolved
        }
        if UIAccessibility.isReduceMotionEnabled {
            view.layer.removeAnimation(forKey: "pam.keyframes")
            animationDelegates[nodeId] = nil
            if let last = resolvedFrames.last {
                view.alpha = CGFloat((last["opacity"] as? NSNumber)?.doubleValue ?? 1)
                let x = CGFloat((last["translationX"] as? NSNumber)?.doubleValue ?? 0)
                let y = CGFloat((last["translationY"] as? NSNumber)?.doubleValue ?? 0)
                let scaleX = CGFloat((last["scaleX"] as? NSNumber)?.doubleValue ?? 1)
                let scaleY = CGFloat((last["scaleY"] as? NSNumber)?.doubleValue ?? 1)
                let rotation = CGFloat((last["rotation"] as? NSNumber)?.doubleValue ?? 0)
                view.transform = CGAffineTransform(translationX: x, y: y)
                    .scaledBy(x: scaleX, y: scaleY)
                    .rotated(by: rotation * .pi / 180)
            }
            return
        }
        let playState = Int(
            state.properties[PamConstants.animationPlayState]?.integerOrNil() ?? 1
        )
        if playState == 2 {
            if view.layer.speed != 0 {
                view.layer.timeOffset = view.layer.convertTime(CACurrentMediaTime(), from: nil)
                view.layer.speed = 0
            }
            return
        }
        if view.layer.speed == 0 {
            let paused = view.layer.timeOffset
            view.layer.speed = 1
            view.layer.timeOffset = 0
            view.layer.beginTime = view.layer.convertTime(CACurrentMediaTime(), from: nil) - paused
        }
        view.layer.removeAnimation(forKey: "pam.keyframes")
        animationDelegates[nodeId] = nil
        guard playState == 1 else { return }

        let offsets = resolvedFrames.compactMap { ($0["offset"] as? NSNumber)?.doubleValue }
        guard offsets.count == resolvedFrames.count else { return }
        let specifications: [(String, String, (Double) -> Any)] = [
            ("opacity", "opacity", { NSNumber(value: $0) }),
            ("translationX", "transform.translation.x", { NSNumber(value: $0) }),
            ("translationY", "transform.translation.y", { NSNumber(value: $0) }),
            ("scaleX", "transform.scale.x", { NSNumber(value: $0) }),
            ("scaleY", "transform.scale.y", { NSNumber(value: $0) }),
            ("rotation", "transform.rotation.z", { NSNumber(value: $0 * .pi / 180) }),
        ]
        var animations: [CAAnimation] = []
        for (property, keyPath, transform) in specifications {
            let available = resolvedFrames.compactMap { ($0[property] as? NSNumber)?.doubleValue }
            guard !available.isEmpty else { continue }
            var last = available.first ?? 0
            let values = resolvedFrames.map { frame -> Any in
                if let value = (frame[property] as? NSNumber)?.doubleValue { last = value }
                return transform(last)
            }
            let animation = CAKeyframeAnimation(keyPath: keyPath)
            animation.values = values
            animation.keyTimes = offsets.map { NSNumber(value: $0) }
            animations.append(animation)
        }
        guard !animations.isEmpty else { return }
        let durationMs = state.properties[PamConstants.animationDurationMs]?.integerOrNil() ?? 300
        let iterations = state.properties[PamConstants.animationIterations]?.integerOrNil() ?? 1
        let fill = Int(state.properties[PamConstants.animationFillMode]?.integerOrNil() ?? 2)
        let group = CAAnimationGroup()
        group.animations = animations
        group.duration = min(max(Double(durationMs) / 1_000, 0.001), 60)
        group.beginTime = CACurrentMediaTime() + min(
            max(Double(state.properties[PamConstants.animationDelayMs]?.integerOrNil() ?? 0) / 1_000, 0),
            60
        )
        group.repeatCount = iterations == 0 ? .infinity : Float(min(max(iterations, 1), 10_000))
        group.autoreverses =
            state.properties[PamConstants.animationAutoReverse]?.boolOrNil() ?? false
        group.timingFunction = animationTimingFunction(
            Int(state.properties[PamConstants.animationEasing]?.integerOrNil() ?? 4)
        )
        group.fillMode = fill == 1 ? .removed : (fill == 3 ? .backwards : .forwards)
        group.isRemovedOnCompletion = fill == 1 || fill == 3
        let delegate = PamAnimationDelegate { [weak self] finished in
            guard finished, let self else { return }
            self.animationDelegates[nodeId] = nil
            guard self.nodes[nodeId]?.properties[PamConstants.onAnimationComplete] != nil else {
                return
            }
            self.dispatchEvent(nodeId, EventKind.animationComplete.rawValue, Data())
        }
        group.delegate = delegate
        animationDelegates[nodeId] = delegate
        view.layer.add(group, forKey: "pam.keyframes")
    }

    private func animationTimingFunction(_ value: Int) -> CAMediaTimingFunction {
        switch value {
        case 1: return CAMediaTimingFunction(name: .linear)
        case 2: return CAMediaTimingFunction(name: .easeIn)
        case 3: return CAMediaTimingFunction(name: .easeOut)
        default: return CAMediaTimingFunction(name: .easeInEaseOut)
        }
    }

    private func applyMotion(view: UIView, state: NodeState?, kind: Int) {
        view.layer.removeAllAnimations()
        guard !UIAccessibility.isReduceMotionEnabled, kind != 1 else {
            view.alpha = targetAlpha(state)
            return
        }
        let durationMs = state?.properties[PamConstants.animationDurationMs]?.integerOrNil() ?? 240
        let duration = min(
            max(TimeInterval(durationMs) / 1_000, 0.1),
            kind == 2 ? 60 : 2
        )
        let target = targetAlpha(state)

        if kind == 2 {
            UIView.animate(
                withDuration: duration,
                delay: 0,
                options: [
                    .autoreverse,
                    .repeat,
                    .allowUserInteraction,
                    .beginFromCurrentState,
                ],
                animations: { view.alpha = target * 0.55 }
            )
            return
        }

        let finalTransform = view.transform
        switch kind {
        case 3:
            view.alpha = 0
        case 4:
            view.alpha = 0
            view.transform = finalTransform.scaledBy(x: 0.94, y: 0.94)
        case 5:
            view.alpha = 0
            view.transform = finalTransform.translatedBy(x: 0, y: 18)
        case 6:
            view.alpha = 0
            view.transform = finalTransform.translatedBy(x: 0, y: -18)
        case 7:
            view.alpha = target
            view.transform = finalTransform.scaledBy(x: 0.9, y: 0.9)
        case 8:
            view.alpha = target
            view.transform = finalTransform.translatedBy(x: -8, y: 0)
        default:
            view.alpha = target
        }

        let easing = Int(
            state?.properties[PamConstants.animationEasing]?.integerOrNil() ?? 3
        )
        let curve: UIView.AnimationOptions = switch easing {
        case 1: .curveLinear
        case 2: .curveEaseIn
        case 4: .curveEaseInOut
        default: .curveEaseOut
        }
        UIView.animate(
            withDuration: duration,
            delay: 0,
            usingSpringWithDamping: easing == 5 ? 0.72 : 1,
            initialSpringVelocity: easing == 5 ? 0.35 : 0,
            options: [curve, .allowUserInteraction, .beginFromCurrentState],
            animations: {
                view.alpha = target
                view.transform = finalTransform
            }
        )
    }

    private func targetAlpha(_ state: NodeState?) -> CGFloat {
        guard let value = state?.properties[PamConstants.opacity] else {
            return 1
        }
        return CGFloat(value.decimalOrZero())
    }

    private func resolveImageSource(
        fallback: String,
        sourceSet: String?,
        width: CGFloat
    ) -> String {
        guard let sourceSet else { return fallback }
        let density = UIScreen.main.scale
        let candidates: [(String, CGFloat)] = sourceSet.split(separator: ",").compactMap {
            let parts = $0.trimmingCharacters(in: .whitespaces).split(separator: " ")
            guard parts.count >= 2, let descriptor = parts.last else { return nil }
            let source = parts.dropLast().joined(separator: " ")
            if descriptor.hasSuffix("x"),
               let scale = Double(descriptor.dropLast()) {
                return (source, abs(CGFloat(scale) - density))
            }
            if descriptor.hasSuffix("w"),
               let candidateWidth = Double(descriptor.dropLast()) {
                return (source, abs(CGFloat(candidateWidth) - width) / max(width, 1))
            }
            return nil
        }
        return candidates.min(by: { $0.1 < $1.1 })?.0 ?? fallback
    }

    private func parseImageHeaders(_ value: String?) -> [String: String] {
        guard let value else { return [:] }
        return value.split(separator: "\n").prefix(32).reduce(into: [:]) { result, line in
            guard let separator = line.firstIndex(of: ":") else { return }
            let name = String(line[..<separator])
            let header = String(line[line.index(after: separator)...])
            if !name.isEmpty && header.utf8.count <= 4_096 {
                result[name] = header
            }
        }
    }

    private func configureMediaCache(_ view: PamMediaView, state: NodeState) {
        view.setCache(
            policy: Int(
                state.properties[PamConstants.mediaCachePolicy]?.integerOrNil() ?? 1
            ),
            key: state.properties[PamConstants.mediaCacheKey]?.textOrNil(),
            maxAgeMs: state.properties[PamConstants.mediaCacheMaxAgeMs]?.integerOrNil() ?? 0,
            maxBytes: state.properties[PamConstants.mediaCacheMaxBytes]?.integerOrNil() ?? 0,
            checksum: state.properties[PamConstants.mediaCacheChecksum]?.textOrNil(),
            pinned: state.properties[PamConstants.mediaCachePinOffline]?.boolOrNil() ?? false,
            streaming: state.properties[PamConstants.mediaCacheStreaming]?.boolOrNil() ?? false,
            downloadWhilePlaying:
                state.properties[PamConstants.mediaCacheDownloadWhilePlaying]?.boolOrNil() ?? false
        )
    }

    private func dispatchMediaCacheEvent(
        nodeId: Int64,
        kind: EventKind,
        key: String,
        loaded: Int64 = 0,
        total: Int64 = 0,
        disk: Bool = false
    ) {
        let payload = (try? WireMap.encode([
            "key": .text(key),
            "loaded": .integer(loaded),
            "total": .integer(total),
            "disk": .flag(disk),
        ])) ?? Data()
        dispatchEvent(nodeId, kind.rawValue, payload)
    }

    private func decodeImage(
        _ data: Data,
        targetSize: CGSize,
        multiplier: CGFloat
    ) -> UIImage? {
        guard targetSize.width > 0, targetSize.height > 0,
              let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            return UIImage(data: data)
        }
        let pixels = max(targetSize.width, targetSize.height)
            * UIScreen.main.scale
            * min(max(multiplier, 0.1), 8)
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(pixels, 1),
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
        else {
            return UIImage(data: data)
        }
        return UIImage(cgImage: image)
    }

    private func localImage(_ source: String) -> UIImage? {
        if source.lowercased().hasPrefix("asset://") {
            guard let path = try? normalizedPamAssetPath(source) else {
                return nil
            }
            let resourcePath = path as NSString
            let directory = resourcePath.deletingLastPathComponent
            let fileName = resourcePath.lastPathComponent
            if let url = Bundle.main.url(
                forResource: fileName,
                withExtension: nil,
                subdirectory: directory
            ) {
                return UIImage(contentsOfFile: url.path)
            }
            if let resourceRoot = Bundle.main.resourceURL {
                let url = resourceRoot.appendingPathComponent(path, isDirectory: false)
                if FileManager.default.fileExists(atPath: url.path) {
                    return UIImage(contentsOfFile: url.path)
                }
            }
            return UIImage(named: path)
        }
        if let url = sandboxFileURL(source) {
            return UIImage(contentsOfFile: url.path)
        }
        if source.hasPrefix("file://"), let url = URL(string: source) {
            return UIImage(contentsOfFile: url.path)
        }
        return UIImage(named: source)
    }

    private func imageView(for view: UIView) -> UIImageView? {
        if let imageView = view as? UIImageView {
            return imageView
        }
        return (view as? PamDrawingCanvas)?.imageView
    }

    private func notifyDrawingImageChanged(nodeId: Int64) {
        (views[nodeId] as? PamDrawingCanvas)?.imageDidChange()
    }

    private func sandboxFileURL(_ source: String) -> URL? {
        guard let uri = URLComponents(string: source),
              uri.scheme?.lowercased() == "pam-file",
              uri.host == nil || uri.host?.isEmpty == true,
              !uri.path.isEmpty else {
            return nil
        }
        let base = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        )[0]
            .appendingPathComponent("pam-files", isDirectory: true)
            .resolvingSymlinksInPath()
            .standardizedFileURL
        let relative = String(uri.path.drop(while: { $0 == "/" }))
        let candidate = base
            .appendingPathComponent(relative)
            .resolvingSymlinksInPath()
            .standardizedFileURL
        guard candidate.path.hasPrefix(base.path + "/"),
              FileManager.default.fileExists(atPath: candidate.path) else {
            return nil
        }
        return candidate
    }

    private func loadImagePlaceholder(
        _ source: String,
        into imageView: UIImageView,
        nodeId: Int64,
        generation: Int
    ) {
        if let image = localImage(source) {
            imageView.image = image
            notifyDrawingImageChanged(nodeId: nodeId)
            return
        }
        guard let url = URL(string: source),
              let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http" else { return }
        imageSession.dataTask(with: url) { [weak self, weak imageView] data, _, _ in
            guard let self, let data, let image = UIImage(data: data) else { return }
            DispatchQueue.main.async {
                guard let state = self.nodes[nodeId],
                      state.imageGeneration == generation,
                      state.imageLoading else { return }
                imageView?.image = image
                self.notifyDrawingImageChanged(nodeId: nodeId)
            }
        }.resume()
    }

    private func loadImage(_ source: String, into imageView: UIImageView, nodeId: Int64) {
        guard let state = nodes[nodeId] else { return }
        let resolvedSource = resolveImageSource(
            fallback: source,
            sourceSet: state.properties[PamConstants.imageSourceSet]?.textOrNil(),
            width: max(imageView.bounds.width, 1)
        )
        guard let url = URL(string: resolvedSource),
              let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http" else {
            imageView.image = localImage(resolvedSource)
            return
        }
        let policy = Int(
            state.properties[PamConstants.mediaCachePolicy]?.integerOrNil() ?? 4
        )
        guard [3, 4, 5, 7, 8].contains(policy) else {
            loadImageFromNetwork(source, into: imageView, nodeId: nodeId)
            return
        }

        cancelImageLoad(for: state)
        state.imageGeneration += 1
        state.imageLoading = true
        let generation = state.imageGeneration
        let key = state.properties[PamConstants.mediaCacheKey]?.textOrNil()
        let identity = PamMediaDiskCache.shared.identity(source: resolvedSource, stableKey: key)
        let targetSize = imageView.bounds.size
        let multiplier = CGFloat(
            state.properties[PamConstants.imageResizeMultiplier]?.decimalOrNil() ?? 1
        )
        PamMediaDiskCache.shared.data(
            source: resolvedSource,
            stableKey: key,
            maxAgeMs: state.properties[PamConstants.mediaCacheMaxAgeMs]?.integerOrNil() ?? 0
        ) { [weak self, weak imageView] data in
            guard let self else { return }
            let image = data.flatMap {
                self.decodeImage(
                    $0,
                    targetSize: targetSize,
                    multiplier: multiplier
                )
            }
            DispatchQueue.main.async {
                guard let current = self.nodes[nodeId],
                      current.imageGeneration == generation,
                      let imageView else { return }
                if let image {
                    imageView.image = image
                    current.imageLoading = false
                    if current.properties[PamConstants.onMediaCacheHit] != nil {
                        self.dispatchMediaCacheEvent(
                            nodeId: nodeId,
                            kind: .mediaCacheHit,
                            key: identity,
                            loaded: Int64(data?.count ?? 0),
                            total: Int64(data?.count ?? 0),
                            disk: true
                        )
                    }
                    self.dispatchCachedImageLoad(
                        nodeId: nodeId,
                        source: resolvedSource,
                        image: image
                    )
                    return
                }
                if current.properties[PamConstants.onMediaCacheMiss] != nil {
                    self.dispatchMediaCacheEvent(
                        nodeId: nodeId,
                        kind: .mediaCacheMiss,
                        key: identity
                    )
                }
                if policy == 7 {
                    current.imageLoading = false
                    self.dispatchImageCacheOnlyError(nodeId: nodeId)
                } else {
                    self.loadImageFromNetwork(source, into: imageView, nodeId: nodeId)
                }
            }
        }
    }

    private func loadImageFromNetwork(
        _ source: String,
        into imageView: UIImageView,
        nodeId: Int64
    ) {
        guard let state = nodes[nodeId] else {
            return
        }
        let resolvedSource = resolveImageSource(
            fallback: source,
            sourceSet: state.properties[PamConstants.imageSourceSet]?.textOrNil(),
            width: max(imageView.bounds.width, 1)
        )
        guard let url = URL(string: resolvedSource) else { return }

        cancelImageLoad(for: state)
        state.imageGeneration += 1
        state.imageLoading = true
        state.imageProgressLoaded = 0
        state.imageProgressTotal = 0
        state.imageProgressScheduled = false

        let generation = state.imageGeneration
        if let placeholder = state.properties[PamConstants.imageLoadingIndicatorSource]?.textOrNil()
            ?? state.properties[PamConstants.imageDefaultSource]?.textOrNil() {
            loadImagePlaceholder(
                placeholder,
                into: imageView,
                nodeId: nodeId,
                generation: generation
            )
        }
        let cachePolicy = Int(
            state.properties[PamConstants.imageCachePolicy]?.integerOrNil() ?? 1
        )
        let requestCachePolicy: URLRequest.CachePolicy
        switch cachePolicy {
        case 2:
            requestCachePolicy = .reloadIgnoringLocalCacheData
        case 3:
            requestCachePolicy = .returnCacheDataElseLoad
        case 4:
            requestCachePolicy = .returnCacheDataDontLoad
        default:
            requestCachePolicy = .useProtocolCachePolicy
        }
        var request = URLRequest(url: url, cachePolicy: requestCachePolicy)
        parseImageHeaders(
            state.properties[PamConstants.imageRequestHeaders]?.textOrNil()
        ).forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        let task = imageSession.dataTask(with: request)
        let context = ImageLoadContext(
            nodeId: nodeId,
            generation: generation,
            source: resolvedSource,
            imageView: imageView,
            progressive:
                state.properties[PamConstants.imageProgressiveRendering]?.boolOrNil() ?? false
        )
        state.imageTask = task

        context.onStart = { [weak self] context in
            guard let self else { return }
            guard let state = self.nodes[context.nodeId],
                  state.imageGeneration == context.generation else {
                return
            }
            state.imageLoading = true
            if state.properties[PamConstants.onImageLoadStart] != nil {
                self.dispatchEvent(
                    context.nodeId,
                    EventKind.imageLoadStart.rawValue,
                    Data(),
                )
            }
        }

        context.onProgress = { [weak self] context in
            guard let self else { return }
            guard let state = self.nodes[context.nodeId],
                  state.imageGeneration == context.generation else { return }
            state.imageProgressLoaded = context.progressLoaded
            state.imageProgressTotal = context.progressTotal
            guard state.properties[PamConstants.onImageProgress] != nil else { return }

            if !state.imageProgressScheduled {
                state.imageProgressScheduled = true
                DispatchQueue.main.async {
                    guard let state = self.nodes[context.nodeId], state.imageProgressScheduled else {
                        return
                    }
                    state.imageProgressScheduled = false
                    self.dispatchImageProgress(state)
                }
            }
        }

        context.onSuccess = { [weak self] context, data in
            guard let self else { return }
            guard let state = self.nodes[context.nodeId],
                  state.imageGeneration == context.generation,
                  let hostView = self.views[context.nodeId],
                  let imageView = self.imageView(for: hostView) else {
                return
            }
            let multiplier = state.properties[PamConstants.imageResizeMultiplier]?.decimalOrNil() ?? 1
            guard let image = self.decodeImage(
                data,
                targetSize: imageView.bounds.size,
                multiplier: CGFloat(multiplier)
            ) else { return }
            let mediaPolicy = Int(
                state.properties[PamConstants.mediaCachePolicy]?.integerOrNil() ?? 4
            )
            if [3, 4, 5, 6, 8].contains(mediaPolicy) {
                let key = state.properties[PamConstants.mediaCacheKey]?.textOrNil()
                let identity = PamMediaDiskCache.shared.identity(
                    source: context.source,
                    stableKey: key
                )
                PamMediaDiskCache.shared.store(
                    data,
                    source: context.source,
                    stableKey: key,
                    checksum: state.properties[PamConstants.mediaCacheChecksum]?.textOrNil(),
                    limit: state.properties[PamConstants.mediaCacheMaxBytes]?.integerOrNil() ?? 0,
                    pinned:
                        state.properties[PamConstants.mediaCachePinOffline]?.boolOrNil() ?? false
                ) { [weak self] stored in
                    guard stored else { return }
                    DispatchQueue.main.async {
                        guard let self, let current = self.nodes[context.nodeId],
                              current.imageGeneration == context.generation,
                              current.properties[PamConstants.onMediaCacheReady] != nil else {
                            return
                        }
                        self.dispatchMediaCacheEvent(
                            nodeId: context.nodeId,
                            kind: .mediaCacheReady,
                            key: identity,
                            loaded: Int64(data.count),
                            total: Int64(data.count),
                            disk: true
                        )
                    }
                }
            }
            DispatchQueue.main.async {
                guard let state = self.nodes[context.nodeId], state.imageGeneration == context.generation else {
                    return
                }
                let fade = state.properties[PamConstants.imageFadeDurationMs]?.integerOrNil() ?? 300
                if fade > 0 && !UIAccessibility.isReduceMotionEnabled {
                    UIView.transition(
                        with: imageView,
                        duration: min(Double(fade) / 1_000, 10),
                        options: .transitionCrossDissolve,
                        animations: { imageView.image = image }
                    )
                } else {
                    imageView.image = image
                }
                self.notifyDrawingImageChanged(nodeId: context.nodeId)
                state.imageLoading = false
                guard state.properties[PamConstants.onImageLoad] != nil else { return }
                let payload = (try? WireMap.encode(
                    [
                        "uri": .text(context.source),
                        "width": .decimal(Double(image.size.width)),
                        "height": .decimal(Double(image.size.height)),
                    ],
                )) ?? Data()
                self.dispatchEvent(
                    context.nodeId,
                    EventKind.imageLoad.rawValue,
                    payload,
                )
            }
        }

        context.onPartial = { [weak self] context, data in
            guard let self,
                  let state = self.nodes[context.nodeId],
                  state.imageGeneration == context.generation,
                  let hostView = self.views[context.nodeId],
                  let imageView = self.imageView(for: hostView),
                  let image = UIImage(data: data) else { return }
            imageView.image = image
            self.notifyDrawingImageChanged(nodeId: context.nodeId)
        }

        context.onError = { [weak self] context, error in
            guard let self else { return }
            guard let state = self.nodes[context.nodeId],
                  state.imageGeneration == context.generation else {
                return
            }
            state.imageLoading = false
            state.imageProgressScheduled = false
            if state.properties[PamConstants.onImageError] == nil {
                return
            }
            let payload = (try? WireMap.encode(["error": .text(error)])) ?? Data()
            self.dispatchEvent(context.nodeId, EventKind.imageError.rawValue, payload)
        }

        context.onEnd = { [weak self] context in
            guard let self else { return }
            guard let state = self.nodes[context.nodeId],
                  state.imageGeneration == context.generation else {
                return
            }
            state.imageLoading = false
            if state.properties[PamConstants.onImageLoadEnd] != nil {
                self.dispatchEvent(context.nodeId, EventKind.imageLoadEnd.rawValue, Data())
            }
            if let task = context.task {
                self.imageSessionDelegate.unregister(taskIdentifier: task.taskIdentifier)
                self.imageLoadContexts[task.taskIdentifier] = nil
            }
            state.imageTask = nil
        }

        imageSessionDelegate.register(context, for: task)
        imageLoadContexts[task.taskIdentifier] = context
        task.resume()
        if state.properties[PamConstants.onImageLoadStart] != nil {
            context.onStart?(context)
        }
    }

    private func dispatchCachedImageLoad(nodeId: Int64, source: String, image: UIImage) {
        guard let state = nodes[nodeId] else { return }
        if state.properties[PamConstants.onImageLoad] != nil {
            let payload = (try? WireMap.encode([
                "uri": .text(source),
                "width": .decimal(Double(image.size.width)),
                "height": .decimal(Double(image.size.height)),
            ])) ?? Data()
            dispatchEvent(nodeId, EventKind.imageLoad.rawValue, payload)
        }
        if state.properties[PamConstants.onImageLoadEnd] != nil {
            dispatchEvent(nodeId, EventKind.imageLoadEnd.rawValue, Data())
        }
    }

    private func dispatchImageCacheOnlyError(nodeId: Int64) {
        guard let state = nodes[nodeId] else { return }
        if state.properties[PamConstants.onImageError] != nil {
            let payload = (try? WireMap.encode([
                "error": .text("Image is not available in cache"),
            ])) ?? Data()
            dispatchEvent(nodeId, EventKind.imageError.rawValue, payload)
        }
        if state.properties[PamConstants.onImageLoadEnd] != nil {
            dispatchEvent(nodeId, EventKind.imageLoadEnd.rawValue, Data())
        }
    }

    private func dispatchImageProgress(_ state: NodeState) {
        guard state.properties[PamConstants.onImageProgress] != nil else {
            return
        }
        let payload = (try? WireMap.encode(
            [
                "loaded": .integer(state.imageProgressLoaded),
                "total": .integer(state.imageProgressTotal),
            ],
        )) ?? Data()
        dispatchEvent(
            state.id,
            EventKind.imageProgress.rawValue,
            payload,
        )
    }

    private func cancelImageLoad(for state: NodeState) {
        if let task = state.imageTask {
            task.cancel()
            imageSessionDelegate.unregister(taskIdentifier: task.taskIdentifier)
            imageLoadContexts[task.taskIdentifier] = nil
            state.imageTask = nil
        }
        state.imageProgressLoaded = 0
        state.imageProgressTotal = 0
        state.imageProgressScheduled = false
        state.imageLoading = false
        imageLoadContexts = imageLoadContexts.filter { _, context in
            context.nodeId != state.id
        }
    }

    private func dispatchImageLoadStart(_ state: NodeState) {
        guard let _ = state.properties[PamConstants.onImageLoadStart] else {
            return
        }
        dispatchEvent(
            state.id,
            EventKind.imageLoadStart.rawValue,
            Data(),
        )
    }

    private final class EventBridge: NSObject, UIScrollViewDelegate, UIGestureRecognizerDelegate {
        private let nodeId: Int64
        private let kind: Int
        private let dispatchEvent: (Int64, Int, Data) -> Void
        private weak var tap: UITapGestureRecognizer?
        private weak var longPress: UILongPressGestureRecognizer?
        private weak var pressPointer: UILongPressGestureRecognizer?
        private weak var directiveTouch: UILongPressGestureRecognizer?
        private weak var outsideTap: UITapGestureRecognizer?
        private weak var rippleGesture: UILongPressGestureRecognizer?
        private weak var semanticGesture: UIGestureRecognizer?
        private weak var rippleOverlay: UIView?
        private weak var directiveView: UIView?
        private var frameObservation: NSKeyValueObservation?
        private var emitsIntersect = false
        private var emitsMutate = false
        private var emitsResize = false
        private var emitsTouchStart = false
        private var emitsTouchMove = false
        private var emitsTouchEnd = false
        private var lastIntersection: Bool?
        private weak var textField: PamInputField?
        private var focusField: PamInputField?
        private weak var control: UIControl?
        private weak var scrollView: UIScrollView?
        private weak var refreshControl: PamRefreshContainer?
        private weak var drawer: PamDrawerLayout?
        private var modal: PamModalHost?
        private weak var modalView: PamModalHost?
        private var pressInKind: Int?
        private var pressOutKind: Int?
        private var pressMoveKind: Int?
        private var scrollOffset: Data = Data()
        private var scrollScheduled = false
        private var emitsScroll = false
        private var emitsEndReached = false
        private var endReachedThreshold: CGFloat = 0.5
        private var endReachedSent = false
        private var lastScrollContentLength: CGFloat = -1
        private var lastScrollViewportLength: CGFloat = -1
        private var inputSelectionStart = 0
        private var inputSelectionEnd = 0
        private var inputSelectionScheduled = false
        private var semanticGestureType = 0
        private var semanticGestureDirection = 1
        private var semanticGestureComposition = 1
        private var semanticGestureMinimumDistance: CGFloat = 12
        private var semanticGestureBegan = false
        private var emitsGestureBegin = false
        private var emitsGestureUpdate = false
        private var emitsGestureEnd = false
        private var emitsGestureCancel = false
        private var pendingGesturePayload: Data?
        private var gestureUpdateScheduled = false
        private var nativeGestureTransform = false
        private var nativeGestureMinimumScale: CGFloat = 1
        private var nativeGestureMaximumScale: CGFloat = 4
        private var nativeGestureBaseTransform = CGAffineTransform.identity

        init(nodeId: Int64, kind: Int, dispatchEvent: @escaping (Int64, Int, Data) -> Void) {
            self.nodeId = nodeId
            self.kind = kind
            self.dispatchEvent = dispatchEvent
        }

        func attachButtonPress(_ button: UIButton) {
            button.addTarget(self, action: #selector(onPress), for: .touchUpInside)
            control = button
        }

        func attachSubmit(_ control: UIControl) {
            control.addTarget(self, action: #selector(onSubmit), for: .touchUpInside)
            control.addTarget(self, action: #selector(onSubmit), for: .primaryActionTriggered)
            self.control = control
        }

        func attachPress(to view: UIView) {
            let recognizer = UITapGestureRecognizer(target: self, action: #selector(onPress))
            recognizer.cancelsTouchesInView = false
            view.addGestureRecognizer(recognizer)
            tap = recognizer
        }

        func attachLongPress(to view: UIView) {
            let recognizer = UILongPressGestureRecognizer(target: self, action: #selector(onLongPress(_:)))
            recognizer.minimumPressDuration = 0.5
            view.addGestureRecognizer(recognizer)
            longPress = recognizer
        }

        func attachPressPointer(
            to view: UIView,
            pressIn: Int?,
            pressOut: Int?,
            pressMove: Int?,
        ) {
            pressInKind = pressIn
            pressOutKind = pressOut
            pressMoveKind = pressMove
            let recognizer = UILongPressGestureRecognizer(
                target: self,
                action: #selector(onPressPointer(_:)),
            )
            recognizer.minimumPressDuration = 0
            recognizer.cancelsTouchesInView = false
            recognizer.delaysTouchesEnded = false
            view.addGestureRecognizer(recognizer)
            pressPointer = recognizer
        }

        func attachDirectives(
            to view: UIView,
            host: UIView,
            clickOutside: Bool,
            intersect: Bool,
            mutate: Bool,
            resize: Bool,
            touchStart: Bool,
            touchMove: Bool,
            touchEnd: Bool
        ) {
            directiveView = view
            emitsIntersect = intersect
            emitsMutate = mutate
            emitsResize = resize
            emitsTouchStart = touchStart
            emitsTouchMove = touchMove
            emitsTouchEnd = touchEnd

            if intersect || mutate || resize {
                frameObservation = view.observe(\.frame, options: [.initial, .old, .new]) {
                    [weak self] _, change in
                    self?.onDirectiveLayout(old: change.oldValue, new: change.newValue)
                }
            }
            if touchStart || touchMove || touchEnd {
                let recognizer = UILongPressGestureRecognizer(
                    target: self,
                    action: #selector(onDirectiveTouch(_:))
                )
                recognizer.minimumPressDuration = 0
                recognizer.cancelsTouchesInView = false
                recognizer.delaysTouchesEnded = false
                view.addGestureRecognizer(recognizer)
                directiveTouch = recognizer
            }
            if clickOutside {
                let recognizer = UITapGestureRecognizer(
                    target: self,
                    action: #selector(onOutsideTap(_:))
                )
                recognizer.cancelsTouchesInView = false
                recognizer.delegate = self
                host.addGestureRecognizer(recognizer)
                outsideTap = recognizer
            }
        }

        func attachRipple(
            to view: UIView,
            color: Int64,
            alpha: Double,
            radius: Double?
        ) {
            let overlay = UIView(frame: view.bounds)
            overlay.isUserInteractionEnabled = false
            overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            let inheritedColor: UIColor
            if color != 0 {
                inheritedColor = UIColor(argb: color)
            } else if let label = view as? UILabel {
                inheritedColor = label.textColor
            } else if let button = view as? UIButton {
                inheritedColor = button.titleColor(for: .normal) ?? button.tintColor
            } else {
                inheritedColor = view.tintColor
            }
            overlay.backgroundColor = inheritedColor.withAlphaComponent(
                CGFloat(max(0, min(1, alpha)))
            )
            overlay.alpha = 0
            overlay.layer.cornerRadius = radius.map { CGFloat($0) }
                ?? view.layer.cornerRadius
            overlay.clipsToBounds = true
            view.addSubview(overlay)

            let recognizer = UILongPressGestureRecognizer(
                target: self,
                action: #selector(onRipple(_:))
            )
            recognizer.minimumPressDuration = 0
            recognizer.cancelsTouchesInView = false
            recognizer.delaysTouchesEnded = false
            recognizer.delegate = self
            view.addGestureRecognizer(recognizer)
            rippleGesture = recognizer
            rippleOverlay = overlay
        }

        func attachSemanticGesture(
            to view: UIView,
            type: Int,
            minimumPointers: Int,
            maximumPointers: Int,
            direction: Int,
            composition: Int,
            minimumDistance: Double,
            minimumDuration: Double,
            emitsBegin: Bool,
            emitsUpdate: Bool,
            emitsEnd: Bool,
            emitsCancel: Bool,
            nativeTransform: Bool,
            nativeMinimumScale: Double,
            nativeMaximumScale: Double
        ) {
            semanticGestureType = type
            semanticGestureDirection = direction
            semanticGestureComposition = composition
            semanticGestureMinimumDistance = max(0, minimumDistance)
            emitsGestureBegin = emitsBegin
            emitsGestureUpdate = emitsUpdate
            emitsGestureEnd = emitsEnd
            emitsGestureCancel = emitsCancel
            nativeGestureTransform = nativeTransform
            nativeGestureMinimumScale = CGFloat(max(0.01, nativeMinimumScale))
            nativeGestureMaximumScale = max(
                nativeGestureMinimumScale,
                CGFloat(nativeMaximumScale)
            )

            let minimum = min(max(minimumPointers, 1), 10)
            let maximum = min(max(maximumPointers, minimum), 10)
            let recognizer: UIGestureRecognizer
            switch type {
            case 1:
                let tap = UITapGestureRecognizer(
                    target: self,
                    action: #selector(onSemanticGesture(_:))
                )
                tap.numberOfTouchesRequired = minimum
                recognizer = tap
            case 2, 5:
                let pan = UIPanGestureRecognizer(
                    target: self,
                    action: #selector(onSemanticGesture(_:))
                )
                pan.minimumNumberOfTouches = minimum
                pan.maximumNumberOfTouches = maximum
                recognizer = pan
            case 3:
                recognizer = UIPinchGestureRecognizer(
                    target: self,
                    action: #selector(onSemanticGesture(_:))
                )
            case 4:
                recognizer = UIRotationGestureRecognizer(
                    target: self,
                    action: #selector(onSemanticGesture(_:))
                )
            default:
                let longPress = UILongPressGestureRecognizer(
                    target: self,
                    action: #selector(onSemanticGesture(_:))
                )
                longPress.minimumPressDuration = max(0.001, minimumDuration)
                longPress.numberOfTouchesRequired = minimum
                recognizer = longPress
            }
            recognizer.cancelsTouchesInView = composition == 1
            recognizer.delegate = self
            view.addGestureRecognizer(recognizer)
            semanticGesture = recognizer
        }

        func attachTextField(_ field: PamInputField) {
            field.addTarget(self, action: #selector(onTextChanged(_:)), for: .editingChanged)
            textField = field
        }

        func attachFocus(_ field: UITextField) {
            field.addTarget(self, action: #selector(onFocus), for: .editingDidBegin)
            if let inputField = field as? PamInputField {
                focusField = inputField
            }
        }

        func attachBlur(_ field: UITextField) {
            field.addTarget(self, action: #selector(onBlur), for: .editingDidEnd)
            if let inputField = field as? PamInputField {
                focusField = inputField
            }
        }

        func attachInputCallbacks(
            field: PamInputField,
            hasSelection: Bool,
            hasContentSize: Bool,
            hasKey: Bool,
            hasEndEditing: Bool,
        ) {
            if hasSelection || hasContentSize || hasKey {
                field.setInputCallbacks(
                    selection: hasSelection ? { [weak self] start, end in
                        self?.scheduleInputSelection(start: start, end: end)
                    } : nil,
                    contentSize: hasContentSize ? { [weak self] width, height in
                        self?.dispatchInputContentSize(width: width, height: height)
                    } : nil,
                    key: hasKey ? { [weak self] key in
                        self?.dispatchInputKey(key)
                    } : nil,
                )
            } else {
                field.setInputCallbacks()
            }
            if hasEndEditing {
                field.onInputEndEditing = { [weak self] value in
                    self?.dispatchInputEndEditing(value: value)
                }
            } else {
                field.onInputEndEditing = nil
            }
            textField = field
        }

        func attachControlValueChanged(_ control: UIControl) {
            control.addTarget(self, action: #selector(onToggle), for: .valueChanged)
            self.control = control
        }

        func attachScrollEvents(
            _ scroll: UIScrollView,
            isHorizontal: Bool,
            emitScroll: Bool,
            emitEndReached: Bool,
            endReachedThreshold: Double,
        ) {
            scroll.delegate = self
            scrollView = scroll
            emitsScroll = emitScroll
            emitsEndReached = emitEndReached
            self.endReachedThreshold = CGFloat(endReachedThreshold)
            endReachedSent = false
            lastScrollContentLength = -1
            lastScrollViewportLength = -1
            scroll.isDirectionalLockEnabled = true
            scroll.alwaysBounceVertical = !isHorizontal
            scroll.alwaysBounceHorizontal = isHorizontal
        }

        func attachRefresh(_ refresh: PamRefreshContainer) {
            refresh.setOnRefresh { [weak self] in
                guard let self else { return }
                self.dispatchEvent(self.nodeId, EventKind.refresh.rawValue, Data())
            }
            refreshControl = refresh
        }

        func attachDrawerCallbacks(_ drawer: PamDrawerLayout, onOpen: Bool, onClose: Bool) {
            drawer.setCallbacks(
                opened: onOpen ? { [weak self] in
                    self?.dispatchEvent(
                        self?.nodeId ?? 0,
                        EventKind.drawerOpen.rawValue,
                        Data(),
                    )
                } : nil,
                closed: onClose ? { [weak self] in
                    self?.dispatchEvent(
                        self?.nodeId ?? 0,
                        EventKind.drawerClose.rawValue,
                        Data(),
                    )
                } : nil,
            )
            self.drawer = drawer
        }

        func attachModalCallbacks(
            _ modal: PamModalHost,
            state: NodeState,
            handleRequestClose: Bool,
            handleShow: Bool,
            handleDismiss: Bool,
            handleOrientationChange: Bool,
            handleBottomSheetChange: Bool,
            handleBottomSheetDismiss: Bool,
        ) {
            modal.setCallbacks(
                onRequestClose: handleRequestClose ? { [weak self] in
                    guard let self else { return }
                    if state.properties[PamConstants.onModalRequestClose] != nil {
                        self.dispatchEvent(
                            self.nodeId,
                            EventKind.modalRequestClose.rawValue,
                            Data(),
                        )
                    }
                    if state.properties[PamConstants.onNativeEvent] != nil {
                        self.dispatchNative(
                            self.nodeId,
                            EventKind.native.rawValue,
                            EventBridge.modalDismissPayload,
                        )
                    }
                } : nil,
                onShow: handleShow ? { [weak self] in
                    guard let self else { return }
                    self.dispatchEvent(self.nodeId, EventKind.modalShow.rawValue, Data())
                } : nil,
                onDismiss: handleDismiss ? { [weak self] in
                    guard let self else { return }
                    self.dispatchEvent(self.nodeId, EventKind.modalDismiss.rawValue, Data())
                } : nil,
                onOrientationChange: handleOrientationChange ? { [weak self] orientation in
                    guard let self else { return }
                    self.dispatchEvent(
                        self.nodeId,
                        EventKind.modalOrientationChange.rawValue,
                        String(orientation).data(using: .utf8) ?? Data(),
                    )
                } : nil,
            )
            modal.setBottomSheetCallbacks(
                onChange: handleBottomSheetChange ? { [weak self] index, position in
                    guard let self else { return }
                    let payload = (try? WireMap.encode([
                        "index": .integer(Int64(index)),
                        "position": .decimal(Double(position)),
                    ])) ?? Data()
                    self.dispatchEvent(
                        self.nodeId,
                        EventKind.bottomSheetChange.rawValue,
                        payload
                    )
                } : nil,
                onDismiss: handleBottomSheetDismiss ? { [weak self] in
                    guard let self else { return }
                    self.dispatchEvent(
                        self.nodeId,
                        EventKind.bottomSheetDismiss.rawValue,
                        Data()
                    )
                } : nil
            )
            self.modal = modal
            self.modalView = modal
        }

        func detach() {
            if let tap {
                tap.view?.removeGestureRecognizer(tap)
            }
            if let longPress {
                longPress.view?.removeGestureRecognizer(longPress)
            }
            if let pressPointer {
                pressPointer.view?.removeGestureRecognizer(pressPointer)
            }
            if let directiveTouch {
                directiveTouch.view?.removeGestureRecognizer(directiveTouch)
            }
            if let outsideTap {
                outsideTap.view?.removeGestureRecognizer(outsideTap)
            }
            if let rippleGesture {
                rippleGesture.view?.removeGestureRecognizer(rippleGesture)
            }
            if let semanticGesture {
                semanticGesture.view?.removeGestureRecognizer(semanticGesture)
            }
            rippleOverlay?.removeFromSuperview()
            frameObservation?.invalidate()
            frameObservation = nil
            if let textField {
                textField.setInputCallbacks()
                textField.onInputEndEditing = nil
                textField.removeTarget(self, action: #selector(onTextChanged(_:)), for: .editingChanged)
                textField.removeTarget(self, action: #selector(onSubmit), for: .primaryActionTriggered)
            }
            if let focusField {
                focusField.removeTarget(self, action: #selector(onFocus), for: .editingDidBegin)
                focusField.removeTarget(self, action: #selector(onBlur), for: .editingDidEnd)
            }
            self.focusField = nil
            if let control {
                control.removeTarget(self, action: #selector(onPress), for: .touchUpInside)
                control.removeTarget(self, action: #selector(onSubmit), for: .touchUpInside)
                control.removeTarget(self, action: #selector(onSubmit), for: .primaryActionTriggered)
                control.removeTarget(self, action: #selector(onToggle), for: .valueChanged)
            }
            if let scrollView, scrollView.delegate === self {
                scrollView.delegate = nil
            }
            if let refreshControl {
                refreshControl.setOnRefresh(nil)
            }
            if let drawer {
                drawer.setCallbacks(opened: nil, closed: nil)
            }
            if let modal {
                modal.setCallbacks(
                    onRequestClose: nil,
                    onShow: nil,
                    onDismiss: nil,
                    onOrientationChange: nil,
                )
            }
            if let modalView {
                modalView.setCallbacks(
                    onRequestClose: nil,
                    onShow: nil,
                    onDismiss: nil,
                    onOrientationChange: nil,
                )
            }
            self.tap = nil
            self.longPress = nil
            self.pressPointer = nil
            self.directiveTouch = nil
            self.outsideTap = nil
            self.rippleGesture = nil
            self.semanticGesture = nil
            self.rippleOverlay = nil
            self.directiveView = nil
            self.textField = nil
            self.control = nil
            self.scrollView = nil
            self.refreshControl = nil
            self.drawer = nil
            self.modal = nil
            self.modalView = nil
            self.pressInKind = nil
            self.pressOutKind = nil
            self.pressMoveKind = nil
            self.pendingGesturePayload = nil
            self.gestureUpdateScheduled = false
            self.semanticGestureBegan = false
            self.emitsScroll = false
            self.emitsEndReached = false
            self.endReachedSent = false
            self.lastScrollContentLength = -1
            self.lastScrollViewportLength = -1
            self.lastIntersection = nil
        }

        @objc private func onDirectiveTouch(_ sender: UILongPressGestureRecognizer) {
            let eventKind: Int
            switch sender.state {
            case .began:
                guard emitsTouchStart else { return }
                eventKind = EventKind.touchStart.rawValue
            case .changed:
                guard emitsTouchMove else { return }
                eventKind = EventKind.touchMove.rawValue
            case .ended, .cancelled, .failed:
                guard emitsTouchEnd else { return }
                eventKind = EventKind.touchEnd.rawValue
            default:
                return
            }
            dispatchPressPointer(
                sender,
                kind: eventKind,
                locationInView: sender.location(in: sender.view),
                locationInWindow: sender.location(in: sender.view?.window)
            )
        }

        @objc private func onSemanticGesture(_ sender: UIGestureRecognizer) {
            let viewPoint = sender.location(in: sender.view)
            let windowPoint = sender.location(in: sender.view?.window)
            var translation = CGPoint.zero
            var velocity = CGPoint.zero
            var scale: CGFloat = 1
            var rotation: CGFloat = 0
            if let pan = sender as? UIPanGestureRecognizer {
                translation = pan.translation(in: sender.view)
                velocity = pan.velocity(in: sender.view)
            }
            if let pinch = sender as? UIPinchGestureRecognizer {
                scale = pinch.scale
                velocity = CGPoint(x: pinch.velocity, y: 0)
            }
            if let rotationGesture = sender as? UIRotationGestureRecognizer {
                rotation = rotationGesture.rotation
                velocity = CGPoint(x: rotationGesture.velocity, y: 0)
            }
            applyNativeGestureTransform(
                sender,
                translation: translation,
                scale: scale,
                rotation: rotation
            )

            if semanticGestureType == 2 || semanticGestureType == 5 {
                guard matchesSemanticDirection(translation) else {
                    if sender.state == .ended || sender.state == .cancelled {
                        emitSemanticCancel(
                            viewPoint: viewPoint,
                            windowPoint: windowPoint,
                            translation: translation,
                            velocity: velocity,
                            scale: scale,
                            rotation: rotation,
                            pointers: sender.numberOfTouches
                        )
                    }
                    return
                }
                let distance = hypot(translation.x, translation.y)
                if distance < semanticGestureMinimumDistance,
                   sender.state != .cancelled,
                   sender.state != .failed {
                    return
                }
            }

            let semanticState: Int
            let eventKind: Int
            switch sender.state {
            case .began:
                semanticGestureBegan = true
                semanticState = 1
                eventKind = EventKind.gestureBegin.rawValue
                guard emitsGestureBegin else { return }
            case .changed:
                if !semanticGestureBegan {
                    semanticGestureBegan = true
                    if emitsGestureBegin {
                        dispatchSemanticGesture(
                            kind: EventKind.gestureBegin.rawValue,
                            state: 1,
                            viewPoint: viewPoint,
                            windowPoint: windowPoint,
                            translation: translation,
                            velocity: velocity,
                            scale: scale,
                            rotation: rotation,
                            pointers: sender.numberOfTouches
                        )
                    }
                }
                semanticState = 2
                eventKind = EventKind.gestureUpdate.rawValue
                guard emitsGestureUpdate else { return }
            case .ended:
                if semanticGestureType == 1 && !semanticGestureBegan {
                    semanticGestureBegan = true
                    if emitsGestureBegin {
                        dispatchSemanticGesture(
                            kind: EventKind.gestureBegin.rawValue,
                            state: 1,
                            viewPoint: viewPoint,
                            windowPoint: windowPoint,
                            translation: translation,
                            velocity: velocity,
                            scale: scale,
                            rotation: rotation,
                            pointers: sender.numberOfTouches
                        )
                    }
                }
                semanticState = 3
                eventKind = EventKind.gestureEnd.rawValue
                semanticGestureBegan = false
                guard emitsGestureEnd else { return }
            case .cancelled, .failed:
                semanticState = sender.state == .failed ? 5 : 4
                eventKind = EventKind.gestureCancel.rawValue
                semanticGestureBegan = false
                guard emitsGestureCancel else { return }
            default:
                return
            }
            let payload = semanticGesturePayload(
                state: semanticState,
                viewPoint: viewPoint,
                windowPoint: windowPoint,
                translation: translation,
                velocity: velocity,
                scale: scale,
                rotation: rotation,
                pointers: sender.numberOfTouches
            )
            if semanticState == 2 {
                pendingGesturePayload = payload
                scheduleSemanticGestureUpdate()
            } else {
                dispatchEvent(nodeId, eventKind, payload)
            }
        }

        private func applyNativeGestureTransform(
            _ sender: UIGestureRecognizer,
            translation: CGPoint,
            scale: CGFloat,
            rotation: CGFloat
        ) {
            guard nativeGestureTransform, let child = sender.view?.subviews.first else {
                return
            }
            if sender.state == .began {
                nativeGestureBaseTransform = child.transform
            }
            switch semanticGestureType {
            case 2, 5:
                child.transform = nativeGestureBaseTransform.concatenating(
                    CGAffineTransform(
                        translationX: translation.x,
                        y: translation.y
                    )
                )
            case 3:
                let baseScale = hypot(
                    nativeGestureBaseTransform.a,
                    nativeGestureBaseTransform.c
                )
                let target = min(
                    nativeGestureMaximumScale,
                    max(nativeGestureMinimumScale, baseScale * scale)
                )
                let relative = target / max(baseScale, 0.0001)
                child.transform = nativeGestureBaseTransform.scaledBy(
                    x: relative,
                    y: relative
                )
            case 4:
                child.transform = nativeGestureBaseTransform.rotated(by: rotation)
            default:
                break
            }
        }

        private func emitSemanticCancel(
            viewPoint: CGPoint,
            windowPoint: CGPoint,
            translation: CGPoint,
            velocity: CGPoint,
            scale: CGFloat,
            rotation: CGFloat,
            pointers: Int
        ) {
            guard semanticGestureBegan, emitsGestureCancel else { return }
            semanticGestureBegan = false
            dispatchSemanticGesture(
                kind: EventKind.gestureCancel.rawValue,
                state: 4,
                viewPoint: viewPoint,
                windowPoint: windowPoint,
                translation: translation,
                velocity: velocity,
                scale: scale,
                rotation: rotation,
                pointers: pointers
            )
        }

        private func dispatchSemanticGesture(
            kind: Int,
            state: Int,
            viewPoint: CGPoint,
            windowPoint: CGPoint,
            translation: CGPoint,
            velocity: CGPoint,
            scale: CGFloat,
            rotation: CGFloat,
            pointers: Int
        ) {
            dispatchEvent(
                nodeId,
                kind,
                semanticGesturePayload(
                    state: state,
                    viewPoint: viewPoint,
                    windowPoint: windowPoint,
                    translation: translation,
                    velocity: velocity,
                    scale: scale,
                    rotation: rotation,
                    pointers: pointers
                )
            )
        }

        private func semanticGesturePayload(
            state: Int,
            viewPoint: CGPoint,
            windowPoint: CGPoint,
            translation: CGPoint,
            velocity: CGPoint,
            scale: CGFloat,
            rotation: CGFloat,
            pointers: Int
        ) -> Data {
            (try? WireMap.encode([
                "type": .integer(Int64(semanticGestureType)),
                "state": .integer(Int64(state)),
                "x": .decimal(viewPoint.x),
                "y": .decimal(viewPoint.y),
                "pageX": .decimal(windowPoint.x),
                "pageY": .decimal(windowPoint.y),
                "translationX": .decimal(translation.x),
                "translationY": .decimal(translation.y),
                "velocityX": .decimal(velocity.x),
                "velocityY": .decimal(velocity.y),
                "scale": .decimal(scale),
                "rotation": .decimal(rotation),
                "pointerCount": .integer(Int64(max(pointers, 1))),
                "timestamp": .integer(
                    Int64(ProcessInfo.processInfo.systemUptime * 1_000)
                ),
            ])) ?? Data()
        }

        private func scheduleSemanticGestureUpdate() {
            guard !gestureUpdateScheduled else { return }
            gestureUpdateScheduled = true
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.gestureUpdateScheduled = false
                guard let payload = self.pendingGesturePayload else { return }
                self.pendingGesturePayload = nil
                self.dispatchEvent(
                    self.nodeId,
                    EventKind.gestureUpdate.rawValue,
                    payload
                )
            }
        }

        private func matchesSemanticDirection(_ translation: CGPoint) -> Bool {
            switch semanticGestureDirection {
            case 2: return translation.x < 0 && abs(translation.x) >= abs(translation.y)
            case 3: return translation.x > 0 && abs(translation.x) >= abs(translation.y)
            case 4: return translation.y < 0 && abs(translation.y) >= abs(translation.x)
            case 5: return translation.y > 0 && abs(translation.y) >= abs(translation.x)
            case 6: return abs(translation.x) >= abs(translation.y)
            case 7: return abs(translation.y) >= abs(translation.x)
            default: return true
            }
        }

        @objc private func onOutsideTap(_ sender: UITapGestureRecognizer) {
            guard sender.state == .ended, let view = directiveView else { return }
            let point = sender.location(in: view)
            if !view.bounds.contains(point) {
                let windowPoint = sender.location(in: view.window)
                let payload = (try? WireMap.encode([
                    "pageX": .decimal(windowPoint.x),
                    "pageY": .decimal(windowPoint.y),
                ])) ?? Data()
                dispatchEvent(nodeId, EventKind.clickOutside.rawValue, payload)
            }
        }

        @objc private func onRipple(_ sender: UILongPressGestureRecognizer) {
            guard let overlay = rippleOverlay else { return }
            if UIAccessibility.isReduceMotionEnabled {
                overlay.alpha = sender.state == .began ? 1 : 0
                return
            }
            switch sender.state {
            case .began:
                UIView.animate(
                    withDuration: 0.075,
                    delay: 0,
                    options: [.beginFromCurrentState, .allowUserInteraction]
                ) {
                    overlay.alpha = 1
                }
            case .ended, .cancelled, .failed:
                UIView.animate(
                    withDuration: 0.18,
                    delay: 0,
                    options: [.beginFromCurrentState, .allowUserInteraction]
                ) {
                    overlay.alpha = 0
                }
            default:
                break
            }
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            if gestureRecognizer === semanticGesture {
                return semanticGestureComposition == 2
            }
            return true
        }

        private func onDirectiveLayout(old: CGRect?, new: CGRect?) {
            guard let view = directiveView, let frame = new else { return }
            if emitsResize, old?.size != frame.size {
                let payload = (try? WireMap.encode([
                    "width": .decimal(frame.width),
                    "height": .decimal(frame.height),
                ])) ?? Data()
                dispatchEvent(nodeId, EventKind.resize.rawValue, payload)
            }
            if emitsMutate, old != frame {
                let payload = (try? WireMap.encode([
                    "x": .decimal(frame.minX),
                    "y": .decimal(frame.minY),
                    "width": .decimal(frame.width),
                    "height": .decimal(frame.height),
                ])) ?? Data()
                dispatchEvent(nodeId, EventKind.mutate.rawValue, payload)
            }
            if emitsIntersect {
                let intersecting = view.window != nil &&
                    !view.isHidden &&
                    view.alpha > 0 &&
                    view.convert(view.bounds, to: nil).intersects(UIScreen.main.bounds)
                if lastIntersection != intersecting {
                    lastIntersection = intersecting
                    let payload = (try? WireMap.encode([
                        "intersecting": .flag(intersecting),
                    ])) ?? Data()
                    dispatchEvent(nodeId, EventKind.intersect.rawValue, payload)
                }
            }
        }

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            let isHorizontal = scrollView.alwaysBounceHorizontal && !scrollView.alwaysBounceVertical
            dispatchEndReachedIfNeeded(scrollView, isHorizontal: isHorizontal)

            guard emitsScroll else {
                return
            }

            let offset = isHorizontal ? scrollView.contentOffset.x : scrollView.contentOffset.y
            scrollOffset = "\(offset)".data(using: .utf8) ?? Data()
            if scrollScheduled {
                return
            }
            scrollScheduled = true
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                guard self.scrollScheduled else { return }
                self.scrollScheduled = false
                self.dispatchEvent(
                    self.nodeId,
                    EventKind.scroll.rawValue,
                    self.scrollOffset,
                )
            }
        }

        private func dispatchEndReachedIfNeeded(
            _ scrollView: UIScrollView,
            isHorizontal: Bool,
        ) {
            guard emitsEndReached else {
                return
            }

            let inset = scrollView.adjustedContentInset
            let contentLength = isHorizontal
                ? scrollView.contentSize.width
                : scrollView.contentSize.height
            let viewportLength = isHorizontal
                ? scrollView.bounds.width - inset.left - inset.right
                : scrollView.bounds.height - inset.top - inset.bottom

            guard contentLength > 0, viewportLength > 0 else {
                return
            }

            if contentLength != lastScrollContentLength ||
                viewportLength != lastScrollViewportLength {
                lastScrollContentLength = contentLength
                lastScrollViewportLength = viewportLength
                endReachedSent = false
            }

            guard !endReachedSent else {
                return
            }

            let offset = isHorizontal
                ? scrollView.contentOffset.x + inset.left
                : scrollView.contentOffset.y + inset.top
            let distanceFromEnd = contentLength - (offset + viewportLength)
            guard distanceFromEnd <= viewportLength * endReachedThreshold else {
                return
            }

            endReachedSent = true
            dispatchEvent(nodeId, EventKind.endReached.rawValue, Data())
        }

        @objc private func onPress() {
            dispatchEvent(nodeId, kind, Data())
        }

        @objc private func onLongPress(_ sender: UILongPressGestureRecognizer) {
            guard sender.state == .began else { return }
            dispatchEvent(nodeId, EventKind.longPress.rawValue, Data())
        }

        @objc private func onSubmit() {
            let payload = (try? WireMap.encode([
                "value": .text(textField?.text ?? ""),
            ])) ?? Data()
            dispatchEvent(nodeId, EventKind.submit.rawValue, payload)
        }

        @objc private func onPressPointer(_ sender: UILongPressGestureRecognizer) {
            let kind: Int
            switch sender.state {
            case .began:
                guard let pressInKind else { return }
                kind = pressInKind
            case .ended, .cancelled, .failed:
                guard let pressOutKind else { return }
                kind = pressOutKind
            case .changed:
                guard let pressMoveKind else { return }
                kind = pressMoveKind
            default:
                return
            }
            dispatchPressPointer(
                sender,
                kind: kind,
                locationInView: sender.location(in: sender.view),
                locationInWindow: sender.location(in: sender.view?.window),
            )
        }

        @objc private func onTextChanged(_ sender: UITextField) {
            guard let field = sender as? PamInputField else {
                return
            }
            if field.suppressTextChangeEvents {
                return
            }
            let value = sender.text ?? ""
            let payload = (try? WireMap.encode(["value": .text(value)])) ?? Data()
            dispatchEvent(nodeId, EventKind.change.rawValue, payload)
        }

        @objc private func onToggle() {
            guard let control = control as? PamVuetifySwitch else {
                dispatchEvent(nodeId, EventKind.toggle.rawValue, Data())
                return
            }
            let payload = (try? WireMap.encode(["value": .flag(control.isOn)])) ?? Data()
            dispatchEvent(nodeId, EventKind.toggle.rawValue, payload)
        }

        @objc private func onFocus() {
            dispatchEvent(nodeId, EventKind.focus.rawValue, Data())
        }

        @objc private func onBlur() {
            dispatchEvent(nodeId, EventKind.blur.rawValue, Data())
        }

        private func dispatchPressPointer(
            _ gesture: UILongPressGestureRecognizer,
            kind: Int,
            locationInView: CGPoint,
            locationInWindow: CGPoint?,
        ) {
            let location = locationInWindow ?? .zero
            let payload = (try? WireMap.encode([
                "x": .decimal(locationInView.x),
                "y": .decimal(locationInView.y),
                "pageX": .decimal(location.x),
                "pageY": .decimal(location.y),
                "timestamp": .integer(Int64(ProcessInfo.processInfo.systemUptime * 1000)),
                "pointerId": .integer(0),
            ])) ?? Data()
            dispatchEvent(nodeId, kind, payload)
        }

        private func scheduleInputSelection(start: Int, end: Int) {
            inputSelectionStart = start
            inputSelectionEnd = end
            if inputSelectionScheduled {
                return
            }
            inputSelectionScheduled = true
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.inputSelectionScheduled = false
                self.dispatchInputSelection(start: self.inputSelectionStart, end: self.inputSelectionEnd)
            }
        }

        private func dispatchInputEndEditing(value: String) {
            let payload = (try? WireMap.encode(["value": .text(value)])) ?? Data()
            dispatchEvent(
                nodeId,
                EventKind.inputEndEditing.rawValue,
                payload,
            )
        }

        private func dispatchInputSelection(start: Int, end: Int) {
            let payload = (try? WireMap.encode([
                "start": .integer(Int64(start)),
                "end": .integer(Int64(end)),
            ])) ?? Data()
            dispatchEvent(
                nodeId,
                EventKind.inputSelectionChange.rawValue,
                payload,
            )
        }

        private func dispatchInputContentSize(width: Int, height: Int) {
            let payload = (try? WireMap.encode([
                "width": .decimal(Double(width)),
                "height": .decimal(Double(height)),
            ])) ?? Data()
            dispatchEvent(
                nodeId,
                EventKind.inputContentSizeChange.rawValue,
                payload,
            )
        }

        private func dispatchInputKey(_ key: String) {
            let payload = (try? WireMap.encode(["key": .text(key)])) ?? Data()
            dispatchEvent(
                nodeId,
                EventKind.inputKeyPress.rawValue,
                payload,
            )
        }

        private func dispatchNative(_ nodeId: Int64, _ kind: Int, _ payload: Data) {
            dispatchEvent(nodeId, kind, payload)
        }

        private static let modalDismissPayload = (try? WireMap.encode(
            [
                "action": .integer(1),
                "dismissed": .flag(true),
            ],
        )) ?? Data()
    }
}

private extension NodeKind {
    var isContainer: Bool {
        switch self {
        case .text, .input, .button, .activityIndicator, .toggle, .image,
             .drawingCanvas, .spacer, .statusBar:
            return false
        default:
            return true
        }
    }
}

private extension PropValue {
    func textOrNil() -> String? {
        switch self {
        case let .text(value):
            value
        case .decimal:
            nil
        default:
            nil
        }
    }

    func integerOrNil() -> Int64? {
        switch self {
        case let .integer(value):
            value
        default:
            nil
        }
    }

    func decimalOrNil() -> Double? {
        switch self {
        case let .decimal(value):
            value
        default:
            nil
        }
    }

    func decimalOrZero() -> Double {
        decimalOrNil() ?? 0
    }

    func boolOrNil() -> Bool? {
        switch self {
        case let .flag(value):
            value
        default:
            nil
        }
    }

    func bytesOrNil() -> Data? {
        switch self {
        case let .bytes(value):
            value
        default:
            nil
        }
    }

    func propertiesOrNil() -> [String: WireValue]? {
        switch self {
        case let .properties(value):
            value
        default:
            nil
        }
    }
}

private final class PamBorderLayers {
    private let left = CALayer()
    private let top = CALayer()
    private let right = CALayer()
    private let bottom = CALayer()

    init(host: CALayer) {
        for layer in [left, top, right, bottom] {
            layer.zPosition = 10_000
            host.addSublayer(layer)
        }
    }

    func update(
        bounds: CGRect,
        left leftWidth: CGFloat,
        top topWidth: CGFloat,
        right rightWidth: CGFloat,
        bottom bottomWidth: CGFloat,
        color: CGColor
    ) {
        left.backgroundColor = color
        top.backgroundColor = color
        right.backgroundColor = color
        bottom.backgroundColor = color

        left.frame = CGRect(
            x: bounds.minX,
            y: bounds.minY,
            width: leftWidth,
            height: bounds.height,
        )
        top.frame = CGRect(
            x: bounds.minX,
            y: bounds.minY,
            width: bounds.width,
            height: topWidth,
        )
        right.frame = CGRect(
            x: bounds.maxX - rightWidth,
            y: bounds.minY,
            width: rightWidth,
            height: bounds.height,
        )
        bottom.frame = CGRect(
            x: bounds.minX,
            y: bounds.maxY - bottomWidth,
            width: bounds.width,
            height: bottomWidth,
        )
    }

    func remove() {
        left.removeFromSuperlayer()
        top.removeFromSuperlayer()
        right.removeFromSuperlayer()
        bottom.removeFromSuperlayer()
    }
}

private final class NodeState {
    let id: Int64
    var parent: Int64
    var index: Int
    let kind: NodeKind
    var properties: [Int: PropValue]
    let mountOrder: Int64
    var imageTask: URLSessionTask?
    var childrenNeedRethrow: UIView?
    var imageGeneration: Int
    var imageLoading: Bool
    var imageProgressLoaded: Int64
    var imageProgressTotal: Int64
    var imageProgressScheduled: Bool

    init(
        id: Int64,
        parent: Int64,
        index: Int,
        kind: NodeKind,
        properties: [Int: PropValue],
        mountOrder: Int64,
        imageTask: URLSessionTask?,
        imageGeneration: Int,
        imageLoading: Bool,
        imageProgressLoaded: Int64,
        imageProgressTotal: Int64,
        imageProgressScheduled: Bool,
    ) {
        self.id = id
        self.parent = parent
        self.index = index
        self.kind = kind
        self.properties = properties
        self.mountOrder = mountOrder
        self.imageTask = imageTask
        self.imageGeneration = imageGeneration
        self.imageLoading = imageLoading
        self.imageProgressLoaded = imageProgressLoaded
        self.imageProgressTotal = imageProgressTotal
        self.imageProgressScheduled = imageProgressScheduled
    }
}

extension UIColor {
    convenience init(argb: Int64) {
        let value = UInt64(truncatingIfNeeded: argb)
        let r = CGFloat((value >> 16) & 0xFF) / 255.0
        let g = CGFloat((value >> 8) & 0xFF) / 255.0
        let b = CGFloat(value & 0xFF) / 255.0
        let a = CGFloat((value >> 24) & 0xFF) / 255.0
        self.init(red: r, green: g, blue: b, alpha: a)
    }
}
