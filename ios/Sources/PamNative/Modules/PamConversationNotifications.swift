import Foundation
import Intents
import UIKit
import UserNotifications

// iOS side of the 1.1.0 notification pipeline (Android PamConversationNotifications,
// PamNotificationActions and PamPushRendering): conversation notifications with
// inline reply / mark-as-read, a durable action queue drained by
// Notifications::onAction(), native endpoint delivery while PHP is suspended
// and declarative push rendering for data-only (content-available) pushes.

/// `{name}` placeholders resolved from push data and action context.
/// `{credential:user_id|recipient_user_id}` is the PamNotificationCredentials
/// token of the account named by the first non-empty listed push data field.
enum PamNotificationTemplate {
    private static let placeholder = try! NSRegularExpression(pattern: "\\{([A-Za-z0-9_.:|-]{1,128})\\}")
    private static let storageKey = "^[A-Za-z0-9_.-]{1,128}$"
    static let credentialPrefix = "credential:"
    typealias Credentials = (String) -> String?
    /// Credentials are only ever resolved in headers, never in a URL or body.
    static let noCredentials: Credentials = { _ in nil }

    static func variables(_ dataJson: String) -> [String: String] {
        guard let object = try? JSONSerialization.jsonObject(with: Data(dataJson.utf8)) as? [String: Any] else {
            return [:]
        }
        var result: [String: String] = [:]
        for (name, value) in object {
            if let text = value as? String {
                result[name] = text
            } else if let number = value as? NSNumber {
                result[name] = CFGetTypeID(number) == CFBooleanGetTypeID()
                    ? (number.boolValue ? "true" : "false")
                    : number.stringValue
            }
        }
        return result
    }

    static func render(
        _ template: String,
        _ variables: [String: String],
        encode: (String) -> String = { $0 },
        credentials: Credentials = noCredentials
    ) -> String {
        let source = template as NSString
        var output = ""
        var cursor = 0
        for match in placeholder.matches(in: template, range: NSRange(location: 0, length: source.length)) {
            output += source.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
            output += encode(resolve(source.substring(with: match.range(at: 1)), variables, credentials))
            cursor = match.range.location + match.range.length
        }
        output += source.substring(from: cursor)
        return output
    }

    /// True when every placeholder in the template has a value.
    static func complete(_ template: String, _ variables: [String: String]) -> Bool {
        names(template).allSatisfy { name in
            variables[name] != nil || name == "uuid" || name == "now" || name == "action_id"
                || name.hasPrefix("storage:") || name.hasPrefix(credentialPrefix)
        }
    }

    /// Account id a `credential:` placeholder points at, or "" when the push names none.
    static func credentialAccount(_ name: String, _ variables: [String: String]) -> String {
        guard name.hasPrefix(credentialPrefix) else { return "" }
        for field in name.dropFirst(credentialPrefix.count).split(separator: "|") {
            let value = (variables[String(field)] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if !value.isEmpty { return value }
        }
        return ""
    }

    /// True when a `credential:` placeholder in the template has no stored token.
    static func missingCredential(_ template: String, _ variables: [String: String], _ credentials: Credentials) -> Bool {
        names(template).contains { name in
            guard name.hasPrefix(credentialPrefix) else { return false }
            let account = credentialAccount(name, variables)
            return account.isEmpty || (credentials(account) ?? "").isEmpty
        }
    }

    private static func names(_ template: String) -> [String] {
        let source = template as NSString
        return placeholder.matches(in: template, range: NSRange(location: 0, length: source.length)).map {
            source.substring(with: $0.range(at: 1))
        }
    }

    private static func resolve(_ name: String, _ variables: [String: String], _ credentials: Credentials) -> String {
        if let value = variables[name] { return value }
        if name.hasPrefix(credentialPrefix) {
            let account = credentialAccount(name, variables)
            return account.isEmpty ? "" : (credentials(account) ?? "")
        }
        if name == "uuid" { return UUID().uuidString.lowercased() }
        if name == "now" { return String(Int64(Date().timeIntervalSince1970 * 1_000)) }
        if name == "action_id" { return PamReplyFailure.createActionId() }
        if name.hasPrefix("storage:") {
            let key = String(name.dropFirst("storage:".count))
            guard key.range(of: storageKey, options: .regularExpression) != nil else { return "" }
            return PamNativeStorage.string(forKey: key) ?? ""
        }
        return ""
    }
}

/// Reads values written by `Storage` (the StorageModule key space).
enum PamNativeStorage {
    static func string(forKey key: String) -> String? {
        UserDefaults.standard.string(forKey: "pam.storage.\(key)") ?? UserDefaults.standard.string(forKey: key)
    }
}

/// Executes the declarative HTTP request attached to a notification action.
enum PamNotificationEndpoint {
    private static let header = "^[A-Za-z0-9-]{1,64}$"

    /// True when a header needs a credential the push's account has no token for.
    static func missingCredential(
        _ endpoint: [String: Any],
        variables: [String: String],
        credentials: PamNotificationTemplate.Credentials = PamNotificationCredentials.token
    ) -> Bool {
        ((endpoint["headers"] as? [String: Any]) ?? [:]).values.contains { value in
            guard let raw = value as? String else { return false }
            return PamNotificationTemplate.missingCredential(raw, variables, credentials)
        }
    }

