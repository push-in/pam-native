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
            constraints.append(toast.widthAnchor.constraint(equalToConstant: min(340, window.bounds.width - 32)))
            constraints.append(toast.heightAnchor.constraint(greaterThanOrEqualToConstant: 60))
            constraints.append(spec.bottom
                ? toast.bottomAnchor.constraint(equalTo: window.bottomAnchor, constant: -spec.offset)
                : toast.topAnchor.constraint(equalTo: window.topAnchor, constant: spec.offset))
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
        card.layer.cornerRadius = 6
        card.layer.shadowColor = UIColor.black.cgColor
        card.layer.shadowOpacity = 0.1
        card.layer.shadowRadius = 4
        card.layer.shadowOffset = CGSize(width: 0, height: 0)
        let accent = UIView()
        accent.backgroundColor = UIColor(argb: spec.accentColor)
        accent.translatesAutoresizingMaskIntoConstraints = false
        accent.layer.cornerRadius = 6
        accent.layer.maskedCorners = [.layerMinXMinYCorner, .layerMinXMaxYCorner]
        card.addSubview(accent)
        let title = UILabel()
        title.text = spec.title
        title.font = font(spec.fontFamily, size: spec.titleSize, weight: 700)
        title.textColor = UIColor(argb: spec.titleColor)
        title.numberOfLines = 1
        let message = UILabel()
        message.text = spec.message
        message.font = font(spec.fontFamily, size: spec.messageSize, weight: 400)
        message.textColor = UIColor(argb: spec.messageColor)
        message.numberOfLines = 2
        message.isHidden = spec.message.isEmpty
        let stack = UIStackView(arrangedSubviews: [title, message])
        stack.axis = .vertical
        stack.spacing = 2
        stack.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(stack)
        NSLayoutConstraint.activate([
            accent.leadingAnchor.constraint(equalTo: card.leadingAnchor),
            accent.topAnchor.constraint(equalTo: card.topAnchor),
            accent.bottomAnchor.constraint(equalTo: card.bottomAnchor),
            accent.widthAnchor.constraint(equalToConstant: 5),
            stack.leadingAnchor.constraint(equalTo: accent.trailingAnchor, constant: 15),
            stack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -15),
            stack.centerYAnchor.constraint(equalTo: card.centerYAnchor),
            stack.topAnchor.constraint(greaterThanOrEqualTo: card.topAnchor, constant: 8),
        ])
        card.isAccessibilityElement = true
        card.accessibilityLabel = [spec.title, spec.message].filter { !$0.isEmpty }.joined(separator: ". ")
        return card
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
