import CryptoKit
import XCTest
@testable import PamNative

/// Mirrors android/app/src/test/.../HotReloadStoreTest.kt.
final class PamHotReloadStoreTests: XCTestCase {
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("pam-hot-reload-store-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    func testManifestVerifiesFullTextsAndDeltasAgainstTheVersionDigest() throws {
        let base = try manifest(["index.php": "<?php", "src/A.php": "a", "src/Old.php": "o"])
        let next = files(["index.php": "<?php", "src/A.php": "a2", "src/New.php": "n"])
        let delta = "PNMD1 \(base.version)\n+ \(line(next["src/A.php"]!))\n+ \(line(next["src/New.php"]!))\n- src/Old.php\n"
        let applied = try PamHotReloadManifest.parse(delta, expectedVersion: version(next), base: base)
        XCTAssertEqual(applied.files.keys.sorted(), ["index.php", "src/A.php", "src/New.php"])
        XCTAssertThrowsError(try PamHotReloadManifest.parse(delta, expectedVersion: version(files(["index.php": "x"])), base: base))
        XCTAssertThrowsError(try PamHotReloadManifest.parse(delta, expectedVersion: version(next), base: nil))
        XCTAssertFalse(PamHotReloadManifest.isSafePath("../escape.php"))
    }

    func testFirstSyncReusesBootFilesAndEditsApplyInPlace() throws {
        let boot = root.appendingPathComponent("release", isDirectory: true)
        try write("<?php", to: boot.appendingPathComponent("index.php"))
        try write("a", to: boot.appendingPathComponent("src/A.php"))
        let store = PamHotReloadStore(root: root.appendingPathComponent("dev"), localSources: [boot])
        let first = try manifest(["index.php": "<?php", "src/A.php": "a", "src/B.php": "b"])

        let plan = store.plan(first)
        XCTAssertTrue(plan.fresh)
        XCTAssertEqual(plan.missing, [sha("b")])
        XCTAssertEqual(try store.receive(from: try objects(["b"]), expected: plan.missing), 1)
        try store.apply(plan)
        let edited = store.live.appendingPathComponent("src/A.php")
        XCTAssertEqual(try String(contentsOf: edited), "a")
        let firstStamp = try XCTUnwrap(
            FileManager.default.attributesOfItem(atPath: edited.path)[.modificationDate] as? Date
        )

        let second = try manifest(["index.php": "<?php", "src/A.php": "a2"])
        let delta = store.plan(second)
        XCTAssertFalse(delta.fresh)
        XCTAssertEqual(delta.changed.map(\.path), ["src/A.php"])
        XCTAssertEqual(delta.removed, ["src/B.php"])
        try store.receive(from: try objects(["a2"]), expected: delta.missing)
        try store.begin(delta)
        XCTAssertThrowsError(try store.begin(delta))
        try store.apply(delta)
        store.previousRequestReleased(second.version)
        XCTAssertFalse(store.hasPendingRequest)
        XCTAssertEqual(try String(contentsOf: edited), "a2")
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.live.appendingPathComponent("src/B.php").path))
        let secondStamp = try XCTUnwrap(
            FileManager.default.attributesOfItem(atPath: edited.path)[.modificationDate] as? Date
        )
        XCTAssertGreaterThanOrEqual(secondStamp.timeIntervalSince(firstStamp), 1)

        let reopened = PamHotReloadStore(root: root.appendingPathComponent("dev"))
        XCTAssertEqual(reopened.currentLiveManifest?.version, second.version)
        XCTAssertTrue(reopened.plan(second).isEmpty)
    }

    func testObjectStreamsRejectTamperedObjects() throws {
        let store = PamHotReloadStore(root: root.appendingPathComponent("dev"))
        var tampered = Data("PNO1".utf8)
        tampered.append(Data(SHA256.hash(data: Data("good".utf8))))
        tampered.append(littleEndian(4))
        tampered.append(Data("evil".utf8))
        XCTAssertThrowsError(try store.receive(from: try handle(tampered), expected: [sha("good")]))
        XCTAssertThrowsError(try store.receive(from: try objects(["other"]), expected: [sha("good")]))
    }

    private func sha(_ text: String) -> String { PamHotReloadManifest.sha256Hex(Data(text.utf8)) }

    private func files(_ entries: [String: String]) -> [String: PamHotReloadFile] {
        entries.reduce(into: [:]) { result, entry in
            result[entry.key] = PamHotReloadFile(path: entry.key, sha256: sha(entry.value), size: Int64(entry.value.utf8.count))
        }
    }

    private func line(_ file: PamHotReloadFile) -> String { "\(file.sha256) \(file.size) \(file.path)" }

    private func text(_ files: [String: PamHotReloadFile]) -> String {
        PamHotReloadManifest.magic + files.keys.sorted().map { line(files[$0]!) + "\n" }.joined()
    }

    private func version(_ files: [String: PamHotReloadFile]) -> String {
        PamHotReloadManifest.sha256Hex(Data(text(files).utf8))
    }

    private func manifest(_ entries: [String: String]) throws -> PamHotReloadManifest {
        let files = files(entries)
        return try PamHotReloadManifest.parse(text(files), expectedVersion: version(files), base: nil)
    }

    private func littleEndian(_ value: UInt64) -> Data {
        withUnsafeBytes(of: value.littleEndian) { Data($0) }
    }

    private func objects(_ contents: [String]) throws -> FileHandle {
        var data = Data("PNO1".utf8)
        for content in contents {
            data.append(Data(SHA256.hash(data: Data(content.utf8))))
            data.append(littleEndian(UInt64(content.utf8.count)))
            data.append(Data(content.utf8))
        }
        return try handle(data)
    }

    private func handle(_ data: Data) throws -> FileHandle {
        let file = root.appendingPathComponent("stream-\(UUID().uuidString)")
        try data.write(to: file)
        return try FileHandle(forReadingFrom: file)
    }

    private func write(_ text: String, to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(text.utf8).write(to: url)
    }
}
