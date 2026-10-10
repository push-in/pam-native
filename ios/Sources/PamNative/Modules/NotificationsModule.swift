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

    /// UNUserNotificationCenterDelegate `didReceive`: handles conversation
    /// inline replies / mark-as-read natively (endpoint delivery, durable
    /// Notifications::onAction() queue) and reports other taps as opens.
    /// Call `completionHandler` exactly as UIKit hands it in.
    public static func didReceive(response: UNNotificationResponse, completionHandler: @escaping () -> Void) {
        let userInfo = response.notification.request.content.userInfo
        // APNs pushes whose category belongs to a PushRendering rule (reply,
        // mark-as-read, retry, buttons) are answered natively.
        if userInfo[PamConversationNotifications.userInfoKey] == nil,
           response.actionIdentifier != UNNotificationDefaultActionIdentifier,
           response.actionIdentifier != UNNotificationDismissActionIdentifier {
            let token = PamBackgroundTaskToken()
            let finish = PamMainCallback(completionHandler)
            token.begin("pam-notification-category-action")
            let handled = PamNotificationCategories.handle(response) {
                DispatchQueue.main.async {
                    finish.call()
                    token.end()
                }
            }
            if handled { return }
            token.end()
        }
        guard let key = userInfo[PamConversationNotifications.userInfoKey] as? String,
              response.actionIdentifier == PamConversationNotifications.replyAction ||
                response.actionIdentifier == PamNotificationCategories.retryAction ||
                response.actionIdentifier == PamConversationNotifications.markReadAction else {
            if response.actionIdentifier != UNNotificationDismissActionIdentifier {
                didOpen(response: response)
            }
            completionHandler()
            return
        }
        let retrying = response.actionIdentifier == PamNotificationCategories.retryAction
        let type = response.actionIdentifier == PamConversationNotifications.markReadAction
            ? PamNotificationActionType.markRead
            : PamNotificationActionType.reply
        let retry: (actionId: String, uuid: String)? = retrying
            ? ((userInfo[PamNotificationCategories.retryActionIdKey] as? String) ?? "", (userInfo[PamNotificationCategories.retryUuidKey] as? String) ?? "")
            : nil
        let text = String(
            ((response as? UNTextInputNotificationResponse)?.userText
                ?? (retrying ? userInfo[PamNotificationCategories.retryTextKey] as? String : nil) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(PamConversationMessage.maxText)
        )
        if type == PamNotificationActionType.reply && text.isEmpty {
            completionHandler()
            return
        }
        // Keep the process alive while the endpoint request runs.
        let token = PamBackgroundTaskToken()
        let finish = PamMainCallback(completionHandler)
        DispatchQueue.main.async {
            token.begin("pam-notification-action")
            PamNotificationActions.handle(type: type, key: key, text: text, retry: retry) {
                DispatchQueue.main.async {
                    finish.call()
                    token.end()
                }
            }
        }
    }

    /// `application(_:didReceiveRemoteNotification:fetchCompletionHandler:)`
    /// for data-only (`content-available: 1`) pushes: applies PushRendering
    /// rules natively, then reports the push to PHP (`PushMessage::$rendered`).
    public static func didReceiveRemote(
        userInfo: [AnyHashable: Any],
        completion: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        var data: [String: Any] = [:]
        for (key, value) in userInfo {
            guard let key = key as? String, key != "aps" else { continue }
            data[key] = value
        }
        let aps = userInfo["aps"] as? [String: Any]
        let alert = aps?["alert"] as? [String: Any]
        let title = (alert?["title"] as? String) ?? ""
        let body = (alert?["body"] as? String) ?? (aps?["alert"] as? String) ?? ""
        let id = (userInfo["gcm.message_id"] as? String)
            ?? (userInfo["google.message_id"] as? String)
            ?? (userInfo["id"] as? String)
            ?? UUID().uuidString
        let dataJson = JSONSerialization.isValidJSONObject(data)
            ? (try? JSONSerialization.data(withJSONObject: data)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
            : "{}"
        DispatchQueue.global(qos: .userInitiated).async {
            let rendered = PamPushRendering.render(id: id, title: title, body: body, dataJson: dataJson)
            PushTokenRegistry.shared.report(
                event: 1,
                id: id,
                title: title,
                body: body,
                dataJson: dataJson,
                deepLink: (data["pam.deepLink"] as? String) ?? (data["deepLink"] as? String) ?? (data["deep_link"] as? String) ?? "",
                rendered: rendered
            )
            // Give UserNotifications a moment to persist the posted request.
            DispatchQueue.main.asyncAfter(deadline: .now() + (rendered ? 0.5 : 0)) {
                completion(rendered ? .newData : .noData)
            }
        }
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
            case "unregisterPush":
                DispatchQueue.main.async {
                    UIApplication.shared.unregisterForRemoteNotifications()
                    PushTokenRegistry.shared.unregister()
                    completion(.success, Data())
                }
            case "nextPushEvent":
                PushTokenRegistry.shared.nextEvent(completion: completion)
            case "showConversation":
                let values = try WireMap.decode(payload)
                guard case let .text(specJSON)? = values["spec"],
                      let object = try JSONSerialization.jsonObject(with: Data(specJSON.utf8)) as? [String: Any] else {
                    throw NotificationsError("Invalid conversation payload")
                }
                let spec = try PamConversationSpec.from(object)
                PamConversationNotifications.show(spec) { error in
                    if let error {
                        completion(.failure, Data(error.localizedDescription.utf8))
                    } else {
                        completion(.success, (try? WireMap.encode(["key": .text(spec.key)])) ?? Data())
                    }
                }
            case "cancelConversation":
                let values = try WireMap.decode(payload)
                guard case let .text(key)? = values["key"] else {
                    throw NotificationsError("Missing conversation key")
                }
                PamConversationNotifications.cancel(key: key)
                completion(.success, Data())
            case "nextAction":
                PamNotificationActions.next(completion)
            case "registerPushRendering":
                let values = try WireMap.decode(payload)
                guard case let .text(rule)? = values["rule"] else { throw NotificationsError("Missing push rendering rule") }
                try PamPushRendering.register(rule)
                completion(.success, Data())
            case "forgetPushRendering":
                let values = try WireMap.decode(payload)
                if case .flag(true)? = values["all"] {
                    PamPushRendering.clear()
                } else {
                    guard case let .text(field)? = values["field"], case let .text(type)? = values["type"] else {
                        throw NotificationsError("Missing push rendering field/type")
                    }
                    PamPushRendering.forget(field: field, type: type)
                }
                completion(.success, Data())
            case "setCredential":
                let values = try WireMap.decode(payload)
                guard case let .text(account)? = values["account"], case let .text(token)? = values["token"] else {
                    throw NotificationsError("Missing credential account/token")
                }
                try PamNotificationCredentials.set(account: account, token: token)
                completion(.success, Data())
            case "removeCredential":
                let values = try WireMap.decode(payload)
                guard case let .text(account)? = values["account"] else { throw NotificationsError("Missing credential account") }
                PamNotificationCredentials.remove(account: account)
                completion(.success, Data())
            case "replaceCredentials":
                let values = try WireMap.decode(payload)
                guard case let .text(json)? = values["tokens"],
                      let tokens = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: String] else {
                    throw NotificationsError("Invalid credential map")
                }
                try PamNotificationCredentials.replace(tokens)
                completion(.success, Data())
            case "setActiveRoute":
                let values = try WireMap.decode(payload)
                guard case let .text(name)? = values["name"] else { throw NotificationsError("Missing route name") }
                var params = "{}"
                var path = ""
                if case let .text(value)? = values["params"] { params = value }
                if case let .text(value)? = values["path"] { path = value }
                PamActiveRoute.update(name: name, paramsJson: params, path: path)
                completion(.success, Data())
            default:
                throw NotificationsError("Unknown notifications method \(method)")
            }
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    func close() {
        PushTokenRegistry.shared.closeEvents()
        PamNotificationActions.close("Notifications module closed")
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

    func unregister() {
        lock.lock()
        token = nil
        let callbacks = waiters
        waiters.removeAll()
        lock.unlock()
        callbacks.forEach { $0(.failure, Data("Push registration was cancelled".utf8)) }
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
        report(
            event: event,
            id: request.identifier,
            title: content.title,
            body: content.body,
            dataJson: String(decoding: data, as: UTF8.self),
            deepLink: deepLink,
            rendered: false
        )
    }

    func report(
        event: Int64,
        id: String,
        title: String,
        body: String,
        dataJson: String,
        deepLink: String,
        rendered: Bool
    ) {
        let data = dataJson.utf8.count <= 256 * 1_024 ? dataJson : "{}"
        let payload = (try? WireMap.encode([
            "event": .integer(event),
            "id": .text(String(id.prefix(512))),
            "title": .text(String(title.prefix(4_096))),
            "body": .text(String(body.prefix(16_384))),
            "data": .text(data),
            "deepLink": .text(String(deepLink.prefix(8_192))),
            "rendered": .flag(rendered),
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

struct NotificationsError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Carries a UIKit completion handler across queues.
final class PamMainCallback: @unchecked Sendable {
    private let callback: () -> Void
    init(_ callback: @escaping () -> Void) { self.callback = callback }
    func call() { callback() }
}
