import UIKit
import XCTest
@testable import PamNative

/// LogBox-style error overlay (mirrors Android `ErrorOverlayInstrumentedTest`).
/// Uncompiled on Linux — needs Mac validation.
@MainActor
final class PamErrorOverlayTests: XCTestCase {
    private func payload(fatal: Bool, message: String = "Boom", fingerprint: String = "fp-1") -> String {
        let json: [String: Any] = [
            "version": 2,
            "type": "App\\Errors\\ChatException",
            "message": message,
            "file": "/var/mobile/Containers/Data/Application/X/Documents/pam/ota-releases/abc123/src/Chat.php",
            "line": 42,
            "phase": fatal ? "render" : "event",
            "fatal": fatal,
            "frames": [
                ["file": "/x/pam/ota-releases/abc123/vendor/pushinbr/pam-native/src/Runtime.php", "line": 10, "call": "Runtime::run()", "kind": "framework"],
                ["file": "/x/pam/ota-releases/abc123/src/Chat.php", "line": 42, "call": "Chat->send()", "kind": "app"],
            ],
            "appFrame": 1,
            "snippet": ["file": "src/Chat.php", "line": 42, "start": 41, "lines": ["a();", "throw new X();", "b();"]],
            "fingerprint": fingerprint,
        ]
        let data = try! JSONSerialization.data(withJSONObject: json)
        return "PAMERR1\n" + String(decoding: data, as: UTF8.self)
    }

    func testVersionTwoPayloadIsParsedWithAppRelativePaths() {
        let report = PamRuntimeErrorReport.parse(payload(fatal: true))
        XCTAssertEqual(report.shortType, "ChatException")
        XCTAssertEqual(report.file, "src/Chat.php")
        XCTAssertEqual(report.phase, "render")
        XCTAssertTrue(report.fatal)
        XCTAssertEqual(report.appFrame, 1)
        XCTAssertEqual(report.frames[0].file, "vendor/pushinbr/pam-native/src/Runtime.php")
        XCTAssertTrue(report.copyText().contains(">   42 | throw new X();"))
    }

    func testPlainTextAndLegacyTracesStillRender() {
        let native = PamRuntimeErrorReport.parse("Pam Native failed to start")
        XCTAssertEqual(native.type, "NativeRuntimeError")
        XCTAssertTrue(native.fatal)
        let frames = PamRuntimeErrorReport.framesFromTrace("#0 /app/src/A.php(12): A->b()\n#1 {main}")
        XCTAssertEqual(frames.count, 2)
        XCTAssertEqual(frames[0].line, 12)
        XCTAssertEqual(frames[1].call, "{main}")
    }

    func testNonFatalErrorsShowAToastAndRepeatsCount() {
        let overlay = PamErrorOverlay(developerMode: true)
        let report = PamRuntimeErrorReport.parse(payload(fatal: false))
        overlay.report(report)
        XCTAssertTrue(overlay.isToastVisible)
        XCTAssertFalse(overlay.isInspectorVisible)
        overlay.report(report)
        XCTAssertEqual(overlay.entries.count, 1)
        XCTAssertEqual(overlay.entries[0].count, 2)
        // The overlay never swallows touches outside its toast.
        overlay.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        overlay.layoutIfNeeded()
        XCTAssertFalse(overlay.point(inside: CGPoint(x: 195, y: 100), with: nil))
    }

    func testFatalErrorsOpenTheInspectorAndDismissedErrorsStayDismissed() {
        let overlay = PamErrorOverlay(developerMode: true)
        let fatal = PamRuntimeErrorReport.parse(payload(fatal: true, fingerprint: "fatal"))
        overlay.report(PamRuntimeErrorReport.parse(payload(fatal: false, message: "Other", fingerprint: "other")))
        overlay.report(fatal)
        XCTAssertTrue(overlay.isInspectorVisible)
        XCTAssertEqual(overlay.index, 1)
        overlay.dismissCurrent()
        XCTAssertEqual(overlay.entries.count, 1)
        overlay.dismissCurrent()
        XCTAssertTrue(overlay.isHidden)
        overlay.report(fatal)
        XCTAssertTrue(overlay.isHidden, "a dismissed error is not re-shown until reload")
        overlay.onRuntimeReload()
        overlay.report(fatal)
        XCTAssertTrue(overlay.isInspectorVisible)
    }

    func testReleaseFallbackHidesOnTheNextFrame() {
        let overlay = PamErrorOverlay(developerMode: false)
        var reloads = 0
        overlay.onReload = { reloads += 1 }
        overlay.showFallback()
        XCTAssertTrue(overlay.isFallbackVisible)
        overlay.onFrameCommitted()
        XCTAssertFalse(overlay.isFallbackVisible)
        XCTAssertTrue(overlay.isHidden)
        XCTAssertEqual(reloads, 0)
    }

    func testDevErrorOverlayModeFollowsInfoPlist() {
        XCTAssertTrue(PamErrorOverlay.developerMode(debugBuild: true, bundle: Bundle(for: Self.self)))
        XCTAssertFalse(PamErrorOverlay.developerMode(debugBuild: false, bundle: Bundle(for: Self.self)))
    }
}
