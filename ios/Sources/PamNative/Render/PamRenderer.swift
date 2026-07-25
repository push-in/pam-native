import Foundation
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

        cancelImageLoad(for: state)

        if let view = views[id] {
            nativeViews.release(view: view)
            view.removeFromSuperview()
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
               let imageView = view as? UIImageView {
                cancelImageLoad(for: state)
                imageView.image = nil
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
            if let imageView = view as? UIImageView {
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

        let left = frame.x - parentFrame.x
        let top = frame.y - parentFrame.y
        let width = max(0, frame.width)
        let height = max(0, frame.height)

        view.frame = CGRect(
            x: CGFloat(left),
            y: CGFloat(top),
            width: CGFloat(width),
            height: CGFloat(height),
        )
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
        case .screen, .column, .row, .view, .keyboardAvoidingView, .safeAreaView, .inputAccessoryView:
            return UIView()
        case .pressable:
            return UIButton(type: .system)
        case .button:
            return UIButton(type: .system)
        case .text:
            return UILabel()
        case .input:
            let field = PamInputField()
            field.borderStyle = .roundedRect
            return field
        case .image, .imageBackground:
            return UIImageView()
        case .scroll, .list, .sectionList, .virtualList:
            return UIScrollView()
        case .spacer:
            return UIView()
        case .activityIndicator:
            let indicator = UIActivityIndicatorView(style: .medium)
            indicator.startAnimating()
            return indicator
        case .toggle:
            return UISwitch()
        case .modal:
            return PamModalHost()
        case .drawerLayout:
            return PamDrawerLayout()
        case .statusBar:
            return UIView()
        case .navigationHost:
            return UIView()
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

        if eventProperties.contains(PamConstants.onToggle), let switchView = view as? UISwitch {
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
            )
            eventBridges[nodeId]?[EventKind.modalRequestClose.rawValue] = bridge
        }
    }

    private func dispatchNativeViewEvent(nodeId: Int64, kind: Int, payload: Data) {
        guard let state = nodes[nodeId] else { return }
        guard let eventProperty = nativeEventProperty(kind), state.properties[eventProperty] != nil else {
            return
        }
        dispatchEvent(nodeId, kind, payload)
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
            if let textValue = value.textOrNil(), let field = view as? UITextField {
                field.text = textValue
            } else if let boolValue = value.boolOrNil(), let switchView = view as? UISwitch {
                switchView.isOn = boolValue
            }
        case PamConstants.placeholder:
            if let textValue = value.textOrNil(), let field = view as? UITextField {
                field.placeholder = textValue
            }
        case PamConstants.source:
            if let imageView = view as? UIImageView, let source = value.textOrNil() {
                loadImage(source, into: imageView, nodeId: nodeId)
            }
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
        case PamConstants.backgroundColor, PamConstants.borderColor:
            if let color = value.integerOrNil() {
                view.backgroundColor = UIColor(argb: color)
            }
        case PamConstants.enabled:
            if let enabled = value.boolOrNil() {
                view.isUserInteractionEnabled = enabled
                if let control = view as? UIControl {
                    control.isEnabled = enabled
                }
            }
        case PamConstants.accessibilityLabel:
            view.accessibilityLabel = value.textOrNil()
        case PamConstants.testId:
            view.accessibilityIdentifier = value.textOrNil()
        case PamConstants.textColor:
            if let color = value.integerOrNil() {
                if let label = view as? UILabel {
                    label.textColor = UIColor(argb: color)
                }
            }
        case PamConstants.fontSize:
            if let size = value.decimalOrNil() {
                if let label = view as? UILabel {
                    label.font = UIFont.systemFont(ofSize: CGFloat(size))
                } else if let button = view as? UIButton {
                    button.titleLabel?.font = UIFont.systemFont(ofSize: CGFloat(size))
                }
            }
        case PamConstants.visible:
            if let modal = view as? PamModalHost {
                modal.setVisible(value.boolOrNil() ?? true)
            } else if let indicator = view as? UIActivityIndicatorView {
                if value.boolOrNil() ?? true {
                    indicator.startAnimating()
                } else {
                    indicator.stopAnimating()
                }
            } else {
                view.isHidden = !(value.boolOrNil() ?? true)
            }
        case PamConstants.modalPresentation:
            (view as? PamModalHost)?.setPresentation(Int(value.integerOrNil() ?? 2))
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
        case PamConstants.drawerOpen:
            (view as? PamDrawerLayout)?.setOpen(value.boolOrNil() ?? false, animated: true)
        case PamConstants.hostProperties:
            nativeViews.update(view: view, properties: value.propertiesOrNil() ?? [:])
        default:
            break
        }
    }

    private func resetProperty(view: UIView, nodeId _: Int64, key: Int, state: NodeState) {
        switch key {
        case PamConstants.visible:
            if let modal = view as? PamModalHost {
                modal.setVisible(true)
            } else if let indicator = view as? UIActivityIndicatorView {
                indicator.startAnimating()
            } else {
                view.isHidden = false
            }
        case PamConstants.modalPresentation:
            (view as? PamModalHost)?.setPresentation(2)
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
        case PamConstants.drawerOpen:
            (view as? PamDrawerLayout)?.setOpen(false, animated: true)
        default:
            break
        }
    }

    private func configureScrollView(_ scroll: UIScrollView, horizontal: Bool) {
        scroll.alwaysBounceVertical = !horizontal
        scroll.alwaysBounceHorizontal = horizontal
        scroll.showsHorizontalScrollIndicator = horizontal
        scroll.showsVerticalScrollIndicator = !horizontal
        scroll.isDirectionalLockEnabled = true
    }

    private func loadImage(_ source: String, into imageView: UIImageView, nodeId: Int64) {
        guard let url = URL(string: source), let state = nodes[nodeId] else {
            return
        }

        cancelImageLoad(for: state)
        state.imageGeneration += 1
        state.imageLoading = true
        state.imageProgressLoaded = 0
        state.imageProgressTotal = 0
        state.imageProgressScheduled = false

        let generation = state.imageGeneration
        let request = URLRequest(url: url, cachePolicy: .returnCacheDataElseLoad)
        let task = imageSession.downloadTask(with: request)
        let context = ImageLoadContext(
            nodeId: nodeId,
            generation: generation,
            source: source,
            imageView: imageView,
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
                  let image = UIImage(data: data),
                  let imageView = self.views[context.nodeId] as? UIImageView else {
                return
            }
            DispatchQueue.main.async {
                guard let state = self.nodes[context.nodeId], state.imageGeneration == context.generation else {
                    return
                }
                imageView.image = image
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

    private final class EventBridge: NSObject, UIScrollViewDelegate {
        private let nodeId: Int64
        private let kind: Int
        private let dispatchEvent: (Int64, Int, Data) -> Void
        private weak var tap: UITapGestureRecognizer?
        private weak var longPress: UILongPressGestureRecognizer?
        private weak var pressPointer: UILongPressGestureRecognizer?
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
            self.emitsScroll = false
            self.emitsEndReached = false
            self.endReachedSent = false
            self.lastScrollContentLength = -1
            self.lastScrollViewportLength = -1
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
            guard let control = control as? UISwitch else {
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
        case .text, .input, .button, .activityIndicator, .toggle, .image, .spacer, .statusBar:
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

    func propertiesOrNil() -> [String: WireValue]? {
        switch self {
        case let .properties(value):
            value
        default:
            nil
        }
    }
}

private final class NodeState {
    let id: Int64
    var parent: Int64
    var index: Int
    let kind: NodeKind
    var properties: [Int: PropValue]
    let mountOrder: Int64
    var imageTask: URLSessionDownloadTask?
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
        imageTask: URLSessionDownloadTask?,
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
