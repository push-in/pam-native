import Foundation

public final class HttpModule: NativeModule, ClosableNativeModule, @unchecked Sendable {
    private let session: URLSession
    private let queue = DispatchQueue(label: "pam.native.http", qos: .userInitiated)
    private var closed = false

    public convenience init() {
        self.init(configuration: .default)
    }

    init(configuration: URLSessionConfiguration) {
        session = URLSession(configuration: configuration)
    }

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        if closed {
            completion(.failure, "HTTP module is closed".data(using: .utf8) ?? Data())
            return
        }

        if method != "get" && method != "request" {
            completion(.failure, "Unknown HTTP method".data(using: .utf8) ?? Data())
            return
        }

        queue.async {
            do {
                let values = try WireMap.decode(payload)
                guard case let .text(urlText)? = values["url"] else {
                    throw RuntimeError("HTTP URL is required")
                }
                guard
                    let url = URL(string: urlText),
                    let scheme = url.scheme?.lowercased(),
                    url.host != nil,
                    url.user == nil,
                    url.password == nil
                else {
                    throw RuntimeError("Invalid URL")
                }
                #if !DEBUG
                if scheme != "https" {
                    throw RuntimeError("HTTP requests require HTTPS")
                }
                #else
                if scheme != "https" && scheme != "http" {
                    throw RuntimeError("Unsupported URL scheme")
                }
                #endif

                var request = URLRequest(url: url)
                let requestMethod: String
                if method == "get" {
                    requestMethod = "GET"
                } else {
                    guard case let .text(value)? = values["method"] else {
                        throw RuntimeError("HTTP method is required")
                    }
                    requestMethod = value
                }
                guard Self.allowedMethods.contains(requestMethod) else {
                    throw RuntimeError("Unsupported HTTP method \(requestMethod)")
                }
                request.httpMethod = requestMethod
                request.addValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
                if case let .integer(timeoutMs)? = values["timeoutMs"] {
                    request.timeoutInterval = Double(min(120_000, max(1_000, timeoutMs))) / 1_000
                } else {
                    request.timeoutInterval = 30
                }

                if case let .text(headersText)? = values["headers"] {
                    guard
                        let headersData = headersText.data(using: .utf8),
                        let headers = try JSONSerialization.jsonObject(with: headersData)
                            as? [String: String],
                        headers.count <= 32
                    else {
                        throw RuntimeError("Invalid HTTP headers")
                    }
                    for (name, value) in headers {
                        guard
                            name.range(of: Self.safeHeaderName, options: .regularExpression) != nil,
                            value.utf8.count <= 8_192,
                            !value.contains("\r"),
                            !value.contains("\n")
                        else {
                            throw RuntimeError("Invalid HTTP header")
                        }
                        guard !Self.reservedTraceHeaders.contains(name.lowercased()) else {
                            throw RuntimeError("Trace headers require an origin-scoped context")
                        }
                        request.setValue(value, forHTTPHeaderField: name)
                    }
                }

                var traceparent: String?
                if case let .text(value)? = values["traceparent"] {
                    traceparent = value
                }
                var traceOrigin: String?
                if case let .text(value)? = values["traceOrigin"] {
                    traceOrigin = value
                }
                if traceparent != nil || traceOrigin != nil {
                    guard
                        let traceparent,
                        let traceOrigin,
                        traceparent.range(of: Self.traceparentPattern, options: .regularExpression) != nil,
                        Self.origin(of: url) == traceOrigin,
                        traceOrigin.hasPrefix("https://")
                    else {
                        throw RuntimeError("Invalid or cross-origin HTTP trace context")
                    }
                    request.setValue(traceparent, forHTTPHeaderField: "traceparent")
                }

                if case let .text(body)? = values["body"] {
                    guard body.utf8.count <= Self.maxRequestBytes else {
                        throw RuntimeError("HTTP request body exceeds one MiB")
                    }
                    request.httpBody = body.data(using: .utf8)
                }

                let dataTask = self.session.dataTask(with: request) { data, response, error in
                    if let error {
                        completion(.failure, error.localizedDescription.data(using: .utf8) ?? Data())
                        return
                    }
                    let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
                    guard let bodyData = data, bodyData.count <= 900 * 1024 else {
                        completion(.failure, "HTTP response too large".data(using: .utf8) ?? Data())
                        return
                    }
                    let body = String(data: bodyData, encoding: .utf8) ?? ""
                    do {
                        let responsePayload = try WireMap.encode([
                            "statusCode": .integer(Int64(statusCode)),
                            "body": .text(body)
                        ])
                        completion(.success, responsePayload)
                    } catch {
                        completion(.failure, "Cannot encode response".data(using: .utf8) ?? Data())
                    }
                }
                dataTask.resume()
            } catch {
                completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
            }
        }
    }

    public func close() {
        closed = true
        session.invalidateAndCancel()
    }

    private struct RuntimeError: LocalizedError {
        let message: String
        init(_ value: String) { self.message = value }
        var errorDescription: String? { message }
    }

    private static let allowedMethods = Set(["GET", "POST", "PUT", "PATCH", "DELETE"])
    private static let reservedTraceHeaders = Set(["traceparent", "tracestate"])
    private static let traceparentPattern = "^00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$"
    private static let safeHeaderName = "^[A-Za-z0-9-]{1,64}$"
    private static let maxRequestBytes = 1_048_576

    private static func origin(of url: URL) -> String? {
        guard let scheme = url.scheme?.lowercased(), let host = url.host?.lowercased() else {
            return nil
        }
        let canonicalHost = host.contains(":") ? "[\(host)]" : host
        let port = url.port.flatMap { $0 == 443 ? nil : ":\($0)" } ?? ""
        return "\(scheme)://\(canonicalHost)\(port)"
    }
}
