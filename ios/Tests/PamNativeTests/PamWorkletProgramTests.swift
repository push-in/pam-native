import XCTest
import UIKit
@testable import PamNative

final class PamWorkletProgramTests: XCTestCase {
    @MainActor
    func testDisplayLinkDoesNotRetainAnAbandonedWorklet() throws {
        PamMotionPolicy.reduceMotionOverride = false
        defer { PamMotionPolicy.reduceMotionOverride = nil }
        let program = try XCTUnwrap(PamWorkletProgram.decode(Data([0x50, 0x4e, 0x57, 0x31, 1, 0, 1, 0])))
        let view = UIView()
        weak var released: PamWorkletAnimator?
        do {
            let animator = PamWorkletAnimator(view: view, program: program, target: .opacity,
                                              durationMs: 1000, iterations: 0, completion: {})
            released = animator
            animator.start()
        }
        XCTAssertNil(released, "the infinite display link must not keep an unowned worklet ticking")
    }

    func testDecodesAndEvaluatesPHPCompatibleOpacityProgram() throws {
        var bytes = Data([0x50, 0x4e, 0x57, 0x31, 0x03, 0x00])
        bytes.append(contentsOf: [0x01, 0x00])
        bytes.append(contentsOf: [0x08, 0x04])
        append(0, to: &bytes)
        append(200, to: &bytes)
        append(1, to: &bytes)
        append(0, to: &bytes)
        bytes.append(contentsOf: [0x07, 0x02])
        append(0, to: &bytes)
        append(1, to: &bytes)

        let program = try XCTUnwrap(PamWorkletProgram.decode(bytes))
        XCTAssertEqual(try XCTUnwrap(program.evaluate(50)), 0.75, accuracy: 0.000_001)
    }

    func testRejectsMalformedAndUnsafePrograms() {
        XCTAssertNil(PamWorkletProgram.decode(Data([0x50, 0x4e, 0x57, 0x31, 0x01, 0x00])))

        var divideByZero = Data([0x50, 0x4e, 0x57, 0x31, 0x02, 0x00, 0x01, 0x00, 0x06, 0x01])
        append(0, to: &divideByZero)
        XCTAssertNil(PamWorkletProgram.decode(divideByZero))
    }

    func testTargetsRemainSequentialAndStable() {
        XCTAssertEqual(
            [
                PamWorkletTarget.opacity,
                .translationX,
                .translationY,
                .scale,
                .rotationDegrees,
            ].map(\.rawValue),
            Array(1...5)
        )
    }

    private func append(_ value: Double, to data: inout Data) {
        var bits = value.bitPattern.littleEndian
        Swift.withUnsafeBytes(of: &bits) { data.append(contentsOf: $0) }
    }
}
