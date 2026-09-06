import Foundation
import CryptoKit

enum PrivateFileSha256 {
    static let maximumBytes = 64 * 1024 * 1024

    static func digest(root: URL, path: String, cancelled: () -> Bool = { false }) throws -> String {
        guard !path.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              path.utf8.count <= 4096, !path.hasPrefix("/"), !path.contains("\\"),
              !path.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }),
              !path.components(separatedBy: "/").contains(where: { $0.isEmpty || $0 == "." || $0 == ".." }) else {
            throw HashError("Invalid private file path")
        }
        let base = root.standardizedFileURL.resolvingSymlinksInPath()
        let file = base.appendingPathComponent(path).standardizedFileURL.resolvingSymlinksInPath()
        guard file.path.hasPrefix(base.path + "/") else { throw HashError("File is outside the private directory") }
        let metadata = try file.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard metadata.isRegularFile == true, let expected = metadata.fileSize else { throw HashError("File does not exist") }
        guard expected <= maximumBytes else { throw HashError("File exceeds the 64 MiB hash limit") }
        let input = try FileHandle(forReadingFrom: file)
        defer { try? input.close() }
        var hash = SHA256()
        var count = 0
        while true {
            guard !cancelled() else { throw HashError("File hashing cancelled") }
            guard let data = try input.read(upToCount: 64 * 1024), !data.isEmpty else { break }
            count += data.count
            guard count <= expected, count <= maximumBytes else { throw HashError("File changed while hashing") }
            hash.update(data: data)
        }
        guard count == expected else { throw HashError("File changed while hashing") }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private struct HashError: LocalizedError {
        let message: String
        init(_ message: String) { self.message = message }
        var errorDescription: String? { message }
    }
}
