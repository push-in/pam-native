import Foundation
import UIKit
import UserNotifications

// iOS side of PushRenderingRule::category() / reply(hideWhen:) / replyFailures()
// / action() (Android: PamPushRendering, PamNotificationActions). APNs alert
// pushes carry the category the server chose; the framework registers each
// rule's category with its reply (UNTextInputNotificationAction), mark-as-read
// and buttons, answers them natively (app killed or not) with the rule's
// endpoints and, when a reply fails, keeps the text in the tray with the
// reason, a retry that reuses {action_id}/{uuid} and the draft field for the
// opened app.

/// What a failed inline reply shows, resolved from a ReplyFailures config.
struct PamReplyFailure: Equatable {
    let text: String
    let retry: Bool
    let keepReply: Bool

    /// `status` <= 0: no HTTP response; a missing credential counts as 401.
    static func resolve(_ config: [String: Any], status: Int, message: String?) -> PamReplyFailure {
        let entry: [String: Any]
        if status <= 0 {
            entry = (config["offline"] as? [String: Any]) ?? [:]
        } else {
            let statuses = (config["statuses"] as? [[String: Any]]) ?? []
            entry = statuses.first { candidate in
                ((candidate["codes"] as? [Any]) ?? []).contains { ($0 as? NSNumber)?.intValue == status }
            } ?? (config["otherwise"] as? [String: Any]) ?? [:]
        }
        let serverMessage = (entry["serverMessage"] as? Bool) ?? false
        let trimmed = message?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let text = status > 0 && serverMessage && !trimmed.isEmpty && trimmed.count <= 160
            ? trimmed
            : (((entry["text"] as? String).flatMap { $0.isEmpty ? nil : $0 }) ?? "Could not send.")
        return PamReplyFailure(
            text: text,
            retry: (entry["retry"] as? Bool) ?? (status <= 0),
            keepReply: (entry["keepReply"] as? Bool) ?? (status <= 0)
        )
    }

    /// Positive, unique per reply: milliseconds × 1000 + random.
    static func createActionId(now: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)) -> String {
        String(now * 1_000 + Int64.random(in: 0..<1_000))
    }
}

public enum PamNotificationCategories {
    static let retryAction = "pam.reply.retry"
    static let buttonPrefix = "pam.button."
    static let failedRetrySuffix = ".pam-failed-retry"
    static let failedSuffix = ".pam-failed"
    static let retryTextKey = "pam.retry.text"
    static let retryActionIdKey = "pam.retry.actionId"
    static let retryUuidKey = "pam.retry.uuid"

    /// Category identifier → its action identifiers, for every registered rule.
    static func plan(_ rules: [[String: Any]]) -> [String: [String]] {
        var plan: [String: [String]] = [:]
        for rule in rules {
            guard let category = rule["category"] as? [String: Any],
                  let identifier = category["identifier"] as? String, !identifier.isEmpty else { continue }
            let hasReply = !(((rule["reply"] as? [String: Any])?["label"] as? String) ?? "").isEmpty
            let hasMarkRead = !(((rule["markRead"] as? [String: Any])?["label"] as? String) ?? "").isEmpty
            let buttons = ((rule["actions"] as? [[String: Any]]) ?? []).compactMap { $0["id"] as? String }.map { buttonPrefix + $0 }
            var actions: [String] = []
            if hasReply { actions.append(PamConversationNotifications.replyAction) }
            if hasMarkRead { actions.append(PamConversationNotifications.markReadAction) }
            plan[identifier] = actions + buttons
            if let without = category["withoutReply"] as? String, !without.isEmpty {
                plan[without] = (hasMarkRead ? [PamConversationNotifications.markReadAction] : []) + buttons
            }
            if rule["replyFailures"] is [String: Any] {
                plan[identifier + failedRetrySuffix] = [retryAction] + (hasReply ? [PamConversationNotifications.replyAction] : [])
                plan[identifier + failedSuffix] = []
            }
        }
        return plan
    }

    /// The rule whose category (or its no-reply / failure variant) is `identifier`.
    static func rule(forCategory identifier: String) -> [String: Any]? {
        guard !identifier.isEmpty else { return nil }
        return PamPushRendering.rules().first { rule in
            guard let category = rule["category"] as? [String: Any], let main = category["identifier"] as? String else { return false }
            let without = (category["withoutReply"] as? String) ?? ""
            return [main, without, main + failedRetrySuffix, main + failedSuffix].contains(identifier) && !identifier.isEmpty
        }
    }

