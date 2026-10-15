import XCTest
@testable import PamNative

final class DeviceStatusChangesTests: XCTestCase {
    func testDeliversTheFirstStatusThenOnlyChanges() {
        let changes = DeviceStatusChanges()
        XCTAssertTrue(changes.accept(Data([1, 2, 3])))
        XCTAssertFalse(changes.accept(Data([1, 2, 3])), "an identical sample must not wake PHP")
        XCTAssertTrue(changes.accept(Data([1, 2, 4])))
        XCTAssertFalse(changes.accept(Data([1, 2, 4])))
        XCTAssertTrue(changes.accept(Data([1, 2, 3])), "going back to an earlier status is a change")
    }
}
