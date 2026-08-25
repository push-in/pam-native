import PamNative
import PamNativePlugins
import UIKit

@main
final class PamAppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private var runtime: PamRuntime?
#if DEBUG
    private var devTools: PamDevToolsOverlay?
    private let diagnosticsQueue = DispatchQueue(
        label: "dev.pam.native.diagnostics",
        qos: .utility
    )
#endif

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        let controller = UIViewController()
        controller.view.backgroundColor = .systemBackground
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window

#if DEBUG
        let devTools = PamDevToolsOverlay()
        devTools.translatesAutoresizingMaskIntoConstraints = false
        controller.view.addSubview(devTools)
        NSLayoutConstraint.activate([
            devTools.topAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.topAnchor, constant: 12),
            devTools.trailingAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.trailingAnchor, constant: -12),
            devTools.leadingAnchor.constraint(greaterThanOrEqualTo: controller.view.safeAreaLayoutGuide.leadingAnchor, constant: 12),
        ])
        self.devTools = devTools
#endif

        guard let embeddedEntry = Bundle.main.url(
            forResource: "__PAM_ENTRY_BASENAME__",
            withExtension: "__PAM_ENTRY_EXTENSION__",
            subdirectory: "PamBundle"
        ) else {
            presentFatalError("PAM entry file is missing from the application bundle.")
            return false
        }
        let entry = PamActiveUpdateInstaller.resolve(embeddedEntry: embeddedEntry)

        let runtime = PamRuntime(
            hostView: controller.view,
            nativeModules: PamNativePluginRegistry.modules(),
            nativeViews: PamNativePluginRegistry.views(),
            reportError: { [weak self] message in
                DispatchQueue.main.async { self?.presentFatalError(message) }
            },
            onFrameCommitted: { [weak self] metrics in
#if DEBUG
                self?.devTools?.update(metrics)
#endif
            },
            onDiagnostic: { [weak self] diagnostic in
#if DEBUG
                self?.devTools?.record(diagnostic)
#endif
            },
        )
        self.runtime = runtime
        runtime.start(
            entry: entry.path,
            widthDp: Float(controller.view.bounds.width),
            heightDp: Float(controller.view.bounds.height),
            textScale: Float(UIFontMetrics.default.scaledValue(for: 1)),
            darkAppearance: controller.traitCollection.userInterfaceStyle == .dark
        )
#if DEBUG
        runtime.startHotReload()
#endif
        return true
    }

    func applicationWillTerminate(_ application: UIApplication) {
        runtime?.close()
    }

    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
#if DEBUG
        guard url.scheme == "__PAM_DIAGNOSTICS_SCHEME__" else { return false }
        if url.host == "devtools" {
            devTools?.toggle()
            return true
        }
        guard url.host == "diagnostics" else { return false }
        let requestID = url.lastPathComponent
        guard requestID.range(of: "^[a-f0-9]{32}$", options: .regularExpression) != nil,
              let devTools = devTools,
              let snapshot = try? devTools.snapshotData() else { return false }
        publishDiagnostics(snapshot, requestID: requestID)
        return true
#else
        return false
#endif
    }

#if DEBUG
    private func publishDiagnostics(_ snapshot: Data, requestID: String) {
        diagnosticsQueue.async {
            let fileManager = FileManager.default
            guard let directory = fileManager.urls(
                for: .cachesDirectory,
                in: .userDomainMask
            ).first else { return }
            if let files = try? fileManager.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: nil
            ) {
                files.filter { $0.lastPathComponent.hasPrefix("pam-diagnostics-") }
                    .forEach { try? fileManager.removeItem(at: $0) }
            }
            let destination = directory
                .appendingPathComponent("pam-diagnostics-\(requestID).json")
            try? snapshot.write(to: destination, options: .atomic)
        }
    }
#endif

    private func presentFatalError(_ message: String) {
        guard let controller = window?.rootViewController,
              controller.presentedViewController == nil else { return }
        let alert = UIAlertController(title: "PAM Native", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Close", style: .cancel))
        controller.present(alert, animated: true)
    }
}
