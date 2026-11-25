import XCTest
@testable import PamNative

final class PamInteractionPriorityTests: XCTestCase {
    func testEveryDiscreteActionOpensTheResponseWindow() {
        let actions: [EventKind] = [
            .press, .change, .back, .longPress, .submit, .refresh, .toggle,
            .inputKeyPress, .modalRequestClose, .drop, .menuAction,
            .navigationGesturePop, .accessibilityAction, .spanPress,
            .doubleTap, .gestureSettle,
        ]
        for action in actions {
            var policy = PamInteractionPriority()
            XCTAssertFalse(policy.isActive(now: 1), "\(action)")
            policy.record(kind: action.rawValue, now: 1)
            XCTAssertTrue(policy.isActive(now: 1.1), "\(action)")
            XCTAssertFalse(policy.isActive(now: 1.25), "\(action)")
        }
    }

    func testProgressAndLifecycleDoNotOpenOrExtendTheWindow() {
        let background: [EventKind] = [
            .scroll, .gestureUpdate, .mediaProgress, .mediaReady, .imageProgress,
            .mediaCacheProgress, .animationComplete, .layout, .textLayout,
            .routeFocused, .routeCovered, .appState, .moduleResult, .native,
            .focus, .blur, .touchMove, .pressMove, .pageChange, .listWindow,
        ]
        for event in background {
            var policy = PamInteractionPriority()
            policy.record(kind: event.rawValue, now: 1)
            XCTAssertFalse(policy.isActive(now: 1.1), "\(event)")
            policy.record(kind: EventKind.press.rawValue, now: 2)
            policy.record(kind: event.rawValue, now: 2.2)
            XCTAssertFalse(policy.isActive(now: 2.3), "\(event) must not extend priority")
        }
    }

    func testResetAndInvalidClockDoNotCarryPriorityAcrossMounts() {
        var policy = PamInteractionPriority()
        policy.record(kind: EventKind.back.rawValue, now: 10)
        XCTAssertFalse(policy.isActive(now: 9))
        XCTAssertTrue(policy.isActive(now: 10.1))
        policy.reset()
        XCTAssertFalse(policy.isActive(now: 10.1))
        policy.record(kind: -1, now: 10.1)
        XCTAssertFalse(policy.isActive(now: 10.1))
    }
}
