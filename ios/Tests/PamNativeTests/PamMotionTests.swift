import UIKit
import XCTest
@testable import PamNative

/// Mirrors android/app/src/test/.../PamMotionTest.kt (uncompiled — needs Mac validation).
final class PamMotionTests: XCTestCase {
    func testSpringSettlingMatchesThePhpSolver() {
        // Values computed by Pam\Native\Animation\Spring::durationMs().
        XCTAssertEqual(PamSpring(config: .standard, from: 0, to: 1).durationMs, 1_272)
        XCTAssertEqual(PamSpring(config: PamSpringConfig(stiffness: 300, damping: 7, mass: 0.5)!, from: 0.5, to: 1.1).durationMs, 916)
        XCTAssertEqual(
            PamSpring(config: PamSpringConfig(stiffness: 230, damping: 22, mass: 0.72)!, from: 640, to: 0, initialVelocity: -2_000).durationMs,
            477
        )
        XCTAssertEqual(PamSpring(config: PamSpringConfig(stiffness: 1_000, damping: 200, mass: 1)!, from: 0, to: 1).durationMs, 1_217)
    }

    func testUnderdampedSpringOvershootsAndSettlesOnTarget() {
        let spring = PamSpring(config: PamSpringConfig(stiffness: 300, damping: 7, mass: 0.5)!, from: 0.5, to: 1.1)
        let peak = (0...spring.durationMs).map { spring.position(Double($0) / 1_000) }.max() ?? 0
        XCTAssertGreaterThan(peak, 1.1, "heart burst spring must overshoot")
        XCTAssertEqual(spring.position(Double(spring.durationMs) / 1_000), 1.1, accuracy: 1e-9)
        XCTAssertEqual(spring.position(0), 0.5, accuracy: 1e-9)
    }

    func testCubicBezierMatchesCssKeywords() {
        let ease = PamEasings.parse("ease")
        XCTAssertEqual(ease(0), 0, accuracy: 1e-6)
        XCTAssertEqual(ease(1), 1, accuracy: 1e-6)
        XCTAssertEqual(ease(0.5), 0.8024, accuracy: 2e-3)
        XCTAssertEqual(PamEasings.parse("bezier:0.25:0.25:0.75:0.75")(0.37), 0.37, accuracy: 1e-3)
        XCTAssertEqual(PamEasings.parse("ease-in-quad")(0.5), 0.25, accuracy: 1e-6)
    }

    func testProgramParsesPhasesAndRejectsUnknownProperties() throws {
        let program = try XCTUnwrap(PamMotionProgram.parse("""
        pam-motion 1 id=42 iterations=-1
        0 scale set(0.5) spring(1.1,300,7,0.5,0,0) timing(1,120,ease-in-out-quad,0)
        0 opacity set(0) timing(1,60,linear,0) wait(360) timing(0,260,ease-in-quad,0)
        1 translateX timing(-100%,200,linear,10)
        """))
        XCTAssertEqual(program.id, 42)
        XCTAssertEqual(program.iterations, -1)
        XCTAssertEqual(program.phases.count, 2)
        XCTAssertEqual(program.steps(phase: 0, property: .scale)?.count, 3)
        XCTAssertEqual(
            program.steps(phase: 1, property: .translateX),
            [.timing(to: PamMotionValue(-100, percent: true), durationMs: 200, easing: "linear", delayMs: 10)]
        )
        XCTAssertNil(PamMotionProgram.parse("pam-motion 1 id=1\n0 width timing(1,10,linear,0)"))
        XCTAssertNil(PamMotionProgram.parse("not a program"))
    }

    func testTimelineRunsTracksInParallelAndPhasesSequentially() throws {
        let program = try XCTUnwrap(PamMotionProgram.parse("""
        pam-motion 1 id=1 iterations=1
        0 opacity timing(1,100,linear,0)
        0 translateX timing(50,300,linear,0)
        1 opacity timing(0,100,linear,50)
        """))
        let timeline = PamMotionTimeline.build(program, current: { _ in 0 }, resolve: { _, value in value.number })
        XCTAssertEqual(timeline.durationMs, 450)
        XCTAssertEqual(try XCTUnwrap(timeline.value(.opacity, at: 50)), 0.5, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(timeline.value(.opacity, at: 320)), 1, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(timeline.value(.translateX, at: 150)), 25, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(timeline.value(.opacity, at: 400)), 0.5, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(timeline.finalValues()[.opacity]), 0, accuracy: 1e-9)
    }

    func testHeartBurstTimelineMatchesReelPageDurations() throws {
        let program = try XCTUnwrap(PamMotionProgram.parse("""
        pam-motion 1 id=7 iterations=1
        0 scale set(0.5) spring(1.1,300,7,0.5,0,0) timing(1,120,ease-in-out-quad,0)
        0 opacity set(0) timing(1,60,linear,0) wait(360) timing(0,260,ease-in-quad,0)
        """))
        let timeline = PamMotionTimeline.build(program, current: { _ in 1 }, resolve: { _, value in value.number })
        XCTAssertEqual(timeline.durationMs, 916 + 120)
        XCTAssertEqual(try XCTUnwrap(timeline.value(.opacity, at: 200)), 1, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(timeline.value(.opacity, at: 700)), 0, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(timeline.value(.scale, at: 0)), 0.5, accuracy: 1e-9)
    }

