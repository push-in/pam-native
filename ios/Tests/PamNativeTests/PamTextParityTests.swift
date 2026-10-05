import CoreText
import UIKit
import XCTest
@testable import PamNative

/// React Native typography parity on iOS (mirrors Android
/// `PamTextParityInstrumentedTest`). Uncompiled on Linux — needs Mac validation.
@MainActor
final class PamTextParityTests: XCTestCase {
    override func tearDown() {
        PamTextEnvironment.textScale = 1
        super.tearDown()
    }

    func testEngineMeasurementMatchesTheRenderedTextView() throws {
        let request = PamTextMeasureRequest(
            text: "Olá, mundo! A chat bubble that wraps over a few lines 😀",
            spans: nil,
            fontFamily: nil,
            fontFeatures: nil,
            fontSize: 16,
            fontScale: 1,
            letterSpacing: 0.2,
            lineHeight: 22,
            availableWidth: 180,
            fontWeight: 400,
            italic: false,
            textTransform: 1,
            maxLines: 0
        )
        let measured = PamTextMeasurer.measure(request)
        XCTAssertLessThanOrEqual(measured.width, 180)
        XCTAssertGreaterThan(measured.lines, 1)
        XCTAssertEqual(measured.height, CGFloat(measured.lines) * 22, accuracy: 0.01)

        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.text: .text(request.text),
            PamConstants.fontSize: .decimal(16),
            PamConstants.letterSpacing: .decimal(0.2),
            PamConstants.lineHeight: .decimal(22),
        ], kind: .text, frame: Frame(x: 0, y: 0, width: Float(measured.width), height: Float(measured.height)))
        defer { renderer.close() }
        let text = try XCTUnwrap(view as? PamTextView)
        let content = try XCTUnwrap(text.pamContent)
        let drawn = text.currentLayout(content)
        XCTAssertEqual(drawn.lines.count, measured.lines, "the box re-breaks exactly like its measurement")
        XCTAssertEqual(drawn.firstBaseline, measured.baseline, accuracy: 0.01)
        XCTAssertFalse(drawn.truncated)
    }

    func testLineHeightScalesWithFontScale() {
        // 1.6.1: line pitch follows fontScale (allowFontScaling default true).
        var request = PamTextMeasureRequest(
            text: "one\ntwo",
            spans: nil,
            fontFamily: nil,
            fontFeatures: nil,
            fontSize: 14,
            fontScale: 1.5,
            letterSpacing: 0,
            lineHeight: 20,
            availableWidth: .infinity,
            fontWeight: 400,
            italic: false,
            textTransform: 1,
            maxLines: 0
        )
        XCTAssertEqual(PamTextMeasurer.measure(request).height, 60, accuracy: 0.5)
        request.fontScale = 1
        XCTAssertEqual(PamTextMeasurer.measure(request).height, 40, accuracy: 0.5)
        XCTAssertEqual(PamTextEnvironment.effectiveScale(allowsScaling: false, maximumMultiplier: 0, device: 2), 1)
        XCTAssertEqual(PamTextEnvironment.effectiveScale(allowsScaling: true, maximumMultiplier: 1.2, device: 2), 1.2)
    }

    func testNumberOfLinesTruncatesWithATailEllipsis() {
        var style = PamTextStyle()
        style.maxLines = 1
        let content = PamTextLayout.content(raw: "A long single line that cannot fit in the box", spansWire: nil, style: style)
        let result = PamTextLayout.layout(content, style: style, width: 80)
        XCTAssertEqual(result.lines.count, 1)
        XCTAssertTrue(result.truncated)
        XCTAssertGreaterThan(result.totalLineCount, 1)
        XCTAssertLessThanOrEqual(result.lines[0].width, 80.5)
    }

    func testRichSpansDrawAsOneParagraphAndDispatchPresses() throws {
        var events: [(Int64, Int, Data)] = []
        let spans = "0,5,,700,,4294901760,,2,,,,;6,12,,,,,,,,,3,"
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.text: .text("Hello @world!"),
            PamConstants.textSpans: .text(spans),
            PamConstants.onSpanPress: .integer(1),
        ], kind: .text, frame: Frame(x: 0, y: 0, width: 300, height: 40)) { id, kind, payload in
            events.append((id, kind, payload))
        }
        defer { renderer.close() }
        let parsed = PamTextSpanSpec.parse(spans)
        XCTAssertEqual(parsed.count, 2)
        XCTAssertEqual(parsed[0].fontWeight, 700)
        XCTAssertEqual(parsed[0].decoration, 2)
        XCTAssertEqual(parsed[1].press, 3)
        let text = try XCTUnwrap(view as? PamTextView)
        let content = try XCTUnwrap(text.pamContent)
        let layout = text.currentLayout(content)
        let line = try XCTUnwrap(layout.lines.first)
        let mentionX = CTLineGetOffsetForStringIndex(line.line, 8, nil)
        XCTAssertEqual(text.spanSlot(at: CGPoint(x: mentionX + 1, y: line.top + line.baseline - 2)), 3)
        XCTAssertNil(text.spanSlot(at: CGPoint(x: 2, y: line.top + line.baseline - 2)))
        XCTAssertNotNil(text.onSpanPress)
        text.onSpanPress?(3)
        XCTAssertEqual(events.last?.1, EventKind.spanPress.rawValue)
        XCTAssertEqual(events.last.map { String(decoding: $0.2, as: UTF8.self) }, "3")
    }

    func testTextTransformAppliesPerSpanSegment() {
        let specs = PamTextSpanSpec.parse("0,5,,,,,,,,,,2")
        let (text, offsets) = PamTextLayout.transformedContent(raw: "hello world", specs: specs, baseTransform: 4)
        XCTAssertEqual(text, "HELLO World")
        XCTAssertEqual(offsets(5), 5)
    }

    func testOnLayoutReportsTheFrameRelativeToItsParentOnce() {
        var payloads: [Data] = []
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 360, height: 720))
        let renderer = PamRenderer(hostView: host) { _, kind, payload in
            if kind == EventKind.layout.rawValue { payloads.append(payload) }
        }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .column, properties: [:])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view, properties: [PamConstants.onLayout: .integer(1)])),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 360, height: 720)),
            .layout(id: 2, frame: Frame(x: 10, y: 30, width: 300, height: 300)),
            .layout(id: 3, frame: Frame(x: 25, y: 70, width: 100, height: 50)),
            .setRoot(1),
        ]])
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        renderer.commit([[.layout(id: 3, frame: Frame(x: 25, y: 70, width: 100, height: 50))]])
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        XCTAssertEqual(payloads.count, 1)
        let values = try? WireMap.decode(payloads[0])
        XCTAssertEqual(values?["x"], .decimal(15))
        XCTAssertEqual(values?["y"], .decimal(40))
        XCTAssertEqual(values?["width"], .decimal(100))
    }

    func testBareFontFamilyResolvesReactNativeFileConventions() throws {
        var repository = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { repository.deleteLastPathComponent() }
        let fixture = repository.appendingPathComponent("crates/pam-native-engine/tests/fixtures/fonts/Inter.ttf")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let fonts = root.appendingPathComponent("pam/assets/fonts")
        try FileManager.default.createDirectory(at: fonts, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.copyItem(at: fixture, to: fonts.appendingPathComponent("SpaceGrotesk-SemiBold.ttf"))
        try FileManager.default.copyItem(at: fixture, to: fonts.appendingPathComponent("SpaceGrotesk.ttf"))
        let resolver = PamFontResolver(resourceRoot: root)
        let exact = try XCTUnwrap(resolver.conventionalFontAsset(family: "Space Grotesk", weight: 600, italic: false))
        XCTAssertEqual(exact.0, "pam/assets/fonts/SpaceGrotesk-SemiBold.ttf")
        XCTAssertTrue(exact.1)
        let fallback = try XCTUnwrap(resolver.conventionalFontAsset(family: "SpaceGrotesk", weight: 300, italic: false))
        XCTAssertEqual(fallback.0, "pam/assets/fonts/SpaceGrotesk.ttf")
        XCTAssertFalse(fallback.1)
        XCTAssertTrue(resolver.font(family: "SpaceGrotesk", size: 14, weight: 600, italic: false).familyName.contains("Inter"))
        XCTAssertNil(resolver.conventionalFontAsset(family: "../evil", weight: 400, italic: false))
    }

    func testIconGlyphRendersFromAPackagedFont() throws {
        var repository = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { repository.deleteLastPathComponent() }
        let fixture = repository.appendingPathComponent("crates/pam-native-engine/tests/fixtures/fonts/Inter.ttf")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let fonts = root.appendingPathComponent("pam/assets/fonts")
        try FileManager.default.createDirectory(at: fonts, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.copyItem(at: fixture, to: fonts.appendingPathComponent("Icons.ttf"))
        let resolver = PamFontResolver(resourceRoot: root)
        var style = PamTextStyle()
        style.fontFamily = "asset://assets/fonts/Icons.ttf"
        style.fontSize = 24
        let size = PamTextLayout.measure(raw: "A", spansWire: nil, style: style, availableWidth: .infinity, resolver: resolver)
        XCTAssertGreaterThan(size.width, 0)
        XCTAssertGreaterThan(size.height, 20)
    }
}
