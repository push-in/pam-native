import UIKit

/// Embeds one PAM Native runtime inside an existing UIKit navigation or tab hierarchy.
///
/// Embedded PHP lives as long as the process and cannot be restarted inside
/// it. Closing the controller detaches its view; a later controller for the
/// same entry re-attaches to the live runtime and remounts the current tree
/// (PHP state is kept, nothing re-executes). Modules and native views are the
/// ones the first controller registered.
public final class PamNativeViewController: UIViewController {
    private static var liveRuntime: PamRuntime?
    private static var liveEntry: String?

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
        if let live = Self.liveRuntime, live.isRunning, Self.liveEntry == entryURL.path {
            runtime = live
            live.attach(hostView: view)
            updateViewport()
            return
        }
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

    public override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        guard lastViewport != .zero else { return }
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
        guard let runtime else { return }
        self.runtime = nil
        if runtime === Self.liveRuntime {
            runtime.detach()
        } else {
            runtime.close()
        }
    }

    deinit {
        close()
    }

    private func start(runtime: PamRuntime) {
        guard FileManager.default.fileExists(atPath: entryURL.path) else {
            errorHandler("PAM Native brownfield entry does not exist.")
            return
        }
        Self.liveRuntime = runtime
        Self.liveEntry = entryURL.path
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
