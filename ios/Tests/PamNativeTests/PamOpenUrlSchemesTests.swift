import XCTest
@testable import PamNative

final class PamOpenUrlSchemesTests: XCTestCase {
    func testDefaultsAcceptWebMailPhoneAndMapsOnly() {
        let schemes = SystemModule.openSchemes(declared: [])
        XCTAssertEqual(schemes, ["https", "http", "mailto", "tel", "geo"])
        XCTAssertFalse(schemes.contains("otpauth"))
    }

    func testDeclaredSchemesAreAddedButDangerousOnesNever() {
        let schemes = SystemModule.openSchemes(declared: ["otpauth", " WhatsApp ", "", "file", "javascript", "intent"])
        XCTAssertTrue(schemes.contains("otpauth"))
        XCTAssertTrue(schemes.contains("whatsapp"))
        for blocked in ["file", "javascript", "intent", ""] {
            XCTAssertFalse(schemes.contains(blocked), blocked)
        }
    }
}
