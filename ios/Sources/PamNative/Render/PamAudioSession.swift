import AVFoundation
import Foundation

/// A player whose sound the shared `AVAudioSession` policy follows.
protocol PamAudioParticipant: AnyObject {
    /// Playing (or about to: a play that waits for its start buffer counts).
    var audioPlaying: Bool { get }
    /// Its sound reaches the speaker (not muted, volume above 0).
    var audioAudible: Bool { get }
    /// An interruption (call, alarm, Siri) began: pause and remember.
    func audioInterruptionBegan()
    /// The interruption ended; `shouldResume` is the system's hint.
    func audioInterruptionEnded(shouldResume: Bool)
    /// The output went away (headphones unplugged): audible playback pauses.
    func audioOutputLost()
}

/// What the session is set to while something plays.
struct PamAudioSessionPlan: Equatable {
    var mixWithOthers: Bool
    var activate: Bool
}

/// The calls the policy makes on the system session (faked in tests).
protocol PamAudioSessionBackend: AnyObject {
    var category: AVAudioSession.Category { get }
    var mode: AVAudioSession.Mode { get }
    var categoryOptions: AVAudioSession.CategoryOptions { get }
    func setCategory(_ category: AVAudioSession.Category, mode: AVAudioSession.Mode, options: AVAudioSession.CategoryOptions) throws
    func setActive(_ active: Bool) throws
}

final class PamSystemAudioSessionBackend: PamAudioSessionBackend {
    private var session: AVAudioSession { AVAudioSession.sharedInstance() }
    var category: AVAudioSession.Category { session.category }
    var mode: AVAudioSession.Mode { session.mode }
    var categoryOptions: AVAudioSession.CategoryOptions { session.categoryOptions }
    func setCategory(_ category: AVAudioSession.Category, mode: AVAudioSession.Mode, options: AVAudioSession.CategoryOptions) throws {
        try session.setCategory(category, mode: mode, options: options)
    }
    func setActive(_ active: Bool) throws { try session.setActive(active) }
}

/// Video sound policy, the same as React Native expo-video (`audioMixingMode`
/// `auto`): while a player plays the session is `.playback`/`.moviePlayback`,
/// so sound plays with the silent switch on; with no audible player it adds
/// `.mixWithOthers`, so muted autoplay never stops another app's music; an
/// audible player takes the session over (other audio stops). A session in
/// a recording or call category is only taken over by an audible video,
/// never by a muted one. Interruptions pause
/// playing players and resume them when the system says so; losing the
/// output (headphones unplugged) pauses audible playback.
final class PamAudioSession {
    static let shared = PamAudioSession()

    private let backend: PamAudioSessionBackend
    private let participants = NSHashTable<AnyObject>.weakObjects()
    private var observers: [NSObjectProtocol] = []
    private let center: NotificationCenter
    /// The session was activated by this policy and no interruption or
    /// deactivation happened since.
    private(set) var active = false
    private(set) var interrupted = false

    init(backend: PamAudioSessionBackend = PamSystemAudioSessionBackend(), center: NotificationCenter = .default) {
        self.backend = backend
        self.center = center
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { [weak self] note in
            let raw = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.uintValue
            let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.uintValue ?? 0
            let resume = AVAudioSession.InterruptionOptions(rawValue: options).contains(.shouldResume)
            Self.onMain {
                switch raw.flatMap(AVAudioSession.InterruptionType.init(rawValue:)) {
                case .began?: self?.interruptionBegan()
                case .ended?: self?.interruptionEnded(shouldResume: resume)
                default: break
                }
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil) { [weak self] note in
            let raw = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? NSNumber)?.uintValue
            guard raw.flatMap(AVAudioSession.RouteChangeReason.init(rawValue:)) == .oldDeviceUnavailable else { return }
            Self.onMain { self?.outputLost() }
        })
    }

    deinit { observers.forEach(center.removeObserver) }

    private static func onMain(_ work: @escaping () -> Void) {
        if Thread.isMainThread { work() } else { DispatchQueue.main.async(execute: work) }
    }

    private var current: [PamAudioParticipant] {
        participants.allObjects.compactMap { $0 as? PamAudioParticipant }
    }

    func register(_ participant: PamAudioParticipant) { participants.add(participant) }

    func unregister(_ participant: PamAudioParticipant) {
        participants.remove(participant)
    }

    /// expo-video `auto`: mix while nothing audible plays; nil while nothing plays.
    static func plan(_ players: [(playing: Bool, audible: Bool)]) -> PamAudioSessionPlan? {
        let playing = players.filter(\.playing)
        guard !playing.isEmpty else { return nil }
        return PamAudioSessionPlan(mixWithOthers: !playing.contains(where: \.audible), activate: true)
    }

    /// Called after a player starts, pauses, changes mute/volume or goes away.
    func update() {
        guard !interrupted,
              let plan = Self.plan(current.map { ($0.audioPlaying, $0.audioAudible) }) else { return }
        let options: AVAudioSession.CategoryOptions = plan.mixWithOthers ? [.mixWithOthers] : []
        let category = backend.category
        // A muted video never takes a session someone records or calls in.
        if plan.mixWithOthers, category == .playAndRecord || category == .record || category == .multiRoute { return }
        if category != .playback || backend.mode != .moviePlayback || backend.categoryOptions != options {
            do {
                try backend.setCategory(.playback, mode: .moviePlayback, options: options)
            } catch {
                NSLog("PamAudioSession setCategory failed: %@", String(describing: error))
                return
            }
            active = false
        }
        guard plan.activate, !active else { return }
        do {
            try backend.setActive(true)
            active = true
        } catch {
            NSLog("PamAudioSession setActive failed: %@", String(describing: error))
        }
    }

    func interruptionBegan() {
        interrupted = true
        active = false
        current.forEach { $0.audioInterruptionBegan() }
    }

    func interruptionEnded(shouldResume: Bool) {
        interrupted = false
        current.forEach { $0.audioInterruptionEnded(shouldResume: shouldResume) }
        update()
    }

    func outputLost() {
        current.filter { $0.audioPlaying && $0.audioAudible }.forEach { $0.audioOutputLost() }
        update()
    }
}
