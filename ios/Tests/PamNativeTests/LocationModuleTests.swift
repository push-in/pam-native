import CoreLocation
import XCTest
@testable import PamNative

/// Location 1.35.0 contract shared with Android (LocationContractTest) and PHP
/// (`LocationError`, `LocationServicesResult`). Uncompiled — needs Mac validation.
final class LocationModuleTests: XCTestCase {
    func testFailureCodesMatchAndroidAndPhpInOrder() {
        XCTAssertEqual(LocationFailure.allCases.map(\.rawValue), ["permission", "disabled", "unavailable", "timeout"])
        XCTAssertEqual(
            String(decoding: LocationFailure.permission.payload("Location permission is required"), as: UTF8.self),
            "permission: Location permission is required"
        )
        XCTAssertEqual(LocationFailure.from(CLError(.denied)), .permission)
        XCTAssertEqual(LocationFailure.from(CLError(.locationUnknown)), .unavailable)
        XCTAssertEqual([LocationServicesResult.enabled, .denied, .unavailable].map(\.rawValue), [1, 2, 3])
    }

    func testServicesStateAndRequestAgree() throws {
        let module = LocationModule()
        let enabled = try call(module, "servicesEnabled")
        guard case let .flag(on)? = enabled["enabled"] else { return XCTFail("enabled flag") }
        let requested = try call(module, "requestServices")
        XCTAssertEqual(requested["result"], .integer(on ? 1 : 3))
    }

    func testUnknownMethodFailsWithTheUnavailableCode() {
        let done = expectation(description: "completion")
        LocationModule().invoke(method: "nope", payload: Data()) { status, payload in
            XCTAssertEqual(status, .failure)
            XCTAssertTrue(String(decoding: payload, as: UTF8.self).hasPrefix("unavailable: "))
            done.fulfill()
        }
        wait(for: [done], timeout: 2)
    }

    private func call(_ module: LocationModule, _ method: String) throws -> [String: WireValue] {
        let done = expectation(description: method)
        var result = Data()
        module.invoke(method: method, payload: try WireMap.encode([:])) { status, payload in
            XCTAssertEqual(status, .success)
            result = payload
            done.fulfill()
        }
        wait(for: [done], timeout: 5)
        return try WireMap.decode(result)
    }
}
