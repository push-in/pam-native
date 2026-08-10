import XCTest
@testable import PamNative

final class PamProtocolTests: XCTestCase {
    func testDirectiveProtocolIdsRemainSequentialAndAppendOnly() {
        let events = [
            EventKind.press,
            .change,
            .back,
            .moduleResult,
            .longPress,
            .focus,
            .blur,
            .submit,
            .scroll,
            .refresh,
            .toggle,
            .endReached,
            .drawerOpen,
            .drawerClose,
            .native,
            .appState,
            .dimensions,
            .memoryPressure,
            .imageLoadStart,
            .imageProgress,
            .imageLoad,
            .imageError,
            .imageLoadEnd,
            .inputEndEditing,
            .inputSelectionChange,
            .inputContentSizeChange,
            .inputKeyPress,
            .pressIn,
            .pressOut,
            .pressMove,
            .modalRequestClose,
            .modalShow,
            .modalDismiss,
            .modalOrientationChange,
            .clickOutside,
            .intersect,
            .mutate,
            .resize,
            .touchStart,
            .touchMove,
            .touchEnd,
            .gestureBegin,
            .gestureUpdate,
            .gestureEnd,
            .gestureCancel,
            .bottomSheetChange,
            .bottomSheetDismiss,
            .webViewLoad,
            .webViewError,
            .webViewMessage,
            .mediaReady,
            .mediaProgress,
            .mediaEnd,
            .mediaError,
            .dragStart,
            .dragEnd,
            .drop,
            .menuAction,
            .navigationGesturePop,
            .animationComplete,
        ]
        XCTAssertEqual(events.map(\.rawValue), Array(1...60))
        XCTAssertEqual(PamConstants.onClickOutside, 285)
        XCTAssertEqual(PamConstants.onIntersect, 286)
        XCTAssertEqual(PamConstants.onMutate, 287)
        XCTAssertEqual(PamConstants.onResize, 288)
        XCTAssertEqual(PamConstants.onTouchStart, 289)
        XCTAssertEqual(PamConstants.onTouchMove, 290)
        XCTAssertEqual(PamConstants.onTouchEnd, 291)
        XCTAssertEqual(PamConstants.gestureType, 303)
        XCTAssertEqual(PamConstants.onGestureCancel, 314)
        XCTAssertEqual(PamConstants.bottomSheetSnapPoints, 315)
        XCTAssertEqual(PamConstants.onBottomSheetDismiss, 324)
        XCTAssertEqual(NodeKind.media.rawValue, 28)
        XCTAssertEqual(NodeKind.drawingCanvas.rawValue, 29)
        XCTAssertEqual(NodeKind.canvas.rawValue, 31)
        XCTAssertEqual(PamConstants.drawingUndoRequest, 395)
        XCTAssertEqual(PamConstants.flexWrap, 396)
        XCTAssertEqual(PamConstants.bottomPercent, 400)
        XCTAssertEqual(PamConstants.shadowColor, 405)
        XCTAssertEqual(PamConstants.onMediaError, 346)
        XCTAssertEqual(PamConstants.onMenuAction, 354)
        XCTAssertEqual(PamConstants.onNavigationGesturePop, 358)
        XCTAssertEqual(PamConstants.onAnimationComplete, 365)
        XCTAssertEqual(PamConstants.webViewAllowedHosts, 366)
        XCTAssertEqual(PamConstants.mediaCacheChecksum, 384)
        XCTAssertEqual(PamConstants.canvasCommands, 440)
        XCTAssertEqual(PamConstants.workletIterations, 444)
        XCTAssertEqual(PamConstants.navigationBarHidden, 445)
        XCTAssertEqual(PamConstants.borderStyle, 446)
        XCTAssertEqual(PamConstants.scrollTargetAlignment, 447)
        XCTAssertEqual(PamConstants.pressScale, 448)
        XCTAssertEqual(PamConstants.sharedTransitionConfig, 449)
        XCTAssertEqual(
            [
                NativeOperation.httpGet,
                .storageGet,
                .storageSet,
                .alert,
                .toast,
                .share,
                .openUrl,
                .canOpenUrl,
                .vibrate,
                .deviceInfo,
                .keyboardDismiss,
                .permissionCheck,
                .permissionRequest,
                .closeApp,
                .haptic,
                .clipboardSetText,
                .clipboardGetText,
                .clipboardHasText,
                .sensorRead,
            ].map(\.rawValue),
            Array(1...19)
        )
    }

    func testRustGoldenSetRootBatchDecodesOnSwift() throws {
        let bytes = Data(hex: "504e4231010001000000060100000000000000")
        let mutations = try BatchDecoder.decode(bytes)
        guard case let .setRoot(root) = mutations.first else {
            return XCTFail("Expected a SetRoot mutation")
        }
        XCTAssertEqual(mutations.count, 1)
        XCTAssertEqual(root, 1)
    }

    func testDirectiveGeometryPayloadRoundTrips() throws {
        let encoded = try WireMap.encode([
            "x": .decimal(12.5),
            "y": .decimal(18.25),
            "width": .decimal(120),
            "height": .decimal(48),
            "intersecting": .flag(true),
        ])
        let decoded = try WireMap.decode(encoded)
        XCTAssertEqual(decoded["x"]?.decimal, 12.5)
        XCTAssertEqual(decoded["y"]?.decimal, 18.25)
        XCTAssertEqual(decoded["width"]?.decimal, 120)
        XCTAssertEqual(decoded["height"]?.decimal, 48)
        XCTAssertEqual(decoded["intersecting"]?.flag, true)
    }
}

private extension Data {
    init(hex: String) {
        self.init(
            stride(from: 0, to: hex.count, by: 2).compactMap { offset in
                let start = hex.index(hex.startIndex, offsetBy: offset)
                let end = hex.index(start, offsetBy: 2)
                return UInt8(hex[start..<end], radix: 16)
            }
        )
    }
}

private extension WireValue {
    var decimal: Double? {
        if case let .decimal(value) = self { return value }
        return nil
    }

    var flag: Bool? {
        if case let .flag(value) = self { return value }
        return nil
    }
}
