import UIKit
import XCTest
@testable import PamNative

/// UIKit regressions; requires an iOS test destination (not executable on Linux).
@MainActor
final class PamNativeGestureTransformTests: XCTestCase {
    func testAdjacentDetectorsShareContentButAnIntermediateViewKeepsTargetsSeparate() throws {
        for separated in [false, true] {
            let host = UIView(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
            let renderer = PamRenderer(hostView: host, dispatchEvent: { _, _, _ in })
            let native: [Int: PropValue] = [PamConstants.gestureNativeTransform: .flag(true)]
            var mutations: [Mutation] = [
                .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
                .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .pressable, properties: native)),
            ]
            if separated {
                mutations.append(.create(NodeSpec(id: 3, parent: 2, index: 0, kind: .view, properties: [:])))
            }
            mutations += [
                .create(NodeSpec(id: 4, parent: separated ? 3 : 2, index: 0, kind: .pressable, properties: native)),
                .create(NodeSpec(id: 5, parent: 4, index: 0, kind: .view, properties: [:])),
                .setRoot(1),
            ]
            renderer.commit([mutations])
            let outerTarget = try XCTUnwrap(host.viewWithTag(separated ? 3 : 5))
            let content = try XCTUnwrap(host.viewWithTag(5))
            XCTAssertTrue(renderer.nativeTransformTarget(2) === outerTarget)
            XCTAssertTrue(renderer.nativeTransformTarget(4) === content)
            renderer.close()
        }
    }

    func testPinchAndPanPreserveEachOthersValuesAndReportAbsoluteTransforms() throws {
        let view = UIView()
        PamMotionTarget.write(view, .scale, 1.6)
        PamMotionTarget.write(view, .translateX, 18)
        PamMotionTarget.write(view, .translateY, -7)
        var pinch = PamNativeGestureTransform()
        var pan = PamNativeGestureTransform()
        pinch.begin(on: view)
        pan.begin(on: view)
        _ = pinch.apply(on: view, type: 3, translation: .zero, scale: 1.25, rotation: 0,
                        minimumScale: 1, maximumScale: 4, translationLimitX: 0)
        let applied = pan.apply(on: view, type: 2, translation: CGPoint(x: 20, y: 12), scale: 1, rotation: 0,
                                minimumScale: 1, maximumScale: 4, translationLimitX: 0)
        XCTAssertEqual(applied.scaleX, 2, accuracy: 0.0001)
        XCTAssertEqual(applied.translateX, 38, accuracy: 0.0001)
        XCTAssertEqual(applied.translateY, 5, accuracy: 0.0001)
        // PHP commits all applied components in a different property order.
        PamMotionTarget.write(view, .translateY, 5)
        PamMotionTarget.write(view, .scaleY, 2)
        PamMotionTarget.write(view, .translateX, 38)
        PamMotionTarget.write(view, .scaleX, 2)
        pinch.begin(on: view)
        let second = pinch.apply(on: view, type: 3, translation: .zero, scale: 1.1, rotation: 0,
                                 minimumScale: 1, maximumScale: 4, translationLimitX: 0)
        XCTAssertEqual(second.scaleX, 2.2, accuracy: 0.0001)
        XCTAssertEqual(second.translateX, 38, accuracy: 0.0001)
        XCTAssertEqual(second.translateY, 5, accuracy: 0.0001)
        let wire = try WireMap.decode(WireMap.encode(second.gesturePayload))
        XCTAssertEqual(wire["nativeScale"], .decimal(2.2))
        XCTAssertEqual(wire["nativeTranslationX"], .decimal(38))
        XCTAssertEqual(wire["nativeTranslationY"], .decimal(5))
    }

    func testFocalPinchKeepsTheContentPointUnderTheFingersAndPanResumesFromIt() throws {
        let view = UIView()
        PamMotionTarget.write(view, .translateX, 20)
        PamMotionTarget.write(view, .translateY, -10)
        PamMotionTarget.write(view, .scale, 1.5)
        var pinch = PamNativeGestureTransform()
        var pan = PamNativeGestureTransform()
        let pivot = CGPoint(x: 160, y: 240)
        let focal = CGPoint(x: 240, y: 280)
        pinch.begin(on: view, focal: focal)
        pan.begin(on: view)
        PamNativeGestureTransform.focalZoomTargets.insert(ObjectIdentifier(view))
        let zoomed = pinch.apply(on: view, type: 3, translation: .zero, scale: 2, rotation: 0,
                                 minimumScale: 1, maximumScale: 5, translationLimitX: 0, focal: focal, pivot: pivot)
        // Content point under the focus before and after: (focal - pivot - t) / s.
        XCTAssertEqual((focal.x - pivot.x - 20) / 1.5, (focal.x - pivot.x - zoomed.translateX) / 3, accuracy: 0.0001)
        XCTAssertEqual((focal.y - pivot.y + 10) / 1.5, (focal.y - pivot.y - zoomed.translateY) / 3, accuracy: 0.0001)
        let held = pan.apply(on: view, type: 2, translation: CGPoint(x: 30, y: 0), scale: 1, rotation: 0,
                             minimumScale: 1, maximumScale: 5, translationLimitX: 0)
        XCTAssertEqual(held.translateX, zoomed.translateX, accuracy: 0.0001)
        PamNativeGestureTransform.focalZoomTargets.remove(ObjectIdentifier(view))
        let resumed = pan.apply(on: view, type: 2, translation: CGPoint(x: 40, y: 0), scale: 1, rotation: 0,
                                minimumScale: 1, maximumScale: 5, translationLimitX: 0)
        XCTAssertEqual(resumed.translateX, zoomed.translateX + 10, accuracy: 0.0001)
    }

    func testTakingOverTransformStopsItsRunnersAndKeepsOpacityRunning() throws {
        let view = UIView()
        let coordinator = PamMotionCoordinator(dispatch: { _, _, _ in }, isMounted: { _ in true },
                                                wants: { _, _ in false }, firstChild: { _ in nil })
        let motion = coordinator.state(5)
        func runner(_ property: PamMotionProperty, target: Double) -> PamMotionRunner {
            let program = PamMotionProgram(id: 1, iterations: 1, phases: [[
                (property, [.timing(to: PamMotionValue(target), durationMs: 800, easing: "linear", delayMs: 0)])
            ]])
            return PamMotionRunner(view: view, timeline: PamMotionTimeline.build(program,
                current: { PamMotionTarget.read(view, $0) }, resolve: { _, value in value.number }), iterations: 1)
        }
        let scale = runner(.scale, target: 2.4)
        let translation = runner(.translateX, target: 80)
        let opacity = runner(.opacity, target: 0.5)
        motion.transitionRunners[.scale] = scale
        motion.transitionRunners[.translateX] = translation
        motion.runner = opacity
        for animation in [scale, translation, opacity] { animation.start(reducedMotion: false) }
        PamMotionTarget.write(view, .scale, 1.6)
        PamMotionTarget.write(view, .translateX, 24)
        coordinator.takeOverTransform(nodeId: 5, view: view)
        XCTAssertFalse(scale.isRunning)
        XCTAssertFalse(translation.isRunning)
        XCTAssertTrue(opacity.isRunning)
        XCTAssertEqual(PamMotionTarget.read(view, .scale), 1.6, accuracy: 0.0001)
        XCTAssertEqual(PamMotionTarget.read(view, .translateX), 24, accuracy: 0.0001)
        coordinator.remove(5)
        XCTAssertFalse(opacity.isRunning)
    }
}