    static func send(_ endpoint: [String: Any], variables: [String: String], completion: @escaping (Int) -> Void) {
        sendForResult(endpoint, variables: variables) { status, _ in completion(status) }
    }

    /// HTTP status (0 without a response) and the error response's JSON `message`.
    static func sendForResult(_ endpoint: [String: Any], variables: [String: String], completion: @escaping (Int, String?) -> Void) {
        let resultCompletion = completion
        let completion = { (status: Int) in resultCompletion(status, nil) }
        let method = ((endpoint["method"] as? String) ?? "POST").uppercased()
        guard ["POST", "PUT", "PATCH", "DELETE"].contains(method), let template = endpoint["url"] as? String else {
            completion(0)
            return
        }
        let rendered = PamNotificationTemplate.render(template, variables) {
            $0.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-._~"))) ?? ""
        }
        guard let url = URL(string: rendered), let scheme = url.scheme?.lowercased(), url.host != nil, url.user == nil else {
            completion(0)
            return
        }
        #if DEBUG
        guard scheme == "https" || scheme == "http" else { completion(0); return }
        #else
        guard scheme == "https" else { completion(0); return }
        #endif
        var request = URLRequest(url: url, timeoutInterval: 8)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        for (name, value) in (endpoint["headers"] as? [String: Any]) ?? [:] {
            guard name.range(of: header, options: .regularExpression) != nil, let raw = value as? String else { continue }
            let headerValue = PamNotificationTemplate.render(raw, variables, credentials: PamNotificationCredentials.token)
            guard !headerValue.contains("\r"), !headerValue.contains("\n"), headerValue.utf8.count <= 8_192 else { continue }
            request.setValue(headerValue, forHTTPHeaderField: name)
        }
        if let body = endpoint["body"], body is [String: Any] || body is [Any],
           let data = try? JSONSerialization.data(withJSONObject: renderJson(body, variables)) {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = data
        }
        let session = URLSession(configuration: .ephemeral, delegate: PamNoRedirects(), delegateQueue: nil)
        session.dataTask(with: request) { body, response, _ in
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            var message: String?
            if status >= 400, let body, body.count <= 16_384,
               let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
               let text = (object["message"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty {
                message = text
            }
            resultCompletion(status, message)
            session.finishTasksAndInvalidate()
        }.resume()
    }

    static func renderJson(_ value: Any, _ variables: [String: String]) -> Any {
        if let object = value as? [String: Any] { return object.mapValues { renderJson($0, variables) } }
        if let array = value as? [Any] { return array.map { renderJson($0, variables) } }
        if let text = value as? String { return PamNotificationTemplate.render(text, variables) }
        return value
    }
}

private final class PamNoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

struct PamConversationPerson: Equatable {
    var name: String
    var avatar: String = ""
    var key: String = ""

    var json: [String: Any] { ["name": name, "avatar": avatar, "key": key] }

    static func from(_ json: Any?) -> PamConversationPerson? {
        guard let object = json as? [String: Any],
              let name = (object["name"] as? String)?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { return nil }
        return PamConversationPerson(
            name: String(name.prefix(256)),
            avatar: String(((object["avatar"] as? String) ?? "").prefix(8_192)),
            key: String(((object["key"] as? String) ?? "").prefix(256))
        )
    }
}

struct PamConversationMessage: Equatable {
    static let maxText = 4_096
    var id: String
    var text: String
    var timestamp: Int64
    /// nil for messages written by the device user.
    var sender: PamConversationPerson?

    var json: [String: Any] {
        var value: [String: Any] = ["id": id, "text": text, "timestamp": timestamp]
        if let sender { value["sender"] = sender.json }
        return value
    }

    static func from(_ object: [String: Any]) -> PamConversationMessage {
        let timestamp = (object["timestamp"] as? NSNumber)?.int64Value ?? 0
        return PamConversationMessage(
            id: String(((object["id"] as? String) ?? "").prefix(256)),
            text: String(((object["text"] as? String) ?? "").prefix(maxText)),
            timestamp: timestamp > 0 ? timestamp : Int64(Date().timeIntervalSince1970 * 1_000),
            sender: PamConversationPerson.from(object["sender"])
        )
    }
}

/// Same JSON contract as the Android ConversationSpec.
struct PamConversationSpec {
    static let maxHistory = 25
    static let keyPattern = "^[A-Za-z0-9_.:@#/-]{1,128}$"

    var key: String
    var title = ""
    var group = false
    var me = PamConversationPerson(name: "You")
    var messages: [PamConversationMessage] = []
    var replyLabel = ""
    var markReadLabel = ""
    var replyEndpoint: [String: Any]?
    var markReadEndpoint: [String: Any]?
    var deepLink = ""
    var dataJson = "{}"
    var importance = 3
    var silent = false
    /// ReplyFailures config: send first, keep failed replies visible.
    var replyFailures: [String: Any]?

    var json: [String: Any] {
        [
            "key": key,
            "title": title,
            "group": group,
            "self": me.json,
            "messages": messages.map(\.json),
            "replyLabel": replyLabel,
            "markReadLabel": markReadLabel,
            "replyEndpoint": replyEndpoint ?? NSNull(),
            "markReadEndpoint": markReadEndpoint ?? NSNull(),
            "deepLink": deepLink,
            "data": dataJson,
            "importance": importance,
            "silent": silent,
            "replyFailures": replyFailures ?? NSNull(),
        ]
    }

    static func from(_ object: [String: Any]) throws -> PamConversationSpec {
        guard let key = object["key"] as? String, key.range(of: keyPattern, options: .regularExpression) != nil else {
            throw PamNotificationError("Conversation key must contain 1-128 safe characters")
        }
        var spec = PamConversationSpec(key: key)
        spec.title = String(((object["title"] as? String) ?? "").prefix(512))
        spec.group = (object["group"] as? Bool) ?? false
        spec.me = PamConversationPerson.from(object["self"]) ?? PamConversationPerson(name: "You")
        spec.messages = ((object["messages"] as? [[String: Any]]) ?? [])
            .prefix(maxHistory)
            .map(PamConversationMessage.from)
            .filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        spec.replyLabel = String(((object["replyLabel"] as? String) ?? "").prefix(64))
        spec.markReadLabel = String(((object["markReadLabel"] as? String) ?? "").prefix(64))
        spec.replyEndpoint = object["replyEndpoint"] as? [String: Any]
        spec.markReadEndpoint = object["markReadEndpoint"] as? [String: Any]
        spec.deepLink = String(((object["deepLink"] as? String) ?? "").prefix(8_192))
        let data = (object["data"] as? String) ?? "{}"
        spec.dataJson = data.trimmingCharacters(in: .whitespaces).isEmpty ? "{}" : String(data.prefix(262_144))
        spec.importance = min(max((object["importance"] as? NSNumber)?.intValue ?? 3, 1), 4)
        spec.silent = (object["silent"] as? Bool) ?? false
        spec.replyFailures = object["replyFailures"] as? [String: Any]
        return spec
    }

    /// Merges [incoming] into the stored history (dedup by id, oldest first).
    static func merge(previous: PamConversationSpec?, incoming: PamConversationSpec) -> PamConversationSpec {
        var order: [String] = []
        var history: [String: PamConversationMessage] = [:]
        for (index, message) in ((previous?.messages ?? []) + incoming.messages).enumerated() {
            let id = message.id.isEmpty ? "auto-\(message.timestamp)-\(index)-\(message.text.hashValue)" : message.id
            if history[id] == nil { order.append(id) }
            history[id] = message
        }
        var merged = incoming
        merged.messages = Array(
            order.compactMap { history[$0] }
                .enumerated()
                .sorted { $0.element.timestamp == $1.element.timestamp ? $0.offset < $1.offset : $0.element.timestamp < $1.element.timestamp }
                .map(\.element)
                .suffix(maxHistory)
        )
        return merged
    }
}

struct PamNotificationError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

public enum PamConversationNotifications {
    static let replyAction = "pam.conversation.reply"
    static let markReadAction = "pam.conversation.markRead"
    static let userInfoKey = "pam.conversation"
    private static let storeKey = "pam-native-conversations"
    private static let maxConversations = 64
    private static let avatarBytes = 2 * 1024 * 1024
    private static let lock = NSLock()

    static func identifier(_ key: String) -> String { "pam-conversation-\(key)" }

    /// Merges messages into the stored history and posts the notification.
    @discardableResult
    static func show(_ incoming: PamConversationSpec, completion: ((Error?) -> Void)? = nil) -> PamConversationSpec {
        lock.lock()
        let merged = PamConversationSpec.merge(previous: loadLocked(incoming.key), incoming: incoming)
        storeLocked(merged)
        lock.unlock()
        post(merged, completion: completion)
        return merged
    }

    /// Records the user's own inline reply (iOS dismisses the notification itself).
    static func appendOwnReply(key: String, text: String) {
        lock.lock()
        defer { lock.unlock() }
        guard var spec = loadLocked(key) else { return }
        spec = PamConversationSpec.merge(previous: spec, incoming: {
            var reply = spec
            reply.messages = [PamConversationMessage(
                id: "reply-\(UUID().uuidString)",
                text: text,
                timestamp: Int64(Date().timeIntervalSince1970 * 1_000),
                sender: nil
            )]
            return reply
        }())
        storeLocked(spec)
    }

    static let failedCategoryPrefix = "pam.conversation.failed."

    /// Keeps an undelivered reply of a local conversation in the tray (reason, retry, draft field).
    static func postFailure(_ spec: PamConversationSpec, failure: PamReplyFailure, text: String, actionId: String, uuid: String) {
        let failures = spec.replyFailures ?? [:]
        let retryLabel = (failures["retryLabel"] as? String) ?? "Try again"
        let category = failure.retry ? failedCategoryPrefix + categoryIdentifier(replyLabel: retryLabel, markReadLabel: "") : ""
        let center = UNUserNotificationCenter.current()
        center.getNotificationCategories { categories in
            if !category.isEmpty, !categories.contains(where: { $0.identifier == category }) {
                var next = categories
                next.insert(UNNotificationCategory(
                    identifier: category,
                    actions: [UNNotificationAction(identifier: PamNotificationCategories.retryAction, title: retryLabel, options: [])],
                    intentIdentifiers: [],
                    options: []
                ))
                center.setNotificationCategories(next)
            }
            let content = UNMutableNotificationContent()
            content.threadIdentifier = spec.key
            content.categoryIdentifier = category
            content.title = spec.title.isEmpty ? (spec.messages.last?.sender?.name ?? "") : spec.title
            content.subtitle = (failures["sender"] as? String) ?? "Not sent"
            content.body = "\(failure.text)\n“\(text)”"
            content.sound = nil
            var data = (try? JSONSerialization.jsonObject(with: Data(spec.dataJson.utf8)) as? [String: Any]) ?? [:]
            if let draftField = failures["draftField"] as? String, !draftField.isEmpty { data[draftField] = text }
            content.userInfo = [
                userInfoKey: spec.key,
                "pam.data": data,
                "pam.deepLink": spec.deepLink,
                PamNotificationCategories.retryTextKey: text,
                PamNotificationCategories.retryActionIdKey: actionId,
                PamNotificationCategories.retryUuidKey: uuid,
            ]
            center.add(UNNotificationRequest(identifier: identifier(spec.key), content: content, trigger: nil))
        }
    }

    static func cancel(key: String) {
        lock.lock()
        var all = UserDefaults.standard.dictionary(forKey: storeKey) ?? [:]
        all.removeValue(forKey: key)
        UserDefaults.standard.set(all, forKey: storeKey)
        lock.unlock()
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [identifier(key)])
    }

    static func load(key: String) -> PamConversationSpec? {
        lock.lock()
        defer { lock.unlock() }
        return loadLocked(key)
    }

    private static func loadLocked(_ key: String) -> PamConversationSpec? {
        guard let text = UserDefaults.standard.dictionary(forKey: storeKey)?[key] as? String,
              let object = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] else { return nil }
        return try? PamConversationSpec.from(object)
    }

