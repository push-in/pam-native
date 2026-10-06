import UIKit

/// LogBox-style runtime error overlay (React Native LogBox/RedBox model).
///
/// Debug builds (or `devErrorOverlay: "always"`): non-fatal errors appear as a
/// compact bottom toast that only takes its own touches; tapping it opens a
/// full-screen, scrollable inspector (exception, app frame, source snippet,
/// collapsible framework frames, Dismiss / Copy / Reload, `‹ 1 / N ›`).
/// Fatal errors open the inspector directly. A dismissed error is not shown
/// again until the runtime reloads. Release builds never show stacks: the
/// host shows `showFallback()` ("Something went wrong" + Try again).
public final class PamErrorOverlay: UIView {
    public enum Mode: Int {
        case off = 0
        case debug = 1
        case always = 2
    }

    struct Entry {
        var report: PamRuntimeErrorReport
        var count: Int
    }

    /// `PamDevErrorOverlay` in Info.plist (generated from `devErrorOverlay`).
    public static func developerMode(debugBuild: Bool, bundle: Bundle = .main) -> Bool {
        let raw = (bundle.object(forInfoDictionaryKey: "PamDevErrorOverlay") as? NSNumber)?.intValue ?? Mode.debug.rawValue
        switch Mode(rawValue: raw) ?? .debug {
        case .off: return false
        case .debug: return debugBuild
        case .always: return true
        }
    }

    public let developerMode: Bool
    public var onReload: (() -> Void)?

    private(set) var entries: [Entry] = []
    private(set) var index = 0
    private var dismissed = Set<String>()
    private var expandedFrames = false

    private let toast = UIControl()
    private let toastTitle = UILabel()
    private let toastCounter = UILabel()
    private let toastClose = UIButton(type: .system)
    private let inspector = UIView()
    private let inspectorScroll = UIScrollView()
    private let inspectorStack = UIStackView()
    private let headerTitle = UILabel()
    private let previousButton = UIButton(type: .system)
    private let nextButton = UIButton(type: .system)
    private let counterLabel = UILabel()
    private let minimizeButton = UIButton(type: .system)
    private let fallback = UIView()

    var isInspectorVisible: Bool { !inspector.isHidden }
    var isToastVisible: Bool { !toast.isHidden }
    var isFallbackVisible: Bool { !fallback.isHidden }

    public init(developerMode: Bool) {
        self.developerMode = developerMode
        super.init(frame: .zero)
        backgroundColor = .clear
        isHidden = true
        accessibilityViewIsModal = false
        // The toast, inspector and fallback (dozens of views and constraints)
        // are built the first time one is shown: launches that never fail do
        // not pay for them before their first frame.
        toast.isHidden = true
        inspector.isHidden = true
        fallback.isHidden = true
    }

    private var built = false

    private func buildIfNeeded() {
        guard !built else { return }
        built = true
        buildToast()
        buildInspector()
        buildFallback()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    /// Only the visible toast/inspector/fallback take touches.
    public override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        for view in [toast, inspector, fallback] where !view.isHidden {
            if view.frame.contains(point) { return true }
        }
        return false
    }

    // MARK: Hosting

    /// Pins the overlay above every runtime view of `host`'s window.
    func install(over host: UIView) {
        let container = host.window ?? host
        if superview !== container {
            removeFromSuperview()
            translatesAutoresizingMaskIntoConstraints = false
            container.addSubview(self)
            NSLayoutConstraint.activate([
                leadingAnchor.constraint(equalTo: container.leadingAnchor),
                trailingAnchor.constraint(equalTo: container.trailingAnchor),
                topAnchor.constraint(equalTo: container.topAnchor),
                bottomAnchor.constraint(equalTo: container.bottomAnchor),
            ])
        }
        container.bringSubviewToFront(self)
    }

    // MARK: Queue

    public func report(_ report: PamRuntimeErrorReport) {
        guard !dismissed.contains(report.fingerprint) else { return }
        buildIfNeeded()
        if let existing = entries.firstIndex(where: { $0.report.fingerprint == report.fingerprint }) {
            entries[existing].count += 1
            entries[existing].report = report
            if report.fatal { index = existing }
        } else {
            entries.append(Entry(report: report, count: 1))
            if report.fatal || inspector.isHidden { index = entries.count - 1 }
        }
        superview?.bringSubviewToFront(self)
        isHidden = false
        if report.fatal || !inspector.isHidden {
            showInspector()
        } else {
            showToast()
        }
    }

