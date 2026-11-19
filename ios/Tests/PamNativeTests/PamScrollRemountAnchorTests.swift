import UIKit
import XCTest
@testable import PamNative

/// A Dynamic Type change re-measures every row and a surface remount
/// rebuilds the views: ScrollViews and lists keep the row under their
/// leading edge, by node id and with its new extent, not the pixel offset
/// (Android `PamConfigurationRemountStateInstrumentedTest`).
@MainActor
final class PamScrollRemountAnchorTests: XCTestCase {
    private var windows: [UIWindow] = []

    override func tearDown() {
        windows.forEach { $0.isHidden = true }
        windows.removeAll()
        super.tearDown()
    }

    // MARK: Anchor geometry

    func testTheRowUnderTheEdgeKeepsItsShareWhenItGrows() throws {
        let rows: [(id: Int64, start: CGFloat, extent: CGFloat)] = [(1, 0, 100), (2, 100, 100), (3, 200, 100)]
        let row = try XCTUnwrap(PamScrollRemountAnchor.hit(rows, edge: 130, reversed: false))
        XCTAssertEqual(row.id, 2)
        let fraction = PamScrollRemountAnchor.fraction(of: row, edge: 130, reversed: false)
        XCTAssertEqual(fraction, 0.3, accuracy: 0.0001)
        let anchor = PamScrollRemountAnchor(rowId: 2, fraction: fraction, atStart: false, atEnd: false, horizontal: false, reversed: false)
        // The row grew to 130 and starts at 130 now.
        let offset = try XCTUnwrap(anchor.restoredOffset(row: (start: 130, extent: 130), viewport: 400, minimum: 0, maximum: 1_000))
        XCTAssertEqual(offset, 169, accuracy: 0.0001)
    }

    func testAnInvertedListAnchorsTheRowAtTheScreenTop() throws {
        // Mirrored: the screen top is the viewport's far end in content coordinates.
        let rows: [(id: Int64, start: CGFloat, extent: CGFloat)] = [(1, 0, 100), (2, 100, 100), (3, 200, 100), (4, 300, 100), (5, 400, 100)]
        let edge = PamScrollRemountAnchor.edge(offset: 50, viewport: 300, reversed: true)
        XCTAssertEqual(edge, 350)
        let row = try XCTUnwrap(PamScrollRemountAnchor.hit(rows, edge: edge, reversed: true))
        XCTAssertEqual(row.id, 4)
        let fraction = PamScrollRemountAnchor.fraction(of: row, edge: edge, reversed: true)
        XCTAssertEqual(fraction, 0.5, accuracy: 0.0001)
        let anchor = PamScrollRemountAnchor(rowId: 4, fraction: fraction, atStart: false, atEnd: false, horizontal: false, reversed: true)
        // Rows grew by 30 %: row 4 spans 390...520.
        let offset = try XCTUnwrap(anchor.restoredOffset(row: (start: 390, extent: 130), viewport: 300, minimum: 0, maximum: 1_000))
        XCTAssertEqual(offset, 155, accuracy: 0.0001)
    }

    func testContainersAtAnEndStayThere() {
        let start = PamScrollRemountAnchor.end(offset: 0, minimum: 0, maximum: 900, horizontal: false, reversed: true)
        XCTAssertEqual(start?.atStart, true)
        XCTAssertEqual(start?.restoredOffset(row: nil, viewport: 300, minimum: 0, maximum: 1_200), 0)
        let end = PamScrollRemountAnchor.end(offset: 900, minimum: 0, maximum: 900, horizontal: false, reversed: false)
        XCTAssertEqual(end?.atEnd, true)
        XCTAssertEqual(end?.restoredOffset(row: nil, viewport: 300, minimum: 0, maximum: 1_200), 1_200)
        XCTAssertNil(PamScrollRemountAnchor.end(offset: 400, minimum: 0, maximum: 900, horizontal: false, reversed: false))
    }

    func testAPagerRestsOnAPage() throws {
        let anchor = PamScrollRemountAnchor(rowId: 3, fraction: 0.2, atStart: false, atEnd: false, horizontal: true, reversed: false)
        XCTAssertEqual(try XCTUnwrap(anchor.restoredOffset(row: (start: 960, extent: 320), viewport: 320, minimum: 0, maximum: 1_600, paging: true)), 960)
        XCTAssertNil(anchor.restoredOffset(row: nil, viewport: 320, minimum: 0, maximum: 1_600))
    }

    // MARK: Renderer

    private static let rows = 60

    private func rowExtent(_ index: Int, scale: Float) -> Float {
        (48 + Float(index % 5) * 14) * scale
    }