    func testTransitionSpecUsesCssListSemantics() throws {
        let rules = PamTransitionSpec.parse(
            "@property transform,opacity\n@duration 300,120\n@timing spring:260:18:1,ease-out\n@delay 0,40"
        )
        let transform = try XCTUnwrap(PamTransitionSpec.rule(rules, for: .translateX))
        XCTAssertEqual(transform.spring, PamSpringConfig(stiffness: 260, damping: 18, mass: 1))
        let opacity = try XCTUnwrap(PamTransitionSpec.rule(rules, for: .opacity))
        XCTAssertEqual(opacity.durationMs, 120)
        XCTAssertEqual(opacity.delayMs, 40)
        XCTAssertEqual(opacity.easing, "ease-out")
        XCTAssertNil(PamTransitionSpec.rule(rules, for: .radius))
        let cyclic = PamTransitionSpec.parse("@property opacity,scale,rotate\n@duration 100,200")
        XCTAssertEqual(cyclic["rotate"]?.durationMs, 100)
        XCTAssertEqual(PamTransitionSpec.rule(cyclic, for: .scaleX)?.durationMs, 200)
    }

    func testDragConfigParsesStoryDismissContract() throws {
        let config = try XCTUnwrap(PamDragConfig.parse([
            "axis=y", "min=0", "snaps=0,100%", "settle=spring:230:22:0.72",
            "settle.1=timing:190:ease-out", "threshold=120", "velocity=900",
            "drive=|scale|0,50%|1,0.955", "drive=replyIcon|opacity|0,46|0,1", "group=inbox",
        ].joined(separator: "\n")))
        XCTAssertFalse(config.horizontal)
        XCTAssertEqual(config.min, 0)
        XCTAssertNil(config.max)
        XCTAssertEqual(config.snaps, [PamMotionValue(0), PamMotionValue(100, percent: true)])
        XCTAssertEqual(config.settle(for: 1).durationMs, 190)
        XCTAssertNil(config.settle(for: 1).spring)
        XCTAssertEqual(config.settle(for: 0).spring, PamSpringConfig(stiffness: 230, damping: 22, mass: 0.72))
        XCTAssertEqual(config.drivers.count, 2)
        XCTAssertEqual(config.drivers[1].ref, "replyIcon")
        XCTAssertEqual(config.group, "inbox")
        XCTAssertNil(PamDragConfig.parse("axis=z"))
    }

    func testDragReleaseChoosesAdjacentSnapByDistanceOrVelocity() {
        let story: [Double] = [0, 1_600]
        XCTAssertEqual(PamDragMath.release(story, origin: 0, position: 80, velocity: 200, threshold: 120, velocityThreshold: 900), 0)
        XCTAssertEqual(PamDragMath.release(story, origin: 0, position: 130, velocity: 0, threshold: 120, velocityThreshold: 900), 1)
        XCTAssertEqual(PamDragMath.release(story, origin: 0, position: 30, velocity: 1_200, threshold: 120, velocityThreshold: 900), 1)
        XCTAssertEqual(PamDragMath.release(story, origin: 0, position: 30, velocity: -1_200, threshold: 120, velocityThreshold: 900), 0)
        let swipeable: [Double] = [-160, 0, 80]
        XCTAssertEqual(PamDragMath.release(swipeable, origin: 1, position: -70, velocity: 0, threshold: 32, velocityThreshold: 800), 0)
        XCTAssertEqual(PamDragMath.release(swipeable, origin: 1, position: 40, velocity: 0, threshold: 32, velocityThreshold: 800), 2)
        XCTAssertEqual(PamDragMath.release(swipeable, origin: 0, position: -20, velocity: 1_500, threshold: 64, velocityThreshold: 800), 1)
        XCTAssertEqual(PamDragMath.release([0], origin: 0, position: 60, velocity: 0, threshold: 46, velocityThreshold: 0), 0)
    }

    func testDragBoundsAndDriversClamp() {
        XCTAssertEqual(PamDragMath.bound(-40, min: 0, max: nil, rubber: 0), 0)
        XCTAssertEqual(PamDragMath.bound(-40, min: 0, max: nil, rubber: 0.15), -6, accuracy: 1e-9)
        XCTAssertEqual(PamDragMath.bound(90, min: nil, max: 72, rubber: 0), 72)
        XCTAssertEqual(PamDragMath.interpolate([0, 46], [0, 1], 23), 0.5, accuracy: 1e-9)
        XCTAssertEqual(PamDragMath.interpolate([0, 46], [0, 1], 72), 1, accuracy: 1e-9)
        XCTAssertEqual(PamDragMath.interpolate([-46, 0, 46], [1, 0, 1], -60), 1, accuracy: 1e-9)
        XCTAssertEqual(PamDragMath.interpolate([46, 0], [1, 0], 23), 0.5, accuracy: 1e-9)
        XCTAssertEqual(PamDragMath.nearest([-160, 0, 80], 3), 1)
    }

