import XCTest
@testable import PamNative

final class FilesParityTests: XCTestCase {
    func testObservedDownloadChannelDeliversOneWaiterAndBoundsEvents() {
        let channel = FileDownloadChannel()
        let pending = expectation(description: "waiting observer receives event")
        channel.next { status, payload in
            XCTAssertEqual(status, .success)
            XCTAssertEqual(payload, Data("first".utf8))
            pending.fulfill()
        }
        channel.offer(Data("first".utf8))
        wait(for: [pending], timeout: 1)
        for number in 0..<6 {
            channel.offer(Data("\(number)".utf8))
        }
        var received: [String] = []
        for _ in 0..<4 {
            channel.next { status, payload in
                XCTAssertEqual(status, .success)
                received.append(String(decoding: payload, as: UTF8.self))
            }
        }
        XCTAssertEqual(received, ["2", "3", "4", "5"])
        channel.close()
        channel.next { status, _ in XCTAssertEqual(status, .failure) }
    }

    func testFilesRejectsUnsafeObservedDownloadAndMissingPreview() throws {
        let files = FilesModule()
        let invalidURL = try WireMap.encode([
            "url": .text("http://example.test/file"),
            "path": .text("downloads/file"),
        ])
        let badURL = expectation(description: "HTTP URL rejected")
        files.invoke(method: "downloadStart", payload: invalidURL) { status, _ in
            XCTAssertEqual(status, .failure)
            badURL.fulfill()
        }
        wait(for: [badURL], timeout: 1)

        let badHeaders = expectation(description: "unsafe headers rejected")
        files.invoke(method: "downloadStart", payload: try WireMap.encode([
            "url": .text("https://example.test/file"),
            "path": .text("downloads/file"),
            "headers": .text(#"{"Host":"other.example.test"}"#),
        ])) { status, _ in
            XCTAssertEqual(status, .failure)
            badHeaders.fulfill()
        }
        wait(for: [badHeaders], timeout: 1)

        let missing = expectation(description: "missing preview rejected")
        files.invoke(method: "open", payload: try WireMap.encode([
            "path": .text("missing-document.pdf"),
            "mimeType": .text("application/pdf"),
        ])) { status, _ in
            XCTAssertEqual(status, .failure)
            missing.fulfill()
        }
        wait(for: [missing], timeout: 1)

        let unknown = expectation(description: "unknown download rejected")
        files.invoke(method: "downloadNext", payload: try WireMap.encode([
            "subscription": .integer(7),
        ])) { status, _ in
            XCTAssertEqual(status, .failure)
            unknown.fulfill()
        }
        wait(for: [unknown], timeout: 1)

        let cancelled = expectation(description: "cancel is idempotent")
        files.invoke(method: "downloadCancel", payload: try WireMap.encode([
            "subscription": .integer(7),
        ])) { status, _ in
            XCTAssertEqual(status, .success)
            cancelled.fulfill()
        }
        wait(for: [cancelled], timeout: 1)
        files.close()
    }
}