    /// Removes the current error; the next queued one is shown or the overlay closes.
    public func dismissCurrent() {
        guard entries.indices.contains(index) else { return close() }
        dismissed.insert(entries[index].report.fingerprint)
        entries.remove(at: index)
        index = min(index, max(0, entries.count - 1))
        if entries.isEmpty {
            close()
        } else if !inspector.isHidden {
            showInspector()
        } else {
            showToast()
        }
    }

    public func dismissAll() {
        entries.forEach { dismissed.insert($0.report.fingerprint) }
        entries.removeAll()
        close()
    }

    /// A fresh runtime (Reload, hot reload): errors may show again.
    public func onRuntimeReload() {
        dismissed.removeAll()
        entries.removeAll()
        index = 0
        close()
        fallback.isHidden = true
    }

    /// A committed frame hides the release fallback.
    public func onFrameCommitted() {
        guard !fallback.isHidden else { return }
        fallback.isHidden = true
        if toast.isHidden && inspector.isHidden { isHidden = true }
    }

    public func showFallback() {
        buildIfNeeded()
        superview?.bringSubviewToFront(self)
        isHidden = false
        toast.isHidden = true
        inspector.isHidden = true
        fallback.isHidden = false
    }

    private func close() {
        toast.isHidden = true
        inspector.isHidden = true
        isHidden = fallback.isHidden
    }

    // MARK: Toast

