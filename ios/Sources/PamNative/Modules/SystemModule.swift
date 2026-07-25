import Foundation
import UIKit
import AVFoundation
import Photos
import AudioToolbox

public final class SystemModule: NativeModule, ClosableNativeModule, @unchecked Sendable {
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
        default: return ""
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
            AVAudioSession.sharedInstance().requestRecordPermission(completionHandler: completion)
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

    private struct RuntimeError: LocalizedError {
        let message: String
        init(_ message: String) { self.message = message }
        var errorDescription: String? { message }
    }
}
