import Foundation

final class HttpUploadSnapshot: @unchecked Sendable {
    static let maximumBytes = 64 * 1024 * 1024
    let url: URL

    private init(url: URL) { self.url = url }
    deinit { try? close() }

    func close() throws {
        if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
    }

    static func create(root: URL, cache: URL, path: String, cancelled: () -> Bool = { false }) throws -> HttpUploadSnapshot {
        guard !path.isEmpty, !path.hasPrefix("/"), !path.contains("\\"), !path.contains("\0"),
              !path.split(separator: "/", omittingEmptySubsequences: false).contains(where: { $0.isEmpty || $0 == "." || $0 == ".." }) else {
            throw UploadFileError("Upload source must be a relative private file path")
        }
        let root = root.standardizedFileURL.resolvingSymlinksInPath()
        let source = root.appendingPathComponent(path).standardizedFileURL.resolvingSymlinksInPath()
        guard source.path.hasPrefix(root.path + "/") else { throw UploadFileError("Upload source is outside the private files directory") }
        let values = try source.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true, let size = values.fileSize, size >= 0, size <= maximumBytes else {
            throw UploadFileError("Upload source is missing, invalid or exceeds 64 MiB")
        }
        guard !cancelled() else { throw UploadFileError("HTTP upload cancelled") }
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        let target = cache.appendingPathComponent("pam-upload-\(UUID().uuidString).tmp")
        guard FileManager.default.createFile(atPath: target.path, contents: nil, attributes: [.posixPermissions: 0o600]) else {
            throw UploadFileError("Cannot create HTTP upload snapshot")
        }
        do {
            let input = try FileHandle(forReadingFrom: source)
            defer { try? input.close() }
            let output = try FileHandle(forWritingTo: target)
            defer { try? output.close() }
            var copied = 0
            while true {
                guard !cancelled() else { throw UploadFileError("HTTP upload cancelled") }
                let bytes = try input.read(upToCount: 64 * 1024) ?? Data()
                if bytes.isEmpty { break }
                copied += bytes.count
                guard copied <= size, copied <= maximumBytes else { throw UploadFileError("Upload source changed during copy") }
                try output.write(contentsOf: bytes)
            }
            guard copied == size else { throw UploadFileError("Upload source changed during copy") }
            return HttpUploadSnapshot(url: target)
        } catch {
            try? FileManager.default.removeItem(at: target)
            throw error
        }
    }
}

private struct UploadFileError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
