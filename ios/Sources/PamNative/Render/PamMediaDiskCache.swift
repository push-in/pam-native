import CryptoKit
import Foundation

struct PamCacheUsage: Sendable {
    let fileCount: Int64
    let totalBytes: Int64
}

/// Encoded-media disk cache shared by images, players and plugins (for
/// example notification avatars), keyed like the image pipeline.
public final class PamMediaDiskCache: @unchecked Sendable {
    struct Resolution: Sendable {
        let url: URL
        let hit: Bool
    }
    public static let shared = PamMediaDiskCache()

    private let root: URL
    /// Lookups gate on-screen pictures: same QoS as their decode, or a grid of
    /// concurrent decodes starves the queue the next lookup waits on.
    private let queue = DispatchQueue(label: "dev.pam.media-cache", qos: .userInitiated)
    /// Directory scans run here so lookups on `queue` never wait for one.
    private let trimQueue = DispatchQueue(label: "dev.pam.media-cache.trim", qos: .background)
    private let memory = NSCache<NSString, NSData>()
    /// Insertion order of `memory` (touched only on `queue`) so a moderate
    /// trim can drop the oldest half; NSCache cannot be halved by itself.
    private var memoryOrder = PamMemoryCostOrder()
    private var downloads: [String: [(Result<Resolution, Error>) -> Void]] = [:]
    /// One directory scan for a burst of stores (a grid of thumbnails stored
    /// one by one scanned and sorted the whole cache per file, and every
    /// lookup queued behind those scans waited seconds before its download).
    private var pendingTrimLimit: Int64?
    private static let trimDelay: DispatchTimeInterval = .milliseconds(1_500)

    private init() {
        root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pam-media-v1", isDirectory: true)
        // A quarter of the media budget (4-24 MB): decoded photos live in
        // PamDecodedImageCache, so encoded bytes only bridge a re-decode.
        memory.totalCostLimit = PamImageDownsampling.memoryCacheBytes() / 4
        try? FileManager.default.createDirectory(
            at: root,
            withIntermediateDirectories: true
        )
    }

    /// Cache identities are hashed once per source (list rows re-bind the
    /// same URLs constantly).
    private let identities = NSCache<NSString, NSString>()
    private let files = NSCache<NSString, NSString>()

    func identity(source: String, stableKey: String?) -> String {
        if let stableKey, !stableKey.isEmpty { return stableKey }
        if let cached = identities.object(forKey: source as NSString) { return cached as String }
        let hashed = sha256(Data(source.utf8))
        identities.setObject(hashed as NSString, forKey: source as NSString)
        return hashed
    }

