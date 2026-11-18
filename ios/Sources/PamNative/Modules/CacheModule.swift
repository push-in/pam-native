import Foundation

/// Sandbox folders `Caches` reports besides the media cache: the cache
/// folder (`Files::CACHE_DIRECTORY`, always cleared) and picker/import copies
/// (`pam-files/imports`, cleared only on request).
struct PamSandboxCacheFolders: Sendable {
    let downloads: URL
    let imports: URL

    static let standard: PamSandboxCacheFolders = {
        let files = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pam-files", isDirectory: true)
        return PamSandboxCacheFolders(
            downloads: files.appendingPathComponent("cache", isDirectory: true),
            imports: files.appendingPathComponent("imports", isDirectory: true)
        )
    }()

    /// (bytes, files) of the regular files under a folder.
    static func usage(of folder: URL) -> (bytes: Int64, files: Int64) {
        guard let walker = FileManager.default.enumerator(
            at: folder,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey]
        ) else { return (0, 0) }
        var bytes: Int64 = 0
        var files: Int64 = 0
        for case let url as URL in walker {
            guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
                  values.isRegularFile == true else { continue }
            bytes += Int64(values.fileSize ?? 0)
            files += 1
        }
        return (bytes, files)
    }

    static func clear(_ folder: URL) {
        let entries = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
        for entry in entries { try? FileManager.default.removeItem(at: entry) }
    }

    func usage() -> (downloads: (bytes: Int64, files: Int64), imports: (bytes: Int64, files: Int64)) {
        (Self.usage(of: downloads), Self.usage(of: imports))
    }

    func clear(imports clearImports: Bool) {
        Self.clear(downloads)
        if clearImports { Self.clear(imports) }
    }
}

final class CacheModule: NativeModule {
    private let folders: PamSandboxCacheFolders

    init(folders: PamSandboxCacheFolders = .standard) {
        self.folders = folders
    }

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        let folders = folders
        switch method {
        case "usage":
            PamMediaDiskCache.shared.usage { usage in
                completion(.success, Self.payload(usage: usage, sandbox: folders.usage(), freedBytes: 0))
            }
        case "clear":
            do {
                let values = try WireMap.decode(payload)
                let preserveOffline: Bool
                if case let .flag(value)? = values["preserveOffline"] {
                    preserveOffline = value
                } else {
                    preserveOffline = true
                }
                let clearImports: Bool
                if case let .flag(value)? = values["imports"] { clearImports = value } else { clearImports = false }
                let before = folders.usage()
                PamMediaDiskCache.shared.clear(preserveOffline: preserveOffline) { usage, freedBytes in
                    folders.clear(imports: clearImports)
                    let after = folders.usage()
                    let sandboxFreed = max(0, before.downloads.bytes + before.imports.bytes - after.downloads.bytes - after.imports.bytes)
                    completion(.success, Self.payload(usage: usage, sandbox: after, freedBytes: freedBytes + sandboxFreed))
                }
            } catch {
                completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
            }
        default:
            completion(.failure, "Unknown cache method \(method)".data(using: .utf8) ?? Data())
        }
    }

    private static func payload(
        usage: PamCacheUsage,
        sandbox: (downloads: (bytes: Int64, files: Int64), imports: (bytes: Int64, files: Int64)),
        freedBytes: Int64
    ) -> Data {
        (try? WireMap.encode([
            "fileCount": .integer(usage.fileCount + sandbox.downloads.files + sandbox.imports.files),
            "freedBytes": .integer(freedBytes),
            "imageBytes": .integer(0),
            "importBytes": .integer(sandbox.imports.bytes),
            "mediaBytes": .integer(usage.totalBytes),
            "temporaryBytes": .integer(sandbox.downloads.bytes),
            "totalBytes": .integer(usage.totalBytes + sandbox.downloads.bytes + sandbox.imports.bytes),
        ])) ?? Data()
    }
}
