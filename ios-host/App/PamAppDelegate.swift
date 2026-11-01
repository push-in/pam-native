import PamNative
import PamNativePlugins
import UIKit
import UserNotifications

@main
final class PamAppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    var window: UIWindow?
    private var runtime: PamRuntime?
    private var splashView: UIView?
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
        // Capture before PHP starts: Linking::initial() consumes the cold-start URL.
        if let url = launchOptions?[.url] as? URL, acceptsApplicationURL(url) {
            PamLinking.captureInitial(url)
        }
        if let activities = launchOptions?[.userActivityDictionary] as? [AnyHashable: Any] {
            for case let activity as NSUserActivity in activities.values {
                if activity.activityType == NSUserActivityTypeBrowsingWeb,
                   let url = activity.webpageURL, isWebURL(url) {
                    PamLinking.captureInitial(url)
                    break
                }
            }
        }
        UNUserNotificationCenter.current().delegate = self
        let window = UIWindow(frame: UIScreen.main.bounds)
        let controller = PamHostViewController()
        // Apply the persisted appearance before the window is visible so the
        // first frame (native and PHP) already uses the effective scheme.
        PamAppearance.apply(to: window)
        controller.view.backgroundColor = PamAppearance.backgroundColor
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window
        // appearance.firstFrame "window": the themed window shows at once and
        // PHP's first frame replaces it (no cover held until that frame).
        if Bundle.main.object(forInfoDictionaryKey: "PamFirstFrameWaitsForPHP") as? Bool != false {
            installSplash(on: controller)
        }

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
                self?.hideSplash()
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
        controller.onGeometryChange = { [weak runtime, weak controller] in
            guard let runtime, let controller else { return }
            runtime.updateViewport(
                widthDp: Float(controller.view.bounds.width),
                heightDp: Float(controller.view.bounds.height),
                textScale: PamRuntime.deviceTextScale(traits: controller.traitCollection),
                darkAppearance: controller.traitCollection.userInterfaceStyle == .dark
            )
        }
        controller.onAppearanceChange = { [weak runtime, weak controller] in
            guard let runtime, let controller else { return }
            runtime.updateViewport(
                widthDp: Float(controller.view.bounds.width),
                heightDp: Float(controller.view.bounds.height),
                textScale: PamRuntime.deviceTextScale(traits: controller.traitCollection),
                darkAppearance: controller.traitCollection.userInterfaceStyle == .dark
            )
        }
        // Under UIScene the cold-start URL and user activity only arrive in
        // `scene(_:willConnectTo:)`, after this method returns. PHP must not
        // boot before Linking::initial() is captured, so the scene delegate
        // starts the runtime; the dispatch below is the fallback for hosts
        // without a scene delegate and runs after the scene connection.
        pendingRuntimeStart = { [weak runtime, weak controller] in
            guard let runtime, let controller else { return }
            runtime.start(
                entry: entry.path,
                widthDp: Float(controller.view.bounds.width),
                heightDp: Float(controller.view.bounds.height),
                textScale: PamRuntime.deviceTextScale(traits: controller.traitCollection),
                darkAppearance: controller.traitCollection.userInterfaceStyle == .dark
            )
#if DEBUG
            runtime.startHotReload()
#endif
        }
        DispatchQueue.main.async { [weak self] in self?.startRuntimeIfNeeded() }
        return true
    }

    private var pendingRuntimeStart: (() -> Void)?

    /// Boots PHP exactly once; safe to call from the scene delegate and from
    /// the launch fallback.
    fileprivate func startRuntimeIfNeeded() {
        guard let start = pendingRuntimeStart else { return }
        pendingRuntimeStart = nil
        start()
    }

    /// `appearance.splash`: keeps the launch screen (its background and, when
    /// configured, the same logo asset at the same point size, centered in the
    /// safe area) until the first PHP frame is committed, so the app goes from
    /// the launch screen straight to its first frame. A stalled boot still
    /// reaches the window after `splashHoldTimeout` (Android parity:
    /// PamActivity.SPLASH_HOLD_TIMEOUT_MS).
    private func installSplash(on controller: UIViewController) {
        let cover = UIView(frame: controller.view.bounds)
        cover.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        cover.backgroundColor = UIColor(named: "PamSplashBackground") ?? PamAppearance.backgroundColor
        cover.isUserInteractionEnabled = false
        if let logo = UIImage(named: "PamSplashLogo") {
            let image = UIImageView(image: logo)
            image.contentMode = .scaleAspectFit
            image.translatesAutoresizingMaskIntoConstraints = false
            cover.addSubview(image)
            NSLayoutConstraint.activate([
                image.centerXAnchor.constraint(equalTo: cover.safeAreaLayoutGuide.centerXAnchor),
                image.centerYAnchor.constraint(equalTo: cover.safeAreaLayoutGuide.centerYAnchor),
                image.widthAnchor.constraint(equalToConstant: logo.size.width),
                image.heightAnchor.constraint(equalToConstant: logo.size.height),
            ])
        }
        controller.view.addSubview(cover)
        splashView = cover
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.splashHoldTimeout) { [weak self] in
            self?.hideSplash()
        }
    }

    private static let splashHoldTimeout: TimeInterval = 4

    private func hideSplash() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let splash = splashView else { return }
        splashView = nil
        UIView.animate(withDuration: 0.18, animations: { splash.alpha = 0 }, completion: { _ in
            splash.removeFromSuperview()
        })
    }

    func applicationWillTerminate(_ application: UIApplication) {
        runtime?.close()
    }

    // MARK: Push notifications (Notifications::registerPush(), PushRendering, actions)

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        PamPushNotifications.didRegister(deviceToken: deviceToken)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        PamPushNotifications.didFailToRegister(error: error)
    }

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        PamPushNotifications.didReceiveRemote(userInfo: userInfo, completion: completionHandler)
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        PamPushNotifications.didReceive(notification: notification)
        completionHandler([.banner, .list, .sound, .badge])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        PamPushNotifications.didReceive(response: response, completionHandler: completionHandler)
    }

    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        if url.scheme?.lowercased() == "__PAM_DIAGNOSTICS_SCHEME__" {
#if DEBUG
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
        guard acceptsApplicationURL(url) else { return false }
        PamLinking.open(url)
        return true
    }

    func application(
        _ application: UIApplication,
        continue userActivity: NSUserActivity,
        restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void
    ) -> Bool {
        guard userActivity.activityType == NSUserActivityTypeBrowsingWeb,
              let url = userActivity.webpageURL, isWebURL(url) else { return false }
        PamLinking.open(url)
        return true
    }

    fileprivate func isWebURL(_ url: URL) -> Bool {
        return ["https", "http"].contains(url.scheme?.lowercased() ?? "")
            && url.host != nil && url.absoluteString.utf8.count <= 8_192
    }

    fileprivate func acceptsApplicationURL(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased(),
              scheme != "__PAM_DIAGNOSTICS_SCHEME__",
              url.absoluteString.utf8.count <= 8_192 else { return false }
        let types = Bundle.main.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] ?? []
        return types.contains { type in
            (type["CFBundleURLSchemes"] as? [String] ?? []).contains { $0.lowercased() == scheme }
        }
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
        hideSplash()
        guard let controller = window?.rootViewController,
              controller.presentedViewController == nil else { return }
        let alert = UIAlertController(title: "PAM Native", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Close", style: .cancel))
        controller.present(alert, animated: true)
    }
}

