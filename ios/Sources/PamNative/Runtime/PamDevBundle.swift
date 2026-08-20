import Foundation

enum PamDevBundle {
    private static let maximumBundleBytes = 16 * 1024 * 1024
    private static let maximumFiles = 10_000
    private static let maximumFileBytes = 8 * 1024 * 1024

    static func extract(_ data: Data, to destination: URL) throws -> URL {
        guard data.count <= maximumBundleBytes else {
            throw BundleError.bundleTooLarge
        }
        let manager = FileManager.default
        let parent = destination.deletingLastPathComponent()
        guard !destination.lastPathComponent.isEmpty else {
            throw BundleError.invalidDestination
        }
        try manager.createDirectory(at: parent, withIntermediateDirectories: true)
        let parentValues = try parent.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        guard parentValues.isDirectory == true, parentValues.isSymbolicLink != true else {
            throw BundleError.invalidDestination
        }
        let staging = parent.appendingPathComponent(".\(destination.lastPathComponent).incoming")
        let backup = parent.appendingPathComponent(".\(destination.lastPathComponent).previous")

        if !manager.fileExists(atPath: destination.path), manager.fileExists(atPath: backup.path) {
            try manager.moveItem(at: backup, to: destination)
        }
        if manager.fileExists(atPath: staging.path) {
            try manager.removeItem(at: staging)
        }
        try manager.createDirectory(at: staging, withIntermediateDirectories: false)

        do {
            try extract(data, into: staging, manager: manager)
            try activate(staging: staging, destination: destination, backup: backup, manager: manager)
        } catch {
            try? manager.removeItem(at: staging)
            throw error
        }
        return destination.appendingPathComponent("index.php", isDirectory: false)
    }

    private static func extract(_ data: Data, into staging: URL, manager: FileManager) throws {
        var reader = Reader(data)
        guard try reader.text(4) == "PNA1" else {
            throw BundleError.invalidMagic
        }
        let fileCount = try reader.unsigned32()
        guard (1...maximumFiles).contains(fileCount) else {
            throw BundleError.invalidFileCount
        }
        var paths = Set<String>()
        for _ in 0..<fileCount {
            let path = try reader.text(reader.unsigned16())
            guard isSafe(path: path) else {
                throw BundleError.unsafePath
            }
            guard paths.insert(path).inserted else {
                throw BundleError.duplicatePath
            }
            let length = try reader.unsigned32()
            guard length <= maximumFileBytes else {
                throw BundleError.fileTooLarge
            }
            let contents = try reader.bytes(length)
            let target = staging.appendingPathComponent(path, isDirectory: false)
            try manager.createDirectory(
                at: target.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try contents.write(to: target, options: .atomic)
        }
        try reader.finish()

        let entry = staging.appendingPathComponent("index.php", isDirectory: false)
        var isDirectory: ObjCBool = false
        guard manager.fileExists(atPath: entry.path, isDirectory: &isDirectory), !isDirectory.boolValue else {
            throw BundleError.missingEntry
        }
    }

    private static func activate(
        staging: URL,
        destination: URL,
        backup: URL,
        manager: FileManager
    ) throws {
        if manager.fileExists(atPath: backup.path) {
            try manager.removeItem(at: backup)
        }
        let hadActive = manager.fileExists(atPath: destination.path)
        if hadActive {
            let values = try destination.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard values.isDirectory == true, values.isSymbolicLink != true else {
                throw BundleError.invalidDestination
            }
            try manager.moveItem(at: destination, to: backup)
        }
        do {
            try manager.moveItem(at: staging, to: destination)
        } catch {
            if hadActive {
                try manager.moveItem(at: backup, to: destination)
            }
            throw error
        }
        if hadActive {
            try manager.removeItem(at: backup)
        }
    }

    private static func isSafe(path: String) -> Bool {
        guard !path.isEmpty, !path.hasPrefix("/"), !path.contains("\\") else {
            return false
        }
        return path.split(separator: "/", omittingEmptySubsequences: false).allSatisfy { segment in
            guard !segment.isEmpty, segment != ".", segment != "..", segment.utf8.count <= 255 else {
                return false
            }
            return segment.utf8.allSatisfy { byte in
                byte >= 65 && byte <= 90
                    || byte >= 97 && byte <= 122
                    || byte >= 48 && byte <= 57
                    || byte == 46 || byte == 95 || byte == 45
            }
        }
    }

    private struct Reader {
        private let data: Data
        private var offset = 0

        init(_ data: Data) {
            self.data = data
        }

        mutating func unsigned16() throws -> Int {
            let value = try bytes(2)
            return Int(value[0]) | Int(value[1]) << 8
        }

        mutating func unsigned32() throws -> Int {
            let value = try bytes(4)
            let decoded = UInt32(value[0])
                | UInt32(value[1]) << 8
                | UInt32(value[2]) << 16
                | UInt32(value[3]) << 24
            guard UInt64(decoded) <= UInt64(Int.max) else {
                throw BundleError.fieldTooLarge
            }
            return Int(decoded)
        }

        mutating func text(_ count: Int) throws -> String {
            let value = try bytes(count)
            guard let decoded = String(data: value, encoding: .utf8) else {
                throw BundleError.invalidText
            }
            return decoded
        }

        mutating func bytes(_ count: Int) throws -> Data {
            guard count >= 0, offset <= data.count, count <= data.count - offset else {
                throw BundleError.truncated
            }
            defer { offset += count }
            return data.subdata(in: offset..<(offset + count))
        }

        func finish() throws {
            guard offset == data.count else {
                throw BundleError.trailingBytes
            }
        }
    }

    enum BundleError: Error {
        case bundleTooLarge
        case invalidDestination
        case invalidMagic
        case invalidFileCount
        case unsafePath
        case duplicatePath
        case fileTooLarge
        case missingEntry
        case fieldTooLarge
        case invalidText
        case truncated
        case trailingBytes
    }
}
