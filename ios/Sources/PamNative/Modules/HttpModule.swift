import Foundation

public final class HttpModule: NativeModule, ClosableNativeModule, @unchecked Sendable {
    private let session: URLSession
    private let queue = DispatchQueue(label: "pam.native.http", qos: .userInitiated)
    private let stateLock = NSLock()
    private var stopped = false
    private var closed: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return stopped
    }
    private let filesRoot: URL
    private let uploadCache: URL

    public convenience init() {
        self.init(configuration: .default)
    }

    init(configuration: URLSessionConfiguration, filesRoot: URL? = nil, uploadCache: URL? = nil) {
        self.filesRoot = filesRoot ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("pam-files")
        self.uploadCache = uploadCache ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("pam-http-uploads")
        session = URLSession(configuration: configuration, delegate: HttpRedirectPolicy(), delegateQueue: nil)
    }

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        if closed {
            completion(.failure, "HTTP module is closed".data(using: .utf8) ?? Data())
            return
        }

        if method != "get" && method != "request" && method != "upload" {
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
                } else if method == "upload" {
                    requestMethod = "PUT"
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
                        if method == "upload" && Self.fileTransportHeaders.contains(name.lowercased()) {
                            throw RuntimeError("File upload headers cannot override HTTP transport fields")
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

                if method == "upload" && values["body"] != nil {
                    throw RuntimeError("File upload cannot include a text body")
                }
                if case let .text(body)? = values["body"] {
                    guard body.utf8.count <= Self.maxRequestBytes else {
                        throw RuntimeError("HTTP request body exceeds one MiB")
                    }
                    request.httpBody = body.data(using: .utf8)
                }

                let snapshot: HttpUploadSnapshot?
                if method == "upload" {
                    guard case let .text(path)? = values["path"] else { throw RuntimeError("Upload source path is required") }
                    snapshot = try HttpUploadSnapshot.create(root: self.filesRoot, cache: self.uploadCache, path: path) { self.closed }
                } else { snapshot = nil }
                let deadline = HttpUploadDeadline()
                let receive: @Sendable (Data?, URLResponse?, Error?) -> Void = { data, response, error in
                    deadline.cancel()
                    do { try snapshot?.close() } catch {
                        completion(.failure, Data("Cannot remove HTTP upload snapshot".utf8))
                        return
                    }
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
                self.stateLock.lock()
                guard !self.stopped else {
                    self.stateLock.unlock()
                    try? snapshot?.close()
                    throw RuntimeError("HTTP module is closed")
                }
                let task: URLSessionTask
                if let snapshot {
                    task = self.session.uploadTask(with: request, fromFile: snapshot.url, completionHandler: receive)
                    deadline.start(task: task, seconds: request.timeoutInterval)
                } else {
                    task = self.session.dataTask(with: request, completionHandler: receive)
                }
                task.resume()
                self.stateLock.unlock()
            } catch {
                completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
            }
        }
    }

    public func close() {
        stateLock.lock()
        if stopped { stateLock.unlock(); return }
        stopped = true
        stateLock.unlock()
        session.invalidateAndCancel()
    }

    private struct RuntimeError: LocalizedError {
        let message: String
        init(_ value: String) { self.message = value }
        var errorDescription: String? { message }
    }

    private static let allowedMethods = Set(["GET", "POST", "PUT", "PATCH", "DELETE"])
    private static let fileTransportHeaders = Set(["host", "content-length", "transfer-encoding", "connection", "trailer", "upgrade"])
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

// Keep HTTP requests bound to the URL chosen by the caller, matching Android.
private final class HttpRedirectPolicy: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

private final class HttpUploadDeadline: @unchecked Sendable {
    private let lock = NSLock()
    private var work: DispatchWorkItem?

    func start(task: URLSessionTask, seconds: TimeInterval) {
        let pending = DispatchWorkItem { [weak task] in task?.cancel() }
        lock.lock()
        work = pending
        lock.unlock()
        DispatchQueue.global().asyncAfter(deadline: .now() + seconds, execute: pending)
    }

    func cancel() {
        lock.lock()
        work?.cancel()
        work = nil
        lock.unlock()
    }
}
