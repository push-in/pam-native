import UIKit
import XCTest
@testable import PamNative

/// Shared elements between a screen and a `<Modal>`; Android
/// `PamModalSharedTransitionTest` plus the iOS flights.
@MainActor
final class PamModalSharedTransitionTests: XCTestCase {
    private func alpha(_ color: UIColor?) -> Int {
        var a: CGFloat = 0
        color?.getRed(nil, green: nil, blue: nil, alpha: &a)
        return Int((a * 255).rounded())
    }

    private func rgb(_ color: UIColor?) -> Int {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0
        color?.getRed(&r, green: &g, blue: &b, alpha: nil)
        return (Int((r * 255).rounded()) << 16) | (Int((g * 255).rounded()) << 8) | Int((b * 255).rounded())
    }

    func testTheTopmostFullWindowBackgroundCoversAnUnmountedModal() {
        let black = UIColor.black, white = UIColor.white
        XCTAssertEqual(rgb(modalCoverColor([
            PamModalCoverLayer(coverage: 1, color: black, opacity: 1),
            PamModalCoverLayer(coverage: 1, color: white, opacity: 1),
        ])), 0)
        XCTAssertEqual(rgb(modalCoverColor([
            PamModalCoverLayer(coverage: 1, color: UIColor.red.withAlphaComponent(0), opacity: 1),
            PamModalCoverLayer(coverage: 0.4, color: black, opacity: 1),
            PamModalCoverLayer(coverage: 0.95, color: white, opacity: 1),
        ])), 0xFFFFFF)
        XCTAssertNil(modalCoverColor([PamModalCoverLayer(coverage: 0.5, color: black, opacity: 1)]))
        XCTAssertNil(modalCoverColor([]))
    }

    func testTheCoverKeepsTheOpacityOfAViewFadedByADrag() {
        XCTAssertEqual(alpha(modalCoverColor([PamModalCoverLayer(coverage: 1, color: .black, opacity: 0.4)])), 102)
        let scrim = UIColor(red: 0x10 / 255.0, green: 0x20 / 255.0, blue: 0x30 / 255.0, alpha: 0x80 / 255.0)
        let cover = modalCoverColor([PamModalCoverLayer(coverage: 1, color: scrim, opacity: 0.5)])
        XCTAssertEqual(alpha(cover), 64)
        XCTAssertEqual(rgb(cover), 0x102030)
    }

    func testTheModalFadesInFromALaunchBackdropAndOutFromWhereItIs() {
        XCTAssertEqual(modalSharedEnterAlpha(0, 0), 0)
        XCTAssertEqual(modalSharedEnterAlpha(0, 0.5), 0.5, accuracy: 1e-6)
        XCTAssertEqual(modalSharedEnterAlpha(0.6, 0), 0.6, accuracy: 1e-6)
        XCTAssertEqual(modalSharedEnterAlpha(0.6, 0.5), 0.8, accuracy: 1e-6)
        XCTAssertEqual(modalSharedEnterAlpha(0.6, 1.4), 1)
        XCTAssertEqual(modalSharedExitAlpha(0.4, 0), 0.4, accuracy: 1e-6)
        XCTAssertEqual(modalSharedExitAlpha(0.4, 0.5), 0.2, accuracy: 1e-6)
        XCTAssertEqual(modalSharedExitAlpha(1, 1), 0)
        XCTAssertEqual(modalSharedExitAlpha(1, 1.2), 0)
    }

    func testCoverColorReadsTheTopmostFullBackgroundOfAModal() {
        let root = UIView(frame: CGRect(x: 0, y: 0, width: 100, height: 200))
        let scrim = UIView(frame: root.bounds)
        scrim.backgroundColor = UIColor.black.withAlphaComponent(0.32)
        let viewer = UIView(frame: root.bounds)
        viewer.backgroundColor = .black
        let card = UIView(frame: CGRect(x: 10, y: 10, width: 40, height: 40))
        card.backgroundColor = .white
        root.addSubview(scrim)
        root.addSubview(viewer)
        viewer.addSubview(card)
        XCTAssertEqual(alpha(PamModalSharedTransition.coverColor(root)), 255)
        XCTAssertEqual(alpha(PamModalSharedTransition.coverColor(root, skip: viewer)), 81)
    }

