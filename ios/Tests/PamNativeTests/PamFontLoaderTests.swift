import CoreText
import XCTest
@testable import PamNative

final class PamFontLoaderTests: XCTestCase {
    func testPackagedVariableFontUsesItsRequestedWeight() throws {
        var repository = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { repository.deleteLastPathComponent() }
        let fixture = repository.appendingPathComponent("crates/pam-native-engine/tests/fixtures/fonts/Inter.ttf")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let fonts = root.appendingPathComponent("pam/fonts")
        try FileManager.default.createDirectory(at: fonts, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.copyItem(at: fixture, to: fonts.appendingPathComponent("Inter.ttf"))
        let loader = PamFontLoader(resourceRoot: root)
        for weight in [400, 500, 600, 700] {
            let font = try XCTUnwrap(loader.assetFont(family: "asset://fonts/Inter.ttf", size: 22, weight: weight))
            XCTAssertTrue(font.familyName.contains("Inter"))
            let variations = try XCTUnwrap(CTFontCopyVariation(font) as? [NSNumber: NSNumber])
            XCTAssertEqual(variations[NSNumber(value: 0x77676874)]?.intValue, weight)
        }
        XCTAssertNil(loader.assetFont(family: "asset://../outside.ttf", size: 22, weight: 400))
        XCTAssertNil(loader.assetFont(family: "asset://fonts/missing.ttf", size: 22, weight: 400))
    }
}
