import UIKit
import XCTest
@testable import PamNative

/// A Pressable with onPressIn/onPressOut inside a paging list (the loops
/// viewer's touch layer) must not block the list's pan: its pointer observer
/// begins on touch down and, kept exclusive, swallowed every swipe as a tap.
@MainActor
final class PamPressObserverScrollTests: XCTestCase {
    /// The recognizer's delegate (the event bridge) is weak; the renderer keeps it.
    private var renderer: PamRenderer?

    private func pointerRecognizer(_ properties: [Int: PropValue]) -> UILongPressGestureRecognizer? {
        let (_, renderer, view) = PamRenderTestSupport.render(properties, kind: .pressable)
        self.renderer = renderer
        return view.gestureRecognizers?
            .compactMap { $0 as? UILongPressGestureRecognizer }
            .first { $0.minimumPressDuration == 0 }
    }

    func testPressInOutObserverRecognizesAlongsideAParentScrollPan() throws {
        let pointer = try XCTUnwrap(pointerRecognizer([
            PamConstants.onPress: .integer(1),
            PamConstants.onPressOut: .integer(1),
        ]))
        let scroll = UIScrollView()
        XCTAssertEqual(
            pointer.delegate?.gestureRecognizer?(pointer, shouldRecognizeSimultaneouslyWith: scroll.panGestureRecognizer),
            true
        )
    }

    func testPressMoveTrackingKeepsExclusivity() throws {
        let pointer = try XCTUnwrap(pointerRecognizer([
            PamConstants.onPressMove: .integer(1),
            PamConstants.onPressOut: .integer(1),
        ]))
        let scroll = UIScrollView()
        XCTAssertEqual(
            pointer.delegate?.gestureRecognizer?(pointer, shouldRecognizeSimultaneouslyWith: scroll.panGestureRecognizer),
            false
        )
    }
}