    private func buildToast() {
        toast.translatesAutoresizingMaskIntoConstraints = false
        toast.backgroundColor = UIColor { $0.userInterfaceStyle == .dark
            ? UIColor(red: 0.20, green: 0.07, blue: 0.08, alpha: 0.97)
            : UIColor(red: 0.99, green: 0.93, blue: 0.93, alpha: 0.98) }
        toast.layer.cornerRadius = 12
        toast.layer.borderWidth = 1
        toast.layer.borderColor = UIColor.systemRed.withAlphaComponent(0.6).cgColor
        toast.accessibilityLabel = Self.text("Open error details", "Abrir detalhes do erro")
        toast.isAccessibilityElement = true
        toast.addTarget(self, action: #selector(openInspector), for: .touchUpInside)
        toastTitle.numberOfLines = 2
        toastTitle.font = .systemFont(ofSize: 14, weight: .semibold)
        toastTitle.textColor = .label
        toastCounter.font = .monospacedDigitSystemFont(ofSize: 12, weight: .medium)
        toastCounter.textColor = .secondaryLabel
        toastClose.setTitle("×", for: .normal)
        toastClose.titleLabel?.font = .systemFont(ofSize: 22, weight: .medium)
        toastClose.accessibilityLabel = Self.text("Dismiss all errors", "Fechar todos os erros")
        toastClose.addTarget(self, action: #selector(dismissAllTapped), for: .touchUpInside)
        let badge = UIView()
        badge.backgroundColor = .systemRed
        badge.layer.cornerRadius = 4
        let row = UIStackView(arrangedSubviews: [badge, toastTitle, toastCounter, toastClose])
        row.alignment = .center
        row.spacing = 10
        row.isUserInteractionEnabled = true
        row.translatesAutoresizingMaskIntoConstraints = false
        toast.addSubview(row)
        addSubview(toast)
        let safe = safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            badge.widthAnchor.constraint(equalToConstant: 8),
            badge.heightAnchor.constraint(equalToConstant: 8),
            toastClose.widthAnchor.constraint(equalToConstant: 32),
            row.leadingAnchor.constraint(equalTo: toast.leadingAnchor, constant: 14),
            row.trailingAnchor.constraint(equalTo: toast.trailingAnchor, constant: -6),
            row.topAnchor.constraint(equalTo: toast.topAnchor, constant: 10),
            row.bottomAnchor.constraint(equalTo: toast.bottomAnchor, constant: -10),
            toast.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: 12),
            toast.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -12),
            toast.bottomAnchor.constraint(equalTo: safe.bottomAnchor, constant: -12),
        ])
        toast.isHidden = true
    }

    private func showToast() {
        guard entries.indices.contains(index) else { return close() }
        let entry = entries[index]
        toastTitle.text = "\(entry.report.shortType): \(entry.report.message)"
        var counter = entries.count > 1 ? "\(index + 1)/\(entries.count)" : ""
        if entry.count > 1 { counter += (counter.isEmpty ? "" : " ") + "×\(entry.count)" }
        toastCounter.text = counter
        toastCounter.isHidden = counter.isEmpty
        inspector.isHidden = true
        toast.isHidden = false
    }

    @objc private func openInspector() {
        showInspector()
    }

    @objc private func dismissAllTapped() {
        dismissAll()
    }

    // MARK: Inspector

    private func buildInspector() {
        inspector.translatesAutoresizingMaskIntoConstraints = false
        inspector.backgroundColor = .systemBackground
        addSubview(inspector)
        NSLayoutConstraint.activate([
            inspector.leadingAnchor.constraint(equalTo: leadingAnchor),
            inspector.trailingAnchor.constraint(equalTo: trailingAnchor),
            inspector.topAnchor.constraint(equalTo: topAnchor),
            inspector.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
        let safe = inspector.safeAreaLayoutGuide

        headerTitle.font = .systemFont(ofSize: 15, weight: .semibold)
        headerTitle.textColor = .systemRed
        configure(previousButton, title: "‹", label: Self.text("Previous error", "Erro anterior"), action: #selector(previousTapped))
        configure(nextButton, title: "›", label: Self.text("Next error", "Próximo erro"), action: #selector(nextTapped))
        configure(minimizeButton, title: Self.text("Minimize", "Minimizar"), label: nil, action: #selector(minimizeTapped))
        counterLabel.font = .monospacedDigitSystemFont(ofSize: 13, weight: .medium)
        counterLabel.textColor = .secondaryLabel
        let header = UIStackView(arrangedSubviews: [headerTitle, UIView(), previousButton, counterLabel, nextButton, minimizeButton])
        header.alignment = .center
        header.spacing = 8
        header.translatesAutoresizingMaskIntoConstraints = false
        inspector.addSubview(header)

        inspectorScroll.translatesAutoresizingMaskIntoConstraints = false
        inspectorScroll.alwaysBounceVertical = true
        inspector.addSubview(inspectorScroll)
        inspectorStack.axis = .vertical
        inspectorStack.spacing = 12
        inspectorStack.translatesAutoresizingMaskIntoConstraints = false
        inspectorScroll.addSubview(inspectorStack)

        let dismiss = UIButton(type: .system)
        configure(dismiss, title: Self.text("Dismiss", "Fechar"), label: Self.text("Dismiss error", "Fechar erro"), action: #selector(dismissTapped))
        let copy = UIButton(type: .system)
        configure(copy, title: Self.text("Copy", "Copiar"), label: nil, action: #selector(copyTapped))
        let reload = UIButton(type: .system)
        configure(reload, title: Self.text("Reload", "Recarregar"), label: nil, action: #selector(reloadTapped))
        let footer = UIStackView(arrangedSubviews: [dismiss, copy, reload])
        footer.distribution = .fillEqually
        footer.spacing = 8
        footer.translatesAutoresizingMaskIntoConstraints = false
        let footerBackground = UIView()
        footerBackground.backgroundColor = .secondarySystemBackground
        footerBackground.translatesAutoresizingMaskIntoConstraints = false
        inspector.addSubview(footerBackground)
        footerBackground.addSubview(footer)

        NSLayoutConstraint.activate([
            header.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: 16),
            header.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -12),
            header.topAnchor.constraint(equalTo: safe.topAnchor, constant: 8),
            header.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
            inspectorScroll.leadingAnchor.constraint(equalTo: safe.leadingAnchor),
            inspectorScroll.trailingAnchor.constraint(equalTo: safe.trailingAnchor),
            inspectorScroll.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 4),
            inspectorScroll.bottomAnchor.constraint(equalTo: footerBackground.topAnchor),
            inspectorStack.leadingAnchor.constraint(equalTo: inspectorScroll.contentLayoutGuide.leadingAnchor, constant: 16),
            inspectorStack.trailingAnchor.constraint(equalTo: inspectorScroll.contentLayoutGuide.trailingAnchor, constant: -16),
            inspectorStack.topAnchor.constraint(equalTo: inspectorScroll.contentLayoutGuide.topAnchor, constant: 8),
            inspectorStack.bottomAnchor.constraint(equalTo: inspectorScroll.contentLayoutGuide.bottomAnchor, constant: -24),
            inspectorStack.widthAnchor.constraint(equalTo: inspectorScroll.frameLayoutGuide.widthAnchor, constant: -32),
            footerBackground.leadingAnchor.constraint(equalTo: inspector.leadingAnchor),
            footerBackground.trailingAnchor.constraint(equalTo: inspector.trailingAnchor),
            footerBackground.bottomAnchor.constraint(equalTo: inspector.bottomAnchor),
            footer.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: 12),
            footer.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -12),
            footer.topAnchor.constraint(equalTo: footerBackground.topAnchor, constant: 8),
            footer.bottomAnchor.constraint(equalTo: safe.bottomAnchor, constant: -8),
            footer.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
        ])
        inspector.isHidden = true
    }

    private func configure(_ button: UIButton, title: String, label: String?, action: Selector) {
        button.setTitle(title, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: title.count == 1 ? 24 : 16, weight: .semibold)
        button.accessibilityLabel = label ?? title
        button.addTarget(self, action: action, for: .touchUpInside)
    }

    func showInspector() {
        guard entries.indices.contains(index) else { return close() }
        buildIfNeeded()
        let entry = entries[index]
        let report = entry.report
        toast.isHidden = true
        inspector.isHidden = false
        accessibilityViewIsModal = true
        headerTitle.text = report.phaseLabel + (entry.count > 1 ? " ×\(entry.count)" : "")
        counterLabel.text = "\(index + 1) / \(entries.count)"
        previousButton.isEnabled = index > 0
        nextButton.isEnabled = index + 1 < entries.count
        minimizeButton.isHidden = report.fatal
        inspectorStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        inspectorStack.addArrangedSubview(label(report.shortType, font: .systemFont(ofSize: 13, weight: .medium), color: .secondaryLabel))
        inspectorStack.addArrangedSubview(label(report.message, font: .systemFont(ofSize: 22, weight: .bold), color: .label))
        let appFrame = report.appFrame.flatMap { report.frames.indices.contains($0) ? report.frames[$0] : nil }
        inspectorStack.addArrangedSubview(label(appFrame?.location ?? report.location, font: Self.mono(13), color: .systemBlue))
        if let snippet = report.snippet {
            inspectorStack.addArrangedSubview(label(Self.text("Source", "Código"), font: .systemFont(ofSize: 15, weight: .semibold), color: .label))
            inspectorStack.addArrangedSubview(snippetView(snippet))
        }
        if !report.frames.isEmpty {
            inspectorStack.addArrangedSubview(label(Self.text("Call stack", "Pilha de chamadas"), font: .systemFont(ofSize: 15, weight: .semibold), color: .label))
            addFrames(report)
        }
        inspectorScroll.setContentOffset(.zero, animated: false)
        UIAccessibility.post(notification: .screenChanged, argument: headerTitle)
    }

    private func addFrames(_ report: PamRuntimeErrorReport) {
        var collapsed: [PamRuntimeErrorReport.Frame] = []
        func flushCollapsed() {
            guard !collapsed.isEmpty else { return }
            if expandedFrames {
                collapsed.forEach { inspectorStack.addArrangedSubview(frameView($0)) }
            } else {
                let toggle = UIButton(type: .system)
                let count = collapsed.count
                toggle.setTitle(Self.text(
                    count == 1 ? "▸ 1 framework frame" : "▸ \(count) framework frames",
                    count == 1 ? "▸ 1 frame do framework" : "▸ \(count) frames do framework"
                ), for: .normal)
                toggle.contentHorizontalAlignment = .leading
                toggle.titleLabel?.font = Self.mono(12)
                toggle.addTarget(self, action: #selector(expandFramesTapped), for: .touchUpInside)
                inspectorStack.addArrangedSubview(toggle)
            }
            collapsed.removeAll()
        }
        for frame in report.frames {
            if frame.isApp {
                flushCollapsed()
                inspectorStack.addArrangedSubview(frameView(frame))
            } else {
                collapsed.append(frame)
            }
        }
        flushCollapsed()
    }

    private func frameView(_ frame: PamRuntimeErrorReport.Frame) -> UIView {
        let text = NSMutableAttributedString(
            string: frame.call.isEmpty ? "{main}" : frame.call,
            attributes: [.font: Self.mono(13), .foregroundColor: frame.isApp ? UIColor.label : UIColor.secondaryLabel]
        )
        text.append(NSAttributedString(
            string: "\n" + frame.location,
            attributes: [.font: Self.mono(11), .foregroundColor: UIColor.tertiaryLabel]
        ))
        let view = UILabel()
        view.numberOfLines = 0
        view.lineBreakMode = .byCharWrapping
        view.attributedText = text
        return view
    }

    private func snippetView(_ snippet: PamRuntimeErrorReport.Snippet) -> UIView {
        let text = NSMutableAttributedString()
        for (offset, line) in snippet.lines.enumerated() {
            let number = snippet.start + offset
            let current = number == snippet.line
            let padded = String(repeating: " ", count: max(0, 4 - String(number).count)) + String(number)
            text.append(NSAttributedString(
                string: (current ? "> " : "  ") + padded + " | " + line + (offset + 1 < snippet.lines.count ? "\n" : ""),
                attributes: [
                    .font: Self.mono(12),
                    .foregroundColor: current ? UIColor.label : UIColor.secondaryLabel,
                    .backgroundColor: current ? UIColor.systemRed.withAlphaComponent(0.15) : UIColor.clear,
                ]
            ))
        }
        let label = UILabel()
        label.numberOfLines = 0
        label.lineBreakMode = .byCharWrapping
        label.attributedText = text
        let container = UIView()
        container.backgroundColor = .secondarySystemBackground
        container.layer.cornerRadius = 8
        label.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(label)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 10),
            label.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -10),
            label.topAnchor.constraint(equalTo: container.topAnchor, constant: 8),
            label.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -8),
        ])
        return container
    }

    private func label(_ text: String, font: UIFont, color: UIColor) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = font
        label.textColor = color
        label.numberOfLines = 0
        label.lineBreakMode = .byWordWrapping
        return label
    }

    @objc private func previousTapped() {
        guard index > 0 else { return }
        index -= 1
        showInspector()
    }

    @objc private func nextTapped() {
        guard index + 1 < entries.count else { return }
        index += 1
        showInspector()
    }

    @objc private func minimizeTapped() {
        accessibilityViewIsModal = false
        showToast()
    }

    @objc private func expandFramesTapped() {
        expandedFrames = true
        showInspector()
    }

    @objc private func dismissTapped() {
        dismissCurrent()
    }

    @objc private func copyTapped() {
        guard entries.indices.contains(index) else { return }
        UIPasteboard.general.string = entries[index].report.copyText()
        UIAccessibility.post(notification: .announcement, argument: Self.text("Error copied to clipboard", "Erro copiado"))
    }

    @objc private func reloadTapped() {
        onRuntimeReload()
        onReload?()
    }

    // MARK: Release fallback

    private func buildFallback() {
        fallback.translatesAutoresizingMaskIntoConstraints = false
        fallback.backgroundColor = .systemBackground
        addSubview(fallback)
        let title = label(Self.text("Something went wrong", "Algo deu errado"), font: .systemFont(ofSize: 24, weight: .bold), color: .label)
        title.textAlignment = .center
        let message = label(
            Self.text(
                "The app hit an unexpected problem. Try again to continue.",
                "O app encontrou um problema inesperado. Tente novamente para continuar."
            ),
            font: .systemFont(ofSize: 16),
            color: .secondaryLabel
        )
        message.textAlignment = .center
        let retry = UIButton(type: .system)
        configure(retry, title: Self.text("Try again", "Tentar novamente"), label: nil, action: #selector(reloadTapped))
        let stack = UIStackView(arrangedSubviews: [title, message, retry])
        stack.axis = .vertical
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        fallback.addSubview(stack)
        NSLayoutConstraint.activate([
            fallback.leadingAnchor.constraint(equalTo: leadingAnchor),
            fallback.trailingAnchor.constraint(equalTo: trailingAnchor),
            fallback.topAnchor.constraint(equalTo: topAnchor),
            fallback.bottomAnchor.constraint(equalTo: bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: fallback.safeAreaLayoutGuide.leadingAnchor, constant: 32),
            stack.trailingAnchor.constraint(equalTo: fallback.safeAreaLayoutGuide.trailingAnchor, constant: -32),
            stack.centerYAnchor.constraint(equalTo: fallback.centerYAnchor),
        ])
        fallback.isHidden = true
    }

    // MARK: Helpers

    static func text(_ english: String, _ portuguese: String) -> String {
        (Locale.preferredLanguages.first ?? "en").hasPrefix("pt") ? portuguese : english
    }

    private static func mono(_ size: CGFloat) -> UIFont {
        .monospacedSystemFont(ofSize: size, weight: .regular)
    }
}