    public func data(
        source: String,
        stableKey: String?,
        maxAgeMs: Int64,
        completion: @escaping @Sendable (Data?) -> Void
    ) {
        let identity = identity(source: source, stableKey: stableKey)
        queue.async {
            // A memory hit is handed over on the cache queue too: bridging
            // the NSData and the caller's decode in its completion ran on the
            // main thread (~60 ms per large poster inside a page mount).
            if let cached = self.memory.object(forKey: identity as NSString) {
                completion(cached as Data)
                return
            }
            let file = self.file(identity)
            guard self.fresh(file, maxAgeMs: maxAgeMs),
                  let data = try? Data(contentsOf: file, options: .mappedIfSafe) else {
                completion(nil)
                return
            }
            self.remember(data, identity: identity)
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
            self.remember(data, identity: identity)
            self.scheduleTrim(limit: limit)
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
                        self.scheduleTrim(limit: 0)
                        self.finish(identity, .success(Resolution(url: target, hit: false)))
                    } catch {
                        self.finish(identity, .failure(error))
                    }
                }
            }.resume()
        }
    }

    private func remember(_ data: Data, identity: String) {
        memory.setObject(data as NSData, forKey: identity as NSString, cost: data.count)
        memoryOrder.insert(identity, cost: data.count, limit: memory.totalCostLimit)
    }

    /// Critical (memory warning) empties the in-memory layer; moderate
    /// (entering background, Android UI_HIDDEN parity) drops its oldest half.
    func trimMemory(critical: Bool = true) {
        if critical {
            memory.removeAllObjects()
            queue.async { self.memoryOrder.removeAll() }
            return
        }
        queue.async {
            for key in self.memoryOrder.trimToHalf() {
                self.memory.removeObject(forKey: key as NSString)
            }
        }
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
            self.memoryOrder.removeAll()
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

    /// On `queue`: the strictest budget asked during the burst wins.
    private func scheduleTrim(limit: Int64) {
        if let pending = pendingTrimLimit {
            if Self.diskBudget(limit: limit) < Self.diskBudget(limit: pending) { pendingTrimLimit = limit }
            return
        }
        pendingTrimLimit = limit
        queue.asyncAfter(deadline: .now() + Self.trimDelay) {
            guard let limit = self.pendingTrimLimit else { return }
            self.pendingTrimLimit = nil
            self.trimQueue.async { self.trim(limit: limit) }
        }
    }

    private func trim(limit: Int64) {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        let files = (try? FileManager.default.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: keys
        )) ?? []
        // Attributes read once per file (prefetched by the listing), not
        // per comparison; pins come from the same listing.
        let pins = Set(files.filter { $0.pathExtension == "pin" }.map { $0.deletingPathExtension().lastPathComponent })
        let media = files.filter { $0.pathExtension == "media" }
            .map { file -> (url: URL, size: Int64, date: Date) in
                let values = try? file.resourceValues(forKeys: Set(keys))
                return (file, Int64(values?.fileSize ?? 0), values?.contentModificationDate ?? .distantPast)
            }
            .sorted { $0.date > $1.date }
        let evicted = Self.evictions(
            sizesNewestFirst: media.map(\.size),
            pinned: media.map { pins.contains($0.url.deletingPathExtension().lastPathComponent) },
            limit: limit
        )
        for index in evicted {
            try? FileManager.default.removeItem(at: media[index].url)
            Self.removePlayableLinks(of: media[index].url)
        }
    }

    // MARK: Playable name

    /// Container extensions a player link may carry (see `playableURL`).
    static let playableExtensions = ["mp4", "mov", "m4a", "mp3", "wav"]

    /// AVFoundation picks the demuxer by the file extension: a `.media` cache
    /// file fails with -11828 "Cannot Open" (Android's ExoPlayer sniffs the
    /// bytes). Players get a relative symlink named after the sniffed
    /// container next to it (no extra bytes on disk); anything else, or a
    /// file that cannot be read, is returned unchanged.
    static func playableURL(for file: URL) -> URL {
        guard file.isFileURL, file.pathExtension == "media",
              let ext = sniffedExtension(of: file) else { return file }
        let link = file.deletingPathExtension().appendingPathExtension(ext)
        let manager = FileManager.default
        if (try? manager.destinationOfSymbolicLink(atPath: link.path)) != file.lastPathComponent {
            try? manager.removeItem(at: link)
            guard (try? manager.createSymbolicLink(
                atPath: link.path,
                withDestinationPath: file.lastPathComponent
            )) != nil else { return file }
        }
        return link
    }

    static func sniffedExtension(of file: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: file) else { return nil }
        defer { try? handle.close() }
        guard let head = try? handle.read(upToCount: 12) else { return nil }
        return sniffedExtension(head: head)
    }

    /// ISO BMFF (`ftyp`: QuickTime, M4A or MP4), MP3 (ID3 tag or frame
    /// sync) and RIFF/WAVE; nil for anything else.
    static func sniffedExtension(head: Data) -> String? {
        let bytes = [UInt8](head)
        if bytes.count >= 12, bytes[4] == 0x66, bytes[5] == 0x74, bytes[6] == 0x79, bytes[7] == 0x70 {
            switch String(decoding: bytes[8..<12], as: UTF8.self) {
            case "qt  ": return "mov"
            case "M4A ", "M4B ": return "m4a"
            default: return "mp4"
            }
        }
        if bytes.count >= 3, bytes[0] == 0x49, bytes[1] == 0x44, bytes[2] == 0x33 { return "mp3" }
        if bytes.count >= 2, bytes[0] == 0xFF, bytes[1] & 0xE0 == 0xE0 { return "mp3" }
        if bytes.count >= 12, bytes[0] == 0x52, bytes[1] == 0x49, bytes[2] == 0x46, bytes[3] == 0x46,
           bytes[8] == 0x57, bytes[9] == 0x41, bytes[10] == 0x56, bytes[11] == 0x45 { return "wav" }
        return nil
    }

    static func removePlayableLinks(of file: URL) {
        let base = file.deletingPathExtension()
        for ext in playableExtensions {
            try? FileManager.default.removeItem(at: base.appendingPathExtension(ext))
        }
    }

    /// Directory budget, Android `NativeMediaFileCache` parity: 256 MB by
    /// default (the RN app's media/image disk budget); a node's
    /// `cacheMaxBytes` may lower it to 16 MB but never raise it past 256 MB.
    static let defaultDiskBytes: Int64 = 256 * 1_024 * 1_024
    static let minimumDiskBytes: Int64 = 16 * 1_024 * 1_024

    static func diskBudget(limit: Int64) -> Int64 {
        min(max(limit > 0 ? limit : defaultDiskBytes, minimumDiskBytes), defaultDiskBytes)
    }

    /// Indexes (newest first) of files to delete so the directory fits the
    /// budget. The newest file is the one being handed to a player or image
    /// view and pinned files are kept for offline use: neither is deleted.
    static func evictions(sizesNewestFirst sizes: [Int64], pinned: [Bool], limit: Int64) -> [Int] {
        let budget = diskBudget(limit: limit)
        var total: Int64 = 0
        var result: [Int] = []
        for (index, size) in sizes.enumerated() {
            total += size
            if index > 0, total > budget, !(index < pinned.count && pinned[index]) {
                result.append(index)
            }
        }
        return result
    }

    private func file(_ identity: String) -> URL {
        let name: String
        if let cached = files.object(forKey: identity as NSString) {
            name = cached as String
        } else {
            name = sha256(Data(identity.utf8))
            files.setObject(name as NSString, forKey: identity as NSString)
        }
        return root.appendingPathComponent(name + ".media")
    }

    private func pin(_ identity: String) -> URL {
        root.appendingPathComponent(sha256(Data(identity.utf8)) + ".pin")
    }

    private func pinForFile(_ file: URL) -> URL {
        root.appendingPathComponent(file.deletingPathExtension().lastPathComponent + ".pin")
    }

    private static let hexDigits = Array("0123456789abcdef".utf8)

    /// Lowercase hex without `String(format:)` (locale lookups per byte).
    static func hex<S: Sequence>(_ bytes: S) -> String where S.Element == UInt8 {
        var output = [UInt8]()
        output.reserveCapacity(64)
        for byte in bytes {
            output.append(hexDigits[Int(byte >> 4)])
            output.append(hexDigits[Int(byte & 0x0F)])
        }
        return String(decoding: output, as: UTF8.self)
    }

    private func sha256(_ data: Data) -> String {
        Self.hex(SHA256.hash(data: data))
    }

    private enum CacheError: Error {
        case invalidURL
        case missingDownload
        case tooLarge
        case checksum
        case cacheMiss
    }
}