    func testTapEffectParsesAnchorAndProgram() throws {
        let effect = try XCTUnwrap(PamTapEffect.parse(
            "ref=heart;tilt=30\npam-motion 1 id=9 iterations=1\n0 scale set(0.5) spring(1.1,300,7,0.5,0,0)"
        ))
        XCTAssertEqual(effect.ref, "heart")
        XCTAssertEqual(effect.tiltDegrees, 30)
        XCTAssertEqual(effect.program.id, 9)
        XCTAssertNil(PamTapEffect.parse("ref=heart"))
    }

    // iOS-specific coverage.

    func testMotionTargetComposesTransformComponents() {
        let view = UIView(frame: CGRect(x: 0, y: 0, width: 200, height: 100))
        PamMotionTarget.write(view, .translateX, 12)
        PamMotionTarget.write(view, .scale, 1.5)
        PamMotionTarget.write(view, .rotate, 90)
        XCTAssertEqual(PamMotionTarget.read(view, .translateX), 12, accuracy: 1e-6)
        XCTAssertEqual(PamMotionTarget.read(view, .scaleY), 1.5, accuracy: 1e-6)
        XCTAssertEqual(PamMotionTarget.read(view, .rotate), 90, accuracy: 1e-6)
        XCTAssertEqual(PamMotionTarget.resolve(view, .translateX, PamMotionValue(50, percent: true)), 100)
        // A renderer-written transform invalidates the remembered components.
        view.transform = CGAffineTransform(translationX: 4, y: 8)
        XCTAssertEqual(PamMotionTarget.read(view, .translateY), 8, accuracy: 1e-6)
        XCTAssertEqual(PamMotionTarget.read(view, .rotate), 0, accuracy: 1e-6)
    }

    func testNativeRefLookupSearchesSubtree() {
        let root = UIView()
        let middle = UIView()
        let heart = UIView()
        heart.pamNativeRef = "heart"
        root.addSubview(middle)
        middle.addSubview(heart)
        XCTAssertTrue(root.pamFindNativeRef("heart") === heart)
        XCTAssertNil(root.pamFindNativeRef("missing"))
    }

    func testReducedMotionRunnerAppliesFinalValuesSynchronously() throws {
        let view = UIView(frame: CGRect(x: 0, y: 0, width: 10, height: 10))
        let program = try XCTUnwrap(PamMotionProgram.parse("pam-motion 1 id=3 iterations=1\n0 opacity set(0) timing(0.4,300,linear,0)"))
        let timeline = PamMotionTimeline.build(program, current: { PamMotionTarget.read(view, $0) }, resolve: { $1.number })
        var completed = false
        PamMotionRunner(view: view, timeline: timeline, iterations: 1, onComplete: { completed = true }).start(reducedMotion: true)
        XCTAssertTrue(completed)
        XCTAssertEqual(view.alpha, 0.4, accuracy: 1e-6)
    }

    func testKeyframeEasingMapsToCoreAnimationCurves() {
        let linear = PamEasings.timingFunction("linear")
        var points: [Float] = [0, 0]
        linear.getControlPoint(at: 1, values: &points)
        XCTAssertEqual(points[0], 0, accuracy: 1e-6)
        let custom = PamEasings.timingFunction("bezier:0.1:0.2:0.3:0.4")
        custom.getControlPoint(at: 2, values: &points)
        XCTAssertEqual(points[0], 0.3, accuracy: 1e-6)
        XCTAssertEqual(points[1], 0.4, accuracy: 1e-6)
    }

    func testListPagingMovesOneItemFromGestureOrigin() {
        let starts: [CGFloat] = [0, 600, 1_200, 1_800]
        XCTAssertEqual(PamVirtualListView.itemPageTarget(starts: starts, start: 600, position: 650, velocity: 900, maximum: 1_800), 1_200)
        XCTAssertEqual(PamVirtualListView.itemPageTarget(starts: starts, start: 600, position: 640, velocity: 0, maximum: 1_800), 600)
        XCTAssertEqual(PamVirtualListView.itemPageTarget(starts: starts, start: 600, position: 480, velocity: 0, maximum: 1_800), 0)
        XCTAssertEqual(PamVirtualListView.itemPageTarget(starts: starts, start: 1_800, position: 1_900, velocity: 2_000, maximum: 1_800), 1_800)
    }

    func testTextLayoutCountsWrappedAndVisibleLines() {
        let text = NSAttributedString(
            string: String(repeating: "palavra ", count: 40),
            attributes: [.font: UIFont.systemFont(ofSize: 14)]
        )
        let all = PamTextLayoutMeasurement.measure(text: text, width: 120, numberOfLines: 0)
        XCTAssertGreaterThan(all.lines, 3)
        XCTAssertFalse(all.truncated)
        let clamped = PamTextLayoutMeasurement.measure(text: text, width: 120, numberOfLines: 2)
        XCTAssertEqual(clamped.visibleLines, 2)
        XCTAssertTrue(clamped.truncated)
        XCTAssertEqual(clamped.lines, all.lines)
        XCTAssertTrue(clamped.lineWidthsJSON.hasPrefix("["))
    }
}
