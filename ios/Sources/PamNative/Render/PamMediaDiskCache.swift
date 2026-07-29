import CryptoKit
import Foundation

struct PamCacheUsage: Sendable {
    let fileCount: Int64
    let totalBytes: Int64
}

final class PamMediaDiskCache: @unchecked Sendable {
    struct Resolution: Sendable {
        let url: URL
        let hit: Bool
    }
    static let shared = PamMediaDiskCache()

    private let root: URL
    private let queue = DispatchQueue(label: "dev.pam.media-cache", qos: .utility)
    private let memory = NSCache<NSString, NSData>()
    private var downloads: [String: [(Result<Resolution, Error>) -> Void]] = [:]

    private init() {
        root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pam-media-v1", isDirectory: true)
        memory.totalCostLimit = 64 * 1024 * 1024
        try? FileManager.default.createDirectory(
            at: root,
            withIntermediateDirectories: true
        )
    }

    func identity(source: String, stableKey: String?) -> String {
        stableKey?.isEmpty == false ? stableKey! : sha256(Data(source.utf8))
    }

    func data(
        source: String,
        stableKey: String?,
        maxAgeMs: Int64,
        completion: @escaping @Sendable (Data?) -> Void
    ) {
        let identity = identity(source: source, stableKey: stableKey)
        if let cached = memory.object(forKey: identity as NSString) {
            completion(cached as Data)
            return
        }
        queue.async {
            let file = self.file(identity)
            guard self.fresh(file, maxAgeMs: maxAgeMs),
                  let data = try? Data(contentsOf: file, options: .mappedIfSafe) else {
                completion(nil)
                return
            }
            self.memory.setObject(data as NSData, forKey: identity as NSString, cost: data.count)
            completion(data)
        }
    }

    func store(
        _ data: Data,
        source: String,
        stableKey: String?,
        checksum: String?,
        limit: Int64,
        pinned: Bool,
        completion: (@Sendable (Bool) -> Void)? = nil
    ) {
        let identity = identity(source: source, stableKey: stableKey)
        queue.async {
            guard checksum == nil || self.sha256(data) == checksum else {
                completion?(false)
                return
            }
            let target = self.file(identity)
            let temporary = self.root.appendingPathComponent(UUID().uuidString + ".pending")
            do {
                try data.write(to: temporary, options: [.atomic])
                _ = try FileManager.default.replaceItemAt(target, withItemAt: temporary)
            } catch {
                try? FileManager.default.removeItem(at: target)
                try? FileManager.default.moveItem(at: temporary, to: target)
            }
            if pinned {
                try? Data([1]).write(to: self.pin(identity), options: .atomic)
            }
            self.memory.setObject(data as NSData, forKey: identity as NSString, cost: data.count)
            self.trim(limit: limit > 0 ? limit : 512 * 1024 * 1024)
            completion?(FileManager.default.fileExists(atPath: target.path))
        }
    }

