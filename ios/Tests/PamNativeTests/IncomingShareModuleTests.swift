import UIKit
import XCTest
@testable import PamNative

final class IncomingShareModuleTests: XCTestCase {
    func testInitialConsumesOnlyFirstShareAndCopiesFilesIntoPrivateSandbox() throws {
        let suite = "dev.pam.incoming-share-test.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let base = FileManager.default.temporaryDirectory
            .appendingPathComponent("pam-incoming-test-\(UUID().uuidString)")
        let groupRoot = base.appendingPathComponent("group")
        let privateRoot = base.appendingPathComponent("private")
        let inbox = groupRoot.appendingPathComponent("pam-share-inbox")
        try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        defer {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: base)
        }
        let fileName = UUID().uuidString
        try Data("photo bytes".utf8).write(to: inbox.appendingPathComponent(fileName))
        defaults.set([
            ["kind": 1, "value": "Olá 🌎", "mimeType": "text/plain", "shareId": "first", "subject": "Picture", "createdAtMillis": 1],
            ["kind": 3, "value": fileName, "name": "original.jpg", "mimeType": "public.jpeg", "shareId": "first", "createdAtMillis": 1],
            ["kind": 1, "value": "next share", "mimeType": "text/plain", "shareId": "second", "createdAtMillis": 1],
        ], forKey: "pam.share.items")
        let module = IncomingShareModule(
            groupName: suite, groupRoot: groupRoot, privateRoot: privateRoot
        )
        defer { module.close() }

        let first = expectation(description: "first share")
        module.invoke(method: "initial", payload: Data()) { status, payload in
            defer { first.fulfill() }
            XCTAssertEqual(status, .success)
            do {
                let values = try WireMap.decode(payload)
                XCTAssertEqual(values["available"], .flag(true))
                XCTAssertEqual(values["text"], .text("Olá 🌎"))
                XCTAssertEqual(values["subject"], .text("Picture"))
                let json = try XCTUnwrap(values["files"])
                guard case let .text(rawFiles) = json,
                      let files = try JSONSerialization.jsonObject(
                        with: Data(rawFiles.utf8)
                      ) as? [[String: Any]],
                      let file = files.first,
                      let path = file["path"] as? String else {
                    XCTFail("Incoming file reference is missing")
                    return
                }
                XCTAssertEqual(files.count, 1)
                XCTAssertEqual(file["mimeType"] as? String, "image/jpeg")
                XCTAssertEqual(file["name"] as? String, "original.jpg")
                XCTAssertEqual(try Data(contentsOf: privateRoot.appendingPathComponent(path)),
                               Data("photo bytes".utf8))
                XCTAssertFalse(FileManager.default.fileExists(
                    atPath: inbox.appendingPathComponent(fileName).path
                ))
            } catch { XCTFail("Cannot decode first share: \(error)") }
        }
        wait(for: [first], timeout: 2)

        let second = expectation(description: "next share")
        module.invoke(method: "next", payload: Data()) { status, payload in
            XCTAssertEqual(status, .success)
            XCTAssertEqual(try? WireMap.decode(payload)["text"], .text("next share"))
            second.fulfill()
        }
        wait(for: [second], timeout: 2)
    }

    func testPendingListenerReceivesShareWhenAppBecomesActive() throws {
        let suite = "dev.pam.incoming-share-test.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let module = IncomingShareModule(groupName: suite)
        defer { module.close() }
        let received = expectation(description: "share after activation")
        module.invoke(method: "next", payload: Data()) { status, payload in
            XCTAssertEqual(status, .success)
            XCTAssertEqual(try? WireMap.decode(payload)["text"], .text("warm share"))
            received.fulfill()
        }
        let armed = expectation(description: "listener is armed")
        module.invoke(method: "next", payload: Data()) { status, _ in
            XCTAssertEqual(status, .failure)
            armed.fulfill()
        }
        wait(for: [armed], timeout: 2)
        defaults.set([
            ["kind": 1, "value": "warm share", "mimeType": "text/plain", "createdAtMillis": 3],
        ], forKey: "pam.share.items")
        NotificationCenter.default.post(
            name: UIApplication.didBecomeActiveNotification, object: nil
        )
        wait(for: [received], timeout: 2)
    }
}
