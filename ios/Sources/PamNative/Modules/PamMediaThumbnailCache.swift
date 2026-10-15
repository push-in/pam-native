import Foundation
import UIKit
import CryptoKit

/// Bounded disk cache of media-library thumbnails (JPEG) in Caches. Least
/// recently used files go first once the folder passes `maxBytes` or
/// `maxFiles`; a hit only touches the modification date.
final class PamMediaThumbnailCache {
    static let shared = PamMediaThumbnailCache(
        directory: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pam-media-thumbnails", isDirectory: true)
    )
    static let maxBytes: Int64 = 24 * 1024 * 1024
    static let maxFiles = 600
    static let minEdge = 32
    static let maxEdge = 1024

    struct Entry: Equatable {
        let name: String
        let bytes: Int64
        let lastUsed: TimeInterval
    }

    let directory: URL
    private let maxBytes: Int64
    private let maxFiles: Int

    init(directory: URL, maxBytes: Int64 = PamMediaThumbnailCache.maxBytes, maxFiles: Int = PamMediaThumbnailCache.maxFiles) {
        self.directory = directory
        self.maxBytes = maxBytes
        self.maxFiles = maxFiles
    }

    static func clampEdge(_ value: Int64) -> Int {
        Int(Swift.min(Swift.max(value, Int64(minEdge)), Int64(maxEdge)))
    }

    func file(for key: String) -> URL {
        let digest = SHA256.hash(data: Data(key.utf8)).prefix(16).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent("\(digest).jpg")
    }

    func hit(_ file: URL) -> Bool {
        let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard size > 0 else { return false }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path)
        return true
    }

    func store(_ data: Data, at file: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try data.write(to: file, options: .atomic)
        trim()
    }

    /// Drops the oldest thumbnails until the cache is back at 75% of its limits.
    func trim() {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles]
        ) else { return }
        let entries = urls.filter { $0.pathExtension == "jpg" }.map { url -> Entry in
            let values = try? url.resourceValues(forKeys: Set(keys))
            return Entry(
                name: url.lastPathComponent,
                bytes: Int64(values?.fileSize ?? 0),
                lastUsed: values?.contentModificationDate?.timeIntervalSince1970 ?? 0
            )
        }
        for name in Self.victims(entries, maxBytes: maxBytes, maxFiles: maxFiles) {
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
        }
    }

    /// Names to delete, oldest first, so the rest fits 75% of both limits.
    static func victims(_ entries: [Entry], maxBytes: Int64, maxFiles: Int) -> [String] {
        var bytes = entries.reduce(Int64(0)) { $0 + $1.bytes }
        var count = entries.count
        guard bytes > maxBytes || count > maxFiles else { return [] }
        let targetBytes = maxBytes * 3 / 4
        let targetFiles = maxFiles * 3 / 4
        var out: [String] = []
        for entry in entries.sorted(by: { $0.lastUsed < $1.lastUsed }) {
            if bytes <= targetBytes && count <= targetFiles { break }
            out.append(entry.name)
            bytes -= entry.bytes
            count -= 1
        }
        return out
    }

    static func fittedSize(width: Int, height: Int, maxPx: Int) -> (Int, Int) {
        let edge = Swift.max(width, height)
        guard edge > maxPx, edge > 0 else { return (width, height) }
        let scale = Double(maxPx) / Double(edge)
        return (Swift.max(1, Int(Double(width) * scale)), Swift.max(1, Int(Double(height) * scale)))
    }

    /// Scales down (never up) so the longer pixel edge is at most `maxPx`.
    static func fitted(_ image: UIImage, maxPx: Int) -> UIImage {
        let pixelWidth = Int(image.size.width * image.scale)
        let pixelHeight = Int(image.size.height * image.scale)
        let (width, height) = fittedSize(width: pixelWidth, height: pixelHeight, maxPx: maxPx)
        guard width != pixelWidth || height != pixelHeight else { return image }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: width, height: height))
        }
    }
}
