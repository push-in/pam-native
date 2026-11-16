import UIKit
import XCTest
@testable import PamNative

/// VoiceOver parity with TalkBack for defaults the Zé Chat audit found:
/// labelled Pressables, container roles, live regions and adjustable nodes.
@MainActor
final class PamAccessibilityParityTests: XCTestCase {
    private func render(
        _ nodes: [NodeSpec],
        renderer configure: (PamRenderer) -> Void = { _ in },
        dispatch: @escaping (Int64, Int, Data) -> Void = { _, _, _ in }
    ) -> (UIView, PamRenderer) {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let renderer = PamRenderer(hostView: host, dispatchEvent: dispatch)
        configure(renderer)
        var batch: [Mutation] = [
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
        ]
        batch += nodes.map { Mutation.create($0) }
        batch.append(.layout(id: 1, frame: Frame(x: 0, y: 0, width: 390, height: 844)))
        batch += nodes.map { Mutation.layout(id: $0.id, frame: Frame(x: 0, y: 0, width: 200, height: 48)) }
        batch.append(.setRoot(1))
        renderer.commit([batch])
        return (host, renderer)
    }

    private func text(_ id: Int64, parent: Int64, _ value: String) -> NodeSpec {
        NodeSpec(id: id, parent: parent, index: 0, kind: .text, properties: [PamConstants.text: .text(value)])
    }

    /// VoiceOver can descend to `view`: nothing on its path hides its
    /// descendants and no ancestor is an element (UIKit stops at one). A
    /// UILabel's own `isAccessibilityElement` comes from the accessibility
    /// runtime, which unit tests do not load, so it is not asserted.
    private func reachable(_ view: UIView) -> Bool {
        var current: UIView? = view
        while let candidate = current {
            if candidate.accessibilityElementsHidden { return false }
            if candidate !== view && candidate.isAccessibilityElement { return false }
            current = candidate.superview
        }
        return true
    }

    func testLabelledPressableIsAButtonElementLikeTalkBack() throws {
        let (host, renderer) = render([
            NodeSpec(id: 2, parent: 1, index: 0, kind: .pressable, properties: [
                PamConstants.accessibilityLabel: .text("Open chat"),
            ]),
            text(3, parent: 2, "Chat"),
            NodeSpec(id: 4, parent: 1, index: 1, kind: .pressable, properties: [
                PamConstants.accessibilityLabel: .text("Row"),
                PamConstants.accessible: .flag(false),
            ]),
            NodeSpec(id: 5, parent: 1, index: 2, kind: .pressable, properties: [
                PamConstants.accessibilityLabel: .text("Decor"),
                PamConstants.accessibilityImportance: .integer(3),
            ]),
            NodeSpec(id: 6, parent: 1, index: 3, kind: .pressable, properties: [
                PamConstants.accessibilityLabel: .text("Card"),
                PamConstants.accessibilityRole: .integer(17),
            ]),
        ])
        defer { renderer.close() }
        let pressable = try XCTUnwrap(host.viewWithTag(2))
        XCTAssertTrue(pressable.isAccessibilityElement)
        XCTAssertTrue(pressable.accessibilityTraits.contains(.button))
        XCTAssertEqual(pressable.accessibilityLabel, "Open chat")
        XCTAssertFalse(try XCTUnwrap(host.viewWithTag(4)).isAccessibilityElement)
        XCTAssertFalse(try XCTUnwrap(host.viewWithTag(5)).isAccessibilityElement)
        let roleNone = try XCTUnwrap(host.viewWithTag(6))
        XCTAssertTrue(roleNone.isAccessibilityElement)
        XCTAssertFalse(roleNone.accessibilityTraits.contains(.button))
    }

