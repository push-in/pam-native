import UIKit
import XCTest
@testable import PamNative

@MainActor
final class PamSwitchCheckedTests: XCTestCase {
    /// `<Switch checked>` (PHP Toggle sends `checked`) turns the switch on and
    /// off, like the Android Switch; removing the property turns it off.
    func testCheckedPropertyDrivesTheSwitch() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 200, height: 100))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .toggle, properties: [
                PamConstants.checked: .flag(true),
            ])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 200, height: 100)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 52, height: 32)),
            .setRoot(1),
        ]])
        let toggle = try XCTUnwrap(host.viewWithTag(2) as? PamVuetifySwitch)
        XCTAssertTrue(toggle.isOn)
        renderer.commit([[.update(id: 2, key: PamConstants.checked, value: .flag(false))]])
        XCTAssertFalse(toggle.isOn)
        renderer.commit([[.update(id: 2, key: PamConstants.checked, value: .flag(true))]])
        XCTAssertTrue(toggle.isOn)
        renderer.commit([[.update(id: 2, key: PamConstants.checked, value: nil)]])
        XCTAssertFalse(toggle.isOn)
    }
}
