import XCTest
@testable import PamNative

final class MediaLibraryModuleTests: XCTestCase {
    func testPhotoAssetURIRoundTripsOpaqueLocalIdentifiers() {
        let identifier = "AA11BB22-CC33-DD44-EE55-FF6677889900/L0/001"
        let source = PamPhotoAssetURI.make(identifier)
        XCTAssertTrue(source.hasPrefix("phasset://asset/"))
        XCTAssertEqual(PamPhotoAssetURI.identifier(source), identifier)
        XCTAssertNil(PamPhotoAssetURI.identifier("phasset://other/abc"))
        XCTAssertNil(PamPhotoAssetURI.identifier("phasset://asset/%2F"))
    }

    func testMediaLibraryModuleIsRegisteredAndRejectsUnknownMethod() {
        let registry = NativeModuleRegistry()
        let completed = expectation(description: "registered media library responds")
        registry.invoke(module: "media-library", method: "unknown", payload: Data()) {
            status, payload in
            XCTAssertEqual(status, .failure)
            XCTAssertTrue(String(decoding: payload, as: UTF8.self).contains("Unknown media-library method"))
            completed.fulfill()
        }
        wait(for: [completed], timeout: 1)
        registry.close()
    }

    func testFilesRejectsAnUnavailablePhotoAsset() throws {
        let module = FilesModule()
        let completed = expectation(description: "missing photo asset rejected")
        module.invoke(
            method: "importUri",
            payload: try WireMap.encode([
                "uri": .text(PamPhotoAssetURI.make("missing-photo-asset")),
            ])
        ) { status, _ in
            XCTAssertEqual(status, .failure)
            completed.fulfill()
        }
        wait(for: [completed], timeout: 1)
    }

    func testPhotoAssetExportWritesChunksAndStopsBeforeExceedingLimit() throws {
        let healthy = FileManager.default.temporaryDirectory
            .appendingPathComponent("pam-photo-export-\(UUID().uuidString)")
        let oversized = FileManager.default.temporaryDirectory
            .appendingPathComponent("pam-photo-export-\(UUID().uuidString)")
        defer {
            try? FileManager.default.removeItem(at: healthy)
            try? FileManager.default.removeItem(at: oversized)
        }
        let accepted = try BoundedPhotoAssetExport(destination: healthy, maximumBytes: 5)
        accepted.append(Data("ab".utf8))
        accepted.append(Data("cde".utf8))
        XCTAssertEqual(try accepted.finish(error: nil), 5)
        XCTAssertEqual(try Data(contentsOf: healthy), Data("abcde".utf8))

        let rejected = try BoundedPhotoAssetExport(destination: oversized, maximumBytes: 5)
        rejected.append(Data("abc".utf8))
        rejected.append(Data("def".utf8))
        XCTAssertThrowsError(try rejected.finish(error: nil))
        XCTAssertEqual(try Data(contentsOf: oversized), Data("abc".utf8))
    }
}
