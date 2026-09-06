import XCTest
import Network
@testable import PamNative

final class HttpModuleTests: XCTestCase {
    override func tearDown() {
        HTTPURLProtocol.handler = nil
        super.tearDown()
    }

    func testGenericJSONRequestPreservesMethodHeadersBodyAndTimeout() throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HTTPURLProtocol.self]
        let module = HttpModule(configuration: configuration)
        let completed = expectation(description: "HTTP request completed")

        HTTPURLProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "PATCH")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-token")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            XCTAssertEqual(
                request.value(forHTTPHeaderField: "traceparent"),
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
            )
            XCTAssertEqual(try requestBody(request), Data(#"{"enabled":true}"#.utf8))
            XCTAssertEqual(request.timeoutInterval, 45, accuracy: 0.01)

            return (
                HTTPURLResponse(
                    url: try XCTUnwrap(request.url),
                    statusCode: 202,
                    httpVersion: nil,
                    headerFields: ["Content-Type": "application/json"]
                )!,
                Data(#"{"accepted":true}"#.utf8)
            )
        }

        let payload = try WireMap.encode([
            "url": .text("https://api.example.test/resource"),
            "method": .text("PATCH"),
            "headers": .text(
                #"{"Authorization":"Bearer access-token","Content-Type":"application/json"}"#
            ),
            "body": .text(#"{"enabled":true}"#),
            "timeoutMs": .integer(45_000),
            "traceparent": .text(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
            ),
            "traceOrigin": .text("https://api.example.test"),
        ])

        module.invoke(method: "request", payload: payload) { status, responsePayload in
            XCTAssertEqual(status, .success)
            do {
                let response = try WireMap.decode(responsePayload)
                XCTAssertEqual(response["statusCode"], .integer(202))
                XCTAssertEqual(response["body"], .text(#"{"accepted":true}"#))
            } catch {
                XCTFail("Cannot decode HTTP response: \(error)")
            }
            completed.fulfill()
        }

        wait(for: [completed], timeout: 2)
        module.close()
    }

    func testRedirectDoesNotForwardHeadersOrBodyToAnotherRequest() throws {
        for crossOrigin in [false, true] {
            let ready = expectation(description: "Loopback HTTP server ready")
            let server = try RedirectHTTPServer(crossOrigin: crossOrigin) { ready.fulfill() }
            let module = HttpModule(configuration: .ephemeral)
            defer { module.close(); server.stop() }
            wait(for: [ready], timeout: 5)
            let completed = expectation(description: "Redirect returned without following")
            let payload = try WireMap.encode([
                "url": .text(try XCTUnwrap(server.url)),
                "method": .text("POST"),
                "headers": .text(#"{"Authorization":"Bearer test-only"}"#),
                "body": .text("private test payload"),
            ])
            module.invoke(method: "request", payload: payload) { status, response in
                defer { completed.fulfill() }
                guard status == .success else {
                    XCTFail("HTTP failed: \(String(data: response, encoding: .utf8) ?? "unknown")")
                    return
                }
                do {
                    XCTAssertEqual(try WireMap.decode(response)["statusCode"], .integer(307))
                } catch { XCTFail("Cannot decode redirect response: \(error)") }
            }
            wait(for: [completed], timeout: 10)
            XCTAssertEqual(server.requestCount, 1)
        }
    }

    func testRejectsGenericAndCrossOriginTraceHeaders() throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HTTPURLProtocol.self]
        let module = HttpModule(configuration: configuration)

        for payload in [
            try WireMap.encode([
                "url": .text("https://api.example.test/resource"),
                "method": .text("GET"),
                "headers": .text(
                    #"{"TraceParent":"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"}"#
                ),
            ]),
            try WireMap.encode([
                "url": .text("https://api.example.test/resource"),
                "method": .text("GET"),
                "traceparent": .text(
                    "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                ),
                "traceOrigin": .text("https://other.example.test"),
            ]),
        ] {
            let completed = expectation(description: "Unsafe trace context rejected")
            module.invoke(method: "request", payload: payload) { status, _ in
                XCTAssertEqual(status, .failure)
                completed.fulfill()
            }
            wait(for: [completed], timeout: 2)
        }
        module.close()
    }
}

private func requestBody(_ request: URLRequest) throws -> Data? {
    if let body = request.httpBody {
        return body
    }
    guard let stream = request.httpBodyStream else {
        return nil
    }
    stream.open()
    defer { stream.close() }
    var body = Data()
    var buffer = [UInt8](repeating: 0, count: 4_096)
    while stream.hasBytesAvailable {
        let count = stream.read(&buffer, maxLength: buffer.count)
        if count < 0 {
            throw stream.streamError ?? URLError(.cannotDecodeContentData)
        }
        if count == 0 {
            break
        }
        body.append(buffer, count: count)
    }
    return body
}

private final class HTTPURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        do {
            let (response, data) = try XCTUnwrap(Self.handler)(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

private final class RedirectHTTPServer: @unchecked Sendable {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "pam.test.http.redirect")
    private var count = 0
    private var connections: [NWConnection] = []
    private let crossOrigin: Bool

    var url: String? { listener.port.map { "http://127.0.0.1:\($0.rawValue)/upload" } }
    var requestCount: Int { queue.sync { count } }

    init(crossOrigin: Bool, ready: @escaping () -> Void) throws {
        self.crossOrigin = crossOrigin
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
        listener.stateUpdateHandler = { state in if case .ready = state { ready() } }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            self.connections.append(connection)
            connection.start(queue: self.queue)
            self.receive(connection, head: Data())
        }
        listener.start(queue: queue)
    }

    private func receive(_ connection: NWConnection, head: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, finished, error in
            guard let self, let data, error == nil else { connection.cancel(); return }
            var head = head
            head.append(data)
            guard head.count <= 16_384 else { connection.cancel(); return }
            guard head.range(of: Data("\r\n\r\n".utf8)) != nil else {
                if finished { connection.cancel() } else { self.receive(connection, head: head) }
                return
            }
            guard let port = self.listener.port else { connection.cancel(); return }
            self.count += 1
            let host = self.crossOrigin ? "localhost" : "127.0.0.1"
            let location = "http://\(host):\(port.rawValue)/redirected"
            let response = self.count == 1
                ? "HTTP/1.1 307 Temporary Redirect\r\nLocation: \(location)\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                : "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in connection.cancel() })
        }
    }

    func stop() {
        listener.cancel()
        queue.sync { connections.forEach { $0.cancel() }; connections.removeAll() }
    }
}
