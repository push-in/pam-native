import XCTest
@testable import PamNative

final class PamWebBridgeTests: XCTestCase {
    func testDocumentStartScriptDefinesBothChannels() {
        XCTAssertTrue(PamWebBridge.documentStartScript.contains("window.PamNative.postMessage"))
        XCTAssertTrue(PamWebBridge.documentStartScript.contains("window.ReactNativeWebView"))
        XCTAssertTrue(PamWebBridge.documentStartScript.contains("messageHandlers.pam"))
    }

    func testOnlyMainFrameMessagesWithinTheLimitPass() {
        XCTAssertTrue(PamWebBridge.accepts("{}", isMainFrame: true))
        XCTAssertFalse(PamWebBridge.accepts("{}", isMainFrame: false))
        XCTAssertFalse(
            PamWebBridge.accepts(
                String(repeating: "x", count: PamWebBridge.maxMessageCharacters + 1),
                isMainFrame: true
            )
        )
    }

    func testChangedScriptRunsOnlyOnLoadedPage() {
        XCTAssertTrue(PamWebBridge.runsOnChange(previous: "a", next: "b", pageLoaded: true))
        XCTAssertFalse(PamWebBridge.runsOnChange(previous: "a", next: "a", pageLoaded: true))
        XCTAssertFalse(PamWebBridge.runsOnChange(previous: "a", next: "", pageLoaded: true))
        XCTAssertFalse(PamWebBridge.runsOnChange(previous: "a", next: "b", pageLoaded: false))
    }

    func testHardenedFixedWebView() {
        let webView = PamWebView()
        XCTAssertFalse(webView.allowsLinkPreview)
        XCTAssertFalse(webView.allowsBackForwardNavigationGestures)
        XCTAssertEqual(webView.configuration.dataDetectorTypes, [])
        webView.setScrollEnabled(false)
        XCTAssertFalse(webView.scrollView.isScrollEnabled)
        XCTAssertFalse(webView.scrollView.bounces)
        webView.setScrollEnabled(true)
        XCTAssertTrue(webView.scrollView.bounces)
    }
}
