import CryptoKit
import Foundation

/// One development bundle file: lowercase SHA-256, size and safe relative path.
struct PamHotReloadFile: Equatable {
    let path: String
    let sha256: String
    let size: Int64
}

/// The `pam dev` file manifest (`PNM1`). Its version is the SHA-256 of the
/// canonical text, so a delta applied on the device is verified end to end.
struct PamHotReloadManifest {
    static let magic = "PNM1\n"
    private static let deltaMagic = "PNMD1 "
    private static let maximumFiles = 10_000
    private static let maximumFileBytes: Int64 = 64 * 1024 * 1024

    let version: String
    let files: [String: PamHotReloadFile]

    static func isVersion(_ value: String) -> Bool {
        (16...64).contains(value.utf8.count) && value.utf8.allSatisfy(isLowerHex)
    }

    /// Parses a full manifest or a delta against `base` and verifies that the
    /// result hashes to `expectedVersion`.
    static func parse(_ body: String, expectedVersion: String, base: PamHotReloadManifest?) throws -> PamHotReloadManifest {
        guard isVersion(expectedVersion) else { throw PamHotReloadSyncError.invalidManifest }
        var files: [String: PamHotReloadFile]
        if body.hasPrefix(deltaMagic) {
            let lines = body.split(separator: "\n", omittingEmptySubsequences: true)
            guard let header = lines.first, let base,
                  String(header.dropFirst(deltaMagic.count)) == base.version else {
                throw PamHotReloadSyncError.deltaMismatch
            }
            files = base.files
            for line in lines.dropFirst() {
                if line.hasPrefix("+ ") {
                    let file = try parseLine(line.dropFirst(2))
                    files[file.path] = file
                } else if line.hasPrefix("- ") {
                    let path = String(line.dropFirst(2))
                    guard isSafePath(path) else { throw PamHotReloadSyncError.unsafePath }
                    files.removeValue(forKey: path)
                } else {
                    throw PamHotReloadSyncError.invalidManifest
                }
            }
        } else {
            guard body.hasPrefix(magic) else { throw PamHotReloadSyncError.invalidManifest }
            files = [:]
            for line in body.dropFirst(magic.count).split(separator: "\n", omittingEmptySubsequences: true) {
                let file = try parseLine(line)
                guard files.updateValue(file, forKey: file.path) == nil else {
                    throw PamHotReloadSyncError.invalidManifest
                }
            }
        }
        guard (1...maximumFiles).contains(files.count), files["index.php"] != nil else {
            throw PamHotReloadSyncError.invalidManifest
        }
        guard sha256Hex(Data(canonicalText(files).utf8)) == expectedVersion else {
            throw PamHotReloadSyncError.verificationFailed
        }
        return PamHotReloadManifest(version: expectedVersion, files: files)
    }

    func text() -> String { Self.canonicalText(files) }

    static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func parseLine(_ line: Substring) throws -> PamHotReloadFile {
        let parts = line.split(separator: " ", maxSplits: 2, omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].utf8.count == 64, parts[0].utf8.allSatisfy(isLowerHex),
              let size = Int64(parts[1]), (0...maximumFileBytes).contains(size) else {
            throw PamHotReloadSyncError.invalidManifest
        }
        let path = String(parts[2])
        guard isSafePath(path) else { throw PamHotReloadSyncError.unsafePath }
        return PamHotReloadFile(path: path, sha256: String(parts[0]), size: size)
    }

    /// Byte-wise path order, the order the CLI writes (paths are ASCII).
    private static func canonicalText(_ files: [String: PamHotReloadFile]) -> String {
        var text = magic
        text.reserveCapacity(files.count * 112)
        for path in files.keys.sorted(by: { Array($0.utf8).lexicographicallyPrecedes(Array($1.utf8)) }) {
            let file = files[path]!
            text += "\(file.sha256) \(file.size) \(path)\n"
        }
        return text
    }

    static func isSafePath(_ path: String) -> Bool {
        let segments = path.split(separator: "/", omittingEmptySubsequences: false)
        return !segments.isEmpty && segments.allSatisfy { segment in
            (1...255).contains(segment.utf8.count) && segment != "." && segment != ".."
                && segment.utf8.allSatisfy { byte in
                    (byte >= 48 && byte <= 57) || (byte >= 65 && byte <= 90) || (byte >= 97 && byte <= 122)
                        || byte == 46 || byte == 95 || byte == 45
                }
        }
    }

    private static func isLowerHex(_ byte: UInt8) -> Bool {
        (byte >= 48 && byte <= 57) || (byte >= 97 && byte <= 102)
    }
}


enum PamHotReloadSyncError: Error {
    case invalidManifest
    case deltaMismatch
    case verificationFailed
    case unsafePath
    case invalidObjectStream
    case objectVerificationFailed
    case pendingRequest
}

/// What one version needs: files to write, files to delete, digests to download.
struct PamHotReloadPlan {
    let manifest: PamHotReloadManifest
    let changed: [PamHotReloadFile]
    let removed: [String]
    let missing: Set<String>
    /// No live tree to update in place: the whole tree is written anew.
    let fresh: Bool

