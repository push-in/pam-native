import PamNative
import PamNativePlugins
import UIKit

@main
final class PamAppDelegate: UIResponder, UIApplicationDelegate {
    private var window: UIWindow?
    private var runtime: PamRuntime?

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

        guard let entry = Bundle.main.url(
            forResource: "__PAM_ENTRY_BASENAME__",
            withExtension: "__PAM_ENTRY_EXTENSION__",
            subdirectory: "PamBundle"
        ) else {
            presentFatalError("PAM entry file is missing from the application bundle.")
            return false
        }

        let runtime = PamRuntime(
            hostView: controller.view,
            nativeModules: PamNativePluginRegistry.modules(),
            nativeViews: PamNativePluginRegistry.views(),
            reportError: { [weak self] message in
                DispatchQueue.main.async { self?.presentFatalError(message) }
            }
        )
        self.runtime = runtime
        runtime.start(
            entry: entry.path,
            widthDp: Float(controller.view.bounds.width),
            heightDp: Float(controller.view.bounds.height),
            textScale: Float(UIFontMetrics.default.scaledValue(for: 1)),
            darkAppearance: controller.traitCollection.userInterfaceStyle == .dark
        )
        return true
    }

    func applicationWillTerminate(_ application: UIApplication) {
        runtime?.close()
    }

    private func presentFatalError(_ message: String) {
        guard let controller = window?.rootViewController,
              controller.presentedViewController == nil else { return }
        let alert = UIAlertController(title: "PAM Native", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Close", style: .cancel))
        controller.present(alert, animated: true)
    }
}
