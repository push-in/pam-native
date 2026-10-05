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
    private let transfers = HttpTransferRegistry()

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

        if method == "transferStart" || method == "transferNext" || method == "transferCancel" {
            transfer(method: method, payload: payload, completion: completion)
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
                    var headers: [String: String] = [:]
                    for (name, value) in (response as? HTTPURLResponse)?.allHeaderFields ?? [:] {
                        if let name = name as? String, headers.count < 64 {
                            headers[name.lowercased()] = "\(value)"
                        }
                    }
                    let headersJson = (try? JSONSerialization.data(withJSONObject: headers))
                        .map { String(decoding: $0, as: UTF8.self) } ?? "{}"
                    do {
                        let responsePayload = try WireMap.encode([
                            "statusCode": .integer(Int64(statusCode)),
                            "body": .text(body),
                            "headers": .text(headersJson),
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
        transfers.cancelAll()
        session.invalidateAndCancel()
    }

    // MARK: Streamed transfers (Http::multipart(), upload progress/cancel)

    private static let transferHttpMethods = Set(["POST", "PUT", "PATCH"])
    private static let transferMultipart: Int64 = 1

    private func transfer(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            if method == "transferStart" {
                try startTransfer(values, completion: completion)
                return
            }
            guard case let .integer(id)? = values["transfer"] else { throw RuntimeError("HTTP transfer id is required") }
            if method == "transferCancel" {
                transfers.remove(Int(id))?.cancel()
                completion(.success, Data())
                return
            }
            guard let transfer = transfers.get(Int(id)) else { throw RuntimeError("HTTP transfer not found") }
            transfer.channel.next(completion)
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    private func startTransfer(_ values: [String: WireValue], completion: @escaping ModuleCompletion) throws {
        guard case let .text(urlText)? = values["url"],
              let url = URL(string: urlText),
              let scheme = url.scheme?.lowercased(),
              url.host != nil, url.user == nil, url.password == nil else {
            throw RuntimeError("Invalid HTTP URL")
        }
        #if DEBUG
        guard scheme == "https" || scheme == "http" else { throw RuntimeError("HTTP requests require HTTPS") }
        #else
        guard scheme == "https" else { throw RuntimeError("HTTP requests require HTTPS") }
        #endif
        var requestMethod = "POST"
        if case let .text(value)? = values["method"] { requestMethod = value }
        guard Self.transferHttpMethods.contains(requestMethod) else {
            throw RuntimeError("Unsupported HTTP transfer method \(requestMethod)")
        }
        var kind = Self.transferMultipart
        if case let .integer(value)? = values["kind"] { kind = value }
        var timeoutMs: Int64 = 60_000
        if case let .integer(value)? = values["timeoutMs"] { timeoutMs = min(max(value, 1_000), 600_000) }
        var baseRequest = URLRequest(url: url)
        baseRequest.httpMethod = requestMethod
        baseRequest.timeoutInterval = Double(timeoutMs) / 1_000
        baseRequest.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
        let multipart = kind == Self.transferMultipart
        if case let .text(headersText)? = values["headers"] {
            guard let data = headersText.data(using: .utf8),
                  let headers = try JSONSerialization.jsonObject(with: data) as? [String: String],
                  headers.count <= 32 else { throw RuntimeError("Invalid HTTP headers") }
            for (name, value) in headers {
                guard name.range(of: Self.safeHeaderName, options: .regularExpression) != nil,
                      value.utf8.count <= 8_192, !value.contains("\r"), !value.contains("\n") else {
                    throw RuntimeError("Invalid HTTP header")
                }
                let lower = name.lowercased()
                guard !Self.reservedTraceHeaders.contains(lower) else {
                    throw RuntimeError("Trace headers require an origin-scoped context")
                }
                guard !Self.fileTransportHeaders.contains(lower) else {
                    throw RuntimeError("File upload headers cannot override HTTP transport fields")
                }
                guard !(multipart && lower == "content-type") else {
                    throw RuntimeError("Multipart requests own the Content-Type boundary")
                }
                baseRequest.setValue(value, forHTTPHeaderField: name)
            }
        }
        var traceparent: String?
        var traceOrigin: String?
        if case let .text(value)? = values["traceparent"] { traceparent = value }
        if case let .text(value)? = values["traceOrigin"] { traceOrigin = value }
        if traceparent != nil || traceOrigin != nil {
            guard let traceparent, let traceOrigin,
                  traceparent.range(of: Self.traceparentPattern, options: .regularExpression) != nil,
                  Self.origin(of: url) == traceOrigin, traceOrigin.hasPrefix("https://") else {
                throw RuntimeError("Trace context origin does not match the HTTP request origin")
            }
            baseRequest.setValue(traceparent, forHTTPHeaderField: "traceparent")
        }
        var partsJson: String?
        var sourcePath: String?
        if case let .text(value)? = values["parts"] { partsJson = value }
        if case let .text(value)? = values["path"] { sourcePath = value }
        if multipart && partsJson == nil { throw RuntimeError("Multipart parts are required") }
        if !multipart && sourcePath == nil { throw RuntimeError("Upload source path is required") }

        let transfer = HttpStreamTransfer()
        let id = transfers.add(transfer)
        completion(.success, (try? WireMap.encode(["transfer": .integer(Int64(id))])) ?? Data())

        let partsSource = partsJson
        let pathSource = sourcePath
        let prepared = baseRequest
        queue.async { [weak self] in
            guard let self else { return }
            var request = prepared
            var snapshot: HttpUploadSnapshot?
            do {
                let bodyURL: URL
                if multipart {
                    let body = try HttpMultipartBody.decode(partsSource ?? "[]", root: self.filesRoot)
                    try FileManager.default.createDirectory(
                        at: self.uploadCache,
                        withIntermediateDirectories: true,
                        attributes: [.posixPermissions: 0o700]
                    )
                    bodyURL = self.uploadCache.appendingPathComponent("pam-multipart-\(UUID().uuidString).tmp")
                    transfer.bodyFile = bodyURL
                    try body.write(to: bodyURL) { transfer.cancelled || self.closed }
                    request.setValue(body.contentType, forHTTPHeaderField: "Content-Type")
                } else {
                    let created = try HttpUploadSnapshot.create(
                        root: self.filesRoot,
                        cache: self.uploadCache,
                        path: pathSource ?? ""
                    ) { transfer.cancelled || self.closed }
                    snapshot = created
                    bodyURL = created.url
                    if request.value(forHTTPHeaderField: "Content-Type") == nil {
                        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
                    }
                }
                guard !transfer.cancelled, !self.closed else { throw RuntimeError("HTTP transfer cancelled") }
                let total = Int64((try? bodyURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
                transfer.channel.offer(HttpTransferEvents.progress(0, total))
                let createdSnapshot = snapshot
                let task = self.session.uploadTask(with: request, fromFile: bodyURL) { data, response, error in
                    defer {
                        transfer.removeBody()
                        try? createdSnapshot?.close()
                    }
                    if let error {
                        transfer.channel.offer(HttpTransferEvents.failed(
                            transfer.cancelled ? "HTTP transfer cancelled" : error.localizedDescription
                        ))
                        return
                    }
                    let http = response as? HTTPURLResponse
                    guard let data, data.count <= 900 * 1024 else {
                        transfer.channel.offer(HttpTransferEvents.failed("HTTP response exceeds one MiB"))
                        return
                    }
                    var headers: [String: String] = [:]
                    for (name, value) in http?.allHeaderFields ?? [:] {
                        if let name = name as? String, headers.count < 64 { headers[name.lowercased()] = "\(value)" }
                    }
                    let headersJson = (try? JSONSerialization.data(withJSONObject: headers))
                        .map { String(decoding: $0, as: UTF8.self) } ?? "{}"
                    transfer.channel.offer(HttpTransferEvents.complete(
                        status: http?.statusCode ?? 0,
                        body: String(decoding: data, as: UTF8.self),
                        headers: headersJson
                    ))
                }
                task.delegate = transfer
                transfer.attach(task, total: total)
                task.resume()
            } catch {
                transfer.removeBody()
                try? snapshot?.close()
                transfer.channel.offer(HttpTransferEvents.failed(
                    transfer.cancelled ? "HTTP transfer cancelled" : error.localizedDescription
                ))
            }
        }
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
