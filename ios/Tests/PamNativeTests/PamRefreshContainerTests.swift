import XCTest
@testable import PamNative

final class PamRefreshContainerTests: XCTestCase {
    func testInsertedScrollChildReceivesTouchesAndTheRefreshControl() {
        let container = PamRefreshContainer(frame: CGRect(x: 0, y: 0, width: 320, height: 400))
        let list = UIScrollView()
        let row = UIView(frame: CGRect(x: 0, y: 100, width: 320, height: 72))
        list.addSubview(row)
        container.setOnRefresh {}
        container.insert(list, index: 0)
        container.layoutIfNeeded()

        XCTAssertTrue(container.hitTest(CGPoint(x: 160, y: 130), with: nil) === row)
        XCTAssertNotNil(list.refreshControl)
    }
}
