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
    #if !os(tvOS)
    func didReceive(response: UNNotificationResponse, completionHandler: @escaping () -> Void)
    #endif
    func didReceiveRemote(userInfo: [AnyHashable: Any], completion: @escaping (UIBackgroundFetchResult) -> Void)
    #if !os(tvOS)
    /// The notification tap that launched the app. Under UIScene it only
    /// arrives in `connectionOptions.notificationResponse`; the host forwards
    /// it before PHP boots.
    func didLaunch(response: UNNotificationResponse)
    #endif
    /// `launchOptions[.remoteNotification]` of a launch, forwarded after the
    /// plugins are built and before PHP boots.
    func didLaunch(remoteNotification: [AnyHashable: Any])
}

/// Launch hooks are optional: a handler that does not need them keeps compiling.
public extension PushEventHandler {
    #if !os(tvOS)
    func didLaunch(response: UNNotificationResponse) {}
    #endif
    func didLaunch(remoteNotification: [AnyHashable: Any]) {}
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
    #if !os(tvOS)
    public static func didReceive(response: UNNotificationResponse, completionHandler: @escaping () -> Void) {
        guard let handler else { completionHandler(); return }
        handler.didReceive(response: response, completionHandler: completionHandler)
    }
    #endif
    public static func didReceiveRemote(userInfo: [AnyHashable: Any], completion: @escaping (UIBackgroundFetchResult) -> Void) {
        guard let handler else { completion(.noData); return }
        handler.didReceiveRemote(userInfo: userInfo, completion: completion)
    }
    #if !os(tvOS)
    public static func didLaunch(response: UNNotificationResponse) { handler?.didLaunch(response: response) }
    #endif
    public static func didLaunch(remoteNotification userInfo: [AnyHashable: Any]) {
        handler?.didLaunch(remoteNotification: userInfo)
    }
}
