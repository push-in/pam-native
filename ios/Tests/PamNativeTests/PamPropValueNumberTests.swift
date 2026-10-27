import XCTest
@testable import PamNative

final class PamPropValueNumberTests: XCTestCase {
    func testIntegerPropertiesAreNumericLikeAndroid() {
        XCTAssertEqual(PropValue.integer(1).decimalOrNil(), 1)
        XCTAssertEqual(PropValue.integer(0).decimalOrZero(), 0)
        XCTAssertEqual(PropValue.decimal(0.5).decimalOrZero(), 0.5)
        XCTAssertNil(PropValue.text("1").decimalOrNil())
    }
}
