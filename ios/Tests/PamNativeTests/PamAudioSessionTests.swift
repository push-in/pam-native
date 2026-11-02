import AVFoundation
import XCTest
@testable import PamNative

private final class FakeBackend: PamAudioSessionBackend {
    var category: AVAudioSession.Category = .soloAmbient
    var mode: AVAudioSession.Mode = .default
    var categoryOptions: AVAudioSession.CategoryOptions = []
    var setCategoryCalls = 0
    var activations = 0
    func setCategory(_ category: AVAudioSession.Category, mode: AVAudioSession.Mode, options: AVAudioSession.CategoryOptions) throws {
        self.category = category
        self.mode = mode
        categoryOptions = options
        setCategoryCalls += 1
    }
    func setActive(_ active: Bool) throws { if active { activations += 1 } }
}

private final class FakePlayer: PamAudioParticipant {
    var audioPlaying: Bool
    var audioAudible: Bool
    var interruptedWhilePlaying = false
    var lostOutput = false
    init(playing: Bool, audible: Bool) {
        audioPlaying = playing
        audioAudible = audible
    }
    func audioInterruptionBegan() {
        interruptedWhilePlaying = audioPlaying
        audioPlaying = false
    }
    func audioInterruptionEnded(shouldResume: Bool) {
        if interruptedWhilePlaying, shouldResume { audioPlaying = true }
        interruptedWhilePlaying = false
    }
    func audioOutputLost() {
        lostOutput = true
        audioPlaying = false
    }
}

/// Video sound follows expo-video `audioMixingMode: 'auto'`: `.playback`
/// (plays with the silent switch on), mixing with other apps while nothing
/// audible plays; interruptions and an unplugged output pause playback.
@MainActor
final class PamAudioSessionTests: XCTestCase {
    private var center: NotificationCenter!
    private var backend: FakeBackend!
    private var session: PamAudioSession!

    override func setUp() {
        super.setUp()
        center = NotificationCenter()
        backend = FakeBackend()
        session = PamAudioSession(backend: backend, center: center)
    }

    func testPlanMixesUnlessAnAudiblePlayerPlays() {
        XCTAssertNil(PamAudioSession.plan([]))
        XCTAssertNil(PamAudioSession.plan([(false, true)]))
        XCTAssertEqual(PamAudioSession.plan([(true, false)])?.mixWithOthers, true)
        XCTAssertEqual(PamAudioSession.plan([(true, true)])?.mixWithOthers, false)
        XCTAssertEqual(PamAudioSession.plan([(false, true), (true, false)])?.mixWithOthers, true)
        XCTAssertEqual(PamAudioSession.plan([(true, false), (true, true)])?.mixWithOthers, false)
    }

    func testMutedAutoplayMixesWithOtherAudio() {
        let muted = FakePlayer(playing: true, audible: false)
        session.register(muted)
        session.update()
        XCTAssertEqual(backend.category, .playback)
        XCTAssertEqual(backend.mode, .moviePlayback)
        XCTAssertEqual(backend.categoryOptions, [.mixWithOthers])
        XCTAssertEqual(backend.activations, 1)
    }

    func testAudibleVideoPlaysWithTheSilentSwitchAndTakesTheSession() {
        let video = FakePlayer(playing: true, audible: true)
        session.register(video)
        session.update()
        XCTAssertEqual(backend.category, .playback)
        XCTAssertEqual(backend.categoryOptions, [])
        // Muting it gives the other apps their audio back (mix again).
        video.audioAudible = false
        session.update()
        XCTAssertEqual(backend.categoryOptions, [.mixWithOthers])
    }

    func testNothingPlayingLeavesTheSessionAlone() {
        let paused = FakePlayer(playing: false, audible: true)
        session.register(paused)
        session.update()
        XCTAssertEqual(backend.category, .soloAmbient)
        XCTAssertEqual(backend.setCategoryCalls, 0)
    }

    func testRepeatedUpdatesDoNotReconfigure() {
        let muted = FakePlayer(playing: true, audible: false)
        session.register(muted)
        session.update()
        session.update()
        session.update()
        XCTAssertEqual(backend.setCategoryCalls, 1)
        XCTAssertEqual(backend.activations, 1)
    }

    func testMutedVideoNeverTakesARecordingSession() {
        backend.category = .playAndRecord
        backend.mode = .voiceChat
        let muted = FakePlayer(playing: true, audible: false)
        session.register(muted)
        session.update()
        XCTAssertEqual(backend.category, .playAndRecord)
        XCTAssertEqual(backend.setCategoryCalls, 0)
        muted.audioAudible = true
        session.update()
        XCTAssertEqual(backend.category, .playback)
    }

