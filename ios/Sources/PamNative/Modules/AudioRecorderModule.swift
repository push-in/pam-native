import AVFoundation
import Foundation

final class AudioRecorderModule: NSObject, NativeModule, ClosableNativeModule, AVAudioRecorderDelegate {
    private var recorder: AVAudioRecorder?
    private var outputURL: URL?

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
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("pam-voice-\(UUID().uuidString).m4a")
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
        active.stop()
        recorder = nil
        outputURL = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
        return try WireMap.encode([
            "uri": .text(url.absoluteString),
            "fileName": .text(url.lastPathComponent),
            "mimeType": .text("audio/mp4"),
            "durationMs": .integer(durationMs),
            "size": .integer(max(0, size)),
        ])
    }

    private func release(deleteOutput: Bool) {
        recorder?.stop()
        recorder = nil
        if deleteOutput, let outputURL {
            try? FileManager.default.removeItem(at: outputURL)
        }
        outputURL = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func discard(_ payload: Data) throws -> Data {
        let values = try WireMap.decode(payload)
        guard case let .text(uri)? = values["uri"],
              let url = URL(string: uri) else {
            throw AudioRecorderError.message("Audio recording URI is required")
        }
        let temporary = FileManager.default.temporaryDirectory.standardizedFileURL
        let file = url.standardizedFileURL
        guard file.deletingLastPathComponent() == temporary,
              file.lastPathComponent.hasPrefix("pam-voice-") else {
            throw AudioRecorderError.message("Audio recording URI is outside the recorder cache")
        }
        if FileManager.default.fileExists(atPath: file.path) {
            try FileManager.default.removeItem(at: file)
        }
        return Data()
    }

    func close() {
        DispatchQueue.main.async {
            self.release(deleteOutput: true)
        }
    }
}

private enum AudioRecorderError: LocalizedError {
    case message(String)

    var errorDescription: String? {
        switch self {
        case let .message(value): value
        }
    }
}
