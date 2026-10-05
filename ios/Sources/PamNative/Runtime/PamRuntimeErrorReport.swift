import Foundation

/// One uncaught runtime error as reported by PHP (`PAMERR1` JSON, version 1 or
/// 2) or by the native host (plain text). Paths are app-relative and frames
/// are classified so the overlay shows the app frame first and collapses
/// framework/vendor frames. Mirrors Android `RuntimeErrorReport`.
public struct PamRuntimeErrorReport: Equatable {
    public struct Frame: Equatable {
        public let file: String?
        public let line: Int
        public let call: String
        public let kind: String

        public var isApp: Bool { kind == PamRuntimeErrorReport.kindApp }
        public var location: String { file.map { "\($0):\(line)" } ?? "[internal function]" }
    }

    public struct Snippet: Equatable {
        public let file: String
        public let line: Int
        public let start: Int
        public let lines: [String]
    }

    public static let kindApp = "app"
    public static let kindFramework = "framework"
    public static let kindVendor = "vendor"
    public static let kindInternal = "internal"

    public let type: String
    public let message: String
    public let file: String
    public let line: Int
    public let column: Int
    public let phase: String
    public let fatal: Bool
    public let frames: [Frame]
    public let appFrame: Int?
    public let snippet: Snippet?
    public let fingerprint: String

    private static let prefix = "PAMERR1\n"
    private static let maxText = 12_000
    private static let maxFrames = 128

    public var shortType: String {
        type.split(separator: "\\").last.map(String.init) ?? type
    }

    public var location: String { line > 0 ? "\(file):\(line)" : file }

    public var phaseLabel: String {
        switch phase {
        case "boot": return "Boot error"
        case "render": return "Render error"
        case "event": return "Event handler error"
        case "module": return "Module callback error"
        case "hot-reload": return "Hot reload error"
        case "native": return "Native runtime error"
        default: return "Uncaught error"
        }
    }

    /// Plain-text report for the clipboard and the console.
    public func copyText() -> String {
        var output = "\(type): \(message)\nat \(location) (\(phaseLabel)\(fatal ? ", fatal" : ""))\n"
        if let snippet {
            output += "\n"
            for (offset, text) in snippet.lines.enumerated() {
                let number = snippet.start + offset
                let padded = String(repeating: " ", count: max(0, 4 - String(number).count)) + String(number)
                output += (number == snippet.line ? "> " : "  ") + padded + " | " + text + "\n"
            }
        }
        if !frames.isEmpty {
            output += "\n"
            for (index, frame) in frames.enumerated() {
                output += "#\(index) \(frame.location): \(frame.call)\n"
            }
        }
        return output.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Strips the installed bundle prefix (`…/pam/releases/<hash>/`, `…/PamBundle/`).
    public static func shortenPath(_ path: String) -> String {
        let patterns = [
            "^.*?/pam/(?:ota-)?releases/[^/]+/",
            "^.*?/PamBundle/",
            "^.*?/Documents/pam/[^/]+/[^/]+/",
        ]
        for pattern in patterns {
            if let range = path.range(of: pattern, options: .regularExpression) {
                return String(path[range.upperBound...])
            }
        }
        return path
    }

    public static func parse(_ raw: String) -> PamRuntimeErrorReport {
        guard raw.hasPrefix(prefix) else { return native(raw) }
        let body = String(raw.dropFirst(prefix.count))
        guard let data = body.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return native(body)
        }
        return fromJSON(json)
    }

    public static func native(_ message: String, fatal: Bool = true, phase: String = "native") -> PamRuntimeErrorReport {
        var text = String(message.prefix(maxText))
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { text = "Pam Native runtime error" }
        return PamRuntimeErrorReport(
            type: "NativeRuntimeError",
            message: text,
            file: "Native runtime",
            line: 0,
            column: 0,
            phase: phase,
            fatal: fatal,
            frames: [],
            appFrame: nil,
            snippet: nil,
            fingerprint: "native:\(text.hashValue)"
        )
    }