    private static func storeLocked(_ spec: PamConversationSpec) {
        var all = UserDefaults.standard.dictionary(forKey: storeKey) ?? [:]
        guard let data = try? JSONSerialization.data(withJSONObject: spec.json) else { return }
        all[spec.key] = String(decoding: data, as: UTF8.self)
        if all.count > maxConversations {
            for key in all.keys.filter({ $0 != spec.key }).prefix(all.count - maxConversations) {
                all.removeValue(forKey: key)
            }
        }
        UserDefaults.standard.set(all, forKey: storeKey)
    }

    /// Category per (reply, mark-read) label pair; registered lazily.
    static func categoryIdentifier(replyLabel: String, markReadLabel: String) -> String {
        guard !replyLabel.isEmpty || !markReadLabel.isEmpty else { return "" }
        let digest = (replyLabel + "\u{0}" + markReadLabel).utf8.reduce(UInt64(1_469_598_103_934_665_603)) {
            ($0 ^ UInt64($1)) &* 1_099_511_628_211
        }
        return "pam.conversation.\(String(digest, radix: 16))"
    }

    private static func ensureCategory(_ spec: PamConversationSpec, then: @escaping (String) -> Void) {
        let identifier = categoryIdentifier(replyLabel: spec.replyLabel, markReadLabel: spec.markReadLabel)
        guard !identifier.isEmpty else {
            then("")
            return
        }
        let center = UNUserNotificationCenter.current()
        center.getNotificationCategories { categories in
            if categories.contains(where: { $0.identifier == identifier }) {
                then(identifier)
                return
            }
            var actions: [UNNotificationAction] = []
            if !spec.replyLabel.isEmpty {
                actions.append(UNTextInputNotificationAction(
                    identifier: replyAction,
                    title: spec.replyLabel,
                    options: [],
                    textInputButtonTitle: spec.replyLabel,
                    textInputPlaceholder: ""
                ))
            }
            if !spec.markReadLabel.isEmpty {
                actions.append(UNNotificationAction(identifier: markReadAction, title: spec.markReadLabel, options: []))
            }
            var next = categories
            next.insert(UNNotificationCategory(
                identifier: identifier,
                actions: actions,
                intentIdentifiers: [INSendMessageIntentIdentifier],
                options: []
            ))
            center.setNotificationCategories(next)
            then(identifier)
        }
    }