    func mediaURL(
        source: String,
        stableKey: String?,
        maxAgeMs: Int64,
        maximumBytes: Int64,
        checksum: String?,
        pinned: Bool,
        cacheOnly: Bool = false,
        completion: @escaping @Sendable (Result<Resolution, Error>) -> Void
    ) {
        let identity = identity(source: source, stableKey: stableKey)
        queue.async {
            let target = self.file(identity)
            if self.fresh(target, maxAgeMs: maxAgeMs) {
                completion(.success(Resolution(url: target, hit: true)))
                return
            }
            if cacheOnly {
                completion(.failure(CacheError.cacheMiss))
                return
            }
            if self.downloads[identity] != nil {
                self.downloads[identity]?.append(completion)
                return
            }
            self.downloads[identity] = [completion]
            guard let url = URL(string: source) else {
                self.finish(identity, .failure(CacheError.invalidURL))
                return
            }
            let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 60)
            URLSession.shared.downloadTask(with: request) { temporary, response, error in
                self.queue.async {
                    if let error {
                        self.finish(identity, .failure(error))
                        return
                    }
                    guard let temporary else {
                        self.finish(identity, .failure(CacheError.missingDownload))
                        return
                    }
                    let expected = response?.expectedContentLength ?? -1
                    let maximum = maximumBytes > 0 ? maximumBytes : 2 * 1024 * 1024 * 1024
                    guard expected < 0 || expected <= maximum,
                          let attributes = try? FileManager.default.attributesOfItem(atPath: temporary.path),
                          let size = attributes[.size] as? NSNumber,
                          size.int64Value <= maximum else {
                        self.finish(identity, .failure(CacheError.tooLarge))
                        return
                    }
                    if let checksum,
                       let data = try? Data(contentsOf: temporary, options: .mappedIfSafe),
                       self.sha256(data) != checksum {
                        self.finish(identity, .failure(CacheError.checksum))
                        return
                    }
                    try? FileManager.default.removeItem(at: target)
                    do {
                        try FileManager.default.moveItem(at: temporary, to: target)
                        if pinned { try? Data([1]).write(to: self.pin(identity), options: .atomic) }
                        self.trim(limit: 512 * 1024 * 1024)
                        self.finish(identity, .success(Resolution(url: target, hit: false)))
                    } catch {
                        self.finish(identity, .failure(error))
                    }
                }
            }.resume()
        }
    }

    func trimMemory() {
        memory.removeAllObjects()
    }

    func usage(completion: @escaping @Sendable (PamCacheUsage) -> Void) {
        queue.async {
            completion(self.currentUsage())
        }
    }

    func clear(
        preserveOffline: Bool,
        completion: @escaping @Sendable (PamCacheUsage, Int64) -> Void
    ) {
        queue.async {
            let before = self.currentUsage()
            let files = (try? FileManager.default.contentsOfDirectory(
                at: self.root,
                includingPropertiesForKeys: nil
            )) ?? []
            let pinnedNames = Set(
                files
                    .filter { $0.pathExtension == "media" }
                    .filter {
                        FileManager.default.fileExists(atPath: self.pinForFile($0).path)
                    }
                    .map { $0.deletingPathExtension().lastPathComponent }
            )
            for file in files {
                let pinned = pinnedNames.contains(
                    file.deletingPathExtension().lastPathComponent
                ) && ["media", "pin"].contains(file.pathExtension)
                if !preserveOffline || !pinned {
                    try? FileManager.default.removeItem(at: file)
                }
            }
            self.memory.removeAllObjects()
            let after = self.currentUsage()
            completion(after, max(0, before.totalBytes - after.totalBytes))
        }
    }

    private func currentUsage() -> PamCacheUsage {
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .fileSizeKey]
        let files = (try? FileManager.default.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: Array(keys)
        )) ?? []
        var bytes: Int64 = 0
        var count: Int64 = 0
        for file in files {
            guard let values = try? file.resourceValues(forKeys: keys),
                  values.isRegularFile == true else {
                continue
            }
            bytes += Int64(values.fileSize ?? 0)
            count += 1
        }
        return PamCacheUsage(fileCount: count, totalBytes: bytes)
    }

    private func finish(_ identity: String, _ result: Result<Resolution, Error>) {
        let callbacks = downloads.removeValue(forKey: identity) ?? []
        callbacks.forEach { $0(result) }
    }

    private func fresh(_ file: URL, maxAgeMs: Int64) -> Bool {
        guard let values = try? file.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]),
              values.isRegularFile == true,
              (values.fileSize ?? 0) > 0 else { return false }
        guard maxAgeMs > 0, let modified = values.contentModificationDate else { return true }
        return Date().timeIntervalSince(modified) * 1_000 <= Double(maxAgeMs)
    }

    private func trim(limit: Int64) {
        let keys: Set<URLResourceKey> = [.fileSizeKey, .contentModificationDateKey]
        let files = (try? FileManager.default.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: Array(keys)
        )) ?? []
        let media = files.filter { $0.pathExtension == "media" }
            .sorted {
                ($0.modificationDate ?? .distantPast) > ($1.modificationDate ?? .distantPast)
            }
        var size: Int64 = 0
        for file in media {
            size += Int64(file.fileSize ?? 0)
            if size > min(max(limit, 16 * 1024 * 1024), 4 * 1024 * 1024 * 1024),
               !FileManager.default.fileExists(atPath: pinForFile(file).path) {
                try? FileManager.default.removeItem(at: file)
            }
        }
    }

    private func file(_ identity: String) -> URL {
        root.appendingPathComponent(sha256(Data(identity.utf8)) + ".media")
    }

    private func pin(_ identity: String) -> URL {
        root.appendingPathComponent(sha256(Data(identity.utf8)) + ".pin")
    }

    private func pinForFile(_ file: URL) -> URL {
        root.appendingPathComponent(file.deletingPathExtension().lastPathComponent + ".pin")
    }

    private func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private enum CacheError: Error {
        case invalidURL
        case missingDownload
        case tooLarge
        case checksum
        case cacheMiss
    }
}

private extension URL {
    var fileSize: Int? {
        try? resourceValues(forKeys: [.fileSizeKey]).fileSize
    }

    var modificationDate: Date? {
        try? resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate
    }
}
