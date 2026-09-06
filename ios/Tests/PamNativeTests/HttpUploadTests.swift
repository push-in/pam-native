import XCTest
import Network
import CryptoKit
@testable import PamNative

final class HttpUploadTests: XCTestCase {
    func testClosingModuleInterruptsUploadAndRemovesSnapshot() throws {
        try assertInterruptedUpload(timeout: false)
    }

    func testUploadDeadlineRemovesSnapshotWhenServerNeverResponds() throws {
        try assertInterruptedUpload(timeout: true)
    }

    private func assertInterruptedUpload(timeout: Bool) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let root = directory.appendingPathComponent("files")
        let cache = directory.appendingPathComponent("snapshots")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let source = root.appendingPathComponent("document.bin")
        try Data(repeating: 42, count: 65_536).write(to: source)
        let ready = expectation(description: "Stalled server ready")
        let received = expectation(description: "Server received upload")
        let server = try UploadHTTPServer(length: 65_536, source: source, responds: false,
                                          received: { received.fulfill() }, ready: { ready.fulfill() })
        let module = HttpModule(configuration: .ephemeral, filesRoot: root, uploadCache: cache)
        defer { module.close(); server.stop() }
        wait(for: [ready], timeout: 5)
        let completed = expectation(description: "Interrupted upload completed")
        module.invoke(method: "upload", payload: try WireMap.encode([
            "url": .text(try XCTUnwrap(server.url)), "path": .text("document.bin"),
            "headers": .text(#"{"Content-Type":"application/octet-stream"}"#),
            "timeoutMs": .integer(timeout ? 1_000 : 15_000),
        ])) { status, _ in
            XCTAssertEqual(status, .failure)
            completed.fulfill()
        }
        wait(for: [received], timeout: 5)
        if !timeout { module.close() }
        wait(for: [completed], timeout: 5)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: cache.path), [])
        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
    }

    func testStreamsTwoMiBFromStableSnapshotAndRemovesTemporaryFile() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let root = directory.appendingPathComponent("files")
        let cache = directory.appendingPathComponent("snapshots")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let source = root.appendingPathComponent("document.bin")
        let bytes = Data((0..<(2 * 1024 * 1024)).map { UInt8($0 % 251) })
        try bytes.write(to: source)
        let ready = expectation(description: "Upload server ready")
        let server = try UploadHTTPServer(length: bytes.count, source: source) { ready.fulfill() }
        let module = HttpModule(configuration: .ephemeral, filesRoot: root, uploadCache: cache)
        defer { module.close(); server.stop() }
        wait(for: [ready], timeout: 5)
        let completed = expectation(description: "File upload completed")
        module.invoke(method: "upload", payload: try WireMap.encode([
            "url": .text(try XCTUnwrap(server.url)), "path": .text("document.bin"),
            "headers": .text(#"{"Content-Type":"application/octet-stream"}"#),
            "timeoutMs": .integer(15_000),
        ])) { status, payload in
            defer { completed.fulfill() }
            guard status == .success else {
                XCTFail("Upload failed: \(String(data: payload, encoding: .utf8) ?? "unknown")")
                return
            }
            do { XCTAssertEqual(try WireMap.decode(payload)["statusCode"], .integer(204)) }
            catch { XCTFail("Invalid upload response: \(error)") }
        }
        wait(for: [completed], timeout: 20)
        XCTAssertEqual(server.digest, SHA256.hash(data: bytes))
        XCTAssertEqual(try Data(contentsOf: source), Data("changed original".utf8))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: cache.path), [])
    }
}

private final class UploadHTTPServer: @unchecked Sendable {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "pam.test.http.upload")
    private let length: Int
    private let source: URL
    private let responds: Bool
    private let onReceived: () -> Void
    private var received = Data()
    private var headersRead = false
    private var connections: [NWConnection] = []

    var url: String? { listener.port.map { "http://127.0.0.1:\($0.rawValue)/upload" } }
    var digest: SHA256.Digest { queue.sync { SHA256.hash(data: received) } }

    init(length: Int, source: URL, responds: Bool = true,
         received: @escaping () -> Void = {}, ready: @escaping () -> Void) throws {
        self.length = length
        self.source = source
        self.responds = responds
        self.onReceived = received
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
        listener.stateUpdateHandler = { state in if case .ready = state { ready() } }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            self.connections.append(connection)
            connection.start(queue: self.queue)
            self.read(connection)
        }
        listener.start(queue: queue)
    }

    private func read(_ connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, finished, error in
            guard let self, let data, error == nil else { connection.cancel(); return }
            self.received.append(data)
            guard self.received.count <= self.length + 16_384 else { connection.cancel(); return }
            if !self.headersRead {
                guard let end = self.received.range(of: Data("\r\n\r\n".utf8)) else {
                    if finished { connection.cancel() } else { self.read(connection) }
                    return
                }
                let head = String(decoding: self.received[..<end.lowerBound], as: UTF8.self)
                let lines = head.components(separatedBy: "\r\n")
                XCTAssertEqual(lines.first, "PUT /upload HTTP/1.1")
                var headers: [String: String] = [:]
                for line in lines.dropFirst() {
                    if let colon = line.firstIndex(of: ":") {
                        headers[line[..<colon].lowercased()] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                    }
                }
                XCTAssertEqual(headers["content-length"], String(self.length))
                XCTAssertEqual(headers["content-type"], "application/octet-stream")
                XCTAssertNil(headers["transfer-encoding"])
                self.received.removeSubrange(..<end.upperBound)
                self.headersRead = true
                do { try Data("changed original".utf8).write(to: self.source) }
                catch { XCTFail("Could not replace source: \(error)") }
            }
            if self.received.count == self.length {
                self.onReceived()
                guard self.responds else { return }
                let response = Data("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8)
                connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
            } else if finished {
                connection.cancel()
            } else { self.read(connection) }
        }
    }

    func stop() {
        listener.cancel()
        queue.sync { connections.forEach { $0.cancel() }; connections.removeAll() }
    }
}