    private static func post(_ spec: PamConversationSpec, completion: ((Error?) -> Void)?) {
        // Only incoming messages alert; own replies are bookkeeping.
        guard let last = spec.messages.last, let sender = last.sender else {
            completion?(nil)
            return
        }
        ensureCategory(spec) { category in
            DispatchQueue.global(qos: .userInitiated).async {
                let content = UNMutableNotificationContent()
                content.threadIdentifier = spec.key
                content.categoryIdentifier = category
                content.title = spec.group && !spec.title.isEmpty ? spec.title : sender.name
                if spec.group { content.subtitle = sender.name }
                content.body = last.text
                content.sound = spec.silent ? nil : .default
                content.interruptionLevel = spec.importance >= 4 ? .timeSensitive : (spec.importance <= 1 ? .passive : .active)
                content.relevanceScore = spec.importance >= 3 ? 1 : 0.5
                var userInfo: [AnyHashable: Any] = [userInfoKey: spec.key]
                if let object = try? JSONSerialization.jsonObject(with: Data(spec.dataJson.utf8)) {
                    userInfo["pam.data"] = object
                }
                if !spec.deepLink.isEmpty { userInfo["pam.deepLink"] = spec.deepLink }
                content.userInfo = userInfo
                let delivered = communication(content, spec: spec, message: last, sender: sender)
                UNUserNotificationCenter.current().add(
                    UNNotificationRequest(identifier: identifier(spec.key), content: delivered, trigger: nil)
                ) { completion?($0) }
            }
        }
    }

