import UIKit
import XCTest
@testable import PamNative

/// React Native component parity (1.6.0) on iOS. Uncompiled on Linux — needs
/// Mac validation.
@MainActor
final class PamComponentParityTests: XCTestCase {
    func testToastMessageDecodesReactNativeToastStyling() {
        let spec = PamToastSpec.decode([
            "title": .text("Saved"),
            "message": .text("Your profile was updated"),
            "bottom": .flag(true),
            "durationMs": .integer(2_500),
            "offset": .decimal(60),
            "accentColor": .integer(0xFF00_AA00),
            "titleSize": .decimal(14),
        ])
        XCTAssertFalse(spec.plain)
        XCTAssertEqual(spec.title, "Saved")
        XCTAssertTrue(spec.bottom)
        XCTAssertEqual(spec.durationMs, 2_500)
        XCTAssertEqual(spec.offset, 60)
        XCTAssertEqual(spec.accentColor, 0xFF00_AA00)
        XCTAssertEqual(spec.titleSize, 14)
        XCTAssertEqual(spec.messageSize, 10)
        let plain = PamToastSpec.decode(["message": .text("Copied"), "long": .flag(true)])
        XCTAssertTrue(plain.plain)
        XCTAssertEqual(plain.durationMs, 3_500)
    }

    func testModalPropsReachTheModalHost() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.modalAnimationType: .integer(4),
            PamConstants.modalBackdropColor: .integer(0x6600_0000),
            PamConstants.modalTransparent: .flag(false),
            PamConstants.visible: .flag(false),
        ], kind: .modal)
        defer { renderer.close() }
        XCTAssertTrue(view is PamModalHost)
    }

    func testMediaReadyPayloadCarriesNaturalSizeAndDuration() throws {
        var events: [(Int, Data)] = []
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.onMediaReady: .integer(1),
            PamConstants.onMediaLoadStart: .integer(1),
            PamConstants.onMediaBuffering: .integer(1),
        ], kind: .media) { _, kind, payload in
            events.append((kind, payload))
        }
        defer { renderer.close() }
        let media = try XCTUnwrap(view as? PamMediaView)
        media.onLoadStart?()
        media.onBuffering?(true)
        media.onReady?(1920, 1080, 12.5)
        XCTAssertEqual(events.map { $0.0 }, [
            EventKind.mediaLoadStart.rawValue,
            EventKind.mediaBuffering.rawValue,
            EventKind.mediaReady.rawValue,
        ])
        let ready = try WireMap.decode(events[2].1)
        XCTAssertEqual(ready["naturalWidth"], .integer(1920))
        XCTAssertEqual(ready["naturalHeight"], .integer(1080))
        XCTAssertEqual(ready["duration"], .decimal(12.5))
    }

    func testScrollViewKeyboardInsetInstallsAnObserver() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.scrollKeyboardInset: .flag(true),
        ], kind: .scroll)
        defer { renderer.close() }
        XCTAssertTrue(view is UIScrollView)
        renderer.commit([[.update(id: 2, key: PamConstants.scrollKeyboardInset, value: nil)]])
        XCTAssertEqual((view as? UIScrollView)?.contentInset.bottom, 0)
    }

    func testMediaCacheIdentityHexMatchesSha256() {
        XCTAssertEqual(PamMediaDiskCache.hex([0x00, 0x0F, 0xA5, 0xFF]), "000fa5ff")
        let identity = PamMediaDiskCache.shared.identity(source: "https://example.com/a.jpg", stableKey: nil)
        XCTAssertEqual(identity.count, 64)
        XCTAssertEqual(identity, PamMediaDiskCache.shared.identity(source: "https://example.com/a.jpg", stableKey: nil))
        XCTAssertEqual(PamMediaDiskCache.shared.identity(source: "x", stableKey: "avatar-1"), "avatar-1")
    }
}