    /// Screen > list (or ScrollView > Column) > rows of varied heights.
    private func tree(kind: NodeKind, inverted: Bool = false, scale: Float) -> [Mutation] {
        var properties: [Int: PropValue] = [:]
        if inverted { properties[PamConstants.listInverted] = .flag(true) }
        var mutations: [Mutation] = [
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: kind, properties: properties)),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
            .layout(id: 2, frame: Frame(x: 0, y: 0, width: 320, height: 480)),
        ]
        let parent: Int64 = kind == .scroll ? 3 : 2
        var y: Float = 0
        for index in 0..<Self.rows {
            let id = Int64(100 + index)
            let extent = rowExtent(index, scale: scale)
            mutations.append(.create(NodeSpec(id: id, parent: parent, index: index, kind: .view, properties: [:])))
            mutations.append(.layout(id: id, frame: Frame(x: 0, y: y, width: 320, height: extent)))
            y += extent
        }
        if kind == .scroll {
            mutations.insert(.create(NodeSpec(id: 3, parent: 2, index: 0, kind: .column, properties: [:])), at: 2)
            mutations.append(.layout(id: 3, frame: Frame(x: 0, y: 0, width: 320, height: y)))
        }
        mutations.append(.setRoot(1))
        return mutations
    }

    private func mount(_ mutations: [Mutation]) -> (renderer: PamRenderer, host: UIView) {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        windows.append(window)
        let host = UIView(frame: window.bounds)
        window.addSubview(host)
        window.isHidden = false
        let renderer = PamRenderer(hostView: host, dispatchEvent: { _, _, _ in })
        renderer.commit([mutations])
        host.layoutIfNeeded()
        return (renderer, host)
    }

    private func scroller(_ host: UIView) throws -> UIScrollView {
        try XCTUnwrap(host.viewWithTag(2) as? UIScrollView)
    }

    /// The row under the scroller's screen top and how far into it the top is.
    private func topRow(_ host: UIView) throws -> (id: Int64, fraction: CGFloat) {
        let scroll = try scroller(host)
        for index in 0..<Self.rows {
            let id = Int64(100 + index)
            guard let view = scroll.viewWithTag(Int(id)), view.superview != nil else { continue }
            let rect = view.convert(view.bounds, to: host)
            if rect.minY <= 0.5, rect.maxY > 0.5 {
                return (id, -rect.minY / rect.height)
            }
        }
        throw XCTSkip("no row under the top edge")
    }

    private func assertKeepsTopRow(kind: NodeKind, inverted: Bool = false, scrollBy: CGFloat, remount: Bool) throws {
        let mounted = mount(tree(kind: kind, inverted: inverted, scale: 1))
        var renderer = mounted.renderer
        let host = mounted.host
        let scroll = try scroller(host)
        scroll.contentOffset.y += scrollBy
        host.layoutIfNeeded()
        scroll.layoutIfNeeded()
        let before = try topRow(host)
        if remount {
            // As PamRuntime.resynchronize/attach: a new renderer on the same host.
            let anchors = renderer.captureScrollAnchors()
            renderer.close()
            renderer = PamRenderer(hostView: host, dispatchEvent: { _, _, _ in })
            renderer.adoptScrollAnchors(anchors)
            renderer.commit([tree(kind: kind, inverted: inverted, scale: 1.3)])
        } else {
            // A Dynamic Type change: re-measured rows in place.
            renderer.refreshTextScale()
            let relayout = tree(kind: kind, inverted: inverted, scale: 1.3).filter {
                if case .layout = $0 { return true }
                return false
            }
            renderer.commit([relayout])
        }
        host.layoutIfNeeded()
        let after = try topRow(host)
        XCTAssertEqual(after.id, before.id)
        XCTAssertEqual(after.fraction, before.fraction, accuracy: 0.02)
        renderer.close()
    }

    func testAVirtualListKeepsItsTopRowWhenTheTextScaleGrows() throws {
        try assertKeepsTopRow(kind: .virtualList, scrollBy: 1_530, remount: false)
    }

    func testAScrollViewKeepsItsTopRowWhenTheTextScaleGrows() throws {
        try assertKeepsTopRow(kind: .scroll, scrollBy: 1_530, remount: false)
    }

    func testAVirtualListKeepsItsTopRowAcrossARemount() throws {
        try assertKeepsTopRow(kind: .virtualList, scrollBy: 1_530, remount: true)
    }

    func testAScrollViewKeepsItsTopRowAcrossARemount() throws {
        try assertKeepsTopRow(kind: .scroll, scrollBy: 1_530, remount: true)
    }

    func testAnInvertedListScrolledIntoTheHistoryKeepsItsTopRowAcrossARemount() throws {
        try assertKeepsTopRow(kind: .virtualList, inverted: true, scrollBy: 1_530, remount: true)
    }

    func testARemountWithoutAnchorsStartsAtTheTop() throws {
        let mounted = mount(tree(kind: .virtualList, scale: 1))
        var renderer = mounted.renderer
        let host = mounted.host
        try scroller(host).contentOffset.y = 1_530
        renderer.close()
        renderer = PamRenderer(hostView: host, dispatchEvent: { _, _, _ in })
        renderer.adoptScrollAnchors([:])
        renderer.commit([tree(kind: .virtualList, scale: 1)])
        host.layoutIfNeeded()
        // The top rests above the adjusted inset (the window's safe area).
        let fresh = try scroller(host)
        XCTAssertEqual(fresh.contentOffset.y, -fresh.adjustedContentInset.top, accuracy: 0.5)
        renderer.close()
    }
}
