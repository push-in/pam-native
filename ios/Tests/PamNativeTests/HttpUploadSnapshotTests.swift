import XCTest
@testable import PamNative

final class HttpUploadSnapshotTests: XCTestCase {
    func testSnapshotIsStableAndRemovedAfterClose() throws {
        try temporary { root, cache in
            let source = root.appendingPathComponent("document.bin")
            let bytes = Data([0, 1, 255, 3])
            try bytes.write(to: source)
            let snapshot = try HttpUploadSnapshot.create(root: root, cache: cache, path: "document.bin")
            try Data("changed original".utf8).write(to: source)
            XCTAssertEqual(try Data(contentsOf: snapshot.url), bytes)
            try snapshot.close()
            try snapshot.close()
            XCTAssertFalse(FileManager.default.fileExists(atPath: snapshot.url.path))
            XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
        }
    }

    func testRejectsTraversalDirectoryMissingSourceAndEscapingSymlink() throws {
        try temporary { root, cache in
            try FileManager.default.createDirectory(at: root.appendingPathComponent("directory"), withIntermediateDirectories: true)
            let outside = cache.appendingPathComponent("outside")
            try Data("private".utf8).write(to: outside)
            try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("link"), withDestinationURL: outside)
            for path in ["", "/absolute", "../outside", "a/../b", "a\\b", "directory", "missing", "link"] {
                XCTAssertThrowsError(try HttpUploadSnapshot.create(root: root, cache: cache, path: path))
            }
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: cache.path), ["outside"])
        }
    }

    func testOversizeAndCancellationDoNotLeakSnapshots() throws {
        try temporary { root, cache in
            let source = root.appendingPathComponent("large.bin")
            XCTAssertTrue(FileManager.default.createFile(atPath: source.path, contents: nil))
            let file = try FileHandle(forWritingTo: source)
            try file.truncate(atOffset: UInt64(HttpUploadSnapshot.maximumBytes + 1))
            try file.close()
            XCTAssertThrowsError(try HttpUploadSnapshot.create(root: root, cache: cache, path: "large.bin"))
            try Data(repeating: 0, count: 128 * 1024).write(to: source)
            var checks = 0
            XCTAssertThrowsError(try HttpUploadSnapshot.create(root: root, cache: cache, path: "large.bin") {
                checks += 1
                return checks >= 3
            })
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: cache.path), [])
        }
    }

    private func temporary(_ test: (URL, URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let root = directory.appendingPathComponent("files")
        let cache = directory.appendingPathComponent("cache")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try test(root, cache)
    }
}
