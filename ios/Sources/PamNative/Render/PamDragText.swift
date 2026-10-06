import Foundation

enum PamDragTextFormat: Int {
    case number = 1
    case clock = 2
    case clockWithTotal = 3
}

struct PamDragTextDriver: Equatable {
    let ref: String
    let format: PamDragTextFormat
    let decimals: Int
    let input: [PamMotionValue]
    let output: [Double]

    func label(position: Double, extent: Double) -> String {
        let input = input.map { $0.percent ? extent * $0.number / 100 : $0.number }
        let value = PamDragMath.interpolate(input, output, position)
        switch format {
        case .number: return String(format: "%.*f", locale: Locale(identifier: "en_US_POSIX"), decimals, value)
        case .clock: return Self.clock(value)
        case .clockWithTotal: return "\(Self.clock(value)) / \(Self.clock(output.last ?? 0))"
        }
    }

    static func parse(_ source: String) -> PamDragTextDriver? {
        let parts = source.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 5, parts[0].range(of: "^[A-Za-z0-9_.:-]{1,64}$", options: .regularExpression) != nil,
              let code = Int(parts[1]), let format = PamDragTextFormat(rawValue: code),
              let decimals = Int(parts[2]), (0...3).contains(decimals) else { return nil }
        let rawInput = parts[3].split(separator: ",").map(String.init)
        let rawOutput = parts[4].split(separator: ",").map(String.init)
        let input = rawInput.compactMap(PamMotionValue.parse)
        let output = rawOutput.compactMap(Double.init).filter(\.isFinite)
        guard (2...8).contains(input.count), input.count == rawInput.count,
              output.count == rawOutput.count, input.count == output.count else { return nil }
        return PamDragTextDriver(ref: parts[0], format: format, decimals: decimals, input: input, output: output)
    }

    private static func clock(_ value: Double) -> String {
        let seconds = Int(floor(min(3_600_000, max(0, value))))
        if seconds >= 3600 { return "\(seconds / 3600):\(String(format: "%02d:%02d", seconds / 60 % 60, seconds % 60))" }
        return "\(seconds / 60):\(String(format: "%02d", seconds % 60))"
    }
}