    private static func fromJSON(_ json: [String: Any]) -> PamRuntimeErrorReport {
        func int(_ key: String, _ fallback: Int) -> Int { (json[key] as? NSNumber)?.intValue ?? fallback }
        let type = json["type"] as? String ?? "PHP error"
        let message = String((json["message"] as? String ?? "Unknown PHP error").prefix(maxText))
        let file = shortenPath(json["file"] as? String ?? "<unknown>")
        let line = int("line", 0)
        let frames: [Frame]
        if let array = json["frames"] as? [Any] {
            frames = array.prefix(maxFrames).compactMap { item in
                guard let object = item as? [String: Any] else { return nil }
                let frameFile = (object["file"] as? String).map(shortenPath)
                return Frame(
                    file: frameFile,
                    line: (object["line"] as? NSNumber)?.intValue ?? 0,
                    call: object["call"] as? String ?? "",
                    kind: object["kind"] as? String ?? classify(frameFile)
                )
            }
        } else {
            frames = framesFromTrace(json["trace"] as? String ?? "")
        }
        let appFrame: Int?
        if let explicit = json["appFrame"] as? NSNumber {
            appFrame = frames.indices.contains(explicit.intValue) ? explicit.intValue : nil
        } else {
            appFrame = frames.firstIndex(where: { $0.isApp })
        }
        let phase = json["phase"] as? String ?? ""
        var snippet: Snippet?
        if let object = json["snippet"] as? [String: Any], let lines = object["lines"] as? [Any] {
            snippet = Snippet(
                file: shortenPath(object["file"] as? String ?? ""),
                line: (object["line"] as? NSNumber)?.intValue ?? 0,
                start: (object["start"] as? NSNumber)?.intValue ?? 1,
                lines: lines.prefix(32).map { ($0 as? String) ?? "" }
            )
        }
        let fingerprint = (json["fingerprint"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            ?? "\(type)\u{0}\(message)\u{0}\(file)\u{0}\(line)"
        return PamRuntimeErrorReport(
            type: type,
            message: message,
            file: file,
            line: line,
            column: int("column", 1),
            phase: phase.isEmpty ? "other" : phase,
            // Version 1 payloads carried no phase: keep their full-screen treatment.
            fatal: (json["fatal"] as? Bool) ?? phase.isEmpty,
            frames: frames,
            appFrame: appFrame,
            snippet: snippet,
            fingerprint: fingerprint
        )
    }

    /// Legacy `getTraceAsString()` text: `#0 /path/File.php(12): call()`.
    static func framesFromTrace(_ trace: String) -> [Frame] {
        guard let expression = try? NSRegularExpression(
            pattern: #"^#\d+\s+(?:(.+)\((\d+)\)|\[internal function\]|\{main\})(?::\s*(.*))?$"#
        ) else { return [] }
        var frames: [Frame] = []
        for raw in trace.split(separator: "\n", omittingEmptySubsequences: true).prefix(maxFrames) {
            let text = raw.trimmingCharacters(in: .whitespaces)
            let range = NSRange(text.startIndex..., in: text)
            guard let match = expression.firstMatch(in: text, range: range) else { continue }
            func group(_ index: Int) -> String? {
                guard let groupRange = Range(match.range(at: index), in: text) else { return nil }
                return String(text[groupRange])
            }
            let file = group(1).flatMap { $0.isEmpty ? nil : shortenPath($0) }
            var call = group(3) ?? ""
            if call.isEmpty, text.contains("{main}") { call = "{main}" }
            frames.append(Frame(file: file, line: Int(group(2) ?? "") ?? 0, call: call, kind: classify(file)))
        }
        return frames
    }

    static func classify(_ file: String?) -> String {
        guard let file else { return kindInternal }
        if file.contains("vendor/pushinbr/pam-native") || file.contains("packages/native/src/") { return kindFramework }
        if file.contains("vendor/") { return kindVendor }
        return kindApp
    }
}