    /// iOS 15 communication notification (sender avatar, group name). Needs
    /// the Communication Notifications capability; without it iOS shows the
    /// plain content.
    private static func communication(
        _ content: UNMutableNotificationContent,
        spec: PamConversationSpec,
        message: PamConversationMessage,
        sender: PamConversationPerson
    ) -> UNNotificationContent {
        let senderPerson = INPerson(
            personHandle: INPersonHandle(value: sender.key.isEmpty ? sender.name : sender.key, type: .unknown),
            nameComponents: nil,
            displayName: sender.name,
            image: avatar(sender.avatar).map { INImage(imageData: $0) },
            contactIdentifier: nil,
            customIdentifier: sender.key.isEmpty ? nil : sender.key
        )
        let me = INPerson(
            personHandle: INPersonHandle(value: spec.me.key.isEmpty ? "self" : spec.me.key, type: .unknown),
            nameComponents: nil,
            displayName: spec.me.name,
            image: nil,
            contactIdentifier: nil,
            customIdentifier: nil,
            isMe: true
        )
        let intent = INSendMessageIntent(
            recipients: spec.group ? [me, senderPerson] : [me],
            outgoingMessageType: .outgoingMessageText,
            content: message.text,
            speakableGroupName: spec.group && !spec.title.isEmpty ? INSpeakableString(spokenPhrase: spec.title) : nil,
            conversationIdentifier: spec.key,
            serviceName: nil,
            sender: senderPerson,
            attachments: nil
        )
        let interaction = INInteraction(intent: intent, response: nil)
        interaction.direction = .incoming
        interaction.donate(completion: nil)
        return (try? content.updating(from: intent)) ?? content
    }

    /// Avatar bytes from the image disk cache, the private sandbox or HTTPS.
    static func avatar(_ source: String) -> Data? {
        guard !source.isEmpty else { return nil }
        if source.hasPrefix("https://") || source.hasPrefix("http://") {
            let cached = PamBlockingResult<Data>()
            PamMediaDiskCache.shared.data(source: source, stableKey: nil, maxAgeMs: 0) { cached.resolve($0) }
            if let data = cached.wait(timeout: 2), !data.isEmpty, data.count <= avatarBytes { return data }
            guard let url = URL(string: source) else { return nil }
            let downloaded = PamBlockingResult<Data>()
            URLSession.shared.dataTask(with: URLRequest(url: url, timeoutInterval: 4)) { data, response, _ in
                let ok = (200..<300).contains((response as? HTTPURLResponse)?.statusCode ?? 0)
                downloaded.resolve(ok && (data?.count ?? 0) <= avatarBytes ? data : nil)
            }.resume()
            return downloaded.wait(timeout: 5)
        }
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pam-files")
        var path = source
        if path.hasPrefix("pam-file:///") {
            path = String(path.dropFirst("pam-file:///".count)).removingPercentEncoding ?? ""
        }
        guard let url = try? PamPrivateFilePath.resolve(root: root, path: path),
              let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= avatarBytes else { return nil }
        return try? Data(contentsOf: url)
    }
}

enum PamNotificationActionType {
    static let reply = 1
    static let markRead = 2
    static let button = 3
}

/// Durable action queue drained by Notifications::onAction() in PHP.
public enum PamNotificationActions {
    private static let lock = NSLock()
    private static let storeKey = "pam-native-notification-actions"
    private static let maxQueued = 64
    private static var waiter: ModuleCompletion?

    /// Optional native HTTP delivery, conversation bookkeeping, then queue.
    static func handle(
        type: Int,
        key: String,
        text: String,
        retry: (actionId: String, uuid: String)? = nil,
        completion: @escaping () -> Void
    ) {
        let spec = PamConversationNotifications.load(key: key)
        let endpoint = type == PamNotificationActionType.reply ? spec?.replyEndpoint : spec?.markReadEndpoint
        var variables = PamNotificationTemplate.variables(spec?.dataJson ?? "{}")
        variables["reply"] = text
        variables["conversation"] = key
        // {action_id} / {uuid} stay fixed for this reply and its retries.
        let actionId = retry.flatMap { $0.actionId.isEmpty ? nil : $0.actionId } ?? PamReplyFailure.createActionId()
        let uuid = retry.flatMap { $0.uuid.isEmpty ? nil : $0.uuid } ?? UUID().uuidString.lowercased()
        variables["action_id"] = actionId
        variables["uuid"] = uuid
        if type == PamNotificationActionType.reply, let spec, let failures = spec.replyFailures {
            handleReplyWithFailures(spec: spec, failures: failures, endpoint: endpoint, variables: variables,
                                    key: key, text: text, actionId: actionId, uuid: uuid, completion: completion)
            return
        }
        // An endpoint authenticated per account ({credential:user_id}) is never
        // sent when the push's account has no token here: the notification is
        // dismissed and nothing goes out with another account's session.
        let missingCredential = endpoint.map { PamNotificationEndpoint.missingCredential($0, variables: variables) } ?? false
        if type == PamNotificationActionType.reply && !missingCredential {
            PamConversationNotifications.appendOwnReply(key: key, text: text)
        } else {
            PamConversationNotifications.cancel(key: key)
        }
        let finish = { (status: Int) in
            report(
                type: type,
                key: key,
                text: text,
                dataJson: spec?.dataJson ?? "{}",
                deepLink: spec?.deepLink ?? "",
                status: status,
                missingCredential: missingCredential
            )
            completion()
        }
        guard let endpoint, !missingCredential else {
            finish(-1)
            return
        }
        PamNotificationEndpoint.send(endpoint, variables: variables, completion: finish)
    }

