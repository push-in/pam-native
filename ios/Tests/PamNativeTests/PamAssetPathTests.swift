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
        [
            "asset://",
            "asset://assets/../secrets.png",
            "asset://assets//brand.png",
            "asset://assets/brand.png?size=2",
            "asset://assets/brand.png#icon",
        ].forEach { source in
            XCTAssertThrowsError(try normalizedPamAssetPath(source))
        }
    }
}
