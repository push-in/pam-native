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
    /// `<Switch>` is the system UISwitch, like React Native iOS: its size is the
    /// UISwitch size (the engine box), and the colors map to onTintColor /
    /// tintColor / thumbTintColor only when set; removing one restores the system color.
    func testSwitchIsTheSystemControl() throws {
        let toggle = PamVuetifySwitch()
        let native = try XCTUnwrap(toggle.subviews.compactMap { $0 as? UISwitch }.first)
        XCTAssertEqual(toggle.intrinsicContentSize, UISwitch().intrinsicContentSize)
        XCTAssertNil(native.onTintColor)
        XCTAssertNil(native.thumbTintColor)
        toggle.trackOnColor = .green
        toggle.thumbColor = .red
        XCTAssertEqual(native.onTintColor, .green)
        XCTAssertEqual(native.thumbTintColor, .red)
        toggle.thumbColor = nil
        XCTAssertNil(native.thumbTintColor)

        toggle.frame = CGRect(x: 0, y: 0, width: 80, height: 40)
        toggle.layoutIfNeeded()
        XCTAssertEqual(native.center.x, 40, accuracy: 0.5)
        XCTAssertEqual(native.center.y, 20, accuracy: 0.5)

        var changes = 0
        let target = ChangeCounter { changes += 1 }
        toggle.addTarget(target, action: #selector(ChangeCounter.fire), for: .valueChanged)
        native.setOn(true, animated: false)
        native.sendActions(for: .valueChanged)
        XCTAssertTrue(toggle.isOn)
        XCTAssertEqual(changes, 1)
        toggle.isOn = false
        XCTAssertFalse(native.isOn)
        XCTAssertEqual(changes, 1)
    }
}

private final class ChangeCounter: NSObject {
    let body: () -> Void
    init(_ body: @escaping () -> Void) { self.body = body }
    @objc func fire() { body() }
}