    /// Sends first; success records the reply, failure keeps it in the tray with the reason.
    private static func handleReplyWithFailures(
        spec: PamConversationSpec,
        failures: [String: Any],
        endpoint: [String: Any]?,
        variables: [String: String],
        key: String,
        text: String,
        actionId: String,
        uuid: String,
        completion: @escaping () -> Void
    ) {
        let missingCredential = endpoint.map { PamNotificationEndpoint.missingCredential($0, variables: variables) } ?? false
        let finish = { (status: Int, message: String?) in
            let delivered = (200...299).contains(status)
            let shown = !delivered && endpoint != nil
            if delivered || endpoint == nil {
                PamConversationNotifications.appendOwnReply(key: key, text: text)
            } else {
                let failure = PamReplyFailure.resolve(failures, status: missingCredential ? 401 : status, message: message)
                PamConversationNotifications.postFailure(spec, failure: failure, text: text, actionId: actionId, uuid: uuid)
            }
            report(type: PamNotificationActionType.reply, key: key, text: text, dataJson: spec.dataJson, deepLink: spec.deepLink,
                   status: (endpoint == nil || missingCredential) ? -1 : status, missingCredential: missingCredential, failureShown: shown)
            completion()
        }
        guard let endpoint, !missingCredential else {
            finish(endpoint == nil ? -1 : 401, nil)
            return
        }
        PamNotificationEndpoint.sendForResult(endpoint, variables: variables, completion: finish)
    }

    static func next(_ completion: @escaping ModuleCompletion) {
        lock.lock()
        guard waiter == nil else {
            lock.unlock()
            completion(.failure, Data("Only one notification action listener can wait at a time".utf8))
            return
        }
        var queue = storedLocked()
        if queue.isEmpty {
            waiter = completion
            lock.unlock()
            return
        }
        let event = queue.removeFirst()
        persistLocked(queue)
        lock.unlock()
        completion(.success, event)
    }

    static func close(_ message: String) {
        lock.lock()
        let pending = waiter
        waiter = nil
        lock.unlock()
        pending?(.failure, Data(message.utf8))
    }

    static func report(
        type: Int,
        key: String,
        text: String,
        dataJson: String,
        deepLink: String,
        status: Int,
        missingCredential: Bool = false,
        failureShown: Bool = false,
        action: String = ""
    ) {
        let payload = (try? WireMap.encode([
            "type": .integer(Int64(type)),
            "conversation": .text(key),
            "text": .text(text),
            "data": .text(dataJson),
            "deepLink": .text(deepLink),
            "handledNatively": .flag(status >= 0),
            "statusCode": .integer(Int64(max(status, 0))),
            "credentialMissing": .flag(missingCredential),
            "failureShown": .flag(failureShown),
            "action": .text(action),
            "timestamp": .integer(Int64(Date().timeIntervalSince1970 * 1_000)),
        ])) ?? Data()
        lock.lock()
        let pending = waiter
        if pending == nil {
            var queue = storedLocked()
            if queue.count >= maxQueued { queue.removeFirst() }
            queue.append(payload)
            persistLocked(queue)
        } else {
            waiter = nil
        }
        lock.unlock()
        pending?(.success, payload)
    }

    private static func storedLocked() -> [Data] {
        (UserDefaults.standard.array(forKey: storeKey) as? [String] ?? []).compactMap { Data(base64Encoded: $0) }
    }

    private static func persistLocked(_ queue: [Data]) {
        UserDefaults.standard.set(queue.suffix(maxQueued).map { $0.base64EncodedString() }, forKey: storeKey)
    }
}

/// Route focused by the PHP navigator; push rendering suppresses
/// notifications for the open screen while the app is active.
public enum PamActiveRoute {
    private static let lock = NSLock()
    private static var name = ""
    private static var params: [String: Any] = [:]
    private static var path = ""
    private static var observers: [NSObjectProtocol] = []
    static var foregroundOverride: Bool?
    private static var active = false

    static func update(name: String, paramsJson: String, path: String) {
        installObservers()
        lock.lock()
        self.name = String(name.prefix(256))
        params = (try? JSONSerialization.jsonObject(with: Data(paramsJson.utf8)) as? [String: Any]) ?? [:]
        self.path = String(path.prefix(8_192))
        lock.unlock()
    }

