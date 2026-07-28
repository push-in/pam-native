import Foundation
import UIKit
import AVFoundation
import Photos
import AudioToolbox
import CoreMotion

public final class SystemModule: NativeModule, ClosableNativeModule, @unchecked Sendable {
    private let motion = CMMotionManager()
    public init() {}

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        switch method {
        case "alert":
            do {
                let values = try WireMap.decode(payload)
                let title = (values["title"] as? WireValue).flatMap { value in
                    if case let .text(value) = value { value } else { nil }
                } ?? ""
                let message = (values["message"] as? WireValue).flatMap { value in
                    if case let .text(value) = value { value } else { nil }
                } ?? ""
                presentAlert(title: title, message: message)
                completion(.success, Data())
            } catch {
                completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
            }
        case "toast":
            completion(.success, Data())
        case "share":
            do {
                let values = try WireMap.decode(payload)
                let text = (values["text"] as? WireValue).flatMap { value in
                    if case let .text(value) = value { value } else { nil }
                } ?? ""
                share(text: text)
                completion(.success, Data())
            } catch {
                completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
            }
        case "openUrl":
            do {
                let values = try WireMap.decode(payload)
                guard let text = (values["url"] as? WireValue).flatMap({ v in
                    if case let .text(v) = v { v } else { nil }
                }), let url = safeURL(from: text) else {
                    completion(.failure, "Invalid URL".data(using: .utf8) ?? Data())
                    return
                }
                guard UIApplication.shared.canOpenURL(url) else {
                    completion(.failure, "Cannot open URL".data(using: .utf8) ?? Data())
                    return
                }
                DispatchQueue.main.async {
                    UIApplication.shared.open(url, options: [:]) { success in
                        if success { completion(.success, Data()) }
                        else { completion(.failure, "Cannot open URL".data(using: .utf8) ?? Data()) }
                    }
                }
            } catch {
                completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
            }
        case "canOpenUrl":
            do {
                let values = try WireMap.decode(payload)
                guard let text = (values["url"] as? WireValue).flatMap({ v in
                    if case let .text(v) = v { v } else { nil }
                }), let url = safeURL(from: text) else {
                    completion(.failure, "Invalid URL".data(using: .utf8) ?? Data())
                    return
                }
                let supported = UIApplication.shared.canOpenURL(url)
                let resultPayload = try WireMap.encode(["supported": .flag(supported)])
                completion(.success, resultPayload)
            } catch {
                completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
            }
        case "vibrate":
            let milliseconds = milliseconds(from: payload)
            let impact = UIImpactFeedbackGenerator(style: .medium)
            DispatchQueue.main.async { impact.impactOccurred() }
            _ = milliseconds
            completion(.success, Data())
        case "haptic":
            haptic(payload)
            completion(.success, Data())
        case "clipboardSetText":
            clipboardSetText(payload: payload, completion: completion)
        case "clipboardGetText":
            clipboardGetText(completion: completion)
        case "clipboardHasText":
            clipboardHasText(completion: completion)
        case "sensorRead":
            sensorRead(payload: payload, completion: completion)
        case "deviceInfo":
            do {
                let screen = UIScreen.main
                let width = screen.bounds.size.width
                let height = screen.bounds.size.height
                let density = screen.scale
                let appearance = UIScreen.main.traitCollection.userInterfaceStyle == .dark ? 2 : 1
                let appState = UIApplication.shared.applicationState == .active ? 1 : 3
                let payload = try WireMap.encode([
                    "width": .decimal(Double(width)),
                    "height": .decimal(Double(height)),
                    "density": .decimal(Double(density)),
                    "appearance": .integer(Int64(appearance)),
                    "appState": .integer(Int64(appState)),
                ])
                completion(.success, payload)
            } catch {
                completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
            }
        case "keyboardDismiss":
            DispatchQueue.main.async {
                UIApplication.shared.sendAction(
                    #selector(UIResponder.resignFirstResponder),
                    to: nil,
                    from: nil,
                    for: nil,
                )
            }
            completion(.success, Data())
        case "permissionCheck":
            do {
                let permission = try requirePermission(payload)
                let granted = checkPermission(permission)
                let payload = try WireMap.encode(["granted": .flag(granted)])
                completion(.success, payload)
            } catch {
                completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
            }
        case "permissionRequest":
            do {
                let permission = try requirePermission(payload)
                requestPermission(permission) { granted in
                    do {
                        let payload = try WireMap.encode(["granted": .flag(granted)])
                        completion(.success, payload)
                    } catch {
                        completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
                    }
                }
            } catch {
                completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
            }
        case "closeApp":
            DispatchQueue.main.async {
                completion(.success, Data())
                exit(0)
            }
        default:
            completion(.failure, "Unknown native operation".data(using: .utf8) ?? Data())
        }
    }

    public func close() {
        motion.stopAccelerometerUpdates()
        motion.stopGyroUpdates()
        motion.stopMagnetometerUpdates()
    }

    public func invoke(operation: NativeOperation, payload: Data, completion: @escaping ModuleCompletion) {
        invoke(method: operationName(operation), payload: payload, completion: completion)
    }

    private func operationName(_ operation: NativeOperation) -> String {
        switch operation {
        case .alert: return "alert"
        case .toast: return "toast"
        case .share: return "share"
        case .openUrl: return "openUrl"
        case .canOpenUrl: return "canOpenUrl"
        case .vibrate: return "vibrate"
        case .deviceInfo: return "deviceInfo"
        case .keyboardDismiss: return "keyboardDismiss"
        case .permissionCheck: return "permissionCheck"
        case .permissionRequest: return "permissionRequest"
        case .closeApp: return "closeApp"
        case .haptic: return "haptic"
        case .clipboardSetText: return "clipboardSetText"
        case .clipboardGetText: return "clipboardGetText"
        case .clipboardHasText: return "clipboardHasText"
        case .sensorRead: return "sensorRead"
        default: return ""
        }
    }

    private func sensorRead(payload: Data, completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            guard case let .integer(type)? = values["type"] else {
                throw RuntimeError("Missing sensor type")
            }
            let timeoutMs: Int
            if case let .integer(value)? = values["timeoutMs"] {
                timeoutMs = min(max(Int(value), 100), 10_000)
            } else {
                timeoutMs = 2_000
            }
            let state = SensorCompletion(completion)
            let timeout = DispatchWorkItem { [weak self] in
                guard state.finishFailure("Sensor read timed out") else { return }
                self?.stopSensor(Int(type))
            }
            DispatchQueue.main.asyncAfter(
                deadline: .now() + .milliseconds(timeoutMs),
                execute: timeout
            )
            let handler: (Double, Double, Double, TimeInterval) -> Void = {
                [weak self] x, y, z, timestamp in
                guard state.finish(x: x, y: y, z: z, timestamp: timestamp) else { return }
                timeout.cancel()
                self?.stopSensor(Int(type))
            }
            switch Int(type) {
            case 1:
                guard motion.isAccelerometerAvailable else {
                    throw RuntimeError("Requested sensor is unavailable")
                }
                motion.startAccelerometerUpdates(to: .main) { data, _ in
                    guard let data else { return }
                    handler(
                        data.acceleration.x,
                        data.acceleration.y,
                        data.acceleration.z,
                        data.timestamp
                    )
                }
            case 2:
                guard motion.isGyroAvailable else {
                    throw RuntimeError("Requested sensor is unavailable")
                }
                motion.startGyroUpdates(to: .main) { data, _ in
                    guard let data else { return }
                    handler(
                        data.rotationRate.x,
                        data.rotationRate.y,
                        data.rotationRate.z,
                        data.timestamp
                    )
                }
            case 3:
                guard motion.isMagnetometerAvailable else {
                    throw RuntimeError("Requested sensor is unavailable")
                }
                motion.startMagnetometerUpdates(to: .main) { data, _ in
                    guard let data else { return }
                    handler(
                        data.magneticField.x,
                        data.magneticField.y,
                        data.magneticField.z,
                        data.timestamp
                    )
                }
            case 4:
                guard motion.isDeviceMotionAvailable else {
                    throw RuntimeError("Requested sensor is unavailable")
                }
                motion.startDeviceMotionUpdates(to: .main) { data, _ in
                    guard let data else { return }
                    handler(
                        data.attitude.roll,
                        data.attitude.pitch,
                        data.attitude.yaw,
                        data.timestamp
                    )
                }
            default:
                throw RuntimeError("Unknown sensor type \(type)")
            }
        } catch {
            completion(.failure, error.localizedDescription.data(using: .utf8) ?? Data())
        }
    }

    private func stopSensor(_ type: Int) {
        switch type {
        case 1: motion.stopAccelerometerUpdates()
        case 2: motion.stopGyroUpdates()
        case 3: motion.stopMagnetometerUpdates()
        case 4: motion.stopDeviceMotionUpdates()
        default: break
        }
    }

    private final class SensorCompletion: @unchecked Sendable {
        private let lock = NSLock()
        private var completed = false
        private let completion: ModuleCompletion

        init(_ completion: @escaping ModuleCompletion) {
            self.completion = completion
        }

        func finish(x: Double, y: Double, z: Double, timestamp: TimeInterval) -> Bool {
            guard claim() else { return false }
            do {
                completion(.success, try WireMap.encode([
                    "x": .decimal(x),
                    "y": .decimal(y),
                    "z": .decimal(z),
                    "timestamp": .integer(Int64(timestamp * 1_000)),
                ]))
            } catch {
                completion(.failure, error.localizedDescription.data(using: .utf8) ?? Data())
            }
            return true
        }

        func finishFailure(_ message: String) -> Bool {
            guard claim() else { return false }
            completion(.failure, message.data(using: .utf8) ?? Data())
            return true
        }

        private func claim() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            guard !completed else { return false }
            completed = true
            return true
        }
    }

    private func clipboardSetText(payload: Data, completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            guard case let .text(text)? = values["text"],
                  text.utf8.count <= 1_048_576 else {
                throw RuntimeError("Clipboard text exceeds one megabyte")
            }
            DispatchQueue.main.async {
                UIPasteboard.general.string = text
                completion(.success, Data())
            }
        } catch {
            completion(.failure, error.localizedDescription.data(using: .utf8) ?? Data())
        }
    }

    private func clipboardGetText(completion: @escaping ModuleCompletion) {
        DispatchQueue.main.async {
            do {
                let text = UIPasteboard.general.string ?? ""
                guard text.utf8.count <= 1_048_576 else {
                    throw RuntimeError("Clipboard text exceeds one megabyte")
                }
                completion(.success, try WireMap.encode(["text": .text(text)]))
            } catch {
                completion(.failure, error.localizedDescription.data(using: .utf8) ?? Data())
            }
        }
    }

    private func clipboardHasText(completion: @escaping ModuleCompletion) {
        DispatchQueue.main.async {
            do {
                completion(
                    .success,
                    try WireMap.encode(["hasText": .flag(UIPasteboard.general.hasStrings)])
                )
            } catch {
                completion(.failure, error.localizedDescription.data(using: .utf8) ?? Data())
            }
        }
    }

    private func presentAlert(title: String, message: String) {
        DispatchQueue.main.async {
            guard let window = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first?.windows.first,
                  let controller = window.rootViewController else {
                return
            }
            let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
            alert.addAction(.init(title: "OK", style: .default))
            controller.present(alert, animated: true)
        }
    }

    private func share(text: String) {
        DispatchQueue.main.async {
            guard let window = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first?.windows.first,
                  let controller = window.rootViewController else {
                return
            }
            let activity = UIActivityViewController(activityItems: [text], applicationActivities: nil)
            controller.present(activity, animated: true)
        }
    }

    private func safeURL(from raw: String) -> URL? {
        guard let url = URL(string: raw), let scheme = url.scheme?.lowercased() else {
            return nil
        }
        let allowed = Set(["https", "http", "mailto", "tel", "geo"])
        guard allowed.contains(scheme) else {
            return nil
        }
        return url
    }

    private func checkPermission(_ permission: String) -> Bool {
        switch permission {
        case "camera":
            return AVCaptureDevice.authorizationStatus(for: .video) == .authorized
        case "microphone":
            if #available(iOS 17.0, *) {
                return AVAudioApplication.shared.recordPermission == .granted
            } else {
                return AVAudioSession.sharedInstance().recordPermission == .granted
            }
        case "photos":
            let status = PHPhotoLibrary.authorizationStatus()
            return status == .authorized || status == .limited
        default:
            return false
        }
    }

    private func requestPermission(_ permission: String, completion: @escaping (Bool) -> Void) {
        switch permission {
        case "camera":
            AVCaptureDevice.requestAccess(for: .video, completionHandler: completion)
        case "microphone":
            AVAudioSession.sharedInstance().requestRecordPermission(completion)
        case "photos":
            if #available(iOS 16.0, *) {
                PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
                    completion(status == .authorized || status == .limited)
                }
            } else {
                PHPhotoLibrary.requestAuthorization { status in
                    completion(status == .authorized || status == .limited)
                }
            }
        default:
            completion(false)
        }
    }

    private func requirePermission(_ payload: Data) throws -> String {
        let values = try WireMap.decode(payload)
        guard case let .text(value)? = values["permission"],
              value.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "_" || $0 == "." }),
              !value.isEmpty else {
            throw RuntimeError("Missing permission")
        }
        return value
    }

    private func milliseconds(from payload: Data) -> Int {
        guard let values = try? WireMap.decode(payload),
              case let .integer(milliseconds)? = values["milliseconds"] else {
            return 30
        }
        let normalized = Int(milliseconds)
        return min(max(normalized, 1), 10_000)
    }

    private func haptic(_ payload: Data) {
        let feedback: Int
        if let values = try? WireMap.decode(payload),
           case let .integer(value)? = values["feedback"] {
            feedback = min(max(Int(value), 1), 7)
        } else {
            feedback = 1
        }
        DispatchQueue.main.async {
            switch feedback {
            case 1:
                UISelectionFeedbackGenerator().selectionChanged()
            case 2:
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
            case 3:
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            case 4:
                UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
            case 5:
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            case 6:
                UINotificationFeedbackGenerator().notificationOccurred(.warning)
            default:
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            }
        }
    }

    private struct RuntimeError: LocalizedError {
        let message: String
        init(_ message: String) { self.message = message }
        var errorDescription: String? { message }
    }
}
