import Foundation
import XCTest
@testable import PamNative

/// Source-ready iOS contract tests. Execute with xcodebuild on macOS.
final class LinkingModuleTests: XCTestCase {
    override func setUp() { super.setUp(); LinkingModule().close() }
    override func tearDown() { LinkingModule().close(); super.tearDown() }

    private func take(_ method: String) -> String {
        var value: String?
        LinkingModule().invoke(method: method, payload: Data()) { status, payload in
            XCTAssertEqual(status, .success)
            let values = (try? WireMap.decode(payload)) ?? [:]
            if case let .text(url)? = values["url"] { value = url }
        }
        XCTAssertNotNil(value, "Expected synchronous queued result")
        return value ?? ""
    }

    func testColdLinkIsConsumedOnceAndFirstLaunchWins() {
        PamLinking.captureInitial(URL(string: "zechat://post/first"))
        PamLinking.captureInitial(URL(string: "zechat://post/second"))
        XCTAssertEqual(take("initialUrl"), "zechat://post/first")
        XCTAssertEqual(take("initialUrl"), "")
    }

    func testWarmAndUniversalLinksQueueBeforePHPListener() {
        PamLinking.open(URL(string: "zechat://chat/one"))
        PamLinking.open(URL(string: "https://zechat.com.br/loop/two"))
        XCTAssertEqual(take("nextUrl"), "zechat://chat/one")
        XCTAssertEqual(take("nextUrl"), "https://zechat.com.br/loop/two")
    }

    func testQueueIsBoundedAndPreservesNewestOrder() {
        for index in 0..<40 { PamLinking.open(URL(string: "zechat://post/\(index)")) }
        for index in 8..<40 { XCTAssertEqual(take("nextUrl"), "zechat://post/\(index)") }
    }

    func testOversizedLinkDoesNotConsumeWaitingListenerAndCloseSettlesIt() {
        var calls = 0
        LinkingModule().invoke(method: "nextUrl", payload: Data()) { status, _ in
            calls += 1
            XCTAssertEqual(status, .failure)
        }
        PamLinking.open(URL(string: "zechat://post/" + String(repeating: "a", count: 8192)))
        XCTAssertEqual(calls, 0)
        LinkingModule().close()
        XCTAssertEqual(calls, 1)
    }

    func testRuntimeCloseClearsColdAndWarmState() {
        PamLinking.captureInitial(URL(string: "zechat://chat/private"))
        PamLinking.open(URL(string: "zechat://chat/private"))
        LinkingModule().close()
        XCTAssertEqual(take("initialUrl"), "")
        PamLinking.open(URL(string: "zechat://chat/new-session"))
        XCTAssertEqual(take("nextUrl"), "zechat://chat/new-session")
    }
}
