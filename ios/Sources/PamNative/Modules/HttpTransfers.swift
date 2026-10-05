import Foundation

/// One part of a streamed multipart/form-data request (Android MultipartPart).
enum HttpMultipartPart: Equatable {
    case field(name: String, value: String)
    case file(name: String, url: URL, filename: String, mimeType: String)

    var name: String {
        switch self {
        case let .field(name, _), let .file(name, _, _, _): return name
        }
    }
}

/// Fixed-length multipart body written to a private temporary file (so the
/// upload task can stream it from disk with byte progress), mirroring the
/// Android MultipartBody limits and header format.
struct HttpMultipartBody {
    static let maxParts = 64
    static let maxFieldBytes = 1_048_576
    static let maxBodyBytes: Int64 = 2 * 1024 * 1024 * 1024
    static let partField = 1
    static let partFile = 2
    private static let bufferBytes = 64 * 1024

    let parts: [HttpMultipartPart]
    let boundary: String
    private let fileLengths: [URL: Int64]

    var contentType: String { "multipart/form-data; boundary=\(boundary)" }

    init(parts: [HttpMultipartPart], boundary: String = "pam-\(UUID().uuidString)") throws {
        guard !parts.isEmpty else { throw HttpTransferError("Multipart requests require at least one part") }
        guard parts.count <= Self.maxParts else {
            throw HttpTransferError("Multipart requests support at most \(Self.maxParts) parts")
        }
        guard boundary.range(of: "^[A-Za-z0-9'()+_,./:=?-]{1,70}$", options: .regularExpression) != nil else {
            throw HttpTransferError("Invalid multipart boundary")
        }
        var lengths: [URL: Int64] = [:]
        for part in parts {
            guard Self.isSafeName(part.name) else { throw HttpTransferError("Invalid multipart field name") }
            switch part {
            case let .field(_, value):
                guard value.utf8.count <= Self.maxFieldBytes else { throw HttpTransferError("Multipart field exceeds 1 MiB") }
            case let .file(_, url, filename, mimeType):
                let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
                guard values?.isRegularFile == true, let size = values?.fileSize else {
                    throw HttpTransferError("Multipart file does not exist")
                }
                guard Self.isSafeFilename(filename) else { throw HttpTransferError("Invalid multipart filename") }
                guard mimeType.range(
                    of: "^[A-Za-z0-9!#$&^_.+-]{1,127}/[A-Za-z0-9!#$&^_.+-]{1,127}$",
                    options: .regularExpression
                ) != nil else { throw HttpTransferError("Invalid multipart MIME type") }
                lengths[url] = Int64(size)
            }
        }
        self.parts = parts
        self.boundary = boundary
        fileLengths = lengths
        guard contentLength() <= Self.maxBodyBytes else { throw HttpTransferError("Multipart body exceeds 2 GiB") }
    }

    static func isSafeName(_ value: String) -> Bool {
        !value.isEmpty && value.count <= 256 && !value.contains("\r") && !value.contains("\n") && !value.contains("\0")
    }

    static func isSafeFilename(_ value: String) -> Bool {
        isSafeName(value) && value.count <= 255 && !value.contains("/") && !value.contains("\\")
    }

    func header(_ part: HttpMultipartPart) -> Data {
        var text = "--\(boundary)\r\nContent-Disposition: form-data; name=\"\(Self.quoted(part.name))\""
        if case let .file(_, _, filename, mimeType) = part {
            text += "; filename=\"\(Self.quoted(filename))\"\r\nContent-Type: \(mimeType)"
        }
        text += "\r\n\r\n"
        return Data(text.utf8)
    }

    var closing: Data { Data("--\(boundary)--\r\n".utf8) }

    func contentLength() -> Int64 {
        parts.reduce(Int64(closing.count)) { total, part in
            let payload: Int64
            switch part {
            case let .field(_, value): payload = Int64(value.utf8.count)
            case let .file(_, url, _, _): payload = fileLengths[url] ?? 0
            }
            return total + Int64(header(part).count) + payload + 2
        }
    }