    /// Registers the rule categories next to the ones already set (conversation categories).
    static func register() {
        let rules = PamPushRendering.rules()
        let categoryPlan = Self.plan(rules)
        let center = UNUserNotificationCenter.current()
        center.getNotificationCategories { existing in
            var next = existing.filter { categoryPlan[$0.identifier] == nil }
            for (identifier, actionIds) in categoryPlan {
                guard let owner = Self.rule(in: rules, category: identifier) else { continue }
                let actions: [UNNotificationAction] = actionIds.compactMap { Self.action(for: $0, rule: owner) }
                next.insert(UNNotificationCategory(identifier: identifier, actions: actions, intentIdentifiers: [], options: []))
            }
            center.setNotificationCategories(next)
        }
    }

    private static func rule(in rules: [[String: Any]], category identifier: String) -> [String: Any]? {
        rules.first { rule in
            guard let category = rule["category"] as? [String: Any], let main = category["identifier"] as? String else { return false }
            return [main, (category["withoutReply"] as? String) ?? "", main + failedRetrySuffix, main + failedSuffix].contains(identifier)
        }
    }

    private static func action(for identifier: String, rule: [String: Any]) -> UNNotificationAction? {
        switch identifier {
        case PamConversationNotifications.replyAction:
            let label = ((rule["reply"] as? [String: Any])?["label"] as? String) ?? "Reply"
            return UNTextInputNotificationAction(identifier: identifier, title: label, options: [], textInputButtonTitle: label, textInputPlaceholder: "")
        case PamConversationNotifications.markReadAction:
            return UNNotificationAction(identifier: identifier, title: ((rule["markRead"] as? [String: Any])?["label"] as? String) ?? "Mark as read", options: [])
        case retryAction:
            return UNNotificationAction(identifier: identifier, title: ((rule["replyFailures"] as? [String: Any])?["retryLabel"] as? String) ?? "Try again", options: [])
        default:
            guard identifier.hasPrefix(buttonPrefix) else { return nil }
            let id = String(identifier.dropFirst(buttonPrefix.count))
            let button = ((rule["actions"] as? [[String: Any]]) ?? []).first { ($0["id"] as? String) == id }
            return button.map { UNNotificationAction(identifier: identifier, title: ($0["label"] as? String) ?? id, options: []) }
        }
    }

    /// Push data of a delivered APNs notification (top-level keys without `aps`, plus `pam.data`).
    static func data(_ userInfo: [AnyHashable: Any]) -> [String: Any] {
        var data: [String: Any] = [:]
        for (key, value) in userInfo {
            guard let key = key as? String, key != "aps" else { continue }
            data[key] = value
        }
        if let nested = userInfo["pam.data"] as? [String: Any] {
            data.merge(nested) { current, _ in current }
        }
        return data
    }

