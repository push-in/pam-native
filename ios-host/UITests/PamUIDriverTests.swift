import XCTest

/// Scriptable UI driver for the installed-app Simulator gate.
///
/// `xcodebuild test -scheme PamNativeUITests` runs the JSON step list in
/// `TEST_RUNNER_PAM_UI_SCRIPT` against the already installed application and
/// writes every requested screenshot to `TEST_RUNNER_PAM_UI_SHOT_DIR`. It never
/// relaunches the application unless a `launch` step asks for it, so the
/// captured state is the one a user would see. Steps:
///
///     {"launch": true}                 cold start (default: activate)
///     {"tap": "Local marketplace"}     first element whose label matches
///     {"tapIfExists": "Back"}         tap when present, otherwise continue
///     {"tapId": "composer.send"}       accessibility identifier
///     {"tapXY": [0.5, 0.9]}            normalized window coordinates
///     {"longPress": "label", "seconds": 1.0}
///     {"type": "hello"}                keyboard input into the focused field
///     {"swipe": "up"}                  up/down/left/right on the window
///     {"fling": "up"}                  fast up/down flick; returns while the
///                                      list still decelerates (scroll QA)
///     {"edgeSwipe": true}              interactive pop from the left edge
///     {"home": true}                   press the home button
///     {"alert": "Open"}                tap a SpringBoard alert button
///     {"openurl": "scheme://path"}     open a URL in the application
///     {"wait": 1.5}                    seconds
///     {"waitFor": "label", "timeout": 10}
///     {"shot": "marketplace"}          PNG in PAM_UI_SHOT_DIR + attachment
///     {"dump": "tree"}                accessibility tree (.txt) + attachment
final class PamUIDriverTests: XCTestCase {
    private var app: XCUIApplication!
    private var shotDirectory: URL?

    override func setUpWithError() throws {
        continueAfterFailure = false
        let environment = ProcessInfo.processInfo.environment
        app = XCUIApplication()
        if let directory = environment["PAM_UI_SHOT_DIR"], !directory.isEmpty {
            let url = URL(fileURLWithPath: directory, isDirectory: true)
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
            shotDirectory = url
        }
    }

    func testRunScript() throws {
        let environment = ProcessInfo.processInfo.environment
        guard let raw = environment["PAM_UI_SCRIPT"], !raw.isEmpty else {
            throw XCTSkip("PAM_UI_SCRIPT is not set; nothing to drive")
        }
        let script = try loadScript(raw)
        if !(script.first?["launch"] as? Bool ?? false) {
            app.activate()
        }
        for (index, step) in script.enumerated() {
            try run(step: step, index: index)
        }
    }

