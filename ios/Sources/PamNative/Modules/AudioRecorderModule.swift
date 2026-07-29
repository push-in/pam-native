import AVFoundation
import Foundation

final class AudioRecorderModule: NSObject, NativeModule, ClosableNativeModule, AVAudioRecorderDelegate {
    private var recorder: AVAudioRecorder?
    private var outputURL: URL?
    private var nextWatchId = 1
    private var watches: [Int: RecorderWatch] = [:]

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        DispatchQueue.main.async {
            do {
                switch method {
                case "start":
                    try self.start()
                    completion(.success, Data())
                case "stop":
                    completion(.success, try self.stop())
                case "cancel":
                    self.release(deleteOutput: true)
                    completion(.success, Data())
                case "discard":
                    completion(.success, try self.discard(payload))
                case "watch":
                    try self.watch(payload, completion)
                case "next":
                    try self.recorderWatch(payload).channel.next(completion)
                case "unwatch":
                    self.stopWatch(try self.subscription(payload))
                    completion(.success, Data())
                default:
                    throw AudioRecorderError.message("Unknown audio recorder method \(method)")
                }
            } catch {
                completion(.failure, Data(error.localizedDescription.utf8))
            }
        }
    }

    private func start() throws {
        guard recorder == nil else {
            throw AudioRecorderError.message("An audio recording is already active")
        }
        let session = AVAudioSession.sharedInstance()
        guard session.recordPermission == .granted else {
            throw AudioRecorderError.message("Microphone permission is required")
        }
        try session.setCategory(.playAndRecord, mode: .spokenAudio, options: [.defaultToSpeaker])
        try session.setActive(true)
        let recordings = try recordingsDirectory()
        let url = recordings.appendingPathComponent("pam-voice-\(UUID().uuidString).m4a")
        let next = try AVAudioRecorder(
            url: url,
            settings: [
                AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey: 44_100,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: 64_000,
                AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
            ]
        )
        next.delegate = self
        next.isMeteringEnabled = true
        guard next.prepareToRecord(), next.record() else {
            throw AudioRecorderError.message("Unable to start audio recording")
        }
        outputURL = url
        recorder = next
    }

    private func stop() throws -> Data {
        guard let active = recorder, let url = outputURL else {
            throw AudioRecorderError.message("No audio recording is active")
        }
        let durationMs = max(0, Int64(active.currentTime * 1_000))
        stopWatches()
        active.stop()
        recorder = nil
        outputURL = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
        return try WireMap.encode([
            "uri": .text(url.absoluteString),
            "relativePath": .text("recordings/\(url.lastPathComponent)"),
            "fileName": .text(url.lastPathComponent),
            "mimeType": .text("audio/mp4"),
            "durationMs": .integer(durationMs),
            "size": .integer(max(0, size)),
        ])
    }

    private func release(deleteOutput: Bool) {
        stopWatches()
        recorder?.stop()
        recorder = nil
        if deleteOutput, let outputURL {
            try? FileManager.default.removeItem(at: outputURL)
        }
        outputURL = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func watch(_ payload: Data, _ completion: @escaping ModuleCompletion) throws {
        guard recorder != nil else {
            throw AudioRecorderError.message("No audio recording is active")
        }
        let values = try WireMap.decode(payload)
        let requested: Int64
        if case let .integer(value)? = values["intervalMs"] {
            requested = value
        } else {
            requested = 100
        }
        let interval = min(max(requested, 50), 1_000)
        let id = nextWatchId
        nextWatchId += 1
        let channel = WatchChannel()
        let timer = DispatchSource.makeTimerSource(queue: .main)
        watches[id] = RecorderWatch(channel: channel, timer: timer)
        timer.schedule(deadline: .now(), repeating: .milliseconds(Int(interval)))
        timer.setEventHandler { [weak self, weak channel] in
            guard let self, let active = self.recorder else {
                self?.stopWatch(id)
                return
            }
            active.updateMeters()
            let power = active.averagePower(forChannel: 0)
            let amplitude = min(max(pow(10.0, Double(power) / 20.0), 0), 1)
            let data = try? WireMap.encode([
                "durationMs": .integer(max(0, Int64(active.currentTime * 1_000))),
                "amplitude": .decimal(amplitude),
            ])
            if let data { channel?.offer(data) }
        }
        timer.resume()
        completion(.success, try WireMap.encode(["subscription": .integer(Int64(id))]))
    }

    private func subscription(_ payload: Data) throws -> Int {
        let values = try WireMap.decode(payload)
        guard case let .integer(value)? = values["subscription"] else {
            throw AudioRecorderError.message("Audio recorder subscription is required")
        }
        return Int(value)
    }

    private func recorderWatch(_ payload: Data) throws -> RecorderWatch {
        let id = try subscription(payload)
        guard let watch = watches[id] else {
            throw AudioRecorderError.message("Audio recorder observation not found")
        }
        return watch
    }

    private func stopWatch(_ id: Int) {
        guard let watch = watches.removeValue(forKey: id) else { return }
        watch.timer.cancel()
        watch.channel.close()
    }

    private func stopWatches() {
        Array(watches.keys).forEach(stopWatch)
    }

    private func discard(_ payload: Data) throws -> Data {
        let values = try WireMap.decode(payload)
        guard case let .text(uri)? = values["uri"],
              let url = URL(string: uri) else {
            throw AudioRecorderError.message("Audio recording URI is required")
        }
        let recordings = try recordingsDirectory().standardizedFileURL
        let file = url.standardizedFileURL
        guard file.deletingLastPathComponent() == recordings,
              file.lastPathComponent.hasPrefix("pam-voice-") else {
            throw AudioRecorderError.message("Audio recording URI is outside the recorder sandbox")
        }
        if FileManager.default.fileExists(atPath: file.path) {
            try FileManager.default.removeItem(at: file)
        }
        return Data()
    }

    private func recordingsDirectory() throws -> URL {
        let support = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let recordings = support
            .appendingPathComponent("pam-files", isDirectory: true)
            .appendingPathComponent("recordings", isDirectory: true)
        try FileManager.default.createDirectory(
            at: recordings,
            withIntermediateDirectories: true
        )
        return recordings
    }

    func close() {
        DispatchQueue.main.async {
            self.release(deleteOutput: true)
        }
    }
}

private struct RecorderWatch {
    let channel: WatchChannel
    let timer: DispatchSourceTimer
}

private enum AudioRecorderError: LocalizedError {
    case message(String)

    var errorDescription: String? {
        switch self {
        case let .message(value): value
        }
    }
}