    private func tagged(_ view: UIView, _ tag: String) {
        view.layer.setValue(tag, forKey: "pamSharedTransitionTag")
    }

    /// A screen thumbnail flies into the Modal on open and home on close;
    /// the Modal (in the same window root) is never paired with itself.
    func testAModalPairsWithTheScreenUnderItAndFadesWithTheFlights() throws {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 360, height: 720))
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        let root = UIView(frame: window.bounds)
        window.addSubview(root)
        let screen = UIView(frame: root.bounds)
        root.addSubview(screen)
        let thumbnail = UIView(frame: CGRect(x: 20, y: 100, width: 80, height: 80))
        thumbnail.backgroundColor = .red
        tagged(thumbnail, "photo")
        screen.addSubview(thumbnail)
        let modal = UIView(frame: root.bounds)
        root.addSubview(modal)
        let full = UIView(frame: CGRect(x: 0, y: 160, width: 360, height: 360))
        full.backgroundColor = .red
        tagged(full, "photo")
        modal.addSubview(full)
        root.layoutIfNeeded()

        let transition = PamModalSharedTransition()
        var fades: [CGFloat] = []
        var entered = false
        XCTAssertTrue(transition.enter(
            modal: modal, below: root, defaultDuration: 0.05,
            fade: { fades.append($0); modal.alpha = $0 },
            onEnd: { entered = true }
        ))
        XCTAssertTrue(transition.running)
        XCTAssertEqual(transition.activeCount, 1)
        XCTAssertEqual(fades.first, 0)
        XCTAssertEqual(fades.last, 1)
        // The pair hides while it flies.
        XCTAssertEqual(thumbnail.alpha, 0)
        XCTAssertEqual(full.alpha, 0)
        transition.cancel()
        XCTAssertFalse(transition.running)
        XCTAssertFalse(entered)
        XCTAssertEqual(thumbnail.alpha, 1)
        XCTAssertEqual(full.alpha, 1)

        var completed: Bool?
        XCTAssertTrue(transition.exit(
            modal: modal, below: root, defaultDuration: 0.05, shadeColor: .black,
            fade: { modal.alpha = 0 },
            onEnd: { completed = $0 }
        ))
        XCTAssertEqual(transition.activeCount, 1)
        // Cut short (shown again): the exit ends not completed.
        transition.cancel()
        XCTAssertEqual(completed, false)

        // Nothing tagged in the Modal: nothing flies.
        full.layer.setValue(nil, forKey: "pamSharedTransitionTag")
        XCTAssertFalse(transition.enter(
            modal: modal, below: root, defaultDuration: 0.05, fade: { _ in }, onEnd: {}
        ))
    }

    func testAnExitRunsToItsEnd() throws {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 360, height: 720))
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        let root = UIView(frame: window.bounds)
        window.addSubview(root)
        let home = UIView(frame: CGRect(x: 20, y: 100, width: 80, height: 80))
        home.backgroundColor = .blue
        tagged(home, "avatar")
        root.addSubview(home)
        let modal = UIView(frame: root.bounds)
        root.addSubview(modal)
        let big = UIView(frame: CGRect(x: 0, y: 100, width: 300, height: 300))
        big.backgroundColor = .blue
        tagged(big, "avatar")
        modal.addSubview(big)
        let transition = PamModalSharedTransition()
        let done = expectation(description: "exit lands")
        XCTAssertTrue(transition.exit(
            modal: modal, below: root, defaultDuration: 0.05, shadeColor: nil,
            fade: { modal.alpha = 0 },
            onEnd: { completed in
                XCTAssertTrue(completed)
                done.fulfill()
            }
        ))
        transition.detach()
        wait(for: [done], timeout: 3)
        XCTAssertFalse(transition.running)
        XCTAssertEqual(home.alpha, 1)
    }
}
