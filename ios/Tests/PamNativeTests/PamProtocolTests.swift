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
            .mediaCacheHit,
            .mediaCacheMiss,
            .mediaCacheProgress,
            .mediaCacheReady,
            .accessibilityAction,
        ]
        XCTAssertEqual(events.map(\.rawValue), Array(1...65))
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
        XCTAssertEqual(PamConstants.hitSlop, 96)
        XCTAssertEqual(PamConstants.hitSlopLeft, 233)
        XCTAssertEqual(PamConstants.hitSlopTop, 234)
        XCTAssertEqual(PamConstants.hitSlopRight, 235)
        XCTAssertEqual(PamConstants.hitSlopBottom, 236)
        XCTAssertEqual(PamConstants.sharedTransitionConfig, 449)
        XCTAssertEqual(PamConstants.accessibilityActions, 450)
        XCTAssertEqual(PamConstants.onAccessibilityAction, 451)
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

    func testDecoderEnforcesSharedPropertyAndNodeLimits() throws {
        let acceptedText = try BatchDecoder.decode(textBatch(length: 1024 * 1024))
        guard case let .create(textNode)? = acceptedText.first,
              case let .text(text)? = textNode.properties[PamConstants.text]
        else {
            return XCTFail("Expected a text property")
        }
        XCTAssertEqual(text.utf8.count, 1024 * 1024)
        XCTAssertThrowsError(try BatchDecoder.decode(textBatch(length: 1024 * 1024 + 1)))

        let acceptedProperties = try BatchDecoder.decode(propertyBatch(count: 128))
        guard case let .create(propertyNode)? = acceptedProperties.first else {
            return XCTFail("Expected a created node")
        }
        XCTAssertEqual(propertyNode.properties.count, 128)
        XCTAssertThrowsError(try BatchDecoder.decode(propertyBatch(count: 129)))
    }

    func testDecoderRejectsMalformedUTF8AcrossTextContainers() {
        let invalid = Data([0xc3, 0x28])
        XCTAssertThrowsError(try BatchDecoder.decode(textBatch(payload: invalid)))

        var list = Data()
        list.appendLittleEndian(UInt32(1))
        list.appendLittleEndian(UInt32(invalid.count))
        list.append(invalid)
        XCTAssertThrowsError(try PackedStringList.decode(list))

        var sections = Data()
        sections.appendLittleEndian(UInt32(1))
        sections.appendLittleEndian(UInt32(invalid.count))
        sections.append(invalid)
        sections.appendLittleEndian(UInt32(0))
        XCTAssertThrowsError(try PackedSectionList.decode(sections))

        var wire = Data()
        wire.appendLittleEndian(UInt16(1))
        wire.appendLittleEndian(UInt16(1))
        wire.append(0x61)
        wire.append(1)
        wire.appendLittleEndian(UInt32(invalid.count))
        wire.append(invalid)
        XCTAssertThrowsError(try WireMap.decode(wire))
        XCTAssertThrowsError(try WireMap.encode(["1invalid": .text("value")]))

        var invalidBoolean = Data()
        invalidBoolean.appendLittleEndian(UInt16(1))
        invalidBoolean.appendLittleEndian(UInt16(4))
        invalidBoolean.append(contentsOf: "flag".utf8)
        invalidBoolean.append(4)
        invalidBoolean.append(2)
        XCTAssertThrowsError(try WireMap.decode(invalidBoolean))

        var duplicate = Data()
        duplicate.appendLittleEndian(UInt16(2))
        for flag in [UInt8(0), UInt8(1)] {
            duplicate.appendLittleEndian(UInt16(3))
            duplicate.append(contentsOf: "key".utf8)
            duplicate.append(4)
            duplicate.append(flag)
        }
        XCTAssertThrowsError(try WireMap.decode(duplicate))
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

private func textBatch(length: Int) -> Data {
    textBatch(payload: Data(repeating: 0x61, count: length))
}

private func textBatch(payload: Data) -> Data {
    var data = batch(propertyCount: 1, payloadBytes: payload.count)
    data.appendLittleEndian(UInt16(PamConstants.text))
    data.append(1)
    data.appendLittleEndian(UInt32(payload.count))
    data.append(payload)
    return data
}

private func propertyBatch(count: Int) -> Data {
    var data = batch(propertyCount: count, payloadBytes: count * 4)
    for key in 1...count {
        data.appendLittleEndian(UInt16(key))
        data.append(4)
        data.append(1)
    }
    return data
}

private func batch(propertyCount: Int, payloadBytes: Int) -> Data {
    var data = Data(capacity: 32 + payloadBytes)
    data.append(contentsOf: "PNB1".utf8)
    data.appendLittleEndian(UInt16(1))
    data.appendLittleEndian(UInt32(1))
    data.append(1)
    data.appendLittleEndian(UInt64(1))
    data.appendLittleEndian(UInt64(0))
    data.appendLittleEndian(UInt32(0))
    data.append(UInt8(NodeKind.screen.rawValue))
    data.appendLittleEndian(UInt16(propertyCount))
    return data
}

private extension Data {
    mutating func appendLittleEndian<T: FixedWidthInteger>(_ value: T) {
        var littleEndian = value.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { append(contentsOf: $0) }
    }

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
