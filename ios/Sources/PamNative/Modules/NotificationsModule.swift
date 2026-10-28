import Foundation
import UIKit
import UserNotifications

public protocol PamPushTokenProvider: AnyObject {
    /// `Pam\Native\PushProvider` value reported with the token (1 = FCM).
    var provider: Int64 { get }
    /// Called on the main thread with every APNs device token.
    func token(apnsToken: Data, completion: @escaping (Result<String, Error>) -> Void)
    /// `Notifications::unregisterPush()`: invalidate the provider token.
    func deleteToken()
}


/// Minimal push hook; the optional plugin owns presentation and action handling.
public protocol PushEventHandler: AnyObject {
    func didRegister(deviceToken: Data)
    func didRefresh(token: String, provider: Int64)
    func didFailToRegister(error: Error)
    func didReceive(notification: UNNotification)
    func didReceive(response: UNNotificationResponse, completionHandler: @escaping () -> Void)
    func didReceiveRemote(userInfo: [AnyHashable: Any], completion: @escaping (UIBackgroundFetchResult) -> Void)
}

public enum PamPushNotifications {
    public static let providerFcm: Int64 = 1
    public static let providerApns: Int64 = 2
    public static var tokenProvider: PamPushTokenProvider?
    public static var handler: PushEventHandler?
    public static func didRegister(deviceToken: Data) { handler?.didRegister(deviceToken: deviceToken) }
    public static func didRefresh(token: String, provider: Int64) { handler?.didRefresh(token: token, provider: provider) }
    public static func didFailToRegister(error: Error) { handler?.didFailToRegister(error: error) }
    public static func didReceive(notification: UNNotification) { handler?.didReceive(notification: notification) }
    public static func didReceive(response: UNNotificationResponse, completionHandler: @escaping () -> Void) {
        guard let handler else { completionHandler(); return }
        handler.didReceive(response: response, completionHandler: completionHandler)
    }
    public static func didReceiveRemote(userInfo: [AnyHashable: Any], completion: @escaping (UIBackgroundFetchResult) -> Void) {
        guard let handler else { completion(.noData); return }
        handler.didReceiveRemote(userInfo: userInfo, completion: completion)
    }
}
