import Foundation
import XCTest
@testable import PamNative

final class ImagePrefetchModuleTests: XCTestCase {
    private func module() -> ImagePrefetchModule {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StalledPrefetchProtocol.self]
        return ImagePrefetchModule(session: URLSession(configuration: configuration), initiallyActive: true)
    }

    private func payload(_ urls: [String], headers: String = "{}", priority: Int64 = 2) throws -> Data {
        try WireMap.encode([
            "urls": .text(String(decoding: JSONSerialization.data(withJSONObject: urls), as: UTF8.self)),
            "headers": .text(headers),
            "priority": .integer(priority),
        ])
    }

    func testHigherPriorityDuplicatePromotesQueuedJobWithoutDuplicatingWork() throws {
        let reads = DeferredPrefetchReads()
        let module = ImagePrefetchModule(initiallyActive: true, cacheLookup: reads.read)
        defer { module.close(); reads.finishAll() }
        let root = "https://prefetch.test/\(UUID().uuidString)/"
        module.invoke(method: "prefetch", payload: try payload([root + "block-a", root + "block-b"])) { _, _ in }
        let completed = expectation(description: "four callers share two queued jobs")
        completed.expectedFulfillmentCount = 4
        let done: ModuleCompletion = { status, data in
            XCTAssertEqual(status, .success)
            XCTAssertEqual(try? WireMap.decode(data)["succeeded"], .integer(1))
            completed.fulfill()
        }
        module.invoke(method: "prefetch", payload: try payload([root + "shared"], priority: 1), completion: done)
        module.invoke(method: "prefetch", payload: try payload([root + "medium"], priority: 3), completion: done)
        module.invoke(method: "prefetch", payload: try payload([root + "shared"], priority: 5), completion: done)
        module.invoke(method: "prefetch", payload: try payload([root + "shared"], priority: 1), completion: done)
        XCTAssertEqual(reads.count, 2)
        reads.finish(0, data: Data([1]))
        XCTAssertEqual(reads.startedSources.last, root + "shared")
        reads.finish(2, data: Data([1]))
        XCTAssertEqual(reads.startedSources.last, root + "medium")
        reads.finish(3, data: Data([1]))
        reads.finish(1, data: Data([1]))
        wait(for: [completed], timeout: 1)
        XCTAssertEqual(reads.startedSources.filter { $0 == root + "shared" }.count, 1)
    }

    func testInactiveInitializationAndRapidResumeWaitForRetiredSlots() throws {
        let reads = DeferredPrefetchReads()
        let module = ImagePrefetchModule(initiallyActive: false, cacheLookup: reads.read)
        defer { module.close(); reads.finishAll() }
        let root = "https://prefetch.test/\(UUID().uuidString)/"
        let hidden = expectation(description: "initially hidden call rejected")
        module.invoke(method: "prefetch", payload: try payload([root + "hidden"])) { _, data in
            XCTAssertEqual(try? WireMap.decode(data)["failed"], .integer(1))
            hidden.fulfill()
        }
        wait(for: [hidden], timeout: 1)
        XCTAssertEqual(reads.count, 0)
        module.setActive(true)
        let first = expectation(description: "old batch canceled once")
        module.invoke(method: "prefetch", payload: try payload([root + "a", root + "b"])) { _, data in
            XCTAssertEqual(try? WireMap.decode(data)["failed"], .integer(2))
            first.fulfill()
        }
        XCTAssertEqual(reads.count, 2)
        module.setActive(false)
        wait(for: [first], timeout: 1)
        module.setActive(true)
        let second = expectation(description: "new batch canceled once")
        module.invoke(method: "prefetch", payload: try payload([root + "c", root + "d"])) { _, data in
            XCTAssertEqual(try? WireMap.decode(data)["failed"], .integer(2))
            second.fulfill()
        }
        XCTAssertEqual(reads.count, 2, "retired jobs still occupy both slots until their callback drains")
        reads.finish(0)
        XCTAssertEqual(reads.count, 3, "exactly one slot becomes reusable")
        reads.finish(1)
        XCTAssertEqual(reads.count, 4)
        DispatchQueue.concurrentPerform(iterations: 8) { _ in module.close() }
        wait(for: [second], timeout: 1)
        module.setActive(true)
        XCTAssertEqual(reads.count, 4, "closed module cannot resume")
    }

    func testDuplicateIdentitySharesSlotsAndAllSubscribersSettleOnPause() throws {
        let module = module()
        defer { module.close(); StalledPrefetchProtocol.observe(nil) }
        let root = "https://prefetch.test/\(UUID().uuidString)"
        let started = expectation(description: "only two distinct downloads start")
        started.expectedFulfillmentCount = 2
        StalledPrefetchProtocol.observe { _ in started.fulfill() }
        let settled = expectation(description: "all three callers settle once")
        settled.expectedFulfillmentCount = 3
        let completion: ModuleCompletion = { status, data in
            XCTAssertEqual(status, .success)
            XCTAssertEqual(try? WireMap.decode(data)["failed"], .integer(1))
            settled.fulfill()
        }
        module.invoke(method: "prefetch", payload: try payload([root + "/same"], headers: #"{"B":"2","A":"1"}"#), completion: completion)
        module.invoke(method: "prefetch", payload: try payload([root + "/same"], headers: #"{"A":"1","B":"2"}"#), completion: completion)
        module.invoke(method: "prefetch", payload: try payload([root + "/different"]), completion: completion)
        wait(for: [started], timeout: 3)
        module.setActive(false)
        wait(for: [settled], timeout: 3)
    }

    func testPendingBudgetOverflowAndBackgroundDoNotKeepRequestsAliveAndSessionResumes() throws {
        let module = module()
        defer { module.close(); StalledPrefetchProtocol.observe(nil) }
        let root = "https://prefetch.test/\(UUID().uuidString)/"
        let started = expectation(description: "two worker slots")
        started.expectedFulfillmentCount = 2
        StalledPrefetchProtocol.observe { _ in started.fulfill() }
        let batches = expectation(description: "two batches canceled exactly once")
        batches.expectedFulfillmentCount = 2
        let completion: ModuleCompletion = { status, data in
            XCTAssertEqual(status, .success)
            XCTAssertEqual(try? WireMap.decode(data)["failed"], .integer(100))
            batches.fulfill()
        }
        module.invoke(method: "prefetch", payload: try payload((0..<100).map { root + String($0) }), completion: completion)
        wait(for: [started], timeout: 3)
        module.invoke(method: "prefetch", payload: try payload((100..<200).map { root + String($0) }), completion: completion)
        let overflow = expectation(description: "overflow completes without enqueue")
        module.invoke(method: "prefetch", payload: try payload([root + "overflow"])) { status, data in
            XCTAssertEqual(status, .success)
            XCTAssertEqual(try? WireMap.decode(data)["failed"], .integer(1))
            overflow.fulfill()
        }
        wait(for: [overflow], timeout: 1)
        module.setActive(false)
        wait(for: [batches], timeout: 3)
        let hidden = expectation(description: "background request fails without download")
        module.invoke(method: "prefetch", payload: try payload([root + "hidden"])) { _, data in
            XCTAssertEqual(try? WireMap.decode(data)["failed"], .integer(1))
            hidden.fulfill()
        }
        wait(for: [hidden], timeout: 1)
        let resumed = expectation(description: "same session can download after resume")
        StalledPrefetchProtocol.observe { _ in resumed.fulfill() }
        module.setActive(true)
        let closed = expectation(description: "close settles resumed request")
        module.invoke(method: "prefetch", payload: try payload([root + "resumed"])) { _, data in
            XCTAssertEqual(try? WireMap.decode(data)["failed"], .integer(1))
            closed.fulfill()
        }
        wait(for: [resumed], timeout: 3)
        module.close()
        wait(for: [closed], timeout: 3)
    }
}

private final class StalledPrefetchProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private static var onStart: ((URL?) -> Void)?
    static func observe(_ callback: ((URL?) -> Void)?) {
        lock.lock()
        onStart = callback
        lock.unlock()
    }
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "prefetch.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock()
        let callback = Self.onStart
        Self.lock.unlock()
        callback?(request.url)
    }
    override func stopLoading() {}
}

private final class DeferredPrefetchReads: @unchecked Sendable {
    private let lock = NSLock()
    private var callbacks: [(@Sendable (Data?) -> Void)?] = []
    private var sources: [String] = []
    var startedSources: [String] {
        lock.lock()
        defer { lock.unlock() }
        return sources
    }
    var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return callbacks.count
    }
    func read(_ source: String, _ key: String?, _ completion: @escaping @Sendable (Data?) -> Void) {
        lock.lock()
        callbacks.append(completion)
        sources.append(source)
        lock.unlock()
    }
    func finish(_ index: Int, data: Data? = nil) {
        lock.lock()
        let callback = callbacks[index]
        callbacks[index] = nil
        lock.unlock()
        callback?(data)
    }
    func finishAll() {
        for index in 0..<count { finish(index) }
    }
}
