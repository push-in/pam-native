import UIKit

typealias PamNativeMeasureTextCallback = @convention(c) (
    UInt64,
    UnsafePointer<UInt8>?,
    Int,
    UnsafePointer<UInt8>?,
    Int,
    UnsafePointer<UInt8>?,
    Int,
    UnsafePointer<UInt8>?,
    Int,
    Float,
    Float,
    Float,
    Float,
    Float,
    Int32,
    Int32,
    Int32,
    UInt32,
    UnsafeMutablePointer<Float>?
) -> Int32

@_silgen_name("pam_native_ios_set_text_measurer")
func pam_native_ios_set_text_measurer(_ callback: PamNativeMeasureTextCallback?)

/// One engine measurement request (logical points).
struct PamTextMeasureRequest: Equatable {
    var text: String
    var spans: String?
    var fontFamily: String?
    var fontFeatures: String?
    var fontSize: CGFloat
    var fontScale: CGFloat
    var letterSpacing: CGFloat
    var lineHeight: CGFloat
    var availableWidth: CGFloat
    var fontWeight: Int
    var italic: Bool
    var textTransform: Int
    var maxLines: Int

    var style: PamTextStyle {
        var style = PamTextStyle()
        style.fontFamily = fontFamily
        style.fontSize = max(1, fontSize)
        style.fontScale = fontScale > 0 ? fontScale : 1
        style.fontWeight = min(1000, max(1, fontWeight))
        style.italic = italic
        style.letterSpacing = letterSpacing
        style.lineHeight = max(0, lineHeight)
        style.textTransform = textTransform
        style.maxLines = max(0, maxLines)
        style.fontFeatures = fontFeatures
        return style
    }
}

/// Host side of `pam_native_engine_set_text_measurer` (React Native's Yoga
/// measure functions): text boxes are sized by `PamTextLayout`, the pipeline
/// `PamTextView` draws with. Runs on the PHP worker thread; it only touches
/// thread-safe CoreText/UIFont APIs and `PamTextEnvironment`.
enum PamTextMeasurer {
    static func install() {
        pam_native_ios_set_text_measurer(pamNativeIosMeasureText)
    }

    static func measure(_ request: PamTextMeasureRequest) -> (width: CGFloat, height: CGFloat, baseline: CGFloat, lines: Int) {
        PamTextLayout.measure(
            raw: request.text,
            spansWire: request.spans,
            style: request.style,
            availableWidth: request.availableWidth
        )
    }

    fileprivate static func string(_ pointer: UnsafePointer<UInt8>?, _ length: Int) -> String? {
        guard let pointer, length > 0 else { return nil }
        return String(decoding: UnsafeBufferPointer(start: pointer, count: length), as: UTF8.self)
    }
}

@_cdecl("pam_native_ios_measure_text")
private func pamNativeIosMeasureText(
    _ nodeId: UInt64,
    _ text: UnsafePointer<UInt8>?,
    _ textLength: Int,
    _ spans: UnsafePointer<UInt8>?,
    _ spansLength: Int,
    _ family: UnsafePointer<UInt8>?,
    _ familyLength: Int,
    _ features: UnsafePointer<UInt8>?,
    _ featuresLength: Int,
    _ fontSize: Float,
    _ fontScale: Float,
    _ letterSpacing: Float,
    _ lineHeight: Float,
    _ availableWidth: Float,
    _ fontWeight: Int32,
    _ italic: Int32,
    _ textTransform: Int32,
    _ maxLines: UInt32,
    _ output: UnsafeMutablePointer<Float>?
) -> Int32 {
    guard let output else { return 0 }
    _ = nodeId
    return autoreleasepool { () -> Int32 in
        let request = PamTextMeasureRequest(
            text: PamTextMeasurer.string(text, textLength) ?? "",
            spans: PamTextMeasurer.string(spans, spansLength),
            fontFamily: PamTextMeasurer.string(family, familyLength),
            fontFeatures: PamTextMeasurer.string(features, featuresLength),
            fontSize: CGFloat(fontSize),
            fontScale: CGFloat(fontScale),
            letterSpacing: CGFloat(letterSpacing),
            lineHeight: CGFloat(lineHeight),
            availableWidth: availableWidth.isFinite && availableWidth > 0 ? CGFloat(availableWidth) : .infinity,
            fontWeight: Int(fontWeight),
            italic: italic != 0,
            textTransform: Int(textTransform),
            maxLines: Int(min(maxLines, UInt32(Int32.max)))
        )
        let measured = PamTextMeasurer.measure(request)
        guard measured.width.isFinite, measured.height.isFinite else { return 0 }
        output[0] = Float(measured.width)
        output[1] = Float(measured.height)
        output[2] = Float(measured.baseline)
        output[3] = Float(measured.lines)
        return 1
    }
}
