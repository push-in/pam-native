import UIKit
import XCTest
@testable import PamNative

@MainActor
final class PamSharedSourceLookupTests: XCTestCase {
    func testTapSourcesExcludeHiddenBranchesButDestinationLookupKeepsThem() {
        let host = UIView()
        let route = UIView()
        host.addSubview(route)
        let visible = UIView()
        visible.layer.setValue("visible", forKey: "pamSharedTransitionTag")
        route.addSubview(visible)
        let hiddenScene = UIView()
        hiddenScene.isHidden = true
        route.addSubview(hiddenScene)
        let hiddenImage = UIView()
        hiddenImage.layer.setValue("hidden", forKey: "pamSharedTransitionTag")
        hiddenScene.addSubview(hiddenImage)
        let transparentScene = UIView()
        transparentScene.alpha = 0
        route.addSubview(transparentScene)
        let transparentImage = UIView()
        transparentImage.layer.setValue("transparent", forKey: "pamSharedTransitionTag")
        transparentScene.addSubview(transparentImage)
        let flights = PamSharedElementFlights(host: host)
        XCTAssertEqual(Set(flights.taggedViews(in: route, visibleOnly: true).keys), ["visible"])
        XCTAssertEqual(Set(flights.taggedViews(in: route).keys), ["visible", "hidden", "transparent"])
        hiddenScene.isHidden = false
        transparentScene.alpha = 1
        XCTAssertEqual(Set(flights.taggedViews(in: route, visibleOnly: true).keys), ["visible", "hidden", "transparent"])
    }
}
