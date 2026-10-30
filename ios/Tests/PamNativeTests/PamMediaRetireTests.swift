import AVFoundation
import UIKit
import XCTest
@testable import PamNative

/// A player released while its item still loads over the network never
/// blocks the main thread (Android releases players on their own thread).
@MainActor
final class PamMediaRetireTests: XCTestCase {
    /// A non-routable address: the connection hangs until it times out.
    private let stalled = "http://10.255.255.1/stalled-\(UUID().uuidString).mp4"

    func testReleasingALoadingPlayerDoesNotBlockTheMainThread() {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        defer { window.isHidden = true }
        window.isHidden = false
        let media = PamMediaView(frame: CGRect(x: 0, y: 0, width: 320, height: 200))
        window.addSubview(media)
        media.setAutoPlay(true)
        media.setSource(stalled)
        XCTAssertTrue(media.hasPlayer)
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        let started = CACurrentMediaTime()
        media.setSource("")
        let elapsed = CACurrentMediaTime() - started
        NSLog("PamMediaRetire release on main took %.3f s", elapsed)
        XCTAssertFalse(media.hasPlayer)
        XCTAssertLessThan(elapsed, 0.1, "the release ran on the main thread")
        media.removeFromSuperview()
    }

    /// Diagnostic: how long the old main-thread teardown takes (logged only).
    func testMainThreadTeardownCostIsLogged() {
        var player: AVPlayer? = AVPlayer(url: URL(string: stalled)!)
        player?.play()
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        let started = CACurrentMediaTime()
        player?.pause()
        player = nil
        NSLog("PamMediaRetire main-thread pause+free took %.3f s", CACurrentMediaTime() - started)
    }
}
