import CoreText
import UIKit

/// One inline run of a rich `Text` node decoded from the `TextSpans` wire value
/// (`start,end,fontSize,fontWeight,fontStyle,color,backgroundColor,decoration,
/// letterSpacing,fontFamily,press,textTransform` separated by `;`). Offsets
/// count Unicode code points of the node's text; empty fields inherit.
struct PamTextSpanSpec: Equatable {
    var start: Int
    var end: Int
    var fontSize: CGFloat?
    var fontWeight: Int?
    var italic: Bool?
    var color: Int64?
    var backgroundColor: Int64?
    var decoration: Int?
    var letterSpacing: CGFloat?
    var fontFamily: String?
    var press: Int?
    var textTransform: Int?

    static func parse(_ wire: String?) -> [PamTextSpanSpec] {
        guard let wire, !wire.isEmpty else { return [] }
        var result: [PamTextSpanSpec] = []
        for record in wire.split(separator: ";", omittingEmptySubsequences: true) {
            let fields = record.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            func field(_ index: Int) -> String? {
                index < fields.count && !fields[index].isEmpty ? fields[index] : nil
            }
            guard let start = field(0).flatMap({ Int($0) }),
                  let end = field(1).flatMap({ Int($0) }),
                  start >= 0, end > start else { continue }
            var spec = PamTextSpanSpec(start: start, end: end)
            if let size = field(2).flatMap({ Double($0) }), size.isFinite, size > 0 {
                spec.fontSize = CGFloat(size)
            }
            spec.fontWeight = field(3).flatMap { Int($0) }.map { min(1000, max(1, $0)) }
            spec.italic = field(4).flatMap { Int($0) }.map { $0 == 2 }
            spec.color = field(5).flatMap { Int64($0) }
            spec.backgroundColor = field(6).flatMap { Int64($0) }
            spec.decoration = field(7).flatMap { Int($0) }
            if let spacing = field(8).flatMap({ Double($0) }), spacing.isFinite {
                spec.letterSpacing = CGFloat(spacing)
            }
            spec.fontFamily = field(9)
            spec.press = field(10).flatMap { Int($0) }.flatMap { $0 >= 0 ? $0 : nil }
            spec.textTransform = field(11).flatMap { Int($0) }
            result.append(spec)
        }
        return result
    }

    init(start: Int, end: Int) {
        self.start = start
        self.end = end
    }
}

/// Text style shared by drawing and engine measurement (logical points).
struct PamTextStyle: Equatable {
    var fontFamily: String?
    var fontSize: CGFloat = 14
    /// Effective accessibility multiplier (already clamped).
    var fontScale: CGFloat = 1
    var fontWeight: Int = 400
    var italic = false
    var letterSpacing: CGFloat = 0
    var lineHeight: CGFloat = 0
    var textTransform = 1
    var maxLines = 0
    var fontFeatures: String?
    /// Whole-node decoration (1 none, 2 underline, 3 line-through, 4 both).
    var decoration = 1
}

/// Drawing-only options of a text node.
struct PamTextDrawOptions: Equatable {
    /// 1 left, 2 center, 3 right, 4 justify; 5 / 6: the box is centered /
    /// end-aligned by its parent (RN shrink-wrap) while its lines stay left.
    var alignment = 1
    /// 1 tail, 2 head, 3 middle, 4 clip, 5 marquee (tail on iOS).
    var ellipsize = 1
    var shadowOffset = CGSize.zero
    var shadowRadius: CGFloat = 0
    var shadowColor: Int64 = 0
    var adjustsFontSizeToFit = false
    var minimumFontScale: CGFloat = 0.01
}

/// One laid-out line. `baseline` and `top` are measured from the top of the box.
struct PamTextLine {
    let line: CTLine
    let range: CFRange
    let top: CGFloat
    let height: CGFloat
    let baseline: CGFloat
    let ascent: CGFloat
    let descent: CGFloat
    let width: CGFloat
    let endsParagraph: Bool
}