    /// Handles a response whose category belongs to a rule; false when it does not.
    static func handle(_ response: UNNotificationResponse, completion: @escaping () -> Void) -> Bool {
        let request = response.notification.request
        let content = request.content
        guard let rule = Self.rule(forCategory: content.categoryIdentifier) else { return false }
        let payload = Self.data(content.userInfo)
        let dataJson = JSONSerialization.isValidJSONObject(payload)
            ? (try? JSONSerialization.data(withJSONObject: payload)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
            : "{}"
        var variables = PamNotificationTemplate.variables(dataJson)
        let deepLink = ((rule["deepLink"] as? String) ?? "").isEmpty
            ? ""
            : PamNotificationTemplate.render((rule["deepLink"] as? String) ?? "", variables)
        let thread = content.threadIdentifier
        switch response.actionIdentifier {
        case PamConversationNotifications.replyAction, retryAction:
            let typed = (response as? UNTextInputNotificationResponse)?.userText
            let text = String((typed ?? (payload[retryTextKey] as? String) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(PamConversationMessage.maxText))
            guard !text.isEmpty else { completion(); return true }
            let retrying = typed == nil
            let actionId = (retrying ? payload[retryActionIdKey] as? String : nil) ?? PamReplyFailure.createActionId()
            let uuid = (retrying ? payload[retryUuidKey] as? String : nil) ?? UUID().uuidString.lowercased()
            variables["reply"] = text
            variables["action_id"] = actionId
            variables["uuid"] = uuid
            let reply = rule["reply"] as? [String: Any]
            send(reply?["endpoint"] as? [String: Any], variables: variables) { status, message, missingCredential in
                let delivered = (200...299).contains(status)
                let failures = rule["replyFailures"] as? [String: Any]
                let report = { (shown: Bool) in
                    PamNotificationActions.report(
                        type: PamNotificationActionType.reply, key: thread, text: text, dataJson: dataJson,
                        deepLink: deepLink, status: missingCredential ? -1 : status, missingCredential: missingCredential,
                        failureShown: shown
                    )
                    completion()
                }
                if delivered || failures == nil {
                    removeThread(thread) { report(false) }
                    return
                }
                let failure = PamReplyFailure.resolve(failures ?? [:], status: missingCredential ? 401 : status, message: message)
                postFailure(
                    failure, text: text, actionId: actionId, uuid: uuid, failures: failures ?? [:],
                    category: ((rule["category"] as? [String: Any])?["identifier"] as? String) ?? "",
                    original: content
                ) { report(true) }
            }
        case PamConversationNotifications.markReadAction:
            send((rule["markRead"] as? [String: Any])?["endpoint"] as? [String: Any], variables: variables) { status, _, missingCredential in
                let finish = {
                    PamNotificationActions.report(
                        type: PamNotificationActionType.markRead, key: thread, text: "", dataJson: dataJson,
                        deepLink: deepLink, status: missingCredential ? -1 : status, missingCredential: missingCredential
                    )
                    completion()
                }
                if (200...299).contains(status) { removeThread(thread, then: finish) } else { finish() }
            }
        default:
            guard response.actionIdentifier.hasPrefix(buttonPrefix) else { return false }
            let id = String(response.actionIdentifier.dropFirst(buttonPrefix.count))
            guard let button = ((rule["actions"] as? [[String: Any]]) ?? []).first(where: { ($0["id"] as? String) == id }) else {
                completion()
                return true
            }
            variables["action_id"] = PamReplyFailure.createActionId()
            variables["uuid"] = UUID().uuidString.lowercased()
            send(button["endpoint"] as? [String: Any], variables: variables) { status, _, missingCredential in
                let finish = {
                    PamNotificationActions.report(
                        type: PamNotificationActionType.button, key: "", text: "", dataJson: dataJson,
                        deepLink: deepLink, status: missingCredential ? -1 : status, missingCredential: missingCredential, action: id
                    )
                    completion()
                }
                if (200...299).contains(status), (button["dismiss"] as? Bool) ?? true {
                    UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [request.identifier])
                }
                finish()
            }
        }
        return true
    }

    /// (status, server message, missing credential); no endpoint = -1.
    private static func send(
        _ endpoint: [String: Any]?,
        variables: [String: String],
        completion: @escaping (Int, String?, Bool) -> Void
    ) {
        guard let endpoint else { completion(-1, nil, false); return }
        if PamNotificationEndpoint.missingCredential(endpoint, variables: variables) {
            completion(401, nil, true)
            return
        }
        PamNotificationEndpoint.sendForResult(endpoint, variables: variables) { status, message in
            completion(status, message, false)
        }
    }

    /// iOS cannot edit a delivered push: a local notification in the same thread keeps the unsent text.
    private static func postFailure(
        _ failure: PamReplyFailure,
        text: String,
        actionId: String,
        uuid: String,
        failures: [String: Any],
        category: String,
        original: UNNotificationContent,
        completion: @escaping () -> Void
    ) {
        let content = UNMutableNotificationContent()
        content.title = original.title
        content.subtitle = (failures["sender"] as? String) ?? "Not sent"
        content.body = "\(failure.text)\n“\(text)”"
        content.threadIdentifier = original.threadIdentifier
        content.sound = nil
        var userInfo = original.userInfo
        if let draftField = failures["draftField"] as? String, !draftField.isEmpty {
            userInfo[draftField] = text
        }
        userInfo[retryTextKey] = text
        userInfo[retryActionIdKey] = actionId
        userInfo[retryUuidKey] = uuid
        content.userInfo = userInfo
        content.categoryIdentifier = category.isEmpty ? "" : category + (failure.retry ? failedRetrySuffix : failedSuffix)
        let identifier = "pam.reply.failed.\(original.threadIdentifier.isEmpty ? UUID().uuidString : original.threadIdentifier)"
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil)) { _ in
            completion()
        }
    }

    /// A delivered reply or read: clear the thread's notifications, as Messages does.
    private static func removeThread(_ thread: String, then completion: @escaping () -> Void) {
        let center = UNUserNotificationCenter.current()
        guard !thread.isEmpty else { completion(); return }
        center.getDeliveredNotifications { notifications in
            center.removeDeliveredNotifications(
                withIdentifiers: notifications.filter { $0.request.content.threadIdentifier == thread }.map(\.request.identifier)
            )
            completion()
        }
    }
}
