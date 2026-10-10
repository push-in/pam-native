import UIKit
import XCTest
@testable import PamNative

/// ViewCapture (1.37.0); requires an iOS test destination (not executable on Linux).
@MainActor
final class ViewCaptureModuleTests: XCTestCase {
    func testCapturesTheSubtreeOfAnInvisibleViewAtTheRequestedScale() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        host.alpha = 0
        let target = UIView(frame: host.bounds)
        target.backgroundColor = .red
        let left = UIView(frame: CGRect(x: 0, y: 0, width: 50, height: 100))
        left.backgroundColor = .blue
        target.addSubview(left)
        host.addSubview(target)
        let module = ViewCaptureModule { $0 == "sticker" ? target : nil }
        let done = expectation(description: "capture")
        var result: (ModuleResultStatus, Data)?
        module.invoke(method: "capture", payload: try WireMap.encode([
            "ref": .text("sticker"), "pixelRatio": .decimal(2), "directory": .text("tests/captures"),
        ])) { status, payload in
            result = (status, payload)
            done.fulfill()
        }
        wait(for: [done], timeout: 10)
        let (status, payload) = try XCTUnwrap(result)
        XCTAssertEqual(status, .success)
        let values = try WireMap.decode(payload)
        XCTAssertEqual(values["width"], .integer(200))
        XCTAssertEqual(values["height"], .integer(200))
        guard case let .text(path)? = values["path"] else { return XCTFail("path") }
        XCTAssertTrue(path.hasPrefix("tests/captures/"))
    }

    func testRejectsUnknownRefsAndUnsafeFolders() {
        XCTAssertThrowsError(try ViewCaptureModule.safeDirectory("../x"))
        XCTAssertThrowsError(try ViewCaptureModule.safeDirectory("a/./b"))
        XCTAssertEqual(try ViewCaptureModule.safeDirectory(""), "view-captures")
        XCTAssertEqual(ViewCaptureModule.captureScale(size: CGSize(width: 1000, height: 300), pixelRatio: 8, screenScale: 3), 4.096, accuracy: 0.0001)
        XCTAssertEqual(ViewCaptureModule.captureScale(size: CGSize(width: 100, height: 100), pixelRatio: 0, screenScale: 3), 3)
        let module = ViewCaptureModule { _ in nil }
        let done = expectation(description: "missing")
        module.invoke(method: "capture", payload: (try? WireMap.encode(["ref": .text("missing")])) ?? Data()) { status, _ in
            XCTAssertEqual(status, .failure)
            done.fulfill()
        }
        wait(for: [done], timeout: 10)
    }
}
