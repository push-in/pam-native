import Foundation
import XCTest
@testable import PamNative

final class PamDevBundleTests: XCTestCase {
    func testExtractsAndActivatesAValidBundle() throws {
        let root = fixtureDirectory("valid")
        defer { try? FileManager.default.removeItem(at: root) }
        let destination = root.appendingPathComponent("version")

        let entry = try PamDevBundle.extract(bundle([
            ("index.php", Data("<?php".utf8)),
            ("src/App.php", Data("app".utf8)),
        ]), to: destination)

        XCTAssertEqual(try String(contentsOf: entry, encoding: .utf8), "<?php")
        XCTAssertEqual(
            try String(contentsOf: destination.appendingPathComponent("src/App.php"), encoding: .utf8),
            "app"
        )
        XCTAssertFalse(root.appendingPathComponent(".version.incoming").fileExists)
        XCTAssertFalse(root.appendingPathComponent(".version.previous").fileExists)
    }

    func testMalformedBundlesCannotReplaceTheActiveApplication() throws {
        let root = fixtureDirectory("preserve")
        defer { try? FileManager.default.removeItem(at: root) }
        let destination = root.appendingPathComponent("version")
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        let active = destination.appendingPathComponent("index.php")
        try Data("active".utf8).write(to: active)

        for malformed in [
            bundle([("../index.php", Data("escape".utf8))]),
            bundle([("index.php", Data()), ("index.php", Data())]),
            Data("PNA1".utf8),
            bundle([("src/App.php", Data())]),
            bundle([("index.php", Data())]) + Data([0]),
        ] {
            XCTAssertThrowsError(try PamDevBundle.extract(malformed, to: destination))
            XCTAssertEqual(try String(contentsOf: active, encoding: .utf8), "active")
        }
    }

    func testRecoversAnInterruptedPreviousActivation() throws {
        let root = fixtureDirectory("recovery")
        defer { try? FileManager.default.removeItem(at: root) }
        let destination = root.appendingPathComponent("version")
        let backup = root.appendingPathComponent(".version.previous")
        try FileManager.default.createDirectory(at: backup, withIntermediateDirectories: true)
        try Data("previous".utf8).write(to: backup.appendingPathComponent("index.php"))

        XCTAssertThrowsError(try PamDevBundle.extract(Data(), to: destination))

        XCTAssertEqual(
            try String(contentsOf: destination.appendingPathComponent("index.php"), encoding: .utf8),
            "previous"
        )
        XCTAssertFalse(backup.fileExists)
    }

    private func fixtureDirectory(_ name: String) -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(
            "pam-ios-dev-bundle-\(name)-\(UUID().uuidString)"
        )
        try! FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    private func bundle(_ files: [(String, Data)]) -> Data {
        var data = Data("PNA1".utf8)
        data.appendLittleEndian(UInt32(files.count))
        for (path, contents) in files {
            let pathData = Data(path.utf8)
            data.appendLittleEndian(UInt16(pathData.count))
            data.append(pathData)
            data.appendLittleEndian(UInt32(contents.count))
            data.append(contents)
        }
        return data
    }
}

private extension URL {
    var fileExists: Bool {
        FileManager.default.fileExists(atPath: path)
    }
}

private extension Data {
    mutating func appendLittleEndian<T: FixedWidthInteger>(_ value: T) {
        var encoded = value.littleEndian
        Swift.withUnsafeBytes(of: &encoded) { append(contentsOf: $0) }
    }
}
