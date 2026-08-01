import Foundation
import UIKit

enum PamWorkletTarget: Int {
    case opacity = 1
    case translationX = 2
    case translationY = 3
    case scale = 4
    case rotationDegrees = 5
}

struct PamWorkletProgram {
    private let instructions: [Instruction]

    func evaluate(_ input: Double) -> Double? {
        guard input.isFinite else { return nil }
        var value = 0.0
        for instruction in instructions {
            let operands = instruction.operands
            switch instruction.opcode {
            case 1: value = input
            case 2: value = operands[0]
            case 3: value += operands[0]
            case 4: value -= operands[0]
            case 5: value *= operands[0]
            case 6: value /= operands[0]
            case 7: value = min(max(value, operands[0]), operands[1])
            case 8:
                value = operands[2]
                    + ((value - operands[0]) / (operands[1] - operands[0]))
                    * (operands[3] - operands[2])
            default: return nil
            }
            guard value.isFinite else { return nil }
        }
        return value
    }

    static func decode(_ data: Data) -> PamWorkletProgram? {
        let bytes = [UInt8](data)
        guard bytes.count >= 8,
              bytes.count <= 6 + 256 * 34,
              bytes[0..<4].elementsEqual([0x50, 0x4e, 0x57, 0x31]) else { return nil }
        let count = Int(bytes[4]) | Int(bytes[5]) << 8
        guard count >= 1, count <= 256 else { return nil }
        let operandCounts = [0, 0, 1, 1, 1, 1, 1, 2, 4]
        var offset = 6
        var instructions: [Instruction] = []
        instructions.reserveCapacity(count)
        for _ in 0..<count {
            guard offset + 2 <= bytes.count else { return nil }
            let opcode = Int(bytes[offset])
            let operandCount = Int(bytes[offset + 1])
            offset += 2
            guard opcode >= 1, opcode <= 8,
                  operandCount == operandCounts[opcode],
                  offset + operandCount * 8 <= bytes.count else { return nil }
            var operands: [Double] = []
            operands.reserveCapacity(operandCount)
            for _ in 0..<operandCount {
                var bits: UInt64 = 0
                for byteIndex in 0..<8 {
                    bits |= UInt64(bytes[offset + byteIndex]) << UInt64(byteIndex * 8)
                }
                offset += 8
                let value = Double(bitPattern: bits)
                guard value.isFinite else { return nil }
                operands.append(value)
            }
            if opcode == 6 && operands[0] == 0 { return nil }
            if opcode == 7 && operands[0] > operands[1] { return nil }
            if opcode == 8 && operands[0] == operands[1] { return nil }
            instructions.append(Instruction(opcode: opcode, operands: operands))
        }
        guard offset == bytes.count else { return nil }
        return PamWorkletProgram(instructions: instructions)
    }

    private struct Instruction {
        let opcode: Int
        let operands: [Double]
    }
}

final class PamWorkletAnimator: NSObject {
    private weak var view: UIView?
    private let program: PamWorkletProgram
    private let target: PamWorkletTarget
    private let durationMs: Double
    private let iterations: Int
    private let baseTransform: CGAffineTransform
    private let completion: () -> Void
    private var displayLink: CADisplayLink?
    private var startedAt: CFTimeInterval = 0

    init(
        view: UIView,
        program: PamWorkletProgram,
        target: PamWorkletTarget,
        durationMs: Int,
        iterations: Int,
        completion: @escaping () -> Void
    ) {
        self.view = view
        self.program = program
        self.target = target
        self.durationMs = Double(durationMs)
        self.iterations = iterations
        self.baseTransform = view.transform
        self.completion = completion
    }

    func start() {
        stop()
        if UIAccessibility.isReduceMotionEnabled {
            apply(inputMs: durationMs)
            if iterations != 0 { completion() }
            return
        }
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    func stop() {
        displayLink?.invalidate()
        displayLink = nil
        startedAt = 0
    }

    @objc private func tick(_ link: CADisplayLink) {
        guard view != nil else {
            stop()
            return
        }
        if startedAt == 0 { startedAt = link.timestamp }
        let elapsedMs = max(0, (link.timestamp - startedAt) * 1_000)
        if iterations > 0 && elapsedMs >= durationMs * Double(iterations) {
            apply(inputMs: durationMs)
            stop()
            completion()
            return
        }
        apply(inputMs: elapsedMs.truncatingRemainder(dividingBy: durationMs))
    }

    private func apply(inputMs: Double) {
        guard let view, let value = program.evaluate(inputMs) else { return }
        switch target {
        case .opacity:
            view.alpha = CGFloat(min(max(value, 0), 1))
        case .translationX:
            view.transform = baseTransform.translatedBy(
                x: CGFloat(min(max(value, -1_000_000), 1_000_000)), y: 0
            )
        case .translationY:
            view.transform = baseTransform.translatedBy(
                x: 0, y: CGFloat(min(max(value, -1_000_000), 1_000_000))
            )
        case .scale:
            let scale = CGFloat(min(max(value, -100), 100))
            view.transform = baseTransform.scaledBy(x: scale, y: scale)
        case .rotationDegrees:
            let degrees = min(max(value, -36_000), 36_000)
            view.transform = baseTransform.rotated(by: CGFloat(degrees) * .pi / 180)
        }
    }

    deinit {
        displayLink?.invalidate()
    }
}