    func testContainerRolesKeepTheirChildrenReachable() throws {
        let (host, renderer) = render([
            NodeSpec(id: 2, parent: 1, index: 0, kind: .view, properties: [
                PamConstants.accessibilityRole: .integer(32),
                PamConstants.accessibilityLabel: .text("Messages"),
            ]),
            text(3, parent: 2, "Hello"),
            NodeSpec(id: 4, parent: 1, index: 1, kind: .row, properties: [
                PamConstants.accessibilityRole: .integer(7),
            ]),
            text(5, parent: 4, "Saved"),
            NodeSpec(id: 6, parent: 1, index: 2, kind: .view, properties: [
                PamConstants.accessibilityRole: .integer(10),
            ]),
            text(7, parent: 6, "Settings"),
            NodeSpec(id: 8, parent: 1, index: 3, kind: .view, properties: [
                PamConstants.accessibilityRole: .integer(10),
                PamConstants.accessibilityLabel: .text("Profile"),
            ]),
            NodeSpec(id: 9, parent: 1, index: 4, kind: .text, properties: [
                PamConstants.text: .text("Network error"),
                PamConstants.accessibilityRole: .integer(7),
            ]),
            NodeSpec(id: 10, parent: 1, index: 5, kind: .view, properties: [
                PamConstants.accessibilityRole: .integer(26),
                PamConstants.accessible: .flag(true),
            ]),
            text(11, parent: 10, "Tab"),
        ])
        defer { renderer.close() }
        let list = try XCTUnwrap(host.viewWithTag(2))
        XCTAssertFalse(list.isAccessibilityElement)
        XCTAssertEqual(list.accessibilityContainerType, .list)
        XCTAssertTrue(try reachable(XCTUnwrap(host.viewWithTag(3))))
        let alert = try XCTUnwrap(host.viewWithTag(4))
        XCTAssertFalse(alert.isAccessibilityElement)
        XCTAssertEqual(alert.accessibilityContainerType, .semanticGroup)
        let unlabelledHeader = try XCTUnwrap(host.viewWithTag(6))
        XCTAssertFalse(unlabelledHeader.isAccessibilityElement)
        XCTAssertTrue(try reachable(XCTUnwrap(host.viewWithTag(5))))
        XCTAssertTrue(try reachable(XCTUnwrap(host.viewWithTag(7))))
        let labelledHeader = try XCTUnwrap(host.viewWithTag(8))
        XCTAssertTrue(labelledHeader.isAccessibilityElement)
        XCTAssertTrue(labelledHeader.accessibilityTraits.contains(.header))
        XCTAssertTrue(try XCTUnwrap(host.viewWithTag(9)).isAccessibilityElement, "a leaf keeps its role")
        let explicit = try XCTUnwrap(host.viewWithTag(10))
        XCTAssertTrue(explicit.isAccessibilityElement)
        XCTAssertEqual(explicit.accessibilityContainerType, .none)
        XCTAssertFalse(try reachable(XCTUnwrap(host.viewWithTag(11))), "an explicit element still groups")

        renderer.commit([[.update(id: 2, key: PamConstants.accessibilityRole, value: nil)]])
        XCTAssertEqual(list.accessibilityContainerType, .none)
    }

    func testUnlabelledInteractiveContainerReadsItsContent() throws {
        let (host, renderer) = render([
            NodeSpec(id: 2, parent: 1, index: 0, kind: .view, properties: [
                PamConstants.accessibilityRole: .integer(2),
            ]),
            text(3, parent: 2, "Follow"),
        ])
        defer { renderer.close() }
        let button = try XCTUnwrap(host.viewWithTag(2))
        XCTAssertTrue(button.isAccessibilityElement)
        XCTAssertEqual(button.accessibilityLabel, "Follow")
    }

