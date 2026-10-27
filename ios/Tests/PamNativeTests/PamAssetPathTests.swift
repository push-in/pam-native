import XCTest
@testable import PamNative

final class PamAssetPathTests: XCTestCase {
    func testResolvesProjectAndExplicitPamAssetPaths() throws {
        XCTAssertEqual(
            try normalizedPamAssetPath("asset://assets/logos/brand.png"),
            "pam/assets/logos/brand.png"
        )
        XCTAssertEqual(
            try normalizedPamAssetPath("asset://pam/assets/logos/brand.png"),
            "pam/assets/logos/brand.png"
        )
        XCTAssertEqual(
            try normalizedPamAssetPath("ASSET://avatar.webp"),
            "pam/avatar.webp"
        )
    }

    func testLeavesNonAssetSourcesUntouched() throws {
        XCTAssertNil(try normalizedPamAssetPath("https://cdn.example.com/avatar.png"))
    }

    func testRejectsEmptyTraversalAndUriSuffixes() throws {
        try [
            "asset://",
            "asset://assets/../secrets.png",
            "asset://assets//brand.png",
            "asset://assets/brand.png?size=2",
            "asset://assets/brand.png#icon",
        ].forEach { source in
            XCTAssertThrowsError(try normalizedPamAssetPath(source))
        }
    }

    func testFindsAssetsInTheStagedPamBundleBeforeThePamLayout() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("pam-asset-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let staged = root.appendingPathComponent("PamBundle/assets/logos/brand.png")
        let legacy = root.appendingPathComponent("pam/assets/fonts/Inter.ttf")
        for file in [staged, legacy] {
            try FileManager.default.createDirectory(
                at: file.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try Data([1]).write(to: file)
        }

        XCTAssertEqual(
            pamAssetFileURL("pam/assets/logos/brand.png", resourceRoot: root)?.standardizedFileURL,
            staged.standardizedFileURL
        )
        XCTAssertEqual(
            pamAssetFileURL("pam/assets/fonts/Inter.ttf", resourceRoot: root)?.standardizedFileURL,
            legacy.standardizedFileURL
        )
        XCTAssertNil(pamAssetFileURL("pam/assets/missing.png", resourceRoot: root))
    }
}
