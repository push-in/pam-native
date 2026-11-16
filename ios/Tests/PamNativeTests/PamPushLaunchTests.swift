import Foundation
import UIKit
import UserNotifications
import XCTest
@testable import PamNative

/// Source-ready iOS contract tests. Execute with xcodebuild on macOS.
///
/// The host forwards the launching push (`launchOptions[.remoteNotification]`,
/// UIScene `connectionOptions.notificationResponse`) to the registered
/// `PushEventHandler` before PHP boots; handlers written before the launch
/// hooks keep compiling with their empty defaults.
final class PamPushLaunchTests: XCTestCase {
    private var previous: PushEventHandler?

    override func setUp() {
        super.setUp()
        previous = PamPushNotifications.handler
    }

    override func tearDown() {
        PamPushNotifications.handler = previous
        super.tearDown()
    }

    /// The hook is a protocol requirement: the handler's own implementation
    /// runs, not the empty default (which would leave `launches` empty).
    func testLaunchingPushReachesTheRegisteredHandler() {
        let recorder = LaunchRecorder()
        PamPushNotifications.handler = recorder
        PamPushNotifications.didLaunch(remoteNotification: ["chat_id": "42"])
        XCTAssertEqual(recorder.launches.count, 1)
        XCTAssertEqual(recorder.launches.first?["chat_id"] as? String, "42")
    }

    func testHandlerWithoutLaunchHooksIgnoresTheLaunch() {
        let handler = MinimalHandler()
        PamPushNotifications.handler = handler
        PamPushNotifications.didLaunch(remoteNotification: ["chat_id": "42"])
        XCTAssertEqual(handler.calls, 0)
    }

    func testNoHandlerIgnoresTheLaunch() {
        PamPushNotifications.handler = nil
        PamPushNotifications.didLaunch(remoteNotification: ["chat_id": "42"])
    }
}

/// The plugin's NotificationPushHandler signatures.
private final class LaunchRecorder: PushEventHandler {
    var launches: [[AnyHashable: Any]] = []

    func didRegister(deviceToken: Data) {}
    func didRefresh(token: String, provider: Int64) {}
    func didFailToRegister(error: Error) {}
    func didReceive(notification: UNNotification) {}
    #if !os(tvOS)
    func didReceive(response: UNNotificationResponse, completionHandler: @escaping () -> Void) { completionHandler() }
    func didLaunch(response: UNNotificationResponse) {}
    #endif
    func didReceiveRemote(userInfo: [AnyHashable: Any], completion: @escaping (UIBackgroundFetchResult) -> Void) {
        completion(.noData)
    }
    func didLaunch(remoteNotification userInfo: [AnyHashable: Any]) { launches.append(userInfo) }
}

/// A handler written before the launch hooks: it still conforms.
private final class MinimalHandler: PushEventHandler {
    var calls = 0

    func didRegister(deviceToken: Data) { calls += 1 }
    func didRefresh(token: String, provider: Int64) { calls += 1 }
    func didFailToRegister(error: Error) { calls += 1 }
    func didReceive(notification: UNNotification) { calls += 1 }
    #if !os(tvOS)
    func didReceive(response: UNNotificationResponse, completionHandler: @escaping () -> Void) {
        calls += 1
        completionHandler()
    }
    #endif
    func didReceiveRemote(userInfo: [AnyHashable: Any], completion: @escaping (UIBackgroundFetchResult) -> Void) {
        calls += 1
        completion(.noData)
    }
}