struct PamTextLayoutResult {
    let lines: [PamTextLine]
    /// Unrounded content size (max line width without trailing whitespace, sum of line heights).
    let size: CGSize
    /// Wrapped lines before `numberOfLines` truncation.
    let totalLineCount: Int
    let truncated: Bool
    let lineWidths: [CGFloat]
    let fullHeight: CGFloat

    var firstBaseline: CGFloat { lines.first.map { $0.top + $0.baseline } ?? 0 }
}

enum PamTextAttribute {
    static let background = NSAttributedString.Key("PamTextBackground")
    static let decoration = NSAttributedString.Key("PamTextDecoration")
    static let press = NSAttributedString.Key("PamTextPress")
}

/// React Native iOS text pipeline on CoreText: the same attributed string,
/// line breaking, line-height rule and pixel rounding are used to measure a
/// box for the Rust engine and to draw it, so text never clips or reflows.
enum PamTextLayout {
    static let ellipsis = "\u{2026}"

    // MARK: Content

    static func transform(_ value: String, _ mode: Int) -> String {
        switch mode {
        case 2: return value.uppercased(with: Locale.current)
        case 3: return value.lowercased(with: Locale.current)
        case 4:
            var output = ""
            var atWordStart = true
            for character in value {
                if character.isWhitespace {
                    atWordStart = true
                    output.append(character)
                } else if atWordStart {
                    atWordStart = false
                    output += character.isLetter ? String(character).uppercased(with: Locale.current) : String(character)
                } else {
                    output.append(character)
                }
            }
            return output
        default:
            return value
        }
    }

    /// Applies the effective transform per span segment and returns the text
    /// plus a code-point → UTF-16 offset mapper for the span ranges.
    static func transformedContent(
        raw: String,
        specs: [PamTextSpanSpec],
        baseTransform: Int
    ) -> (String, (Int) -> Int) {
        let scalars = Array(raw.unicodeScalars)
        let count = scalars.count
        var boundaries: Set<Int> = [0, count]
        for spec in specs {
            boundaries.insert(min(max(spec.start, 0), count))
            boundaries.insert(min(max(spec.end, 0), count))
        }
        let points = boundaries.sorted()
        var output = ""
        var utf16Offset = 0
        var mapped: [Int: Int] = [:]
        for (index, point) in points.enumerated() {
            mapped[point] = utf16Offset
            guard index + 1 < points.count else { break }
            let next = points[index + 1]
            var segment = String.UnicodeScalarView()
            segment.append(contentsOf: scalars[point..<next])
            let transform = specs.last(where: {
                $0.textTransform != nil && $0.start <= point && $0.end >= next
            })?.textTransform ?? baseTransform
            let piece = Self.transform(String(segment), transform)
            output += piece
            utf16Offset += piece.utf16.count
        }
        let total = utf16Offset
        return (output, { point in mapped[min(max(point, 0), count)] ?? total })
    }

    static func baseFont(_ style: PamTextStyle, resolver: PamFontResolver = .shared) -> UIFont {
        resolver.font(
            family: style.fontFamily,
            size: style.fontSize * style.fontScale,
            weight: style.fontWeight,
            italic: style.italic,
            features: style.fontFeatures
        )
    }

