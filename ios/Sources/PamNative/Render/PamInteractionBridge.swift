import Foundation
import UIKit
import UniformTypeIdentifiers

final class PamInteractionBridge: NSObject,
    UIDragInteractionDelegate,
    UIDropInteractionDelegate,
    UIContextMenuInteractionDelegate {
    struct MenuItem {
        let id: String
        let title: String
        let destructive: Bool
        let disabled: Bool
    }

    private weak var view: UIView?
    private let dragData: String
    private let dropEnabled: Bool
    private let menuItems: [MenuItem]
    private let onDragStart: (() -> Void)?
    private let onDragEnd: (() -> Void)?
    private let onDrop: ((String) -> Void)?
    private let onMenuAction: ((String) -> Void)?
    private var dragInteraction: UIDragInteraction?
    private var dropInteraction: UIDropInteraction?
    private var menuInteraction: UIContextMenuInteraction?

    init(
        view: UIView,
        draggable: Bool,
        dragData: String,
        dropEnabled: Bool,
        menuItems: [MenuItem],
        onDragStart: (() -> Void)?,
        onDragEnd: (() -> Void)?,
        onDrop: ((String) -> Void)?,
        onMenuAction: ((String) -> Void)?
    ) {
        self.view = view
        self.dragData = dragData
        self.dropEnabled = dropEnabled
        self.menuItems = menuItems
        self.onDragStart = onDragStart
        self.onDragEnd = onDragEnd
        self.onDrop = onDrop
        self.onMenuAction = onMenuAction
        super.init()
        if draggable {
            let interaction = UIDragInteraction(delegate: self)
            interaction.isEnabled = true
            view.addInteraction(interaction)
            dragInteraction = interaction
        }
        if dropEnabled {
            let interaction = UIDropInteraction(delegate: self)
            view.addInteraction(interaction)
            dropInteraction = interaction
        }
        if !menuItems.isEmpty {
            let interaction = UIContextMenuInteraction(delegate: self)
            view.addInteraction(interaction)
            menuInteraction = interaction
        }
    }

    func detach() {
        if let dragInteraction { view?.removeInteraction(dragInteraction) }
        if let dropInteraction { view?.removeInteraction(dropInteraction) }
        if let menuInteraction { view?.removeInteraction(menuInteraction) }
    }

    func dragInteraction(
        _ interaction: UIDragInteraction,
        itemsForBeginning session: UIDragSession
    ) -> [UIDragItem] {
        onDragStart?()
        return [UIDragItem(itemProvider: NSItemProvider(object: dragData as NSString))]
    }

    func dragInteraction(_ interaction: UIDragInteraction, sessionDidEnd session: UIDragSession) {
        onDragEnd?()
    }

    func dropInteraction(_ interaction: UIDropInteraction, canHandle session: UIDropSession) -> Bool {
        dropEnabled && session.hasItemsConforming(toTypeIdentifiers: [UTType.text.identifier])
    }

    func dropInteraction(
        _ interaction: UIDropInteraction,
        sessionDidUpdate session: UIDropSession
    ) -> UIDropProposal {
        UIDropProposal(operation: .copy)
    }

    func dropInteraction(_ interaction: UIDropInteraction, performDrop session: UIDropSession) {
        guard let provider = session.items.first?.itemProvider else { return }
        provider.loadObject(ofClass: NSString.self) { [weak self] object, _ in
            guard let value = object as? String else { return }
            DispatchQueue.main.async { self?.onDrop?(value) }
        }
    }

    func contextMenuInteraction(
        _ interaction: UIContextMenuInteraction,
        configurationForMenuAtLocation location: CGPoint
    ) -> UIContextMenuConfiguration? {
        UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            guard let self else { return UIMenu(children: []) }
            return UIMenu(children: self.menuItems.map { item in
                var attributes: UIMenuElement.Attributes = []
                if item.destructive { attributes.insert(.destructive) }
                if item.disabled { attributes.insert(.disabled) }
                return UIAction(
                    title: item.title,
                    attributes: attributes
                ) { [weak self] _ in self?.onMenuAction?(item.id) }
            })
        }
    }
}
