import XCTest
@testable import PamNative

final class PamCutoutInsetsTests: XCTestCase {
    func testNotchPhonesReportTheHousingWithoutBars() {
        let portrait = PamCutoutInsets.from(safeArea: UIEdgeInsets(top: 59, left: 0, bottom: 34, right: 0), idiom: .phone)
        XCTAssertEqual(portrait, UIEdgeInsets(top: 59, left: 0, bottom: 0, right: 0))
        let landscape = PamCutoutInsets.from(safeArea: UIEdgeInsets(top: 0, left: 59, bottom: 21, right: 59), idiom: .phone)
        XCTAssertEqual(landscape, UIEdgeInsets(top: 0, left: 59, bottom: 0, right: 59))
    }

    func testClassicStatusBarAndTabletsHaveNoCutout() {
        XCTAssertEqual(PamCutoutInsets.from(safeArea: UIEdgeInsets(top: 20, left: 0, bottom: 0, right: 0), idiom: .phone), .zero)
        XCTAssertEqual(PamCutoutInsets.from(safeArea: UIEdgeInsets(top: 24, left: 0, bottom: 20, right: 0), idiom: .pad), .zero)
    }
}