    func testInterruptionPausesAndResumesOnlyWhenTheSystemSaysSo() {
        let video = FakePlayer(playing: true, audible: true)
        let paused = FakePlayer(playing: false, audible: true)
        session.register(video)
        session.register(paused)
        session.update()
        postInterruption(.began)
        XCTAssertFalse(video.audioPlaying)
        XCTAssertTrue(session.interrupted)
        session.update()
        XCTAssertEqual(backend.activations, 1, "no reactivation while interrupted")
        postInterruption(.ended, resume: true)
        XCTAssertTrue(video.audioPlaying)
        XCTAssertFalse(paused.audioPlaying)
        XCTAssertEqual(backend.activations, 2)

        postInterruption(.began)
        postInterruption(.ended, resume: false)
        XCTAssertFalse(video.audioPlaying)
    }

    func testUnpluggedHeadphonesPauseOnlyAudiblePlayback() {
        let audible = FakePlayer(playing: true, audible: true)
        let muted = FakePlayer(playing: true, audible: false)
        session.register(audible)
        session.register(muted)
        center.post(name: AVAudioSession.routeChangeNotification, object: nil, userInfo: [
            AVAudioSessionRouteChangeReasonKey: NSNumber(value: AVAudioSession.RouteChangeReason.newDeviceAvailable.rawValue),
        ])
        XCTAssertFalse(audible.lostOutput)
        center.post(name: AVAudioSession.routeChangeNotification, object: nil, userInfo: [
            AVAudioSessionRouteChangeReasonKey: NSNumber(value: AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue),
        ])
        XCTAssertTrue(audible.lostOutput)
        XCTAssertFalse(audible.audioPlaying)
        XCTAssertFalse(muted.lostOutput)
        XCTAssertTrue(muted.audioPlaying)
        XCTAssertEqual(backend.categoryOptions, [.mixWithOthers])
    }

    func testReleasedPlayersDropOut() {
        autoreleasepool {
            let video = FakePlayer(playing: true, audible: true)
            session.register(video)
            session.update()
            XCTAssertEqual(backend.categoryOptions, [])
        }
        let muted = FakePlayer(playing: true, audible: false)
        session.register(muted)
        session.update()
        XCTAssertEqual(backend.categoryOptions, [.mixWithOthers])
    }

    func testMediaViewAudibilityFollowsMuteAndVolume() {
        let media = PamMediaView(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        XCTAssertFalse(media.audioPlaying, "no player, not playing")
        XCTAssertTrue(media.audioAudible)
        media.setMuted(true)
        XCTAssertFalse(media.audioAudible)
        media.setMuted(false)
        media.setVolume(0)
        XCTAssertFalse(media.audioAudible)
        media.setVolume(0.5)
        XCTAssertTrue(media.audioAudible)
    }

    func testAudioRenderFailureIsToldApartFromNetworkAndFileErrors() {
        let render = NSError(domain: AVFoundationErrorDomain, code: -11800, userInfo: [
            NSUnderlyingErrorKey: NSError(domain: NSOSStatusErrorDomain, code: -12746),
        ])
        XCTAssertTrue(PamMediaView.isAudioRenderFailure(render))
        XCTAssertTrue(PamMediaView.isAudioRenderFailure(NSError(domain: NSOSStatusErrorDomain, code: -12746)))
        let offline = NSError(domain: AVFoundationErrorDomain, code: -11800, userInfo: [
            NSUnderlyingErrorKey: NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet),
        ])
        XCTAssertFalse(PamMediaView.isAudioRenderFailure(offline))
        let missing = NSError(domain: AVFoundationErrorDomain, code: -11800, userInfo: [
            NSUnderlyingErrorKey: NSError(domain: NSOSStatusErrorDomain, code: -12938),
        ])
        XCTAssertFalse(PamMediaView.isAudioRenderFailure(missing))
        XCTAssertFalse(PamMediaView.isAudioRenderFailure(NSError(domain: AVFoundationErrorDomain, code: -11828)))
        XCTAssertFalse(PamMediaView.isAudioRenderFailure(nil))
    }

    private func postInterruption(_ type: AVAudioSession.InterruptionType, resume: Bool = false) {
        var info: [AnyHashable: Any] = [AVAudioSessionInterruptionTypeKey: NSNumber(value: type.rawValue)]
        if type == .ended {
            info[AVAudioSessionInterruptionOptionKey] = NSNumber(value: resume ? AVAudioSession.InterruptionOptions.shouldResume.rawValue : 0)
        }
        center.post(name: AVAudioSession.interruptionNotification, object: nil, userInfo: info)
    }
}
