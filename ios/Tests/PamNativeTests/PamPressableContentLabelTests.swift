import UIKit
import XCTest
@testable import PamNative

@MainActor
final class PamPressableContentLabelTests: XCTestCase {
    /// A press target without its own label announces its content's labels
    /// (RN `RCTRecursiveAccessibilityLabel`); its own label still wins.
    func testUnlabelledPressableAnnouncesItsContent() {
        let outer = PamPressButton(type: .system)
        let inner = PamPressButton(type: .system)
        inner.accessibilityLabel = "Record audio"
        let icon = UIImageView()
        inner.addSubview(icon)
        outer.addSubview(inner)
        XCTAssertEqual(outer.accessibilityLabel, "Record audio")

        let row = PamPressButton(type: .system)
        let title = UILabel()
        title.text = "Group"
        let subtitle = UILabel()
        subtitle.text = "Last message"
        let hidden = UILabel()
        hidden.text = "Hidden"
        hidden.isHidden = true
        let column = UIView()
        column.addSubview(title)
        column.addSubview(subtitle)
        column.addSubview(hidden)
        row.addSubview(column)
        XCTAssertEqual(row.accessibilityLabel, "Group, Last message")

        row.accessibilityLabel = "Open group"
        XCTAssertEqual(row.accessibilityLabel, "Open group")
        row.accessibilityLabel = nil
        XCTAssertEqual(row.accessibilityLabel, "Group, Last message")
        XCTAssertNil(PamPressButton(type: .system).accessibilityLabel)
    }
}
