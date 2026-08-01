import Foundation
import UserNotifications
import UIKit

public enum PamPushNotifications {
    public static func didRegister(deviceToken: Data) {
        PushTokenRegistry.shared.resolve(
            deviceToken.map { String(format: "%02x", $0) }.joined()
        )
    }

    public static func didFailToRegister(error: Error) {
        PushTokenRegistry.shared.reject(error.localizedDescription)
    }

    public static func didReceive(notification: UNNotification) {
        PushTokenRegistry.shared.report(
            event: 1,
            request: notification.request
        )
    }

    public static func didOpen(response: UNNotificationResponse) {
        PushTokenRegistry.shared.report(
            event: 2,
            request: response.notification.request
        )
    }
}

final class NotificationsModule: NativeModule, ClosableNativeModule {
    // UserNotifications requires a real application bundle. Resolve it only
    // when the notifications capability is invoked so registry composition,
    // package tests, and non-application hosts remain safe.
    private lazy var center = UNUserNotificationCenter.current()

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        do {
            switch method {
            case "requestPermission":
                center.requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
                    if let error {
                        completion(.failure, Data(error.localizedDescription.utf8))
                    } else {
                        let result = (try? WireMap.encode(["granted": .flag(granted)])) ?? Data()
                        completion(.success, result)
                    }
                }
            case "schedule":
                let values = try WireMap.decode(payload)
                guard case let .text(id)? = values["id"],
                      case let .text(title)? = values["title"],
                      case let .text(body)? = values["body"] else {
                    throw NotificationsError("Invalid notification payload")
                }
                let delay: Int64
                if case let .integer(value)? = values["delaySeconds"] {
                    delay = max(0, value)
                } else {
                    delay = 0
                }
                let content = UNMutableNotificationContent()
                content.title = title
                content.body = body
                content.sound = .default
                var userInfo: [AnyHashable: Any] = [:]
                if case let .text(dataJSON)? = values["data"],
                   let data = dataJSON.data(using: .utf8),
                   let object = try? JSONSerialization.jsonObject(with: data) {
                    userInfo["pam.data"] = object
                }
                if case let .text(deepLink)? = values["deepLink"], !deepLink.isEmpty {
                    userInfo["pam.deepLink"] = deepLink
                }
                content.userInfo = userInfo
                let trigger = delay > 0
                    ? UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(delay), repeats: false)
                    : nil
                center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger)) {
                    if let error = $0 {
                        completion(.failure, Data(error.localizedDescription.utf8))
                    } else {
                        completion(.success, Data())
                    }
                }
            case "cancel":
                let values = try WireMap.decode(payload)
                guard case let .text(id)? = values["id"] else {
                    throw NotificationsError("Missing notification id")
                }
                center.removePendingNotificationRequests(withIdentifiers: [id])
                center.removeDeliveredNotifications(withIdentifiers: [id])
                completion(.success, Data())
            case "registerPush":
                PushTokenRegistry.shared.register(completion: completion)
            case "nextPushEvent":
                PushTokenRegistry.shared.nextEvent(completion: completion)
            default:
                throw NotificationsError("Unknown notifications method \(method)")
            }
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    func close() {
        PushTokenRegistry.shared.closeEvents()
    }
}

private final class PushTokenRegistry {
    static let shared = PushTokenRegistry()
    private let lock = NSLock()
    private var token: String?
    private var waiters: [ModuleCompletion] = []
    private var events: [Data] = []
    private var eventWaiter: ModuleCompletion?

    func register(completion: @escaping ModuleCompletion) {
        lock.lock()
        if let token {
            lock.unlock()
            completion(.success, payload(token))
            return
        }
        waiters.append(completion)
        lock.unlock()
        DispatchQueue.main.async {
            UIApplication.shared.registerForRemoteNotifications()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
            self?.reject("APNs token registration timed out")
        }
    }

    func resolve(_ value: String) {
        lock.lock()
        token = value
        let callbacks = waiters
        waiters.removeAll()
        lock.unlock()
        callbacks.forEach { $0(.success, payload(value)) }
    }

    func reject(_ message: String) {
        lock.lock()
        guard token == nil, !waiters.isEmpty else {
            lock.unlock()
            return
        }
        let callbacks = waiters
        waiters.removeAll()
        lock.unlock()
        callbacks.forEach { $0(.failure, Data(message.utf8)) }
    }

    func nextEvent(completion: @escaping ModuleCompletion) {
        lock.lock()
        if !events.isEmpty {
            let payload = events.removeFirst()
            lock.unlock()
            completion(.success, payload)
            return
        }
        guard eventWaiter == nil else {
            lock.unlock()
            completion(.failure, Data("Only one push listener can wait at a time".utf8))
            return
        }
        eventWaiter = completion
        lock.unlock()
    }

    func report(event: Int64, request: UNNotificationRequest) {
        let content = request.content
        let userInfo = content.userInfo
        let dataObject = userInfo["pam.data"] ?? userInfo
        let data: Data
        if JSONSerialization.isValidJSONObject(dataObject),
           let encoded = try? JSONSerialization.data(withJSONObject: dataObject) {
            data = encoded.count <= 256 * 1_024 ? encoded : Data("{}".utf8)
        } else {
            data = Data("{}".utf8)
        }
        let deepLink = userInfo["pam.deepLink"] as? String
            ?? userInfo["deepLink"] as? String
            ?? userInfo["deep_link"] as? String
            ?? ""
        let payload = (try? WireMap.encode([
            "event": .integer(event),
            "id": .text(String(request.identifier.prefix(512))),
            "title": .text(String(content.title.prefix(4_096))),
            "body": .text(String(content.body.prefix(16_384))),
            "data": .text(String(decoding: data, as: UTF8.self)),
            "deepLink": .text(String(deepLink.prefix(8_192))),
        ])) ?? Data()
        lock.lock()
        let callback = eventWaiter
        if callback == nil {
            if events.count >= 64 { events.removeFirst() }
            events.append(payload)
        } else {
            eventWaiter = nil
        }
        lock.unlock()
        callback?(.success, payload)
    }

    func closeEvents() {
        lock.lock()
        let callback = eventWaiter
        eventWaiter = nil
        lock.unlock()
        callback?(.failure, Data("Notifications module closed".utf8))
    }

    private func payload(_ value: String) -> Data {
        (try? WireMap.encode([
            "token": .text(value),
            "provider": .integer(2),
        ])) ?? Data()
    }
}

private struct NotificationsError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
