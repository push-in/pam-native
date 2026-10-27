import UIKit

/// `Toast::message()` (react-native-toast-message `BaseToast` styling: white
/// card, colored left accent, title + message, top/bottom offset, duration)
/// and the plain `Toast::show()` capsule.
struct PamToastSpec: Equatable {
    var title = ""
    var message = ""
    var bottom = false
    var durationMs = 4_000
    var offset: CGFloat = 40
    var accentColor: Int64 = 0xFF69_C779
    var backgroundColor: Int64 = 0xFFFF_FFFF
    var titleColor: Int64 = 0xFF00_0000
    var messageColor: Int64 = 0xFF97_9797
    var titleSize: CGFloat = 12
    var messageSize: CGFloat = 10
    var fontFamily: String?
    var borderColor: Int64?
    var borderWidth: CGFloat = 0
    var cornerRadius: CGFloat = 6
    /// Left accent strip width; 0 hides it.
    var accentWidth: CGFloat = 5
    var elevation: CGFloat = 2
    var paddingHorizontal: CGFloat = 15
    var paddingVertical: CGFloat = 10
    var minHeight: CGFloat = 60
    var titleLines = 1
    /// The card hugs its text (up to the window width minus 16 pt gutters)
    /// instead of the fixed 340 pt card.
    var fitContent = false
    /// Packaged `asset://….png` icon drawn before the text.
    var iconSource: String?
    /// Icon-font glyph drawn with `iconFontFamily` when there is no `iconSource`.
    var iconGlyph: String?
    var iconFontFamily: String?
    var iconColor: Int64?
    var iconSize: CGFloat = 18
    /// Plain `Toast::show()` capsule.
    var plain = false

    static func decode(_ values: [String: WireValue]) -> PamToastSpec {
        func text(_ key: String) -> String? {
            if case let .text(value)? = values[key] { return value }
            return nil
        }
        func number(_ key: String) -> Double? {
            switch values[key] {
            case let .decimal(value)?: return value
            case let .integer(value)?: return Double(value)
            default: return nil
            }
        }
        func integer(_ key: String) -> Int64? {
            switch values[key] {
            case let .integer(value)?: return value
            case let .decimal(value)?: return Int64(value)
            default: return nil
            }
        }
        func flag(_ key: String) -> Bool? {
            if case let .flag(value)? = values[key] { return value }
            return nil
        }
        var spec = PamToastSpec()
        guard values["title"] != nil else {
            spec.plain = true
            spec.message = text("message") ?? ""
            spec.bottom = true
            spec.durationMs = (flag("long") ?? false) ? 3_500 : 2_000
            return spec
        }
        spec.title = text("title") ?? ""
        spec.message = text("message") ?? ""
        spec.bottom = flag("bottom") ?? false
        spec.durationMs = Int(min(60_000, max(500, integer("durationMs") ?? 4_000)))
        spec.offset = CGFloat(max(0, number("offset") ?? 40))
        spec.accentColor = integer("accentColor") ?? spec.accentColor
        spec.backgroundColor = integer("backgroundColor") ?? spec.backgroundColor
        spec.titleColor = integer("titleColor") ?? spec.titleColor
        spec.messageColor = integer("messageColor") ?? spec.messageColor
        spec.titleSize = CGFloat(max(1, number("titleSize") ?? 12))
        spec.messageSize = CGFloat(max(1, number("messageSize") ?? 10))
        spec.fontFamily = text("fontFamily")
        spec.borderColor = integer("borderColor")
        spec.borderWidth = CGFloat(min(8, max(0, number("borderWidth") ?? 0)))
        spec.cornerRadius = CGFloat(min(64, max(0, number("cornerRadius") ?? 6)))
        spec.accentWidth = CGFloat(min(32, max(0, number("accentWidth") ?? 5)))
        spec.elevation = CGFloat(min(24, max(0, number("elevation") ?? 2)))
        spec.paddingHorizontal = CGFloat(min(64, max(0, number("paddingHorizontal") ?? 15)))
        spec.paddingVertical = CGFloat(min(64, max(0, number("paddingVertical") ?? 10)))
        spec.minHeight = CGFloat(min(200, max(0, number("minHeight") ?? 60)))
        spec.titleLines = Int(min(4, max(1, integer("titleLines") ?? 1)))
        spec.fitContent = flag("fitContent") ?? false
        spec.iconSource = text("iconSource")
        spec.iconGlyph = text("iconGlyph")
        spec.iconFontFamily = text("iconFontFamily")
        spec.iconColor = integer("iconColor")
        spec.iconSize = CGFloat(min(64, max(1, number("iconSize") ?? 18)))
        return spec
    }
}