    /// Writes the whole body to [target]; files are copied in 64 KiB chunks.
    func write(to target: URL, cancelled: () -> Bool) throws {
        guard FileManager.default.createFile(atPath: target.path, contents: nil, attributes: [.posixPermissions: 0o600]) else {
            throw HttpTransferError("Cannot create multipart body")
        }
        let output = try FileHandle(forWritingTo: target)
        defer { try? output.close() }
        func emit(_ data: Data) throws {
            guard !cancelled() else { throw HttpTransferError("HTTP transfer cancelled") }
            try output.write(contentsOf: data)
        }
        for part in parts {
            try emit(header(part))
            switch part {
            case let .field(_, value):
                try emit(Data(value.utf8))
            case let .file(_, url, _, _):
                let expected = fileLengths[url] ?? 0
                let input = try FileHandle(forReadingFrom: url)
                defer { try? input.close() }
                var copied: Int64 = 0
                while true {
                    guard !cancelled() else { throw HttpTransferError("HTTP transfer cancelled") }
                    let chunk = try input.read(upToCount: Self.bufferBytes) ?? Data()
                    if chunk.isEmpty { break }
                    copied += Int64(chunk.count)
                    guard copied <= expected else { throw HttpTransferError("Multipart file changed during upload") }
                    try output.write(contentsOf: chunk)
                }
                guard copied == expected else { throw HttpTransferError("Multipart file changed during upload") }
            }
            try emit(Data("\r\n".utf8))
        }
        try emit(closing)
    }

    private static func quoted(_ value: String) -> String { value.replacingOccurrences(of: "\"", with: "%22") }

    /// Decodes the bridge part list, resolving files strictly inside [root].
    static func decode(_ json: String, root: URL) throws -> HttpMultipartBody {
        guard let data = json.data(using: .utf8),
              let items = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            throw HttpTransferError("Multipart parts are required")
        }
        let parts: [HttpMultipartPart] = try items.map { item in
            guard let name = item["name"] as? String else { throw HttpTransferError("Invalid multipart field name") }
            switch (item["type"] as? NSNumber)?.intValue {
            case partField:
                guard let value = item["value"] as? String else { throw HttpTransferError("Multipart field value is required") }
                return .field(name: name, value: value)
            case partFile:
                guard let path = item["path"] as? String else { throw HttpTransferError("Multipart file path is required") }
                let url = try PamPrivateFilePath.resolve(root: root, path: path)
                let filename = (item["filename"] as? String).flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
                    ?? url.lastPathComponent
                let mimeType = (item["mimeType"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                    ?? PamPrivateFilePath.mimeType(for: url)
                return .file(name: name, url: url, filename: filename, mimeType: mimeType)
            default:
                throw HttpTransferError("Unknown multipart part type")
            }
        }
        return try HttpMultipartBody(parts: parts)
    }
}

enum PamPrivateFilePath {
    /// Resolves a relative path strictly inside the private PAM files directory.
    static func resolve(root: URL, path: String) throws -> URL {
        guard !path.isEmpty, !path.hasPrefix("/"), !path.contains("\\"), !path.contains("\0"),
              !path.split(separator: "/", omittingEmptySubsequences: false)
                .contains(where: { $0.isEmpty || $0 == "." || $0 == ".." }) else {
            throw HttpTransferError("File path must be relative")
        }
        let base = root.standardizedFileURL.resolvingSymlinksInPath()
        let target = base.appendingPathComponent(path).standardizedFileURL.resolvingSymlinksInPath()
        guard target.path.hasPrefix(base.path + "/") else { throw HttpTransferError("Invalid file path") }
        return target
    }

