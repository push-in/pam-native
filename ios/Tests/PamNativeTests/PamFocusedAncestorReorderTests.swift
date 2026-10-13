import UIKit
import XCTest
@testable import PamNative

@MainActor
final class PamFocusedAncestorReorderTests: XCTestCase {
    func testMovingFocusedAncestorKeepsResponderSelectionAndDraft() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let controller = UIViewController()
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        var blurCount = 0
        let renderer = PamRenderer(hostView: controller.view) { id, event, _ in
            if id == 5 && event == EventKind.blur.rawValue { blurCount += 1 }
        }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .view, properties: [PamConstants.backgroundColor: .integer(0xFFFFFFFF)])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view, properties: [PamConstants.backgroundColor: .integer(0xFFEEEEEE)])),
            .create(NodeSpec(id: 4, parent: 2, index: 1, kind: .view, properties: [PamConstants.backgroundColor: .integer(0xFFDDDDDD)])),
            .create(NodeSpec(id: 5, parent: 3, index: 0, kind: .input, properties: [PamConstants.onBlur: .flag(true)])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 390, height: 844)),
            .layout(id: 2, frame: Frame(x: 0, y: 100, width: 390, height: 300)),
            .layout(id: 3, frame: Frame(x: 0, y: 0, width: 390, height: 80)),
            .layout(id: 4, frame: Frame(x: 0, y: 100, width: 390, height: 80)),
            .layout(id: 5, frame: Frame(x: 0, y: 0, width: 300, height: 50)),
            .setRoot(1),
        ]])
        let input = controller.view.viewWithTag(5) as! PamInputField
        input.text = "draft before reorder"
        XCTAssertTrue(input.becomeFirstResponder())
        let start = input.position(from: input.beginningOfDocument, offset: 2)!
        let end = input.position(from: input.beginningOfDocument, offset: 7)!
        input.selectedTextRange = input.textRange(from: start, to: end)
        for index in [1, 0, 1, 0] {
            renderer.commit([[.move(id: 3, parent: 2, index: index)]])
            XCTAssertTrue(input.isFirstResponder)
            XCTAssertEqual(input.text, "draft before reorder")
            XCTAssertEqual(input.offset(from: input.beginningOfDocument, to: input.selectedTextRange!.start), 2)
            XCTAssertEqual(input.offset(from: input.beginningOfDocument, to: input.selectedTextRange!.end), 7)
            XCTAssertEqual(blurCount, 0)
        }
    }

    func testHiddenOrdinarySubtreeRetiresOnlyItsOwnEditorAndKeepsDraft() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let controller = UIViewController()
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        let renderer = PamRenderer(hostView: controller.view) { _, _, _ in }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .column, properties: [:])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .input, properties: [:])),
            .create(NodeSpec(id: 4, parent: 1, index: 1, kind: .column, properties: [:])),
            .create(NodeSpec(id: 5, parent: 4, index: 0, kind: .input, properties: [:])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 390, height: 844)),
            .layout(id: 2, frame: Frame(x: 0, y: 100, width: 390, height: 80)),
            .layout(id: 3, frame: Frame(x: 0, y: 0, width: 300, height: 50)),
            .layout(id: 4, frame: Frame(x: 0, y: 200, width: 390, height: 80)),
            .layout(id: 5, frame: Frame(x: 0, y: 0, width: 300, height: 50)),
            .setRoot(1),
        ]])
        let retired = controller.view.viewWithTag(3) as! PamInputField
        let destination = controller.view.viewWithTag(5) as! PamInputField
        retired.text = "preserved draft"
        XCTAssertTrue(retired.becomeFirstResponder())
        renderer.commit([[.update(id: 2, key: PamConstants.visible, value: .flag(false))]])
        XCTAssertFalse(retired.isFirstResponder)
        XCTAssertEqual(retired.text, "preserved draft")
        renderer.commit([[.update(id: 2, key: PamConstants.visible, value: .flag(true))]])
        XCTAssertTrue(destination.becomeFirstResponder())
        renderer.commit([[.update(id: 2, key: PamConstants.visible, value: .flag(false))]])
        XCTAssertTrue(destination.isFirstResponder)
        XCTAssertEqual(retired.text, "preserved draft")
    }


    func testSwitchingRetainedTabsRetiresEditorAndPreservesDraft() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let controller = UIViewController()
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        let tabs = PamTabHost(frame: controller.view.bounds)
        controller.view.addSubview(tabs)
        let first = UIView(), second = UIView()
        let input = PamInputField(frame: CGRect(x: 0, y: 0, width: 300, height: 50))
        let destination = PamInputField(frame: CGRect(x: 0, y: 0, width: 300, height: 50))
        first.addSubview(input)
        second.addSubview(destination)
        tabs.insertScene(first, index: 0)
        tabs.insertScene(second, index: 1)
        tabs.configure(encodedItems: "[{\"label\":\"One\"},{\"label\":\"Two\"}]",
            selectedIndex: 1, position: 1, activeColor: .label,
            inactiveColor: .secondaryLabel, barColor: .systemBackground,
            indicatorColor: .label, swipeEnabled: false)
        tabs.layoutIfNeeded()
        input.text = "tab draft"
        XCTAssertTrue(input.becomeFirstResponder())
        tabs.selectForTesting(2)
        XCTAssertFalse(input.isFirstResponder)
        XCTAssertEqual(input.text, "tab draft")
        XCTAssertTrue(destination.becomeFirstResponder())
        tabs.selectForTesting(2)
        XCTAssertTrue(destination.isFirstResponder)
        tabs.selectForTesting(1)
        XCTAssertFalse(destination.isFirstResponder)
        XCTAssertEqual(input.text, "tab draft")
        XCTAssertFalse(input.isFirstResponder)
    }

}
