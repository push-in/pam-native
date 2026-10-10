import XCTest
@testable import PamNative

/// iOS mirror of the Android 1.1.x native capability coverage
/// (NativeCapabilitiesInstrumentedTest). Uncompiled — needs Mac validation.
final class NativeCapabilitiesParityTests: XCTestCase {
    override func tearDown() {
        PamActiveRoute.foregroundOverride = nil
        PamPushRendering.clear()
        super.tearDown()
    }

    func testMultipartBodyLengthMatchesWrittenBytes() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root.appendingPathComponent("media"), withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try Data(repeating: 7, count: 200_000).write(to: root.appendingPathComponent("media/a.jpg"))
        let body = try HttpMultipartBody.decode(
            #"[{"type":1,"name":"caption","value":"olá \"mundo\""},{"type":2,"name":"file","path":"media/a.jpg"}]"#,
            root: root
        )
        let target = root.appendingPathComponent("body.tmp")
        try body.write(to: target) { false }
        let size = try XCTUnwrap(try target.resourceValues(forKeys: [.fileSizeKey]).fileSize)
        XCTAssertEqual(Int64(size), body.contentLength())
        let head = String(decoding: try Data(contentsOf: target).prefix(400), as: UTF8.self)
        XCTAssertTrue(head.contains("name=\"caption\""))
        XCTAssertTrue(head.contains("filename=\"a.jpg\"\r\nContent-Type: image/jpeg"))
        XCTAssertTrue(body.contentType.hasPrefix("multipart/form-data; boundary=pam-"))
        XCTAssertThrowsError(try HttpMultipartBody.decode(#"[{"type":2,"name":"f","path":"../x"}]"#, root: root))
        XCTAssertThrowsError(try HttpMultipartBody(parts: []))
    }

    func testProgressThrottleReportsWholePercentSteps() {
        var throttle = HttpTransferProgressThrottle(total: 1_000_000)
        XCTAssertTrue(throttle.shouldReport(0))
        XCTAssertFalse(throttle.shouldReport(100))
        XCTAssertTrue(throttle.shouldReport(300_000))
        XCTAssertTrue(throttle.shouldReport(1_000_000))
        XCTAssertFalse(throttle.shouldReport(1_000_000))
    }

    func testTemplateRendersPushDataAndBuiltIns() {
        let variables = PamNotificationTemplate.variables(#"{"chat":"c-1","count":3,"vip":true,"nested":{"x":1}}"#)
        XCTAssertEqual(variables["chat"], "c-1")
        XCTAssertEqual(variables["count"], "3")
        XCTAssertEqual(variables["vip"], "true")
        XCTAssertNil(variables["nested"])
        XCTAssertEqual(PamNotificationTemplate.render("/chats/{chat}/{missing}", variables), "/chats/c-1/")
        XCTAssertTrue(PamNotificationTemplate.complete("/chats/{chat}?t={now}", variables))
        XCTAssertFalse(PamNotificationTemplate.complete("/chats/{missing}", variables))
        XCTAssertEqual(
            PamNotificationTemplate.render("{reply}", ["reply": "a b"]) {
                $0.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
            },
            "a%20b"
        )
    }

    func testCredentialPlaceholdersResolveThePushAccountToken() {
        let tokens = ["user-a": "token-a", "user-b": "token-b"]
        let lookup: PamNotificationTemplate.Credentials = { tokens[PamNotificationCredentials.normalize($0)] }
        let pushA = ["user_id": " USER-A ", "recipient_user_id": "user-b"]
        XCTAssertEqual(
            PamNotificationTemplate.render("Bearer {credential:user_id|recipient_user_id}", pushA, credentials: lookup),
            "Bearer token-a"
        )
        XCTAssertEqual(
            PamNotificationTemplate.render("Bearer {credential:user_id|recipient_user_id}", ["recipient_user_id": "user-b"], credentials: lookup),
            "Bearer token-b"
        )
        // URLs and bodies never resolve credentials.
        XCTAssertEqual(PamNotificationTemplate.render("{credential:user_id}", pushA), "")
        XCTAssertFalse(PamNotificationTemplate.missingCredential("Bearer {credential:user_id}", pushA, lookup))
        XCTAssertTrue(PamNotificationTemplate.missingCredential("Bearer {credential:user_id}", ["user_id": "user-c"], lookup))
        XCTAssertTrue(PamNotificationTemplate.missingCredential("Bearer {credential:user_id}", [:], lookup))
        XCTAssertFalse(PamNotificationTemplate.missingCredential("Bearer {storage:auth.token}", [:], lookup))
        XCTAssertTrue(PamNotificationTemplate.complete("Bearer {credential:user_id}", [:]))
        let endpoint: [String: Any] = [
            "method": "POST",
            "url": "https://api.test/chats/{chat_id}/messages",
            "headers": ["Authorization": "Bearer {credential:user_id}"],
        ]
        XCTAssertFalse(PamNotificationEndpoint.missingCredential(endpoint, variables: pushA, credentials: lookup))
        XCTAssertTrue(PamNotificationEndpoint.missingCredential(endpoint, variables: ["user_id": "user-c"], credentials: lookup))
    }

    func testNotificationCredentialsArePerAccount() throws {
        let previous = PamNotificationCredentials.store
        let memory = MemoryCredentialStore()
        PamNotificationCredentials.store = memory
        defer { PamNotificationCredentials.store = previous }
        try PamNotificationCredentials.set(account: " User-A ", token: "token-a")
        try PamNotificationCredentials.set(account: "user-b", token: "token-b")
        XCTAssertEqual(memory.values.count, 2)
        XCTAssertTrue(memory.values.keys.allSatisfy { $0.count == 64 && !$0.contains("user") })
        XCTAssertEqual(PamNotificationCredentials.token("USER-A"), "token-a")
        PamNotificationCredentials.remove(account: "user-a")
        XCTAssertNil(PamNotificationCredentials.token("user-a"))
        try PamNotificationCredentials.replace(["user-c": "token-c"])
        XCTAssertNil(PamNotificationCredentials.token("user-b"))
        XCTAssertEqual(PamNotificationCredentials.token("user-c"), "token-c")
        XCTAssertThrowsError(try PamNotificationCredentials.set(account: "", token: "x"))
        XCTAssertThrowsError(try PamNotificationCredentials.set(account: "a", token: "x\ny"))
    }

    func testNotificationCredentialsKeychainRoundTrip() throws {
        let store = PamNotificationCredentials.KeychainStore()
        let slot = PamNotificationCredentials.slot("xctest-\(UUID().uuidString)")
        do {
            try store.write(slot, "token-k")
        } catch {
            throw XCTSkip("Keychain unavailable in this test host: \(error)")
        }
        defer { store.delete(slot) }
        XCTAssertEqual(store.read(slot), "token-k")
        store.delete(slot)
        XCTAssertNil(store.read(slot))
    }

    func testActiveRouteSuppressesOnlyMatchingForegroundRoute() {
        PamActiveRoute.update(name: "chat", paramsJson: #"{"id":"c-1"}"#, path: "/chats/c-1?tab=media")
        let variables = ["chat": "c-1"]
        let byParams: [[String: Any]] = [["route": "chat", "params": ["id": "{chat}"]]]
        let byPath: [[String: Any]] = [["path": "/chats/{chat}/"]]
        PamActiveRoute.foregroundOverride = false
        XCTAssertFalse(PamActiveRoute.matches(byParams, variables))
        PamActiveRoute.foregroundOverride = true
        XCTAssertTrue(PamActiveRoute.matches(byParams, variables))
        XCTAssertTrue(PamActiveRoute.matches(byPath, variables))
        XCTAssertFalse(PamActiveRoute.matches(byParams, ["chat": "c-2"]))
        XCTAssertFalse(PamActiveRoute.matches([["path": "/chats/{other}"]], variables))
    }

    func testPushRenderingRuleSuppressedForOpenConversation() throws {
        try PamPushRendering.register(#"""
        {"type":"message","kind":1,"field":"type",
         "conversation":{"key":"{chat}","title":"{chatName}"},
         "message":{"id":"{message_id}","text":"{text}","sender":"{senderName}"},
         "suppress":[{"route":"chat","params":{"id":"{chat}"}}]}
        """#)
        PamActiveRoute.update(name: "chat", paramsJson: #"{"id":"c-1"}"#, path: "")
        PamActiveRoute.foregroundOverride = true
        let data = #"{"type":"message","chat":"c-1","text":"oi","senderName":"Ana","message_id":"m1"}"#
        XCTAssertTrue(PamPushRendering.render(id: "p1", title: "", body: "", dataJson: data))
        XCTAssertFalse(PamPushRendering.render(id: "p2", title: "", body: "", dataJson: #"{"type":"other"}"#))
        XCTAssertThrowsError(try PamPushRendering.register(#"{"type":"","kind":1}"#))
    }

    func testPushTimestampsAcceptSecondsMillisAndIso() {
        XCTAssertEqual(PamPushRendering.timestamp("1700000000"), 1_700_000_000_000)
        XCTAssertEqual(PamPushRendering.timestamp("1700000000000"), 1_700_000_000_000)
        XCTAssertEqual(PamPushRendering.timestamp("2023-11-14T22:13:20Z"), 1_700_000_000_000)
    }

    func testReplyFailuresResolveStatusesServerMessagesAndOffline() {
        let config: [String: Any] = [
            "offline": ["text": "Sem conexão.", "retry": true, "keepReply": true, "serverMessage": false],
            "statuses": [
                ["codes": [401], "text": "Sua sessão expirou.", "retry": false, "keepReply": false, "serverMessage": false],
                ["codes": [422], "text": "Não foi possível enviar.", "retry": true, "keepReply": true, "serverMessage": true],
            ],
            "otherwise": ["text": "O servidor não respondeu.", "retry": true, "keepReply": true, "serverMessage": false],
        ]
        XCTAssertEqual(PamReplyFailure.resolve(config, status: 0, message: nil), PamReplyFailure(text: "Sem conexão.", retry: true, keepReply: true))
        XCTAssertEqual(PamReplyFailure.resolve(config, status: 401, message: "x"), PamReplyFailure(text: "Sua sessão expirou.", retry: false, keepReply: false))
        XCTAssertEqual(PamReplyFailure.resolve(config, status: 422, message: "Aguarde 10 s."), PamReplyFailure(text: "Aguarde 10 s.", retry: true, keepReply: true))
        XCTAssertEqual(PamReplyFailure.resolve(config, status: 503, message: nil).text, "O servidor não respondeu.")
        let id = Int64(PamReplyFailure.createActionId(now: 1_700_000_000_000))!
        XCTAssertTrue((1_700_000_000_000_000...1_700_000_000_000_999).contains(id))
    }

    func testRuleCategoriesAndMostSpecificMatch() {
        let message: [String: Any] = [
            "type": "1", "field": "chat_type",
            "reply": ["label": "Responder", "hideWhen": ["can_reply": "0"]],
            "markRead": ["label": "Marcar como lida"],
            "replyFailures": ["retryLabel": "Tentar de novo"],
            "category": ["identifier": "ZE_MESSAGE_REPLY", "withoutReply": "ZE_MESSAGE"],
        ]
        let invite: [String: Any] = [
            "type": "5", "where": ["system_event": "post_collaboration_invite"], "requires": ["collaboration_invite_id"],
            "actions": [["id": "decline", "label": "Recusar"], ["id": "accept", "label": "Aceitar"]],
            "category": ["identifier": "POST_COLLABORATION_INVITE", "withoutReply": ""],
        ]
        let generic: [String: Any] = ["type": "5"]
        let plan = PamNotificationCategories.plan([message, invite, generic])
        XCTAssertEqual(plan["ZE_MESSAGE_REPLY"], [PamConversationNotifications.replyAction, PamConversationNotifications.markReadAction])
        XCTAssertEqual(plan["ZE_MESSAGE"], [PamConversationNotifications.markReadAction])
        XCTAssertEqual(plan["ZE_MESSAGE_REPLY.pam-failed-retry"], [PamNotificationCategories.retryAction, PamConversationNotifications.replyAction])
        XCTAssertEqual(plan["POST_COLLABORATION_INVITE"], ["pam.button.decline", "pam.button.accept"])
        let rules = [generic, invite]
        XCTAssertNotNil(PamPushRendering.select(rules, data: ["type": 5, "system_event": "post_collaboration_invite", "collaboration_invite_id": "9"])?["where"])
        XCTAssertNil(PamPushRendering.select(rules, data: ["type": 5, "system_event": "post_collaboration_invite"])?["where"])
        XCTAssertNil(PamPushRendering.select(rules, data: ["type": "5", "system_event": "admin"])?["where"])
    }

    func testConversationHistoryMergesByIdAndKeepsLatest() throws {
        let first = try PamConversationSpec.from([
            "key": "c-1",
            "messages": [
                ["id": "m1", "text": "um", "timestamp": 1_000, "sender": ["name": "Ana"]],
                ["id": "m2", "text": "dois", "timestamp": 2_000, "sender": ["name": "Ana"]],
            ],
        ])
        let update = try PamConversationSpec.from([
            "key": "c-1",
            "replyLabel": "Responder",
            "messages": [
                ["id": "m2", "text": "dois (editada)", "timestamp": 2_000, "sender": ["name": "Ana"]],
                ["id": "m3", "text": "três", "timestamp": 3_000],
            ],
        ])
        let merged = PamConversationSpec.merge(previous: first, incoming: update)
        XCTAssertEqual(merged.messages.map(\.id), ["m1", "m2", "m3"])
        XCTAssertEqual(merged.messages[1].text, "dois (editada)")
        XCTAssertNil(merged.messages[2].sender)
        XCTAssertEqual(merged.replyLabel, "Responder")
        XCTAssertThrowsError(try PamConversationSpec.from(["key": "bad key!"]))
    }

    func testCategoryIdentifierIsStablePerLabelPair() {
        let a = PamConversationNotifications.categoryIdentifier(replyLabel: "Responder", markReadLabel: "Lida")
        XCTAssertEqual(a, PamConversationNotifications.categoryIdentifier(replyLabel: "Responder", markReadLabel: "Lida"))
        XCTAssertNotEqual(a, PamConversationNotifications.categoryIdentifier(replyLabel: "Reply", markReadLabel: "Lida"))
        XCTAssertEqual(PamConversationNotifications.categoryIdentifier(replyLabel: "", markReadLabel: ""), "")
    }

    func testActionQueueDeliversQueuedEventsInOrder() {
        PamNotificationActions.report(type: 1, key: "c-1", text: "oi", dataJson: "{}", deepLink: "", status: 200)
        PamNotificationActions.report(type: 2, key: "c-1", text: "", dataJson: "{}", deepLink: "", status: -1)
        var types: [Int64] = []
        var handled: [Bool] = []
        for _ in 0..<2 {
            let next = expectation(description: "action")
            PamNotificationActions.next { status, payload in
                XCTAssertEqual(status, .success)
                let values = (try? WireMap.decode(payload)) ?? [:]
                if case let .integer(type)? = values["type"] { types.append(type) }
                if case let .flag(flag)? = values["handledNatively"] { handled.append(flag) }
                next.fulfill()
            }
            wait(for: [next], timeout: 2)
        }
        XCTAssertEqual(types.suffix(2), [1, 2])
        XCTAssertEqual(handled.suffix(2), [true, false])
    }

    func testScreenShieldOnlyWhileSecureAndCapturedOrInactive() {
        XCTAssertFalse(PamScreenSecurity.shouldShield(enabled: false, captured: true, inactive: true))
        XCTAssertFalse(PamScreenSecurity.shouldShield(enabled: true, captured: false, inactive: false))
        XCTAssertTrue(PamScreenSecurity.shouldShield(enabled: true, captured: true, inactive: false))
        XCTAssertTrue(PamScreenSecurity.shouldShield(enabled: true, captured: false, inactive: true))
    }

    func testWindowModuleReportsCaptureProtection() {
        let done = expectation(description: "secure")
        WindowModule().invoke(method: "secure", payload: (try? WireMap.encode(["enabled": .flag(true)])) ?? Data()) { status, payload in
            XCTAssertEqual(status, .success)
            let values = (try? WireMap.decode(payload)) ?? [:]
            XCTAssertEqual(values["supported"], .flag(true))
            XCTAssertEqual(values["enabled"], .flag(true))
            XCTAssertEqual(values["screenshotsBlocked"], .flag(false))
            done.fulfill()
        }
        wait(for: [done], timeout: 2)
        PamScreenSecurity.shared.setEnabled(false)
    }
}

private final class MemoryCredentialStore: PamNotificationCredentials.Store {
    var values: [String: String] = [:]
    func read(_ slot: String) -> String? { values[slot] }
    func write(_ slot: String, _ token: String) throws { values[slot] = token }
    func delete(_ slot: String) { values[slot] = nil }
    func deleteAll() { values.removeAll() }
}
