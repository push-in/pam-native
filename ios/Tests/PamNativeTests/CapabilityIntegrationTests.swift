import XCTest
@testable import PamNative

private final class PluginFixtureModule: NativeModule {
    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        completion(.success, Data("\(method):".utf8) + payload)
    }
}

@MainActor
private final class PluginFixtureViewFactory: NativeViewFactory {
    func create(context: AnyObject?, emit: @escaping (Data) -> Void) -> UIView {
        UIView()
    }

    func update(view: UIView, properties: [String: WireValue]) {}
    func close() {}
}

@MainActor
private final class VisibilityFixtureView: UIView, NativeChildVisibilityHost {
    var onChildVisibilityChanged: ((UIView, Bool) -> Void)?
}

@MainActor
private final class VisibilityFixtureFactory: NativeViewFactory {
    let view = VisibilityFixtureView()
    func create(context: AnyObject?, emit: @escaping (Data) -> Void) -> UIView { view }
    func update(view: UIView, properties: [String: WireValue]) {}
}

@MainActor
final class CapabilityIntegrationTests: XCTestCase {
    func testAutofocusWaitsForMountAndDoesNotStealFocusOnRelayout() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let controller = UIViewController()
        window.rootViewController = controller
        let field = PamInputField(frame: CGRect(x: 16, y: 60, width: 300, height: 56))
        field.inputView = UIView()
        field.autoFocusRequested = true
        XCTAssertFalse(field.isFirstResponder)
        controller.view.addSubview(field)
        window.makeKeyAndVisible()
        defer { field.resignFirstResponder(); window.isHidden = true }
        let mounted = expectation(description: "autofocus after mounting")
        DispatchQueue.main.async { mounted.fulfill() }
        wait(for: [mounted], timeout: 1)
        XCTAssertTrue(field.isFirstResponder)
        field.resignFirstResponder()
        field.setNeedsLayout()
        field.layoutIfNeeded()
        let relayout = expectation(description: "autofocus remains consumed")
        DispatchQueue.main.async { relayout.fulfill() }
        wait(for: [relayout], timeout: 1)
        XCTAssertFalse(field.isFirstResponder)
    }

    func testControlledSelectionClampsAndSurvivesValueAndSecureUpdates() throws {
        let host = UIView()
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .input, properties: [
                PamConstants.testId: .text("selection-fixture"),
                PamConstants.value: .text("abcdef"),
                PamConstants.inputSelectionStart: .integer(2),
                PamConstants.inputSelectionEnd: .integer(5),
            ])),
            .setRoot(1),
        ]])
        let field = try XCTUnwrap(host.descendant(accessibilityIdentifier: "selection-fixture") as? UITextField)
        func assertSelection(_ start: Int, _ end: Int) throws {
            let selection = try XCTUnwrap(field.selectedTextRange)
            XCTAssertEqual(field.offset(from: field.beginningOfDocument, to: selection.start), start)
            XCTAssertEqual(field.offset(from: field.beginningOfDocument, to: selection.end), end)
        }
        try assertSelection(2, 5)
        renderer.commit([[.update(id: 2, key: PamConstants.secure, value: .flag(true))]])
        try assertSelection(2, 5)
        renderer.commit([[.update(id: 2, key: PamConstants.value, value: .text("abc"))]])
        try assertSelection(2, 3)
        renderer.commit([[.update(id: 2, key: PamConstants.inputSelectionEnd, value: nil)]])
        try assertSelection(2, 2)
        renderer.commit([[.update(id: 2, key: PamConstants.inputSelectionStart, value: .integer(-4))]])
        try assertSelection(0, 0)
        renderer.commit([[.update(id: 2, key: PamConstants.inputSelectionStart, value: nil)]])
        let cursor = try XCTUnwrap(field.position(from: field.beginningOfDocument, offset: 1))
        field.selectedTextRange = field.textRange(from: cursor, to: cursor)
        renderer.commit([[.update(id: 2, key: PamConstants.secure, value: .flag(false))]])
        try assertSelection(1, 1)
    }

    func testSubmitBehaviorControlsActualFirstResponderLifecycle() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let controller = UIViewController()
        window.rootViewController = controller
        let field = PamInputField(frame: CGRect(x: 16, y: 60, width: 300, height: 56))
        controller.view.addSubview(field)
        window.makeKeyAndVisible()
        defer {
            field.resignFirstResponder()
            window.isHidden = true
        }
        var submits = 0
        var ends = 0
        field.onInputEndEditing = { _ in ends += 1 }
        field.addAction(UIAction { _ in submits += 1 }, for: .primaryActionTriggered)
        XCTAssertTrue(field.becomeFirstResponder())
        field.submitBehavior = .submit
        XCTAssertFalse(field.textFieldShouldReturn(field))
        XCTAssertTrue(field.isFirstResponder)
        XCTAssertEqual(submits, 1)
        XCTAssertEqual(ends, 0)
        field.submitBehavior = .blurAndSubmit
        XCTAssertFalse(field.textFieldShouldReturn(field))
        XCTAssertFalse(field.isFirstResponder)
        XCTAssertEqual(submits, 2)
        XCTAssertEqual(ends, 1)
    }

    func testSubmitOnlyDoesNotPretendEditingEnded() {
        let field = PamInputField()
        var submits = 0
        var ends = 0
        field.onInputEndEditing = { _ in ends += 1 }
        field.addAction(UIAction { _ in submits += 1 }, for: .primaryActionTriggered)
        field.submitBehavior = .submit
        XCTAssertFalse(field.textFieldShouldReturn(field))
        XCTAssertEqual(submits, 1)
        XCTAssertEqual(ends, 0)
        field.isInputEditable = false
        XCTAssertFalse(field.textFieldShouldReturn(field))
        XCTAssertEqual(submits, 1)
        field.isInputEditable = true
        field.submitBehavior = .newline
        XCTAssertFalse(field.textFieldShouldReturn(field))
        XCTAssertEqual(submits, 1, "Newline must not become a submit event")
    }

    func testSubmitEventCarriesCurrentEditorValue() throws {
        let host = UIView()
        var payloads: [Data] = []
        let renderer = PamRenderer(hostView: host) { node, kind, payload in
            if kind == EventKind.submit.rawValue {
                XCTAssertEqual(node, 2)
                payloads.append(payload)
            }
        }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .input, properties: [
                PamConstants.testId: .text("submit-fixture"),
                PamConstants.value: .text("Original"),
                PamConstants.onSubmit: .flag(true),
            ])),
            .setRoot(1),
        ]])
        let field = try XCTUnwrap(host.descendant(accessibilityIdentifier: "submit-fixture") as? UITextField)
        func invokeRegisteredSubmitTargets() {
            // SwiftPM runs without UIApplicationMain. Exercise the registered
            // target selectors directly instead of asking UIApplication to send.
            for target in field.allTargets {
                guard let object = target.base as? NSObject else { continue }
                for action in field.actions(forTarget: object, forControlEvent: .primaryActionTriggered) ?? [] {
                    _ = object.perform(NSSelectorFromString(action))
                }
            }
        }
        field.text = "Edited value"
        invokeRegisteredSubmitTargets()
        XCTAssertEqual(payloads.count, 1)
        XCTAssertEqual(try WireMap.decode(XCTUnwrap(payloads.first))["value"], .text("Edited value"))
        renderer.commit([[.update(id: 2, key: PamConstants.onSubmit, value: nil)]])
        invokeRegisteredSubmitTargets()
        XCTAssertEqual(payloads.count, 1, "Removing submit must detach its target")
    }

    func testReadonlyInputRejectsMutationWithoutDisablingSelection() throws {
        let host = UIView()
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .input, properties: [
                PamConstants.testId: .text("readonly-fixture"),
                PamConstants.value: .text("Reference"),
                PamConstants.inputEditable: .flag(false),
            ])),
            .setRoot(1),
        ]])
        let field = try XCTUnwrap(host.descendant(accessibilityIdentifier: "readonly-fixture") as? PamInputField)
        XCTAssertTrue(field.isEnabled)
        XCTAssertTrue(field.isUserInteractionEnabled)
        XCTAssertNotNil(field.inputView)
        XCTAssertFalse(field.textField(field, shouldChangeCharactersIn: NSRange(location: 0, length: 9), replacementString: "Changed"))
        field.insertText("X")
        field.deleteBackward()
        field.setMarkedText("Changed", selectedRange: NSRange(location: 0, length: 0))
        XCTAssertEqual(field.text, "Reference")
        renderer.commit([[.update(id: 2, key: PamConstants.inputEditable, value: nil)]])
        XCTAssertNil(field.inputView)
        XCTAssertTrue(field.textField(field, shouldChangeCharactersIn: NSRange(location: 0, length: 9), replacementString: "Changed"))
    }

    func testInputLengthLimitTruncatesPasteWithoutSplittingUnicode() throws {
        let field = PamInputField()
        field.maximumLength = 4
        field.setTextFromRenderer("12")
        XCTAssertFalse(field.textField(field, shouldChangeCharactersIn: NSRange(location: 2, length: 0), replacementString: "3456"))
        XCTAssertEqual(field.text, "1234")
        XCTAssertFalse(field.textField(field, shouldChangeCharactersIn: NSRange(location: 4, length: 0), replacementString: "5"))
        XCTAssertTrue(field.textField(field, shouldChangeCharactersIn: NSRange(location: 3, length: 1), replacementString: ""))
        field.maximumLength = 3
        field.setTextFromRenderer("ab")
        XCTAssertFalse(field.textField(field, shouldChangeCharactersIn: NSRange(location: 2, length: 0), replacementString: "😀"))
        XCTAssertEqual(field.text, "ab")
        field.maximumLength = 2
        field.setTextFromRenderer("漢字入力")
        field.unmarkText()
        XCTAssertEqual(field.text, "漢字")
        field.maximumLength = 1
        field.setTextFromRenderer("😀a")
        field.unmarkText()
        XCTAssertEqual(field.text, "")
        field.maximumLength = nil
        field.setTextFromRenderer("ab")
        XCTAssertTrue(field.textField(field, shouldChangeCharactersIn: NSRange(location: 2, length: 0), replacementString: "long text"))
    }

    func testInputTextTraitsApplyAndReset() throws {
        let host = UIView()
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .input, properties: [
                PamConstants.testId: .text("traits-fixture"),
                PamConstants.autoComplete: .text("one-time-code"),
                PamConstants.returnKeyType: .integer(2),
                PamConstants.inputAutoCorrect: .flag(false),
                PamConstants.inputAutoCapitalize: .integer(1),
            ])),
            .setRoot(1),
        ]])
        let field = try XCTUnwrap(host.descendant(accessibilityIdentifier: "traits-fixture") as? UITextField)
        XCTAssertEqual(field.autocorrectionType, .no)
        XCTAssertEqual(field.textContentType, .oneTimeCode)
        XCTAssertEqual(field.returnKeyType, .done)
        XCTAssertEqual(field.autocapitalizationType, .none)
        renderer.commit([[
            .update(id: 2, key: PamConstants.inputAutoCorrect, value: .flag(true)),
            .update(id: 2, key: PamConstants.autoComplete, value: .text("new-password")),
            .update(id: 2, key: PamConstants.returnKeyType, value: .integer(5)),
            .update(id: 2, key: PamConstants.inputAutoCapitalize, value: .integer(3)),
        ]])
        XCTAssertEqual(field.autocorrectionType, .yes)
        XCTAssertEqual(field.textContentType, .newPassword)
        XCTAssertEqual(field.returnKeyType, .search)
        XCTAssertEqual(field.autocapitalizationType, .words)
        renderer.commit([[
            .update(id: 2, key: PamConstants.inputAutoCorrect, value: nil),
            .update(id: 2, key: PamConstants.autoComplete, value: nil),
            .update(id: 2, key: PamConstants.returnKeyType, value: nil),
            .update(id: 2, key: PamConstants.inputAutoCapitalize, value: nil),
        ]])
        XCTAssertEqual(field.autocorrectionType, .default)
        XCTAssertNil(field.textContentType)
        XCTAssertEqual(field.returnKeyType, .default)
        XCTAssertEqual(field.autocapitalizationType, .sentences)
    }

    func testSecureInputTogglePreservesTextAndSelection() throws {
        let host = UIView()
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .input, properties: [
                PamConstants.testId: .text("secure-fixture"),
                PamConstants.value: .text("sample-secret"),
                PamConstants.secure: .flag(true),
            ])),
            .setRoot(1),
        ]])
        let field = try XCTUnwrap(host.descendant(accessibilityIdentifier: "secure-fixture") as? UITextField)
        XCTAssertTrue(field.isSecureTextEntry)
        let start = try XCTUnwrap(field.position(from: field.beginningOfDocument, offset: 2))
        let end = try XCTUnwrap(field.position(from: field.beginningOfDocument, offset: 5))
        field.selectedTextRange = field.textRange(from: start, to: end)
        for secure in [false, true, false] {
            renderer.commit([[.update(id: 2, key: PamConstants.secure, value: .flag(secure))]])
            XCTAssertEqual(field.isSecureTextEntry, secure)
            XCTAssertEqual(field.text, "sample-secret")
            let selection = try XCTUnwrap(field.selectedTextRange)
            XCTAssertEqual(field.offset(from: field.beginningOfDocument, to: selection.start), 2)
            XCTAssertEqual(field.offset(from: field.beginningOfDocument, to: selection.end), 5)
        }
        renderer.commit([[.update(id: 2, key: PamConstants.secure, value: .flag(true))]])
        renderer.commit([[.update(id: 2, key: PamConstants.secure, value: nil)]])
        XCTAssertFalse(field.isSecureTextEntry)
    }

    func testInputKeyboardMappingPrecedenceAndRemoval() throws {
        let host = UIView()
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        defer { renderer.close() }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .input, properties: [
                PamConstants.testId: .text("keyboard-fixture"),
                PamConstants.keyboardType: .integer(5),
            ])),
            .setRoot(1),
        ]])
        let field = try XCTUnwrap(host.descendant(accessibilityIdentifier: "keyboard-fixture") as? UITextField)
        XCTAssertEqual(field.keyboardType, .numbersAndPunctuation)
        renderer.commit([[.update(id: 2, key: PamConstants.inputMode, value: .integer(4))]])
        XCTAssertEqual(field.keyboardType, .numberPad)
        renderer.commit([[.update(id: 2, key: PamConstants.inputMode, value: .integer(2))]])
        XCTAssertNotNil(field.inputView)
        renderer.commit([[.update(id: 2, key: PamConstants.inputMode, value: nil)]])
        XCTAssertNil(field.inputView)
        XCTAssertEqual(field.keyboardType, .numbersAndPunctuation)
        renderer.commit([[.update(id: 2, key: PamConstants.keyboardType, value: nil)]])
        XCTAssertEqual(field.keyboardType, .default)
    }

    func testNativeVisibilityRejectsForeignAndRemovedChildren() throws {
        let host = UIView()
        let factory = VisibilityFixtureFactory()
        let renderer = PamRenderer(hostView: host, nativeViews: ["fixture.visibility": factory]) { _, _, _ in }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(id: 2, parent: 1, index: 0, kind: .customView,
                             properties: [PamConstants.hostName: .text("fixture.visibility")])),
            .create(NodeSpec(id: 3, parent: 2, index: 0, kind: .column, properties: [:])),
            .setRoot(1),
        ]])
        let child = try XCTUnwrap(factory.view.subviews.first)
        var requests: [Bool] = []
        renderer.onNativeChildVisibility = { owner, childId, visible in
            XCTAssertEqual(owner, 2)
            XCTAssertEqual(childId, 3)
            requests.append(visible)
        }
        let foreignChild = UIView()
        factory.view.onChildVisibilityChanged?(foreignChild, false)
        factory.view.onChildVisibilityChanged?(child, false)
        factory.view.onChildVisibilityChanged?(child, true)
        let drained = expectation(description: "queued visibility requests")
        DispatchQueue.main.async { drained.fulfill() }
        wait(for: [drained], timeout: 1)
        XCTAssertEqual(requests, [false, true])

        factory.view.onChildVisibilityChanged?(child, false)
        renderer.close()
        XCTAssertNil(factory.view.onChildVisibilityChanged)
        let closedQueue = expectation(description: "stale visibility request discarded")
        DispatchQueue.main.async { closedQueue.fulfill() }
        wait(for: [closedQueue], timeout: 1)
        XCTAssertEqual(requests, [false, true])
    }

    func testVoiceOverExposesSemanticRoleStateValueAndImportance() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let renderer = PamRenderer(hostView: host) { _, _, _ in }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(
                id: 2,
                parent: 1,
                index: 0,
                kind: .text,
                properties: [
                    PamConstants.text: .text("Upload"),
                    PamConstants.accessibilityLabel: .text("Upload progress"),
                    PamConstants.accessibilityRole: .integer(8),
                    PamConstants.accessibilityImportance: .integer(2),
                    PamConstants.accessibilityLiveRegion: .integer(3),
                    PamConstants.accessibilityCheckedState: .integer(3),
                    PamConstants.accessibilityExpanded: .flag(false),
                    PamConstants.accessibilityBusy: .flag(true),
                    PamConstants.accessibilityValueMin: .decimal(0),
                    PamConstants.accessibilityValueMax: .decimal(100),
                    PamConstants.accessibilityValueNow: .decimal(40),
                    PamConstants.accessibilityValueText: .text("40 percent"),
                    PamConstants.selected: .flag(true),
                    PamConstants.enabled: .flag(false),
                    PamConstants.testId: .text("accessible-state"),
                ]
            )),
            .create(NodeSpec(
                id: 3,
                parent: 1,
                index: 1,
                kind: .text,
                properties: [
                    PamConstants.text: .text("Decorative"),
                    PamConstants.accessibilityImportance: .integer(4),
                    PamConstants.testId: .text("hidden-state"),
                ]
            )),
            .create(NodeSpec(
                id: 4,
                parent: 1,
                index: 2,
                kind: .text,
                properties: [
                    PamConstants.text: .text("Upload range"),
                    PamConstants.accessibilityRole: .integer(6),
                    PamConstants.accessibilityValueMin: .decimal(0),
                    PamConstants.accessibilityValueMax: .decimal(100),
                    PamConstants.accessibilityValueNow: .decimal(40),
                    PamConstants.testId: .text("accessible-range"),
                ]
            )),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 390, height: 844)),
            .layout(id: 2, frame: Frame(x: 16, y: 16, width: 200, height: 48)),
            .layout(id: 3, frame: Frame(x: 16, y: 72, width: 200, height: 48)),
            .layout(id: 4, frame: Frame(x: 16, y: 128, width: 200, height: 48)),
            .setRoot(1),
        ]])

        let view = try XCTUnwrap(host.descendant(accessibilityIdentifier: "accessible-state"))
        XCTAssertEqual(view.accessibilityLabel, "Upload progress")
        XCTAssertTrue(view.isAccessibilityElement)
        XCTAssertTrue(view.accessibilityTraits.contains(.button))
        XCTAssertTrue(view.accessibilityTraits.contains(.selected))
        XCTAssertTrue(view.accessibilityTraits.contains(.notEnabled))
        XCTAssertTrue(view.accessibilityTraits.contains(.updatesFrequently))
        XCTAssertEqual(view.accessibilityValue, "40 percent, Mixed, Collapsed, Loading")

        let hidden = try XCTUnwrap(host.descendant(accessibilityIdentifier: "hidden-state"))
        XCTAssertTrue(hidden.accessibilityElementsHidden)
        let range = try XCTUnwrap(host.descendant(accessibilityIdentifier: "accessible-range"))
        XCTAssertTrue(range.accessibilityTraits.contains(.adjustable))
        XCTAssertEqual(range.accessibilityValue, "40 / 100")
        renderer.close()
    }

    func testVoiceOverCustomActionDispatchesItsBoundedIdentifier() throws {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        var events: [(Int64, Int, Data)] = []
        let renderer = PamRenderer(hostView: host) { id, kind, payload in
            events.append((id, kind, payload))
        }
        renderer.commit([[
            .create(NodeSpec(id: 1, parent: 0, index: 0, kind: .screen, properties: [:])),
            .create(NodeSpec(
                id: 2,
                parent: 1,
                index: 0,
                kind: .text,
                properties: [
                    PamConstants.text: .text("Message"),
                    PamConstants.accessibilityActions: .text(
                        #"[{"name":"archive","label":"Archive message"}]"#
                    ),
                    PamConstants.onAccessibilityAction: .flag(true),
                    PamConstants.testId: .text("message-actions"),
                ]
            )),
            .layout(id: 1, frame: Frame(x: 0, y: 0, width: 390, height: 844)),
            .layout(id: 2, frame: Frame(x: 16, y: 16, width: 200, height: 48)),
            .setRoot(1),
        ]])

        let view = try XCTUnwrap(host.descendant(accessibilityIdentifier: "message-actions"))
        let action = try XCTUnwrap(view.accessibilityCustomActions?.first)
        XCTAssertEqual(action.name, "Archive message")
        XCTAssertTrue(action.actionHandler?(action) == true)
        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(events[0].0, 2)
        XCTAssertEqual(events[0].1, EventKind.accessibilityAction.rawValue)
        XCTAssertEqual(String(data: events[0].2, encoding: .utf8), "archive")
        renderer.close()
    }

    func testPluginRegistriesInjectModulesAndViews() {
        let modules = NativeModuleRegistry(additionalModules: [
            "fixture.echo": PluginFixtureModule(),
        ])
        let moduleExpectation = expectation(description: "plugin module result")
        modules.invoke(module: "fixture.echo", method: "ping", payload: Data("pam".utf8)) { status, data in
            XCTAssertEqual(status, .success)
            XCTAssertEqual(String(data: data, encoding: .utf8), "ping:pam")
            moduleExpectation.fulfill()
        }

        let views = NativeViewRegistry(additionalFactories: [
            "fixture.view": PluginFixtureViewFactory(),
        ])
        let view = views.create(name: "fixture.view") { _, _ in }
        XCTAssertTrue(type(of: view) == UIView.self)
        views.release(view: view)
        wait(for: [moduleExpectation], timeout: 1)
    }

    func testDevToolsRendersCapabilityFailureTimeline() {
        let overlay = PamDevToolsOverlay(frame: CGRect(x: 0, y: 0, width: 360, height: 500))
        overlay.update(RuntimeFrameMetrics(
            batches: 1,
            decodeNanos: 1_000_000,
            mountNanos: 2_000_000,
            stats: RuntimeStats(
                commits: 1,
                nodes: 4,
                created: 4,
                removed: 0,
                updated: 0,
                retainedBytes: 1_024,
                fullCommits: 1,
                patchCommits: 0,
                inputBytes: 128,
                outputBytes: 256,
                decodeP95Micros: 900,
                reconcileP95Micros: 700,
                layoutP95Micros: 1_100,
                encodeP95Micros: 100,
                coalescedCommands: 3,
                bufferReuses: 8,
                reusedBufferBytes: 4_096,
                measuredFrames: 120,
                deadlineMisses: 2
            )
        ))
        overlay.record(RuntimeDiagnostic(
            kind: .moduleCall,
            label: "permissions.request",
            durationNanos: 12_000_000,
            failed: true
        ))
        overlay.setVisible(true)

        XCTAssertTrue(overlay.accessibilityValue?.contains("FAIL") == true)
        XCTAssertTrue(overlay.accessibilityValue?.contains("permissions.request") == true)
    }

    func testDevToolsExportsBoundedRedactedCrossHostSnapshot() throws {
        let overlay = PamDevToolsOverlay(frame: .zero)
        for index in 0..<7 {
            overlay.record(RuntimeDiagnostic(
                kind: .error,
                label: "secret-\(index)",
                failed: true
            ))
        }
        overlay.record(RuntimeDiagnostic(
            kind: .network,
            label: "PATCH https://secret.example/private?token=secret",
            durationNanos: 12_345_000,
            methodCode: RuntimeHttpMethod.patch.rawValue,
            statusCode: 202,
            requestBytes: 17,
            responseBytes: 8
        ))

        let data = try overlay.snapshotData(capturedAtUnixMs: 1_234)
        let snapshot = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        XCTAssertEqual(snapshot["schemaVersion"] as? Int, 1)
        XCTAssertEqual(snapshot["surfaceCode"] as? Int, 2)
        XCTAssertEqual(snapshot["platformCode"] as? Int, 2)
        XCTAssertEqual(snapshot["capturedAtUnixMs"] as? Int, 1_234)
        XCTAssertEqual((snapshot["timeline"] as? [[String: Any]])?.count, 8)
        XCTAssertFalse(String(data: data, encoding: .utf8)?.contains("secret-") == true)
        XCTAssertFalse(String(data: data, encoding: .utf8)?.contains("secret.example") == true)
        let network = try XCTUnwrap((snapshot["timeline"] as? [[String: Any]])?.last)
        XCTAssertEqual(network["kindCode"] as? Int, RuntimeDiagnosticKind.network.rawValue)
        XCTAssertEqual(network["methodCode"] as? Int, RuntimeHttpMethod.patch.rawValue)
        XCTAssertEqual(network["statusCode"] as? Int, 202)
        XCTAssertEqual(network["requestBytes"] as? Int, 17)
        XCTAssertEqual(network["responseBytes"] as? Int, 8)
    }

    func testWatchChannelDropsOldValuesAndKeepsLatestBackpressureWindow() {
        let channel = WatchChannel()
        for value in 1...8 {
            channel.offer(Data([UInt8(value)]))
        }
        var received: [UInt8] = []
        for _ in 0..<4 {
            channel.next { status, data in
                XCTAssertEqual(status, .success)
                received.append(data[0])
            }
        }
        XCTAssertEqual(received, [5, 6, 7, 8])
    }
}

@MainActor
private extension UIView {
    func descendant(accessibilityIdentifier identifier: String) -> UIView? {
        if accessibilityIdentifier == identifier { return self }
        for child in subviews {
            if let match = child.descendant(accessibilityIdentifier: identifier) {
                return match
            }
        }
        return nil
    }
}