    private static func installObservers() {
        lock.lock()
        defer { lock.unlock() }
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: nil) { _ in
                lock.lock(); active = true; lock.unlock()
            },
            center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: nil) { _ in
                lock.lock(); active = false; lock.unlock()
            },
        ]
        if Thread.isMainThread {
            active = UIApplication.shared.applicationState == .active
        } else {
            DispatchQueue.main.async {
                let state = UIApplication.shared.applicationState == .active
                lock.lock(); active = state; lock.unlock()
            }
        }
    }

    /// Suppression entries are `{route, params}` or `{path}` templates.
    static func matches(_ rules: [[String: Any]]?, _ variables: [String: String]) -> Bool {
        lock.lock()
        let foreground = foregroundOverride ?? active
        let currentName = name
        let currentParams = params
        let currentPath = path
        lock.unlock()
        guard foreground, let rules else { return false }
        for rule in rules {
            if let routePath = rule["path"] as? String, !routePath.isEmpty {
                guard PamNotificationTemplate.complete(routePath, variables) else { continue }
                if !currentPath.isEmpty,
                   normalize(currentPath) == normalize(PamNotificationTemplate.render(routePath, variables)) {
                    return true
                }
                continue
            }
            guard !currentName.isEmpty, (rule["route"] as? String) == currentName else { continue }
            let expected = (rule["params"] as? [String: Any]) ?? [:]
            let matched = expected.allSatisfy { key, raw in
                let template = raw as? String ?? "\(raw)"
                guard PamNotificationTemplate.complete(template, variables), let actual = currentParams[key] else { return false }
                return "\(actual)" == PamNotificationTemplate.render(template, variables)
            }
            if matched { return true }
        }
        return false
    }

    static func normalize(_ value: String) -> String {
        let trimmed = value.split(separator: "?", maxSplits: 1).first.map(String.init) ?? ""
        let path = trimmed.split(separator: "#", maxSplits: 1).first.map(String.init) ?? ""
        return "/" + path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }
}

/// Declarative push rendering for data-only pushes delivered to the app
/// (`content-available`), even when PHP is suspended.
public enum PamPushRendering {
    static let kindConversation = 1
    static let kindNotification = 2
    static let kindDismiss = 3
    private static let storeKey = "pam-native-push-rendering"

    static func register(_ ruleJson: String) throws {
        guard let rule = try JSONSerialization.jsonObject(with: Data(ruleJson.utf8)) as? [String: Any],
              let type = rule["type"] as? String, !type.trimmingCharacters(in: .whitespaces).isEmpty, type.count <= 128,
              let kind = (rule["kind"] as? NSNumber)?.intValue, (kindConversation...kindDismiss).contains(kind) else {
            throw PamNotificationError("Push rendering rule is invalid")
        }
        var all = UserDefaults.standard.dictionary(forKey: storeKey) ?? [:]
        all[key((rule["field"] as? String) ?? "type", type, rule["where"] as? [String: Any], rule["requires"] as? [String])] = ruleJson
        UserDefaults.standard.set(all, forKey: storeKey)
        if rule["category"] is [String: Any] { PamNotificationCategories.register() }
    }