enum PamToastPresenter {
    private static weak var current: UIView?

    static func show(_ spec: PamToastSpec) {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { show(spec) }
            return
        }
        guard let window = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .flatMap(\.windows)
            .first(where: \.isKeyWindow) else { return }
        current?.removeFromSuperview()
        let toast = spec.plain ? plainView(spec) : cardView(spec)
        toast.translatesAutoresizingMaskIntoConstraints = false
        window.addSubview(toast)
        let guide = window.safeAreaLayoutGuide
        var constraints = [
            toast.centerXAnchor.constraint(equalTo: guide.centerXAnchor),
            toast.widthAnchor.constraint(lessThanOrEqualTo: guide.widthAnchor, constant: -32),
        ]
        if spec.plain {
            constraints.append(toast.bottomAnchor.constraint(equalTo: guide.bottomAnchor, constant: -48))
        } else {
            if !spec.fitContent {
                constraints.append(toast.widthAnchor.constraint(equalToConstant: min(340, window.bounds.width - 32)))
            }
            constraints.append(toast.heightAnchor.constraint(greaterThanOrEqualToConstant: spec.minHeight))
            // Offsets count from the safe area (status bar, notch, Dynamic
            // Island, home indicator) like Android's inset-relative margin;
            // a bottom toast also rests above the keyboard.
            constraints.append(spec.bottom
                ? toast.bottomAnchor.constraint(
                    lessThanOrEqualTo: window.keyboardLayoutGuide.topAnchor,
                    constant: -spec.offset
                )
                : toast.topAnchor.constraint(equalTo: guide.topAnchor, constant: spec.offset))
            if spec.bottom {
                let resting = toast.bottomAnchor.constraint(equalTo: guide.bottomAnchor, constant: -spec.offset)
                resting.priority = .defaultHigh
                constraints.append(resting)
            }
        }
        NSLayoutConstraint.activate(constraints)
        current = toast
        let travel: CGFloat = spec.bottom ? 24 : -24
        let reduced = PamMotionPolicy.isReduced
        toast.alpha = 0
        toast.transform = reduced ? .identity : CGAffineTransform(translationX: 0, y: travel)
        UIView.animate(withDuration: reduced ? 0 : 0.25) {
            toast.alpha = 1
            toast.transform = .identity
        }
        UIAccessibility.post(
            notification: .announcement,
            argument: [spec.title, spec.message].filter { !$0.isEmpty }.joined(separator: ". ")
        )
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(spec.durationMs)) { [weak toast] in
            guard let toast else { return }
            hide(toast, travel: reduced ? 0 : travel)
        }
    }

    private static func hide(_ toast: UIView, travel: CGFloat) {
        UIView.animate(withDuration: 0.2, animations: {
            toast.alpha = 0
            toast.transform = CGAffineTransform(translationX: 0, y: travel)
        }, completion: { _ in
            toast.removeFromSuperview()
        })
    }

    private static func font(_ family: String?, size: CGFloat, weight: Int) -> UIFont {
        PamFontResolver.shared.font(family: family, size: size, weight: weight, italic: false)
    }

    private static func cardView(_ spec: PamToastSpec) -> UIView {
        let card = PamToastCard()
        card.backgroundColor = UIColor(argb: spec.backgroundColor)
        card.layer.cornerRadius = spec.cornerRadius
        card.layer.shadowColor = UIColor.black.cgColor
        // Android elevation in dp, approximated like React Native's shadow.
        card.layer.shadowOpacity = spec.elevation > 0 ? Float(min(0.3, 0.05 + spec.elevation * 0.025)) : 0
        card.layer.shadowRadius = spec.elevation * 1.5
        card.layer.shadowOffset = CGSize(width: 0, height: spec.elevation > 2 ? spec.elevation * 0.6 : 0)
        if let borderColor = spec.borderColor, spec.borderWidth > 0 {
            card.layer.borderColor = UIColor(argb: borderColor).cgColor
            card.layer.borderWidth = spec.borderWidth
        }
        let accent = UIView()
        accent.backgroundColor = UIColor(argb: spec.accentColor)
        accent.translatesAutoresizingMaskIntoConstraints = false
        accent.layer.cornerRadius = spec.cornerRadius
        accent.layer.maskedCorners = [.layerMinXMinYCorner, .layerMinXMaxYCorner]
        accent.isHidden = spec.accentWidth <= 0
        card.addSubview(accent)
        let title = UILabel()
        title.text = spec.title
        title.font = font(spec.fontFamily, size: spec.titleSize, weight: 700)
        title.textColor = UIColor(argb: spec.titleColor)
        title.numberOfLines = spec.titleLines
        title.lineBreakMode = .byTruncatingTail
        title.isHidden = spec.title.isEmpty
        let message = UILabel()
        message.text = spec.message
        message.font = font(spec.fontFamily, size: spec.messageSize, weight: 400)
        message.textColor = UIColor(argb: spec.messageColor)
        message.numberOfLines = 2
        message.isHidden = spec.message.isEmpty
        let texts = UIStackView(arrangedSubviews: [title, message])
        texts.axis = .vertical
        texts.spacing = 2
        var arranged: [UIView] = [texts]
        if let icon = iconView(spec) {
            arranged.insert(icon, at: 0)
        }
        let row = UIStackView(arrangedSubviews: arranged)
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = 10
        row.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(row)
        NSLayoutConstraint.activate([
            accent.leadingAnchor.constraint(equalTo: card.leadingAnchor),
            accent.topAnchor.constraint(equalTo: card.topAnchor),
            accent.bottomAnchor.constraint(equalTo: card.bottomAnchor),
            accent.widthAnchor.constraint(equalToConstant: max(0, spec.accentWidth)),
            row.leadingAnchor.constraint(
                equalTo: card.leadingAnchor,
                constant: max(0, spec.accentWidth) + spec.paddingHorizontal
            ),
            row.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -spec.paddingHorizontal),
            row.centerYAnchor.constraint(equalTo: card.centerYAnchor),
            row.topAnchor.constraint(greaterThanOrEqualTo: card.topAnchor, constant: spec.paddingVertical),
        ])
        card.isAccessibilityElement = true
        card.accessibilityLabel = [spec.title, spec.message].filter { !$0.isEmpty }.joined(separator: ". ")
        return card
    }

    /// Leading icon: a packaged PNG (small assets decode synchronously from
    /// the inline cache) or an icon-font glyph, tinted with `iconColor`.
    private static func iconView(_ spec: PamToastSpec) -> UIView? {
        let tint = UIColor(argb: spec.iconColor ?? spec.titleColor)
        if let source = spec.iconSource {
            guard let path = try? normalizedPamAssetPath(source),
                  let url = pamAssetFileURL(path),
                  let image = PamInlineImages.smallAsset(source, url: url) else { return nil }
            let view = UIImageView(image: spec.iconColor == nil ? image : image.withRenderingMode(.alwaysTemplate))
            view.tintColor = tint
            view.contentMode = .scaleAspectFit
            view.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                view.widthAnchor.constraint(equalToConstant: spec.iconSize),
                view.heightAnchor.constraint(equalToConstant: spec.iconSize),
            ])
            view.isAccessibilityElement = false
            return view
        }
        guard let glyph = spec.iconGlyph, !glyph.isEmpty else { return nil }
        let label = UILabel()
        label.text = glyph
        label.textColor = tint
        // Icon fonts never scale with Dynamic Type (React Native Icon).
        label.font = font(spec.iconFontFamily, size: spec.iconSize, weight: 400)
        label.adjustsFontForContentSizeCategory = false
        label.setContentHuggingPriority(.required, for: .horizontal)
        label.setContentCompressionResistancePriority(.required, for: .horizontal)
        label.isAccessibilityElement = false
        return label
    }

    private static func plainView(_ spec: PamToastSpec) -> UIView {
        let capsule = PamToastCard()
        capsule.backgroundColor = UIColor.black.withAlphaComponent(0.82)
        capsule.layer.cornerRadius = 18
        let label = UILabel()
        label.text = spec.message
        label.textColor = .white
        label.font = .systemFont(ofSize: 14, weight: .medium)
        label.numberOfLines = 3
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        capsule.addSubview(label)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: capsule.leadingAnchor, constant: 16),
            label.trailingAnchor.constraint(equalTo: capsule.trailingAnchor, constant: -16),
            label.topAnchor.constraint(equalTo: capsule.topAnchor, constant: 9),
            label.bottomAnchor.constraint(equalTo: capsule.bottomAnchor, constant: -9),
        ])
        capsule.isAccessibilityElement = true
        capsule.accessibilityLabel = spec.message
        return capsule
    }
}

/// Tap to dismiss early.
private final class PamToastCard: UIView {
    override init(frame: CGRect) {
        super.init(frame: frame)
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(dismiss)))
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    @objc private func dismiss() {
        UIView.animate(withDuration: 0.15, animations: { self.alpha = 0 }, completion: { _ in
            self.removeFromSuperview()
        })
    }
}