    /// Attributed content. Runs without an explicit span color draw with the
    /// context fill color so the node color can change without relayout.
    static func content(
        raw: String,
        spansWire: String?,
        style: PamTextStyle,
        resolver: PamFontResolver = .shared
    ) -> NSAttributedString {
        let specs = PamTextSpanSpec.parse(spansWire)
        let (text, offsets) = transformedContent(raw: raw, specs: specs, baseTransform: style.textTransform)
        let attributed = NSMutableAttributedString(string: text)
        let length = attributed.length
        let font = baseFont(style, resolver: resolver)
        var base: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): font as CTFont,
            NSAttributedString.Key(kCTForegroundColorFromContextAttributeName as String): true,
        ]
        if style.letterSpacing != 0 {
            base[NSAttributedString.Key(kCTKernAttributeName as String)] = style.letterSpacing * style.fontScale
        }
        if style.decoration > 1 {
            base[PamTextAttribute.decoration] = style.decoration
        }
        if length > 0 {
            attributed.addAttributes(base, range: NSRange(location: 0, length: length))
        }
        for spec in specs {
            let start = offsets(spec.start)
            let end = offsets(spec.end)
            guard end > start, end <= length else { continue }
            let range = NSRange(location: start, length: end - start)
            if spec.fontSize != nil || spec.fontFamily != nil || spec.fontWeight != nil || spec.italic != nil {
                let spanFont = resolver.font(
                    family: spec.fontFamily ?? style.fontFamily,
                    size: (spec.fontSize ?? style.fontSize) * style.fontScale,
                    weight: spec.fontWeight ?? style.fontWeight,
                    italic: spec.italic ?? style.italic,
                    features: style.fontFeatures
                )
                attributed.addAttribute(
                    NSAttributedString.Key(kCTFontAttributeName as String),
                    value: spanFont as CTFont,
                    range: range
                )
            }
            if let spacing = spec.letterSpacing {
                attributed.addAttribute(
                    NSAttributedString.Key(kCTKernAttributeName as String),
                    value: spacing * style.fontScale,
                    range: range
                )
            }
            if let color = spec.color {
                attributed.addAttribute(
                    NSAttributedString.Key(kCTForegroundColorFromContextAttributeName as String),
                    value: false,
                    range: range
                )
                attributed.addAttribute(
                    NSAttributedString.Key(kCTForegroundColorAttributeName as String),
                    value: PamARGB.cgColor(color),
                    range: range
                )
            }
            if let background = spec.backgroundColor {
                attributed.addAttribute(PamTextAttribute.background, value: PamARGB.cgColor(background), range: range)
            }
            if let decoration = spec.decoration {
                attributed.addAttribute(PamTextAttribute.decoration, value: decoration, range: range)
            }
            if let press = spec.press {
                attributed.addAttribute(PamTextAttribute.press, value: press, range: range)
            }
        }
        return attributed
    }

    // MARK: Layout

    /// Lays out `content` within `width` (non-finite or <= 0: unbounded).
    static func layout(
        _ content: NSAttributedString,
        style: PamTextStyle,
        width: CGFloat,
        ellipsize: Int = 1,
        resolver: PamFontResolver = .shared
    ) -> PamTextLayoutResult {
        let bounded = width.isFinite && width > 0
        // Half a pixel of tolerance so a box sized from its own measurement re-breaks identically.
        let breakWidth = bounded ? Double(width) + 0.25 / Double(PamTextEnvironment.displayScale) : 1.0e7
        let lineHeight = max(0, style.lineHeight) * style.fontScale
        let length = content.length
        let string = content.string as NSString
        let fallbackFont = baseFont(style, resolver: resolver) as CTFont

        var raw: [(CFRange, CTLine)] = []
        if length > 0 {
            let typesetter = CTTypesetterCreateWithAttributedString(content as CFAttributedString)
            var start = 0
            while start < length {
                var count = CTTypesetterSuggestLineBreak(typesetter, start, breakWidth)
                if count <= 0 { count = length - start }
                let range = CFRange(location: start, length: count)
                raw.append((range, CTTypesetterCreateLine(typesetter, range)))
                start += count
            }
        }
        // A trailing newline opens one more (empty) line, like TextKit and StaticLayout.
        let endsWithNewline = length > 0 && isNewline(string.character(at: length - 1))
        let totalLineCount = max(1, raw.count + (endsWithNewline ? 1 : 0))
        let fullWidths = raw.map { lineWidth($0.1) }

        var truncated = false
        var visible = raw
        var appendEmpty = endsWithNewline || length == 0
        if style.maxLines > 0, totalLineCount > style.maxLines {
            truncated = true
            appendEmpty = false
            visible = Array(raw.prefix(style.maxLines))
            if let last = visible.last, ellipsize != 4 {
                let remainder = CFRange(location: last.0.location, length: length - last.0.location)
                let remainderText = content.attributedSubstring(
                    from: NSRange(location: remainder.location, length: remainder.length)
                )
                let singleLine = NSMutableAttributedString(attributedString: remainderText)
                // Hard breaks inside the remainder render as spaces on the truncated line.
                let mutable = singleLine.mutableString
                for index in stride(from: mutable.length - 1, through: 0, by: -1) where isNewline(mutable.character(at: index)) {
                    mutable.replaceCharacters(in: NSRange(location: index, length: 1), with: " ")
                }
                let source = CTLineCreateWithAttributedString(singleLine as CFAttributedString)
                let tokenAttributes = singleLine.length > 0
                    ? singleLine.attributes(at: max(0, min(singleLine.length - 1, last.0.length - 1)), effectiveRange: nil)
                    : [:]
                let token = CTLineCreateWithAttributedString(
                    NSAttributedString(string: ellipsis, attributes: tokenAttributes) as CFAttributedString
                )
                let type: CTLineTruncationType = ellipsize == 2 ? .start : (ellipsize == 3 ? .middle : .end)
                let truncationWidth = bounded ? Double(width) : Double(lineWidth(last.1))
                if let truncatedLine = CTLineCreateTruncatedLine(source, truncationWidth, type, token) {
                    visible[visible.count - 1] = (last.0, truncatedLine)
                }
            }
        }

        var lines: [PamTextLine] = []
        var top: CGFloat = 0
        var maxWidth: CGFloat = 0
        for (index, entry) in visible.enumerated() {
            var ascent: CGFloat = 0
            var descent: CGFloat = 0
            var leading: CGFloat = 0
            _ = CTLineGetTypographicBounds(entry.1, &ascent, &descent, &leading)
            if ascent + descent <= 0 {
                ascent = CTFontGetAscent(fallbackFont)
                descent = CTFontGetDescent(fallbackFont)
                leading = CTFontGetLeading(fallbackFont)
            }
            let metrics = lineMetrics(ascent: ascent, descent: descent, leading: leading, lineHeight: lineHeight)
            let width = lineWidth(entry.1)
            maxWidth = max(maxWidth, width)
            let end = entry.0.location + entry.0.length
            let endsParagraph = end >= length || (end > 0 && isNewline(string.character(at: end - 1)))
            lines.append(PamTextLine(
                line: entry.1,
                range: entry.0,
                top: top,
                height: metrics.height,
                baseline: metrics.baseline,
                ascent: ascent,
                descent: descent,
                width: width,
                endsParagraph: endsParagraph || index == visible.count - 1
            ))
            top += metrics.height
        }
        if appendEmpty {
            let ascent = CTFontGetAscent(fallbackFont)
            let descent = CTFontGetDescent(fallbackFont)
            let metrics = lineMetrics(
                ascent: ascent,
                descent: descent,
                leading: CTFontGetLeading(fallbackFont),
                lineHeight: lineHeight
            )
            let empty = CTLineCreateWithAttributedString(NSAttributedString(string: "") as CFAttributedString)
            lines.append(PamTextLine(
                line: empty,
                range: CFRange(location: length, length: 0),
                top: top,
                height: metrics.height,
                baseline: metrics.baseline,
                ascent: ascent,
                descent: descent,
                width: 0,
                endsParagraph: true
            ))
            top += metrics.height
        }
        var fullHeight = top
        if truncated {
            // Height of every wrapped line (RN onTextLayout reports the unclamped layout).
            fullHeight = top + CGFloat(totalLineCount - lines.count) * (lines.last?.height ?? 0)
        }
        return PamTextLayoutResult(
            lines: lines,
            size: CGSize(width: maxWidth, height: top),
            totalLineCount: totalLineCount,
            truncated: truncated,
            lineWidths: fullWidths,
            fullHeight: fullHeight
        )
    }

    /// React Native iOS line box: an authored `lineHeight` is the exact line
    /// height (TextKit min = max line height) and taller boxes center the
    /// glyphs (RCTApplyBaselineOffset); shorter boxes keep the bottom.
    static func lineMetrics(
        ascent: CGFloat,
        descent: CGFloat,
        leading: CGFloat,
        lineHeight: CGFloat
    ) -> (height: CGFloat, baseline: CGFloat) {
        let natural = ascent + descent + max(0, leading)
        guard lineHeight > 0 else { return (natural, ascent) }
        let extra = lineHeight - natural
        return (lineHeight, ascent + (extra >= 0 ? extra / 2 : extra))
    }

    static func lineWidth(_ line: CTLine) -> CGFloat {
        let width = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
        let trailing = CGFloat(CTLineGetTrailingWhitespaceWidth(line))
        return max(0, width - trailing)
    }

    private static func isNewline(_ character: unichar) -> Bool {
        character == 0x0A || character == 0x0D || character == 0x2028 || character == 0x2029
    }

    // MARK: Measurement

    /// Engine measurement in points: ceil-to-pixel width (never wider than the
    /// available width), ceil-to-pixel height, first baseline and line count.
    static func measure(
        raw: String,
        spansWire: String?,
        style: PamTextStyle,
        availableWidth: CGFloat,
        resolver: PamFontResolver = .shared
    ) -> (width: CGFloat, height: CGFloat, baseline: CGFloat, lines: Int) {
        let attributed = content(raw: raw, spansWire: spansWire, style: style, resolver: resolver)
        let result = layout(attributed, style: style, width: availableWidth, resolver: resolver)
        var width = PamTextEnvironment.ceilToPixel(result.size.width)
        if availableWidth.isFinite, availableWidth > 0 {
            width = min(width, availableWidth)
        }
        return (
            width,
            PamTextEnvironment.ceilToPixel(result.size.height),
            result.firstBaseline,
            max(1, result.lines.count)
        )
    }

    // MARK: Drawing

    static func horizontalOffset(_ line: PamTextLine, boxWidth: CGFloat, alignment: Int, widest: CGFloat = 0) -> CGFloat {
        let free = max(0, boxWidth - line.width)
        let blockFree = max(0, boxWidth - max(widest, line.width))
        switch alignment {
        case 2: return free / 2
        case 3: return free
        case 5: return blockFree / 2
        case 6: return blockFree
        default: return 0
        }
    }

    /// Draws `result` into a UIKit (top-left origin) context.
    static func draw(
        _ result: PamTextLayoutResult,
        in context: CGContext,
        bounds: CGRect,
        color: UIColor,
        options: PamTextDrawOptions,
        traits: UITraitCollection? = nil
    ) {
        let baseColor = traits.map { color.resolvedColor(with: $0) } ?? color
        context.saveGState()
        defer { context.restoreGState() }
        context.setFillColor(baseColor.cgColor)
        context.setStrokeColor(baseColor.cgColor)

        // Span backgrounds sit behind every glyph.
        for line in result.lines {
            forEachRun(line.line) { run, attributes in
                guard let background = attributes[PamTextAttribute.background] else { return }
                let color = background as! CGColor
                let rect = runRect(run, line: line, bounds: bounds, alignment: options.alignment, widest: result.size.width)
                context.setFillColor(color)
                context.fill(rect)
            }
        }
        context.setFillColor(baseColor.cgColor)

        if PamARGB.alpha(options.shadowColor) > 0 {
            context.setShadow(
                offset: options.shadowOffset,
                blur: max(0, options.shadowRadius),
                color: PamARGB.cgColor(options.shadowColor)
            )
        }

        for line in result.lines {
            var ctLine = line.line
            var x = bounds.minX + horizontalOffset(line, boxWidth: bounds.width, alignment: options.alignment, widest: result.size.width)
            if options.alignment == 4, !line.endsParagraph,
               let justified = CTLineCreateJustifiedLine(line.line, 1.0, Double(bounds.width)) {
                ctLine = justified
                x = bounds.minX
            }
            let baselineY = bounds.minY + line.top + line.baseline
            context.saveGState()
            context.textMatrix = .identity
            // Flip around the baseline: CoreText draws y-up.
            context.translateBy(x: x, y: baselineY)
            context.scaleBy(x: 1, y: -1)
            context.textPosition = .zero
            CTLineDraw(ctLine, context)
            context.restoreGState()
            drawDecorations(ctLine, line: line, x: x, baselineY: baselineY, context: context, baseColor: baseColor.cgColor)
        }
    }

    private static func drawDecorations(
        _ ctLine: CTLine,
        line: PamTextLine,
        x: CGFloat,
        baselineY: CGFloat,
        context: CGContext,
        baseColor: CGColor
    ) {
        forEachRun(ctLine) { run, attributes in
            guard let decoration = attributes[PamTextAttribute.decoration] as? Int, decoration > 1 else { return }
            let range = CTRunGetStringRange(run)
            let start = CTLineGetOffsetForStringIndex(ctLine, range.location, nil)
            let end = CTLineGetOffsetForStringIndex(ctLine, range.location + range.length, nil)
            guard end > start else { return }
            let fontValue = attributes[NSAttributedString.Key(kCTFontAttributeName as String)]
            let font = fontValue.map { $0 as! CTFont }
            let thickness = max(1 / PamTextEnvironment.displayScale, font.map { CTFontGetUnderlineThickness($0) } ?? 1)
            let color: CGColor
            if let explicit = attributes[NSAttributedString.Key(kCTForegroundColorAttributeName as String)],
               (attributes[NSAttributedString.Key(kCTForegroundColorFromContextAttributeName as String)] as? Bool) != true {
                color = explicit as! CGColor
            } else {
                color = baseColor
            }
            context.setFillColor(color)
            if decoration == 2 || decoration == 4 {
                let position = font.map { -CTFontGetUnderlinePosition($0) } ?? 2
                context.fill(CGRect(x: x + start, y: baselineY + position - thickness / 2, width: end - start, height: thickness))
            }
            if decoration == 3 || decoration == 4 {
                let middle = font.map { CTFontGetXHeight($0) / 2 } ?? 4
                context.fill(CGRect(x: x + start, y: baselineY - middle - thickness / 2, width: end - start, height: thickness))
            }
        }
    }

    private static func forEachRun(_ line: CTLine, _ body: (CTRun, [NSAttributedString.Key: Any]) -> Void) {
        guard let runs = CTLineGetGlyphRuns(line) as? [CTRun] else { return }
        for run in runs {
            let attributes = CTRunGetAttributes(run) as NSDictionary as? [NSAttributedString.Key: Any] ?? [:]
            body(run, attributes)
        }
    }

    private static func runRect(_ run: CTRun, line: PamTextLine, bounds: CGRect, alignment: Int, widest: CGFloat) -> CGRect {
        let range = CTRunGetStringRange(run)
        let start = CTLineGetOffsetForStringIndex(line.line, range.location, nil)
        let end = CTLineGetOffsetForStringIndex(line.line, range.location + range.length, nil)
        let x = bounds.minX + horizontalOffset(line, boxWidth: bounds.width, alignment: alignment, widest: widest)
        let baseline = bounds.minY + line.top + line.baseline
        return CGRect(x: x + start, y: baseline - line.ascent, width: max(0, end - start), height: line.ascent + line.descent)
    }

    /// Pressable span slot under `point` (box coordinates), if any.
    static func pressSlot(
        at point: CGPoint,
        in result: PamTextLayoutResult,
        content: NSAttributedString,
        boxWidth: CGFloat,
        alignment: Int
    ) -> Int? {
        guard let line = result.lines.first(where: { point.y >= $0.top && point.y < $0.top + $0.height }) else {
            return nil
        }
        let x = point.x - horizontalOffset(line, boxWidth: boxWidth, alignment: alignment, widest: result.size.width)
        guard x >= 0, x <= line.width else { return nil }
        let index = CTLineGetStringIndexForPosition(line.line, CGPoint(x: x, y: 0))
        guard index != kCFNotFound else { return nil }
        // The index is the caret position; probe the character on each side.
        for candidate in [index, index - 1] where candidate >= line.range.location
            && candidate < line.range.location + line.range.length
            && candidate < content.length {
            if let slot = content.attribute(PamTextAttribute.press, at: candidate, effectiveRange: nil) as? Int {
                let glyphStart = CTLineGetOffsetForStringIndex(line.line, candidate, nil)
                let glyphEnd = CTLineGetOffsetForStringIndex(line.line, candidate + 1, nil)
                if x >= min(glyphStart, glyphEnd) - 1, x <= max(glyphStart, glyphEnd) + 1 {
                    return slot
                }
            }
        }
        return nil
    }
}