/// Root controller that reports system or override appearance changes to PHP
/// so CSS `prefers-color-scheme` restyles without remounting.
final class PamHostViewController: UIViewController {
    var onAppearanceChange: (() -> Void)?
    /// Size or safe-area change (rotation, split view, status bar): the engine
    /// re-lays out SafeAreaView insets and PHP receives new Dimensions.
    var onGeometryChange: (() -> Void)?
    private var lastSize = CGSize.zero
    private var lastInsets = UIEdgeInsets.zero

    // PAM `StatusBar` nodes (RN StatusBar): barStyle, hidden and animated,
    // resolved per screen and modal by PamStatusBarCoordinator, which calls
    // setNeedsStatusBarAppearanceUpdate when they change.
    override var preferredStatusBarStyle: UIStatusBarStyle {
        PamStatusBarCoordinator.shared.config.style
    }

    override var prefersStatusBarHidden: Bool {
        PamStatusBarCoordinator.shared.config.hidden
    }

    override var preferredStatusBarUpdateAnimation: UIStatusBarAnimation {
        PamStatusBarCoordinator.shared.config.updateAnimation
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        reportGeometryIfNeeded()
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        reportGeometryIfNeeded()
    }

    private func reportGeometryIfNeeded() {
        let size = view.bounds.size
        let insets = view.safeAreaInsets
        guard size.width > 0, size.height > 0, size != lastSize || insets != lastInsets else { return }
        lastSize = size
        lastInsets = insets
        // No-op before the runtime starts (it boots with this geometry).
        onGeometryChange?()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.userInterfaceStyle != traitCollection.userInterfaceStyle {
            onAppearanceChange?()
        }
    }
}

/// UIScene entry points. `UIApplicationSceneManifest` in Info.plist makes UIKit
/// deliver URLs and user activities to the scene delegate only, so the
/// `UIApplicationDelegate` callbacks above never run for them. The application
/// delegate keeps building the window and starting PHP; this delegate attaches
/// that window to the scene and forwards every URL and activity.
final class PamSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    private var appDelegate: PamAppDelegate? {
        UIApplication.shared.delegate as? PamAppDelegate
    }

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene, let appDelegate else { return }
        if let window = appDelegate.window {
            window.windowScene = windowScene
            self.window = window
            window.makeKeyAndVisible()
        }
        // Cold start: launchOptions[.url] / userActivityDictionary are empty
        // under UIScene, so Linking::initial() is captured here instead.
        for context in connectionOptions.urlContexts {
            if appDelegate.acceptsApplicationURL(context.url) {
                PamLinking.captureInitial(context.url)
            } else {
                _ = appDelegate.application(UIApplication.shared, open: context.url, options: [:])
            }
        }
        for activity in connectionOptions.userActivities
        where activity.activityType == NSUserActivityTypeBrowsingWeb {
            if let url = activity.webpageURL, appDelegate.isWebURL(url) {
                PamLinking.captureInitial(url)
                break
            }
        }
        appDelegate.startRuntimeIfNeeded()
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        guard let appDelegate else { return }
        for context in URLContexts {
            _ = appDelegate.application(UIApplication.shared, open: context.url, options: [:])
        }
    }

    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        guard let appDelegate else { return }
        _ = appDelegate.application(
            UIApplication.shared,
            continue: userActivity,
            restorationHandler: { _ in }
        )
    }
}