    /// Every registered rule.
    static func rules() -> [[String: Any]] {
        (UserDefaults.standard.dictionary(forKey: storeKey) ?? [:]).values
            .compactMap { ($0 as? String).flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any] } }
    }

    /// The rule for this push: type field plus every `where` condition; the most specific wins.
    static func select(_ rules: [[String: Any]], data: [String: Any]) -> [String: Any]? {
        rules.filter { candidate in
            let field = (candidate["field"] as? String) ?? "type"
            guard let value = data[field], "\(value)" == (candidate["type"] as? String) else { return false }
            let matches = ((candidate["where"] as? [String: Any]) ?? [:]).allSatisfy { key, expected in
                data[key].map { "\($0)" } == "\(expected)"
            }
            let present = ((candidate["requires"] as? [String]) ?? []).allSatisfy { key in
                guard let value = data[key], !(value is NSNull) else { return false }
                return !"\(value)".trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
            return matches && present
        }.max { specificity($0) < specificity($1) }
    }

    private static func specificity(_ rule: [String: Any]) -> Int {
        ((rule["where"] as? [String: Any])?.count ?? 0) + ((rule["requires"] as? [String])?.count ?? 0)
    }

    static func forget(field: String, type: String) {
        var all = UserDefaults.standard.dictionary(forKey: storeKey) ?? [:]
        let prefix = key(field, type, nil, nil)
        for name in all.keys where name == prefix || name.hasPrefix(prefix + "\u{0}") {
            all.removeValue(forKey: name)
        }
        UserDefaults.standard.set(all, forKey: storeKey)
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: storeKey)
    }

    /// Returns true when a rule rendered (or intentionally dismissed/suppressed) the push.
    public static func render(id: String, title: String, body: String, dataJson: String) -> Bool {
        guard let data = try? JSONSerialization.jsonObject(with: Data(dataJson.utf8)) as? [String: Any] else { return false }
        guard let rule = select(rules(), data: data) else { return false }
        var variables = PamNotificationTemplate.variables(dataJson)
        variables["_id"] = id
        variables["_title"] = title
        variables["_body"] = body
        return apply(rule, variables: variables, dataJson: dataJson, id: id)
    }

    private static func apply(_ rule: [String: Any], variables: [String: String], dataJson: String, id: String) -> Bool {
        func text(_ template: Any?) -> String {
            guard let template = template as? String, !template.isEmpty else { return "" }
            return PamNotificationTemplate.render(template, variables).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let suppress = rule["suppress"] as? [[String: Any]]
        switch (rule["kind"] as? NSNumber)?.intValue {
        case kindDismiss:
            let key = text((rule["conversation"] as? [String: Any])?["key"])
            guard !key.isEmpty else { return false }
            PamConversationNotifications.cancel(key: key)
            return true
        case kindConversation:
            if PamActiveRoute.matches(suppress, variables) { return true }
            guard let conversation = rule["conversation"] as? [String: Any],
                  let message = rule["message"] as? [String: Any] else { return false }
            let key = text(conversation["key"])
            let messageText = text(message["text"])
            let sender = text(message["sender"])
            guard !key.isEmpty, !messageText.isEmpty, !sender.isEmpty,
                  key.range(of: PamConversationSpec.keyPattern, options: .regularExpression) != nil else { return false }
            var spec = PamConversationSpec(key: key)
            spec.title = text(conversation["title"])
            spec.group = ["1", "true", "yes"].contains(text(conversation["group"]).lowercased())
            let me = (rule["self"] as? String) ?? ""
            spec.me = PamConversationPerson(name: me.trimmingCharacters(in: .whitespaces).isEmpty ? "You" : me)
            spec.messages = [PamConversationMessage(
                id: text(message["id"]),
                text: String(messageText.prefix(PamConversationMessage.maxText)),
                timestamp: timestamp(text(message["timestamp"])),
                sender: PamConversationPerson(name: sender, avatar: text(message["avatar"]), key: text(message["senderKey"]))
            )]
            let reply = rule["reply"] as? [String: Any]
            let markRead = rule["markRead"] as? [String: Any]
            // hideWhen: the push says this recipient may not reply (e.g. can_reply = "0").
            let replyHidden = ((reply?["hideWhen"] as? [String: Any]) ?? [:]).contains { field, value in
                variables[field] == "\(value)"
            }
            spec.replyLabel = replyHidden ? "" : ((reply?["label"] as? String) ?? "")
            spec.replyFailures = rule["replyFailures"] as? [String: Any]
            spec.replyEndpoint = reply?["endpoint"] as? [String: Any]
            spec.markReadLabel = (markRead?["label"] as? String) ?? ""
            spec.markReadEndpoint = markRead?["endpoint"] as? [String: Any]
            spec.deepLink = text(rule["deepLink"])
            spec.dataJson = dataJson
            spec.importance = (rule["importance"] as? NSNumber)?.intValue ?? 3
            PamConversationNotifications.show(spec)
            return true
        case kindNotification:
            if PamActiveRoute.matches(suppress, variables) { return true }
            let title = text(rule["title"])
            guard !title.isEmpty else { return false }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = text(rule["body"])
            content.sound = .default
            var userInfo: [AnyHashable: Any] = [:]
            if let object = try? JSONSerialization.jsonObject(with: Data(dataJson.utf8)) { userInfo["pam.data"] = object }
            let deepLink = text(rule["deepLink"])
            if !deepLink.isEmpty { userInfo["pam.deepLink"] = deepLink }
            content.userInfo = userInfo
            UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: "pam-push-\(id.isEmpty ? UUID().uuidString : id)", content: content, trigger: nil)
            )
            return true
        default:
            return false
        }
    }

    static func timestamp(_ value: String) -> Int64 {
        let now = Int64(Date().timeIntervalSince1970 * 1_000)
        guard !value.isEmpty else { return now }
        if let integer = Int64(value) { return (1...99_999_999_999).contains(integer) ? integer * 1_000 : integer }
        if let double = Double(value) { return Int64(double < 100_000_000_000 ? double * 1_000 : double) }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: value) { return Int64(date.timeIntervalSince1970 * 1_000) }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value).map { Int64($0.timeIntervalSince1970 * 1_000) } ?? now
    }

    private static func key(_ field: String, _ type: String, _ conditions: [String: Any]?, _ requires: [String]?) -> String {
        let base = "\(field)\u{0}\(type)"
        let wheres = (conditions ?? [:]).keys.sorted().map { "\($0)=\(conditions?[$0].map { "\($0)" } ?? "")" }
        let required = (requires ?? []).sorted().map { "$" + $0 }
        let all = wheres + required
        return all.isEmpty ? base : base + all.map { "\u{0}\($0)" }.joined()
    }
}

/// Thread-safe one-shot result used to bridge callback APIs synchronously on
/// background queues.
final class PamBlockingResult<Value>: @unchecked Sendable {
    private let semaphore = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var value: Value?

    func resolve(_ value: Value?) {
        lock.lock()
        self.value = value
        lock.unlock()
        semaphore.signal()
    }

    func wait(timeout seconds: TimeInterval) -> Value? {
        guard semaphore.wait(timeout: .now() + seconds) == .success else { return nil }
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

/// Mutable background-task token shared by expiration and completion paths.
final class PamBackgroundTaskToken: @unchecked Sendable {
    private let lock = NSLock()
    private var identifier = UIBackgroundTaskIdentifier.invalid

    func begin(_ name: String) {
        let id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            self?.end()
        }
        lock.lock()
        identifier = id
        lock.unlock()
    }

    func end() {
        lock.lock()
        let id = identifier
        identifier = .invalid
        lock.unlock()
        if id != .invalid { UIApplication.shared.endBackgroundTask(id) }
    }
}
