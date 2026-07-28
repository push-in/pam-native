import XCTest
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
            XCTAssertEqual(request.httpBody, Data(#"{"enabled":true}"#.utf8))
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
