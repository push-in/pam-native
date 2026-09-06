import XCTest
@testable import PamNative

final class PrivateFileSha256Tests: XCTestCase {
    func testKnownVectorsAndFileBeyondBridgeLimit() throws {
        try temporary { root in
            let file = root.appendingPathComponent("document.bin")
            try Data().write(to: file)
            XCTAssertEqual(try PrivateFileSha256.digest(root: root, path: "document.bin"), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
            try Data("abc".utf8).write(to: file)
            XCTAssertEqual(try PrivateFileSha256.digest(root: root, path: "document.bin"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
            try Data(repeating: 42, count: 2 * 1024 * 1024).write(to: file)
            XCTAssertEqual(try PrivateFileSha256.digest(root: root, path: "document.bin"), "52d434cbc3b76fa5ce4480fefe3c7769b6be2708434ed3ea735c31b1213c1d38")
            XCTAssertEqual(try file.resourceValues(forKeys: [.fileSizeKey]).fileSize, 2 * 1024 * 1024)
        }
    }

    func testUnsafePathsAndExternalSymlinks() throws {
        try temporary { root in
            try FileManager.default.createDirectory(at: root.appendingPathComponent("folder"), withIntermediateDirectories: true)
            for path in ["", " ", "../outside", "/absolute", "folder", "missing", "a//b", "a/../b", "a\\b", "bad\nname"] {
                XCTAssertThrowsError(try PrivateFileSha256.digest(root: root, path: path))
            }
            let outside = root.deletingLastPathComponent().appendingPathComponent("outside")
            try Data("secret".utf8).write(to: outside)
            try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("link"), withDestinationURL: outside)
            XCTAssertThrowsError(try PrivateFileSha256.digest(root: root, path: "link"))
        }
    }

    func testOversizeCancellationAndTruncatedSource() throws {
        try temporary { root in
            let source = root.appendingPathComponent("large.bin")
            try Data().write(to: source)
            let writer = try FileHandle(forWritingTo: source)
            defer { try? writer.close() }
            try writer.truncate(atOffset: UInt64(PrivateFileSha256.maximumBytes + 1))
            XCTAssertThrowsError(try PrivateFileSha256.digest(root: root, path: "large.bin"))
            try writer.truncate(atOffset: 128 * 1024)
            XCTAssertThrowsError(try PrivateFileSha256.digest(root: root, path: "large.bin", cancelled: { true }))
            XCTAssertEqual(try source.resourceValues(forKeys: [.fileSizeKey]).fileSize, 128 * 1024)
            var checks = 0
            XCTAssertThrowsError(try PrivateFileSha256.digest(root: root, path: "large.bin", cancelled: {
                checks += 1
                if checks == 2 { try? writer.truncate(atOffset: 1) }
                return false
            }))
        }
    }

    private func temporary(_ test: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let root = directory.appendingPathComponent("files")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try test(root)
    }
}
