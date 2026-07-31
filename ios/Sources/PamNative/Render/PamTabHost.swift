import UIKit

/** Retained native tab host. Selection and swipe feedback stay on UIKit's main thread. */
final class PamTabHost: UIView, UITabBarDelegate {
    private struct Item: Equatable {
        let label: String
        let badge: String?
    }

    private let content = UIView()
    private let tabBar = UITabBar()
    private let segmented = UISegmentedControl(items: [])
    private let rail = UIStackView()
    private var scenes: [UIView] = []
    private var items: [Item] = []
    private var selectedIndex = 1
    private var position = 1
    private var activeColor = UIColor.label
    private var inactiveColor = UIColor.secondaryLabel
    private var barColor = UIColor.systemBackground
    private var indicatorColor = UIColor.label
    private var swipeEnabled = false
    var onSelect: ((Int) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true
        addSubview(content)
        addSubview(tabBar)
        addSubview(segmented)
        addSubview(rail)
        tabBar.delegate = self
        segmented.addTarget(self, action: #selector(segmentChanged), for: .valueChanged)
        rail.axis = .vertical
        rail.distribution = .fillEqually
        addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(swiped(_:))))
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func insertScene(_ view: UIView, index: Int) {
        if !scenes.contains(where: { $0 === view }) {
            scenes.insert(view, at: min(max(index, 0), scenes.count))
            content.addSubview(view)
        }
        setNeedsLayout()
        updateSelection()
    }

    func configure(
        encodedItems: String,
        selectedIndex: Int,
        position: Int,
        activeColor: UIColor,
        inactiveColor: UIColor,
        barColor: UIColor,
        indicatorColor: UIColor,
        swipeEnabled: Bool
    ) {
        let decoded: [Item] = ((try? JSONSerialization.jsonObject(with: Data(encodedItems.utf8))) as? [[String: Any]])?
            .prefix(32)
            .compactMap { value in
                guard let label = value["label"] as? String else { return nil }
                return Item(label: String(label.prefix(64)), badge: (value["badge"] as? String).map { String($0.prefix(12)) })
            } ?? []
        let rebuild = decoded != items || self.position != position
        items = decoded
        self.selectedIndex = min(max(selectedIndex, 1), max(items.count, 1))
        self.position = min(max(position, 1), 3)
        self.activeColor = activeColor
        self.inactiveColor = inactiveColor
        self.barColor = barColor
        self.indicatorColor = indicatorColor
        self.swipeEnabled = swipeEnabled
        if rebuild { rebuildBars() }
        updateSelection()
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let bottomHeight: CGFloat = 49 + safeAreaInsets.bottom
        switch position {
        case 2:
            segmented.frame = CGRect(x: 8, y: 4, width: bounds.width - 16, height: 48)
            content.frame = CGRect(x: 0, y: 56, width: bounds.width, height: max(0, bounds.height - 56))
        case 3:
            rail.frame = CGRect(x: 0, y: 0, width: 104, height: bounds.height)
            content.frame = CGRect(x: 104, y: 0, width: max(0, bounds.width - 104), height: bounds.height)
        default:
            content.frame = CGRect(x: 0, y: 0, width: bounds.width, height: max(0, bounds.height - bottomHeight))
            tabBar.frame = CGRect(x: 0, y: bounds.height - bottomHeight, width: bounds.width, height: bottomHeight)
        }
        scenes.forEach { $0.frame = content.bounds }
    }

    func tabBar(_ tabBar: UITabBar, didSelect item: UITabBarItem) {
        select(item.tag)
    }

    private func rebuildBars() {
        tabBar.isHidden = position != 1
        segmented.isHidden = position != 2
        rail.isHidden = position != 3
        tabBar.items = items.enumerated().map { index, item in
            let value = UITabBarItem(title: item.label, image: nil, tag: index + 1)
            value.badgeValue = item.badge
            return value
        }
        segmented.removeAllSegments()
        items.enumerated().forEach { segmented.insertSegment(withTitle: $0.element.label, at: $0.offset, animated: false) }
        rail.arrangedSubviews.forEach { $0.removeFromSuperview() }
        items.enumerated().forEach { index, item in
            var configuration = UIButton.Configuration.plain()
            configuration.title = item.badge.map { "\(item.label)  \($0)" } ?? item.label
            let button = UIButton(configuration: configuration)
            button.tag = index + 1
            button.addTarget(self, action: #selector(railSelected(_:)), for: .touchUpInside)
            rail.addArrangedSubview(button)
        }
    }

    private func select(_ index: Int) {
        guard items.indices.contains(index - 1) else { return }
        selectedIndex = index
        updateSelection()
        onSelect?(index)
    }

    internal var activeSceneIndex: Int { selectedIndex }
    internal func selectForTesting(_ index: Int) { select(index) }

    private func updateSelection() {
        scenes.enumerated().forEach { index, scene in
            let active = index + 1 == selectedIndex
            scene.isHidden = !active
            scene.accessibilityElementsHidden = !active
        }
        tabBar.selectedItem = tabBar.items?.first { $0.tag == selectedIndex }
        tabBar.tintColor = activeColor
        tabBar.unselectedItemTintColor = inactiveColor
        tabBar.backgroundColor = barColor
        segmented.selectedSegmentIndex = selectedIndex - 1
        segmented.selectedSegmentTintColor = indicatorColor
        rail.backgroundColor = barColor
        rail.arrangedSubviews.enumerated().forEach { index, view in
            (view as? UIButton)?.tintColor = index + 1 == selectedIndex ? activeColor : inactiveColor
            (view as? UIButton)?.accessibilityTraits = index + 1 == selectedIndex ? [.button, .selected] : .button
        }
    }

    @objc private func segmentChanged() { select(segmented.selectedSegmentIndex + 1) }
    @objc private func railSelected(_ sender: UIButton) { select(sender.tag) }
    @objc private func swiped(_ gesture: UIPanGestureRecognizer) {
        guard swipeEnabled, position == 2, gesture.state == .ended else { return }
        let delta = gesture.translation(in: self).x
        if delta < -48 { select(min(selectedIndex + 1, items.count)) }
        else if delta > 48 { select(max(selectedIndex - 1, 1)) }
    }
}
