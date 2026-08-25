import UIKit

/// Embeds one PAM Native runtime inside an existing UIKit navigation or tab hierarchy.
public final class PamNativeViewController: UIViewController {
    public typealias ErrorHandler = (String) -> Void

    private let entryURL: URL
    private let nativeModules: [String: NativeModule]
    private let nativeViews: [String: NativeViewFactory]
    private let errorHandler: ErrorHandler
    private var runtime: PamRuntime?
    private var lastViewport = CGSize.zero

    public init(
        entryURL: URL,
        nativeModules: [String: NativeModule] = [:],
        nativeViews: [String: NativeViewFactory] = [:],
        onError: @escaping ErrorHandler
    ) {
        precondition(entryURL.isFileURL, "PAM Native brownfield entries must be local files.")
        self.entryURL = entryURL
        self.nativeModules = nativeModules
        self.nativeViews = nativeViews
        self.errorHandler = onError
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    public override func loadView() {
        view = UIView(frame: .zero)
        view.backgroundColor = .clear
    }

    public override func viewDidLoad() {
        super.viewDidLoad()
        let runtime = PamRuntime(
            hostView: view,
            nativeModules: nativeModules,
            nativeViews: nativeViews,
            reportError: errorHandler
        )
        self.runtime = runtime
        start(runtime: runtime)
    }

    public override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard view.bounds.size != lastViewport else { return }
        lastViewport = view.bounds.size
        updateViewport()
    }

    public override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.userInterfaceStyle != traitCollection.userInterfaceStyle
            || previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            updateViewport()
        }
    }

    public func dispatchBack() -> Bool {
        guard runtime != nil else { return false }
        runtime?.dispatchBack()
        return true
    }

    public func close() {
        runtime?.close()
        runtime = nil
    }

    deinit {
        close()
    }

    private func start(runtime: PamRuntime) {
        guard FileManager.default.fileExists(atPath: entryURL.path) else {
            errorHandler("PAM Native brownfield entry does not exist.")
            return
        }
        runtime.start(
            entry: entryURL.path,
            widthDp: Float(max(view.bounds.width, 1)),
            heightDp: Float(max(view.bounds.height, 1)),
            textScale: Float(UIFontMetrics.default.scaledValue(for: 1)),
            darkAppearance: traitCollection.userInterfaceStyle == .dark
        )
    }

    private func updateViewport() {
        runtime?.updateViewport(
            widthDp: Float(max(view.bounds.width, 1)),
            heightDp: Float(max(view.bounds.height, 1)),
            textScale: Float(UIFontMetrics.default.scaledValue(for: 1)),
            darkAppearance: traitCollection.userInterfaceStyle == .dark
        )
    }
}
