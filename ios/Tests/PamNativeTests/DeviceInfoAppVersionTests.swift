import Foundation
import XCTest
@testable import PamNative

/// Source-ready iOS contract tests. Execute with xcodebuild on macOS.
final class DeviceInfoAppVersionTests: XCTestCase {
    func testDeviceInfoReportsInstalledVersionAndBuild() {
        let done = expectation(description: "deviceInfo")
        var values: [String: WireValue] = [:]
        SystemModule().invoke(method: "deviceInfo", payload: Data()) { status, payload in
            XCTAssertEqual(status, .success)
            values = (try? WireMap.decode(payload)) ?? [:]
            done.fulfill()
        }
        wait(for: [done], timeout: 5)
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? ""
        XCTAssertEqual(values["appVersion"], .text(version))
        XCTAssertEqual(values["buildNumber"], .text(build))
    }
}