    var isEmpty: Bool { changed.isEmpty && removed.isEmpty }
    var changedBytes: Int64 { changed.reduce(0) { $0 + $1.size } }
}

/// Device side of `pam dev` sync (same layout and rules as the Android
/// `HotReloadStore`): `live/` is the stable tree the runtime runs, so OPcache
/// and the component compile cache (keyed by path) only recompile edited
/// files; `live.manifest` records what it holds; `objects/` keeps verified
/// incoming files until they are applied. Edits to a tree in use are applied
/// between two PHP requests, and every written file gets a strictly newer
/// whole-second modification date so OPcache never keeps stale code.
final class PamHotReloadStore {
    let root: URL
    let live: URL
    var liveEntry: URL { live.appendingPathComponent("index.php", isDirectory: false) }
    private let objects: URL
    private let manifestFile: URL
    private let nextLive: URL
    private let localSources: [URL]
    private let lock = NSLock()
    private var lastStampSeconds: Int64 = 0
    private(set) var liveManifest: PamHotReloadManifest?
    private(set) var pendingVersion: String?

    init(root: URL, localSources: [URL] = []) {
        self.root = root
        self.localSources = localSources
        live = root.appendingPathComponent("live", isDirectory: true)
        objects = root.appendingPathComponent("objects", isDirectory: true)
        manifestFile = root.appendingPathComponent("live.manifest", isDirectory: false)
        nextLive = root.appendingPathComponent("live.next", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        liveManifest = Self.loadManifest(manifestFile, entry: liveEntry)
        if let modified = try? manifestFile.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate {
            lastStampSeconds = Int64(modified.timeIntervalSince1970)
        }
        // Interrupted writes and the per-version directories of older hosts.
        let kept: Set<String> = ["live", "live.manifest"]
        for child in (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        where !kept.contains(child.lastPathComponent) {
            try? FileManager.default.removeItem(at: child)
        }
    }

    var hasPendingRequest: Bool {
        lock.lock(); defer { lock.unlock() }
        return pendingVersion != nil
    }

    var currentLiveManifest: PamHotReloadManifest? {
        lock.lock(); defer { lock.unlock() }
        return liveManifest
    }

    private static func loadManifest(_ file: URL, entry: URL) -> PamHotReloadManifest? {
        guard FileManager.default.fileExists(atPath: entry.path),
              let text = try? String(contentsOf: file, encoding: .utf8),
              let newline = text.firstIndex(of: "\n") else { return nil }
        return try? PamHotReloadManifest.parse(
            String(text[text.index(after: newline)...]),
            expectedVersion: String(text[..<newline]),
            base: nil
        )
    }

    func plan(_ next: PamHotReloadManifest) -> PamHotReloadPlan {
        lock.lock(); defer { lock.unlock() }
        let base = FileManager.default.fileExists(atPath: live.path) ? liveManifest : nil
        let changed = next.files.values
            .filter { base?.files[$0.path]?.sha256 != $0.sha256 }
            .sorted { $0.path < $1.path }
        let removed = base.map { base in base.files.keys.filter { next.files[$0] == nil }.sorted() } ?? []
        var missing = Set<String>()
        for file in changed where !FileManager.default.fileExists(atPath: objectURL(file.sha256).path) {
            if !importLocal(file) { missing.insert(file.sha256) }
        }
        return PamHotReloadPlan(manifest: next, changed: changed, removed: removed, missing: missing, fresh: base == nil)
    }

    private func importLocal(_ file: PamHotReloadFile) -> Bool {
        for directory in localSources {
            let candidate = directory.appendingPathComponent(file.path, isDirectory: false)
            guard let handle = try? FileHandle(forReadingFrom: candidate) else { continue }
            defer { try? handle.close() }
            if (try? writeObject(file.sha256, size: file.size, read: { maximum in
                try handle.read(upToCount: maximum) ?? Data()
            })) == true {
                return true
            }
        }
        return false
    }

    /// Reads a `PNO1` stream holding every digest of `expected` and stores each
    /// verified file under `objects/`. Returns the content bytes read.
    @discardableResult
    func receive(from handle: FileHandle, expected: Set<String>) throws -> Int64 {
        lock.lock(); defer { lock.unlock() }
        guard try Self.readExactly(handle, 4) == Data("PNO1".utf8) else {
            throw PamHotReloadSyncError.invalidObjectStream
        }
        var remaining = expected
        var bytes: Int64 = 0
        while !remaining.isEmpty {
            let digest = try Self.readExactly(handle, 32).map { String(format: "%02x", $0) }.joined()
            guard remaining.remove(digest) != nil else { throw PamHotReloadSyncError.invalidObjectStream }
            let sizeData = try Self.readExactly(handle, 8)
            let size = sizeData.enumerated().reduce(Int64(0)) { $0 | (Int64($1.element) << (8 * Int64($1.offset))) }
            guard (0...(64 * 1024 * 1024)).contains(size) else { throw PamHotReloadSyncError.invalidObjectStream }
            var left = size
            let accepted = try writeObject(digest, size: size) { maximum in
                guard left > 0 else { return Data() }
                let chunk = try Self.readExactly(handle, Int(min(Int64(maximum), left)))
                left -= Int64(chunk.count)
                return chunk
            }
            guard accepted else { throw PamHotReloadSyncError.objectVerificationFailed }
            bytes += size
        }
        return bytes
    }

    private static func readExactly(_ handle: FileHandle, _ count: Int) throws -> Data {
        var data = Data()
        while data.count < count {
            guard let chunk = try handle.read(upToCount: count - data.count), !chunk.isEmpty else {
                throw PamHotReloadSyncError.invalidObjectStream
            }
            data.append(chunk)
        }
        return data
    }

    /// Streams `size` bytes from `read` into `objects/<sha256>` when the digest matches.
    private func writeObject(_ sha256: String, size: Int64, read: (Int) throws -> Data) throws -> Bool {
        try FileManager.default.createDirectory(at: objects, withIntermediateDirectories: true)
        let temporary = objects.appendingPathComponent("\(sha256).part", isDirectory: false)
        FileManager.default.createFile(atPath: temporary.path, contents: nil)
        let output = try FileHandle(forWritingTo: temporary)
        var hasher = SHA256()
        var written: Int64 = 0
        do {
            while written <= size {
                let chunk = try read(64 * 1024)
                if chunk.isEmpty { break }
                written += Int64(chunk.count)
                if written > size { break }
                hasher.update(data: chunk)
                try output.write(contentsOf: chunk)
            }
            try output.close()
        } catch {
            try? output.close()
            try? FileManager.default.removeItem(at: temporary)
            throw error
        }
        let digest = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        guard written == size, digest == sha256 else {
            try? FileManager.default.removeItem(at: temporary)
            return false
        }
        let target = objectURL(sha256)
        try? FileManager.default.removeItem(at: target)
        try FileManager.default.moveItem(at: temporary, to: target)
        return true
    }

    private func objectURL(_ sha256: String) -> URL {
        objects.appendingPathComponent(sha256, isDirectory: false)
    }

    func begin(_ plan: PamHotReloadPlan) throws {
        lock.lock(); defer { lock.unlock() }
        guard pendingVersion == nil else { throw PamHotReloadSyncError.pendingRequest }
        pendingVersion = plan.manifest.version
    }

    /// Writes `plan` into `live/`. Call only while no PHP request runs from
    /// `live/`: before the first switch to it, or between two requests.
    func apply(_ plan: PamHotReloadPlan) throws {
        lock.lock(); defer { lock.unlock() }
        let manager = FileManager.default
        let stamp = nextStamp()
        if plan.fresh {
            try? manager.removeItem(at: nextLive)
            try manager.createDirectory(at: nextLive, withIntermediateDirectories: true)
            for file in plan.manifest.files.values { try place(file, in: nextLive, stamp: stamp) }
            try? manager.removeItem(at: live)
            try manager.moveItem(at: nextLive, to: live)
        } else {
            for file in plan.changed { try place(file, in: live, stamp: stamp) }
            for path in plan.removed { try? manager.removeItem(at: live.appendingPathComponent(path, isDirectory: false)) }
        }
        let temporary = root.appendingPathComponent("live.manifest.tmp", isDirectory: false)
        try Data((plan.manifest.version + "\n" + plan.manifest.text()).utf8).write(to: temporary)
        try? manager.removeItem(at: manifestFile)
        try manager.moveItem(at: temporary, to: manifestFile)
        liveManifest = plan.manifest
        try? manager.removeItem(at: objects)
    }

    private func place(_ file: PamHotReloadFile, in directory: URL, stamp: Date) throws {
        let manager = FileManager.default
        let target = directory.appendingPathComponent(file.path, isDirectory: false)
        guard target.standardizedFileURL.path.hasPrefix(directory.standardizedFileURL.path + "/") else {
            throw PamHotReloadSyncError.unsafePath
        }
        let parent = target.deletingLastPathComponent()
        try manager.createDirectory(at: parent, withIntermediateDirectories: true)
        let temporary = parent.appendingPathComponent(".\(target.lastPathComponent).pamnext", isDirectory: false)
        try? manager.removeItem(at: temporary)
        // On APFS this is a copy-on-write clone.
        try manager.copyItem(at: objectURL(file.sha256), to: temporary)
        try manager.setAttributes([.modificationDate: stamp], ofItemAtPath: temporary.path)
        if manager.fileExists(atPath: target.path) {
            _ = try manager.replaceItemAt(target, withItemAt: temporary)
        } else {
            try manager.moveItem(at: temporary, to: target)
        }
    }

    /// Whole seconds (OPcache compares seconds), strictly increasing per apply.
    private func nextStamp() -> Date {
        let seconds = max(Int64(Date().timeIntervalSince1970), lastStampSeconds + 1)
        lastStampSeconds = seconds
        return Date(timeIntervalSince1970: TimeInterval(seconds))
    }

    func previousRequestReleased(_ version: String) {
        lock.lock(); defer { lock.unlock() }
        if pendingVersion == version { pendingVersion = nil }
    }

    func abandon(_ version: String) {
        previousRequestReleased(version)
    }
}
