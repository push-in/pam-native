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

    func testAnIdleRefreshControlDropsItsRepeatingSpinner() throws {
        let container = PamRefreshContainer(frame: CGRect(x: 0, y: 0, width: 320, height: 400))
        let list = UIScrollView()
        container.setOnRefresh {}
        container.insert(list, index: 0)
        container.layoutIfNeeded()
        let control = try XCTUnwrap(list.refreshControl)
        let spinner = CALayer()
        control.layer.addSublayer(spinner)
        let spin = CABasicAnimation(keyPath: "transform.rotation")
        spin.repeatCount = .greatestFiniteMagnitude
        spinner.add(spin, forKey: "spin")
        let fade = CABasicAnimation(keyPath: "opacity")
        spinner.add(fade, forKey: "fade")

        // Scrolling a list that is not refreshing nor pulled settles it.
        list.contentOffset = CGPoint(x: 0, y: 40)

        XCTAssertNil(spinner.animation(forKey: "spin"), "the endless spin no longer runs while idle")
        XCTAssertNotNil(spinner.animation(forKey: "fade"), "finite animations finish normally")
    }

    func testAPulledListKeepsItsSpinner() throws {
        let container = PamRefreshContainer(frame: CGRect(x: 0, y: 0, width: 320, height: 400))
        let list = UIScrollView()
        container.setOnRefresh {}
        container.insert(list, index: 0)
        container.layoutIfNeeded()
        let control = try XCTUnwrap(list.refreshControl)
        let spinner = CALayer()
        control.layer.addSublayer(spinner)
        let spin = CABasicAnimation(keyPath: "transform.rotation")
        spin.repeatCount = .greatestFiniteMagnitude
        spinner.add(spin, forKey: "spin")

        list.contentOffset = CGPoint(x: 0, y: -80)

        XCTAssertNotNil(spinner.animation(forKey: "spin"), "a pull past the top shows the spinner")
    }
}
