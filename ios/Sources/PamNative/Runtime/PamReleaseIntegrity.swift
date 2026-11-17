import Darwin
import Foundation

/// Completion marker of an extracted release directory (`.pam-release`),
/// shared format with Android's `ReleaseIntegrity`.
///
/// A process killed mid-extraction can leave the release path populated with
/// files whose data never reached the disk: empty or missing PHP sources
/// behind a present `index.php`. A release is reused only when this marker,
/// written last after every file and directory was fsynced, lists every file
/// at its recorded size. The check is one small read plus one `stat` per
/// file, never a content hash.
///
/// Format: `pam-release 1 <version> <installation id>`, then one
/// `<size> <relative path>` line per file.
enum PamReleaseIntegrity {
    static let marker = ".pam-release"
    private static let header = "pam-release 1 "

    struct File: Sendable, Equatable {
        let path: String
        let size: Int64
    }

    enum IntegrityError: Error {
        case io(String, Int32)
        case invalidVersion
    }

    /// Regular files under `root` (the marker excluded) with their sizes.
    static func inventory(root: URL) throws -> [File] {
        var files: [File] = []
        try collect(directory: root.path, relative: "", into: &files)
        return files
    }

    private static func collect(directory: String, relative: String, into files: inout [File]) throws {
        for name in try FileManager.default.contentsOfDirectory(atPath: directory).sorted() {
            let path = directory + "/" + name
            let relativePath = relative.isEmpty ? name : relative + "/" + name
            var status = stat()
            guard lstat(path, &status) == 0 else { throw IntegrityError.io(path, errno) }
            switch status.st_mode & S_IFMT {
            case S_IFDIR:
                try collect(directory: path, relative: relativePath, into: &files)
            case S_IFREG:
                if relativePath != marker { files.append(File(path: relativePath, size: Int64(status.st_size))) }
            default:
                continue
            }
        }
    }

    /// Fsyncs `root` and every directory that holds a `files` entry, deepest first.
    static func syncDirectories(root: URL, files: [File]) {
        var directories = Set<String>()
        for file in files {
            var components = file.path.split(separator: "/").dropLast()
            while !components.isEmpty, directories.insert(components.joined(separator: "/")).inserted {
                components = components.dropLast()
            }
        }
        for directory in directories.sorted(by: { $0.count > $1.count }) {
            syncDirectory(root.appendingPathComponent(directory, isDirectory: true).path)
        }
        syncDirectory(root.path)
    }

    /// Best effort: a filesystem that refuses a directory fsync still gets the marker check.
    static func syncDirectory(_ path: String) {
        let descriptor = open(path, O_RDONLY)
        guard descriptor >= 0 else { return }
        _ = fsync(descriptor)
        _ = close(descriptor)
    }

    /// Writes `data` to a temporary beside `url`, fsyncs it, then renames it into place.
    static func writeDurably(_ data: Data, to url: URL) throws {
        let temporary = url.path + ".tmp"
        let descriptor = open(temporary, O_WRONLY | O_CREAT | O_TRUNC, 0o600)
        guard descriptor >= 0 else { throw IntegrityError.io(temporary, errno) }
        var failure: Int32 = 0
        data.withUnsafeBytes { buffer in
            var offset = 0
            while offset < buffer.count {
                let written = Darwin.write(descriptor, buffer.baseAddress! + offset, buffer.count - offset)
                if written < 0 {
                    if errno == EINTR { continue }
                    failure = errno
                    return
                }
                offset += written
            }
        }
        if failure == 0, fsync(descriptor) != 0 { failure = errno }
        _ = close(descriptor)
        guard failure == 0 else {
            unlink(temporary)
            throw IntegrityError.io(temporary, failure)
        }
        guard rename(temporary, url.path) == 0 else {
            let code = errno
            unlink(temporary)
            throw IntegrityError.io(url.path, code)
        }
    }

    /// Records `release` as complete. Callers fsync every listed file and directory first.
    @discardableResult
    static func write(release: URL, version: String, files: [File]) throws -> String {
        guard !version.isEmpty, !version.contains(" "), !version.contains("\n") else {
            throw IntegrityError.invalidVersion
        }
        let id = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        var text = header + version + " " + id + "\n"
        text.reserveCapacity(text.utf8.count + files.count * 64)
        for file in files {
            text += "\(file.size) \(file.path)\n"
        }
        try writeDurably(Data(text.utf8), to: release.appendingPathComponent(marker, isDirectory: false))
        syncDirectory(release.path)
        return id
    }

    /// True when `release` carries a marker for `version` and every listed
    /// file exists at its recorded size.
    static func isComplete(release: URL, version: String) -> Bool {
        guard let data = try? Data(contentsOf: release.appendingPathComponent(marker, isDirectory: false)) else {
            return false
        }
        let lines = String(decoding: data, as: UTF8.self).split(separator: "\n", omittingEmptySubsequences: true)
        guard let first = lines.first, first.hasPrefix(header) else { return false }
        let identity = first.dropFirst(header.count).split(separator: " ")
        guard identity.count == 2, identity[0] == version else { return false }
        guard lines.count > 1 else { return false }
        let base = release.path + "/"
        for line in lines.dropFirst() {
            guard let space = line.firstIndex(of: " "),
                  let size = Int64(line[line.startIndex..<space]) else { return false }
            let path = base + line[line.index(after: space)...]
            var status = stat()
            guard stat(path, &status) == 0,
                  status.st_mode & S_IFMT == S_IFREG,
                  Int64(status.st_size) == size else { return false }
        }
        return true
    }

    /// Installation id of a release's marker, nil without one.
    static func installationId(release: URL) -> String? {
        guard let data = try? Data(contentsOf: release.appendingPathComponent(marker, isDirectory: false)),
              let first = String(decoding: data, as: UTF8.self).split(separator: "\n").first,
              first.hasPrefix(header),
              let id = first.split(separator: " ").last else { return nil }
        return String(id)
    }
}