    func testLiveRegionsAnnounceAppearanceAndChangesWhileVoiceOverRuns() throws {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.isHidden = false
        defer { window.isHidden = true }
        var announcements: [(String, Bool)] = []
        var voiceOver = true
        let (host, renderer) = render([
            NodeSpec(id: 2, parent: 1, index: 0, kind: .view, properties: [
                PamConstants.accessibilityLiveRegion: .integer(2),
            ]),
            text(3, parent: 2, "Saved"),
            NodeSpec(id: 4, parent: 1, index: 1, kind: .text, properties: [
                PamConstants.text: .text(""),
                PamConstants.accessibilityLiveRegion: .integer(3),
            ]),
        ], renderer: { renderer in
            renderer.liveRegionAnnouncementsEnabled = { voiceOver }
            renderer.liveRegionAnnouncer = { announcement in
                let queued = announcement.attribute(
                    .accessibilitySpeechQueueAnnouncement, at: 0, effectiveRange: nil
                ) as? Bool
                announcements.append((announcement.string, queued ?? false))
            }
        })
        defer { renderer.close() }
        // Not in a window yet: nothing is visible, nothing is announced.
        XCTAssertTrue(announcements.isEmpty)

        window.addSubview(host)
        renderer.commit([[.update(id: 3, key: PamConstants.text, value: .text("Saved"))]])
        XCTAssertEqual(announcements.map { $0.0 }, ["Saved"])
        XCTAssertEqual(announcements.map { $0.1 }, [true], "polite queues")

        renderer.commit([[.update(id: 3, key: PamConstants.text, value: .text("Saved"))]])
        XCTAssertEqual(announcements.count, 1, "unchanged content is not repeated")

        renderer.commit([[.update(id: 4, key: PamConstants.text, value: .text("Code expired"))]])
        XCTAssertEqual(announcements.last?.0, "Code expired")
        XCTAssertEqual(announcements.last?.1, false, "assertive interrupts")

        voiceOver = false
        renderer.commit([[.update(id: 3, key: PamConstants.text, value: .text("Failed"))]])
        XCTAssertEqual(announcements.count, 2, "nothing runs while VoiceOver is off")

        // VoiceOver comes back: "Failed" was never heard and is not news.
        voiceOver = true
        renderer.liveRegionAnnouncementsDidChange()
        XCTAssertEqual(announcements.count, 2)
        renderer.commit([[
            .create(NodeSpec(id: 5, parent: 1, index: 2, kind: .text, properties: [
                PamConstants.text: .text("Wrong password"),
                PamConstants.accessibilityLiveRegion: .integer(2),
            ])),
            .layout(id: 5, frame: Frame(x: 0, y: 60, width: 200, height: 48)),
        ]])
        XCTAssertEqual(
            announcements.map { $0.0 },
            ["Saved", "Code expired", "Wrong password"],
            "a region mounted into a visible screen is read, like Android"
        )
        _ = try XCTUnwrap(host.viewWithTag(5))

        renderer.commit([[.update(id: 3, key: PamConstants.text, value: .text("Retry"))]])
        XCTAssertEqual(announcements.last?.0, "Retry", "changes after VoiceOver came back are read")
    }

    func testAdjustableSwipesDispatchIncrementAndDecrement() throws {
        var events: [(Int64, Int, String)] = []
        let (host, renderer) = render([
            NodeSpec(id: 2, parent: 1, index: 0, kind: .pressable, properties: [
                PamConstants.accessibilityLabel: .text("Video position"),
                PamConstants.accessibilityRole: .integer(6),
                PamConstants.onAccessibilityAction: .flag(true),
            ]),
            NodeSpec(id: 3, parent: 1, index: 1, kind: .view, properties: [
                PamConstants.accessibilityLabel: .text("Volume"),
                PamConstants.accessibilityRole: .integer(6),
                PamConstants.onAccessibilityAction: .flag(true),
            ]),
            NodeSpec(id: 4, parent: 1, index: 2, kind: .pressable, properties: [
                PamConstants.accessibilityLabel: .text("Static"),
                PamConstants.accessibilityRole: .integer(6),
            ]),
        ], dispatch: { id, kind, payload in
            events.append((id, kind, String(decoding: payload, as: UTF8.self)))
        })
        defer { renderer.close() }
        let slider = try XCTUnwrap(host.viewWithTag(2))
        XCTAssertTrue(slider.isAccessibilityElement)
        XCTAssertTrue(slider.accessibilityTraits.contains(.adjustable))
        slider.accessibilityIncrement()
        slider.accessibilityDecrement()
        try XCTUnwrap(host.viewWithTag(3)).accessibilityIncrement()
        try XCTUnwrap(host.viewWithTag(4)).accessibilityIncrement()
        XCTAssertEqual(events.map { $0.0 }, [2, 2, 3])
        XCTAssertEqual(Set(events.map { $0.1 }), [EventKind.accessibilityAction.rawValue])
        XCTAssertEqual(events.map { $0.2 }, ["increment", "decrement", "increment"])

        renderer.commit([[.update(id: 2, key: PamConstants.onAccessibilityAction, value: nil)]])
        slider.accessibilityIncrement()
        XCTAssertEqual(events.count, 3, "no handler, no event")
    }
}