    private func loadScript(_ raw: String) throws -> [[String: Any]] {
        var data = Data(raw.utf8)
        if raw.hasPrefix("/"), let file = FileManager.default.contents(atPath: raw) {
            data = file
        }
        let object = try JSONSerialization.jsonObject(with: data)
        guard let steps = object as? [[String: Any]] else {
            throw NSError(domain: "PamUIDriver", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "PAM_UI_SCRIPT must be a JSON array of step objects",
            ])
        }
        return steps
    }

    private func run(step: [String: Any], index: Int) throws {
        let timeout = step["timeout"] as? Double ?? 10
        if step["launch"] as? Bool == true {
            app.launch()
            return
        }
        if let label = step["tap"] as? String {
            let element = try element(labelled: label, timeout: timeout, step: index)
            element.tap()
            return
        }
        if let label = step["tapIfExists"] as? String {
            let predicate = NSPredicate(format: "label == %@ OR label BEGINSWITH %@", label, label)
            let element = app.descendants(matching: .any).matching(predicate).firstMatch
            if element.waitForExistence(timeout: step["timeout"] as? Double ?? 2) { element.tap() }
            return
        }
        if let identifier = step["tapId"] as? String {
            let element = app.descendants(matching: .any).matching(identifier: identifier).firstMatch
            XCTAssertTrue(element.waitForExistence(timeout: timeout), "step \(index): no element with identifier \(identifier)")
            element.tap()
            return
        }
        if let point = step["tapXY"] as? [Double], point.count == 2 {
            app.windows.firstMatch
                .coordinate(withNormalizedOffset: CGVector(dx: point[0], dy: point[1]))
                .tap()
            return
        }
        if let point = step["doubleTapXY"] as? [Double], point.count == 2 {
            app.windows.firstMatch
                .coordinate(withNormalizedOffset: CGVector(dx: point[0], dy: point[1]))
                .doubleTap()
            return
        }
        if let point = step["longPressXY"] as? [Double], point.count == 2 {
            app.windows.firstMatch
                .coordinate(withNormalizedOffset: CGVector(dx: point[0], dy: point[1]))
                .press(forDuration: step["seconds"] as? Double ?? 1)
            return
        }
        if let path = step["drag"] as? [Double], path.count == 4 {
            // Normalized [fromX, fromY, toX, toY]; "velocity" in points/s
            // (default 600, a deliberate finger drag) and "hold" seconds at the end.
            let window = app.windows.firstMatch
            let start = window.coordinate(withNormalizedOffset: CGVector(dx: path[0], dy: path[1]))
            let end = window.coordinate(withNormalizedOffset: CGVector(dx: path[2], dy: path[3]))
            start.press(
                forDuration: step["press"] as? Double ?? 0.05,
                thenDragTo: end,
                withVelocity: XCUIGestureVelocity(CGFloat(step["velocity"] as? Double ?? 600)),
                thenHoldForDuration: step["hold"] as? Double ?? 0
            )
            return
        }
        if let scale = step["pinch"] as? Double {
            // > 1 zooms in, < 1 zooms out; velocity is scale factor per second.
            let velocity = step["velocity"] as? Double ?? (scale > 1 ? 2 : -2)
            app.windows.firstMatch.pinch(withScale: CGFloat(scale), velocity: CGFloat(velocity))
            return
        }
        if let label = step["longPress"] as? String {
            let element = try element(labelled: label, timeout: timeout, step: index)
            element.press(forDuration: step["seconds"] as? Double ?? 1)
            return
        }
        if let text = step["type"] as? String {
            app.typeText(text)
            return
        }
        if let direction = step["swipe"] as? String {
            let window = app.windows.firstMatch
            switch direction {
            case "up": window.swipeUp()
            case "down": window.swipeDown()
            case "left": window.swipeLeft()
            case "right": window.swipeRight()
            default: XCTFail("step \(index): unknown swipe direction \(direction)")
            }
            return
        }
        if let direction = step["fling"] as? String {
            let window = app.windows.firstMatch
            let (from, to): (CGFloat, CGFloat) = switch direction {
            case "up": (0.8, 0.2)
            case "down": (0.2, 0.8)
            default: (0.5, 0.5)
            }
            guard from != to else {
                XCTFail("step \(index): unknown fling direction \(direction)")
                return
            }
            let start = window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: from))
            let end = window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: to))
            start.press(forDuration: 0.01, thenDragTo: end, withVelocity: .fast, thenHoldForDuration: 0)
            return
        }
        if step["edgeSwipe"] as? Bool == true {
            let window = app.windows.firstMatch
            let start = window.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.5))
            let end = window.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5))
            // A slow, held drag so pan recognizers see the full progress
            // instead of one synthesized jump.
            start.press(forDuration: 0.1, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.15)
            return
        }
        if step["home"] as? Bool == true {
            XCUIDevice.shared.press(.home)
            return
        }
        if let button = step["alert"] as? String {
            let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
            let element = springboard.buttons[button].firstMatch
            XCTAssertTrue(element.waitForExistence(timeout: timeout), "step \(index): no system alert button \(button)")
            element.tap()
            return
        }
        if let raw = step["openurl"] as? String, let url = URL(string: raw) {
            if #available(iOS 16.4, *) {
                app.open(url)
            } else {
                XCTFail("step \(index): openurl requires iOS 16.4")
            }
            return
        }
        if let seconds = step["wait"] as? Double {
            Thread.sleep(forTimeInterval: seconds)
            return
        }
        if let label = step["waitFor"] as? String {
            _ = try element(labelled: label, timeout: timeout, step: index)
            return
        }
        if let name = step["shot"] as? String {
            try screenshot(named: name)
            return
        }
        if let name = step["dump"] as? String {
            let tree = app.debugDescription
            let attachment = XCTAttachment(string: tree)
            attachment.name = name
            attachment.lifetime = .keepAlways
            add(attachment)
            if let directory = shotDirectory {
                try tree.write(to: directory.appendingPathComponent("\(name).txt"), atomically: true, encoding: .utf8)
            }
            return
        }
        XCTFail("step \(index): unknown step \(step)")
    }

    private func element(labelled label: String, timeout: Double, step: Int) throws -> XCUIElement {
        let predicate = NSPredicate(format: "label == %@ OR label BEGINSWITH %@", label, label)
        let element = app.descendants(matching: .any).matching(predicate).firstMatch
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "step \(step): no element labelled \(label)")
        return element
    }

    private func screenshot(named name: String) throws {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let directory = shotDirectory {
            try screenshot.pngRepresentation.write(to: directory.appendingPathComponent("\(name).png"))
        }
    }
}
