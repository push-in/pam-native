import FirebaseCore
import FirebaseMessaging
import Foundation
import PamNative

/// iOS twin of android/app/src/firebase: `pam mobile ios:prepare` compiles
/// this file into the generated PamNativePlugins package only when the project
/// ships `GoogleService-Info.plist` (root or `.pam/`). It configures the
/// default Firebase app from that plist and turns every APNs device token into
/// the FCM token, so `Notifications::registerPush()` reports
/// `PushProvider::Fcm` on both platforms and one FCM server reaches both.
/// Swizzling is off (`FirebaseAppDelegateProxyEnabled` = NO): the PAM
/// application delegate keeps owning notifications, categories and actions.
final class PamFirebaseMessaging: NSObject, PamPushTokenProvider, MessagingDelegate {
    static let shared = PamFirebaseMessaging()
    private static var installed = false

    let provider = PamPushNotifications.providerFcm

    /// Called from `PamNativePluginRegistry.modules()`, before PHP starts.
    static func install() {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { install() }
            return
        }
        guard !installed else { return }
        installed = true
        if FirebaseApp.app() == nil {
            guard let path = Bundle.module.path(forResource: "GoogleService-Info", ofType: "plist"),
                  let options = FirebaseOptions(contentsOfFile: path) else {
                NSLog("PAM Native: GoogleService-Info.plist is unreadable; push stays on raw APNs tokens")
                return
            }
            FirebaseApp.configure(options: options)
        }
        Messaging.messaging().delegate = shared
        PamPushNotifications.tokenProvider = shared
    }

    func token(apnsToken: Data, completion: @escaping (Result<String, Error>) -> Void) {
        let messaging = Messaging.messaging()
        // .unknown: Firebase reads sandbox/production from the provisioning profile.
        messaging.setAPNSToken(apnsToken, type: .unknown)
        messaging.token { token, error in
            if let error {
                completion(.failure(error))
            } else {
                completion(.success(token ?? ""))
            }
        }
    }

    func deleteToken() {
        Messaging.messaging().deleteToken { _ in }
    }

    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let fcmToken, !fcmToken.isEmpty else { return }
        PamPushNotifications.didRefresh(token: fcmToken, provider: provider)
    }
}