    static func mimeType(for url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "heic": return "image/heic"
        case "mp4", "m4v": return "video/mp4"
        case "mov": return "video/quicktime"
        case "m4a": return "audio/mp4"
        case "aac": return "audio/aac"
        case "mp3": return "audio/mpeg"
        case "ogg", "opus": return "audio/ogg"
        case "wav": return "audio/wav"
        case "pdf": return "application/pdf"
        case "json": return "application/json"
        case "txt": return "text/plain"
        default: return "application/octet-stream"
        }
    }
}

struct HttpTransferError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Throttles upload progress to whole-percent steps or 256 KiB.
struct HttpTransferProgressThrottle {
    private var reported: Int64 = -1
    private let total: Int64
    private let step: Int64

    init(total: Int64) {
        self.total = total
        step = max(256 * 1024, total / 100)
    }

    mutating func shouldReport(_ sent: Int64) -> Bool {
        guard sent >= total || reported < 0 || sent - reported >= step, sent != reported else { return false }
        reported = sent
        return true
    }
}

enum HttpTransferEvents {
    static func progress(_ sent: Int64, _ total: Int64) -> Data {
        (try? WireMap.encode([
            "state": .integer(1),
            "bytesSent": .integer(sent),
            "totalBytes": .integer(total),
        ])) ?? Data()
    }

    static func complete(status: Int, body: String, headers: String) -> Data {
        (try? WireMap.encode([
            "state": .integer(2),
            "statusCode": .integer(Int64(status)),
            "body": .text(body),
            "headers": .text(headers),
        ])) ?? Data()
    }

    static func failed(_ message: String) -> Data {
        (try? WireMap.encode([
            "state": .integer(3),
            "message": .text(message),
        ])) ?? Data()
    }
}

/// A cancellable upload whose progress/completion events are pulled by PHP
/// through `transferNext` (WatchChannel), like Android.
final class HttpStreamTransfer: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    let channel = WatchChannel()
    private let lock = NSLock()
    private var isCancelled = false
    private var task: URLSessionTask?
    private var throttle = HttpTransferProgressThrottle(total: 0)
    var bodyFile: URL?

    var cancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return isCancelled
    }

    func attach(_ task: URLSessionTask, total: Int64) {
        lock.lock()
        self.task = task
        throttle = HttpTransferProgressThrottle(total: total)
        let cancelledNow = isCancelled
        lock.unlock()
        if cancelledNow { task.cancel() }
    }

    func cancel() {
        lock.lock()
        isCancelled = true
        let running = task
        lock.unlock()
        running?.cancel()
        channel.close()
        removeBody()
    }

    func removeBody() {
        lock.lock()
        let file = bodyFile
        bodyFile = nil
        lock.unlock()
        if let file { try? FileManager.default.removeItem(at: file) }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didSendBodyData bytesSent: Int64,
        totalBytesSent: Int64,
        totalBytesExpectedToSend: Int64
    ) {
        lock.lock()
        let report = throttle.shouldReport(totalBytesSent)
        lock.unlock()
        if report {
            channel.offer(HttpTransferEvents.progress(totalBytesSent, max(totalBytesExpectedToSend, totalBytesSent)))
        }
    }

    // Uploads stay bound to the URL chosen by the caller.
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

final class HttpTransferRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var next = 1
    private var transfers: [Int: HttpStreamTransfer] = [:]

    func add(_ transfer: HttpStreamTransfer) -> Int {
        lock.lock()
        defer { lock.unlock() }
        let id = next
        next += 1
        transfers[id] = transfer
        return id
    }

    func get(_ id: Int) -> HttpStreamTransfer? {
        lock.lock()
        defer { lock.unlock() }
        return transfers[id]
    }

    func remove(_ id: Int) -> HttpStreamTransfer? {
        lock.lock()
        defer { lock.unlock() }
        return transfers.removeValue(forKey: id)
    }

    func cancelAll() {
        lock.lock()
        let all = Array(transfers.values)
        transfers.removeAll()
        lock.unlock()
        all.forEach { $0.cancel() }
    }
}
