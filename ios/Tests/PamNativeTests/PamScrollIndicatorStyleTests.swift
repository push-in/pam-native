import XCTest
@testable import PamNative

final class PamScrollIndicatorStyleTests: XCTestCase {
    func testAppearanceUsesSequentialWireValuesAndNativeStyles() {
        XCTAssertEqual(PamConstants.scrollIndicatorStyle, 465)
        XCTAssertEqual(PamScrollIndicatorStyle.auto.rawValue, 1)
        XCTAssertEqual(PamScrollIndicatorStyle.dark.rawValue, 2)
        XCTAssertEqual(PamScrollIndicatorStyle.light.rawValue, 3)
        XCTAssertEqual(PamScrollIndicatorStyle.auto.native, .default)
        XCTAssertEqual(PamScrollIndicatorStyle.dark.native, .black)
        XCTAssertEqual(PamScrollIndicatorStyle.light.native, .white)
        XCTAssertNil(PamScrollIndicatorStyle(rawValue: 0))
        XCTAssertNil(PamScrollIndicatorStyle(rawValue: 4))
    }
}
