import Foundation
import os
import UIKit
import AVFoundation
import Photos
import AudioToolbox

public final class SystemModule: NativeModule, ClosableNativeModule, @unchecked Sendable {
    /// Choice alerts on screen (main thread only); dismissed with -1 on close.
    private var openChoices: [ObjectIdentifier: (alert: UIAlertController, completion: ModuleCompletion)] = [:]
    public init() {}

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        switch method {
        case "alert":
            do {
                let values = try WireMap.decode(payload)
                let title = values["title"].flatMap { value in
                    if case let .text(value) = value { value } else { nil }
                } ?? ""
                let message = values["message"].flatMap { value in
                    if case let .text(value) = value { value } else { nil }
                } ?? ""
                let buttons = try PamAlertButtons.decode(values)
                if !buttons.isEmpty {
                    presentChoice(title: title, message: message, buttons: buttons, completion: completion)
                    return
                }
                presentAlert(title: title, message: message)
                completion(.success, Data())
            } catch {
                completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
            }
        case "toast":
            if let values = try? WireMap.decode(payload) {
                PamToastPresenter.show(PamToastSpec.decode(values))
            }
            completion(.success, Data())
        case "share":
            do {
                let values = try WireMap.decode(payload)
                let text = values["text"].flatMap { value in
                    if case let .text(value) = value { value } else { nil }
                } ?? ""
                share(text: text, completion: completion)
            } catch {
                completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
            }
        case "openUrl":
            do {
                let values = try WireMap.decode(payload)
                guard let text = values["url"].flatMap({ v in
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
                guard let text = values["url"].flatMap({ v in
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
            // Sensors moved to the pushinbr/pam-native-sensors plugin in 1.46.
            completion(.failure, Data("Sensors moved to the pushinbr/pam-native-sensors plugin".utf8))
        case "deviceInfo":
            DispatchQueue.main.async {
                do {
                    let screen = UIScreen.main
                    let activeScenes = UIApplication.shared.connectedScenes
                        .compactMap { $0 as? UIWindowScene }
                        .filter {
                            $0.activationState == .foregroundActive
                                || $0.activationState == .foregroundInactive
                        }
                    let window = activeScenes
                        .flatMap(\.windows)
                        .first(where: \.isKeyWindow)
                        ?? activeScenes.flatMap(\.windows).first(where: { !$0.isHidden })
                    window?.layoutIfNeeded()
                    window?.rootViewController?.view.layoutIfNeeded()
                    let bounds = window?.bounds ?? screen.bounds
                    let width = bounds.size.width
                    let height = bounds.size.height
                    let density = window?.screen.scale ?? screen.scale
                    let traits = window?.traitCollection ?? screen.traitCollection
                    let appearance = traits.userInterfaceStyle == .dark ? 2 : 1
                    let appState = UIApplication.shared.applicationState == .active ? 1 : 3
                    let windowInsets = window?.safeAreaInsets ?? .zero
                    let rootInsets = window?.rootViewController?.view.safeAreaInsets ?? .zero
                    let insets = UIEdgeInsets(
                        top: max(windowInsets.top, rootInsets.top),
                        left: max(windowInsets.left, rootInsets.left),
                        bottom: max(windowInsets.bottom, rootInsets.bottom),
                        right: max(windowInsets.right, rootInsets.right)
                    )
                    let payload = try WireMap.encode([
                        "width": .decimal(Double(width)),
                        "height": .decimal(Double(height)),
                        "density": .decimal(Double(density)),
                        "appearance": .integer(Int64(appearance)),
                        "appState": .integer(Int64(appState)),
                        "timeZone": .text(TimeZone.current.identifier),
                        "safeAreaTop": .decimal(Double(insets.top)),
                        "safeAreaRight": .decimal(Double(insets.right)),
                        "safeAreaBottom": .decimal(Double(insets.bottom)),
                        "safeAreaLeft": .decimal(Double(insets.left)),
                        // iOS has no heap class; report the process memory budget instead.
                        "memoryClassMb": .integer(Int64(os_proc_available_memory() / 1_048_576)),
                        "lowRamDevice": .flag(ProcessInfo.processInfo.physicalMemory < 3 * 1_073_741_824),
                        "powerSaveMode": .flag(ProcessInfo.processInfo.isLowPowerModeEnabled),
                        "appVersion": .text(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""),
                        "buildNumber": .text(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? ""),
                        "locale": .text(Locale.preferredLanguages.first ?? Locale.current.identifier),
                    ])
                    completion(.success, payload)
                } catch {
                    completion(
                        .failure,
                        (error.localizedDescription).data(using: .utf8) ?? Data()
                    )
                }
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
        DispatchQueue.main.async {
            let open = self.openChoices
            self.openChoices.removeAll()
            for (_, entry) in open {
                entry.alert.dismiss(animated: false)
                if let payload = try? WireMap.encode(["index": .integer(Int64(PamAlertButtons.dismissed))]) {
                    entry.completion(.success, payload)
                }
            }
        }
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

    private func clipboardSetText(payload: Data, completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            guard case let .text(text)? = values["text"],
                  text.utf8.count <= 1_048_576 else {
                throw RuntimeError("Clipboard text exceeds one megabyte")
            }
            DispatchQueue.main.async {
                let sensitive: Bool
                if case .flag(true)? = values["sensitive"] {
                    sensitive = true
                } else {
                    sensitive = false
                }
                if sensitive {
                    guard case let .integer(seconds)? = values["clearAfterSeconds"],
                          (15...300).contains(seconds) else {
                        completion(.failure, "Sensitive clipboard expiry must be between 15 and 300 seconds".data(using: .utf8) ?? Data())
                        return
                    }
                    UIPasteboard.general.setItems(
                        [["public.utf8-plain-text": text]],
                        options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(TimeInterval(seconds))]
                    )
                } else {
                    UIPasteboard.general.string = text
                }
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

    /// `Alert::choose()`: answers the pressed button index once the alert is dismissed.
    private func presentChoice(
        title: String,
        message: String,
        buttons: [PamAlertButton],
        completion: @escaping ModuleCompletion
    ) {
        DispatchQueue.main.async {
            guard let presenter = Self.topPresenter() else {
                completion(.failure, Data("No view controller is available for the alert".utf8))
                return
            }
            let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
            let token = ObjectIdentifier(alert)
            for (index, button) in buttons.enumerated() {
                let style: UIAlertAction.Style
                switch button.style {
                case PamAlertButtons.styleCancel: style = .cancel
                case PamAlertButtons.styleDestructive: style = .destructive
                default: style = .default
                }
                alert.addAction(UIAlertAction(title: button.label, style: style) { [weak self] _ in
                    self?.finishChoice(token, index: index)
                })
            }
            self.openChoices[token] = (alert, completion)
            presenter.present(alert, animated: true)
        }
    }

    private func finishChoice(_ token: ObjectIdentifier, index: Int) {
        guard let entry = openChoices.removeValue(forKey: token) else { return }
        do {
            entry.completion(.success, try WireMap.encode(["index": .integer(Int64(index))]))
        } catch {
            entry.completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    private func share(text: String, completion: @escaping ModuleCompletion) {
        DispatchQueue.main.async {
            guard let controller = Self.topPresenter() else {
                completion(.failure, Data("No view controller is available for sharing".utf8))
                return
            }
            let activity = UIActivityViewController(activityItems: [text], applicationActivities: nil)
            if let popover = activity.popoverPresentationController {
                popover.sourceView = controller.view
                popover.sourceRect = CGRect(x: controller.view.bounds.midX, y: controller.view.bounds.midY, width: 0, height: 0)
                popover.permittedArrowDirections = []
            }
            controller.present(activity, animated: true) {
                completion(.success, Data())
            }
        }
    }

    private static func topPresenter() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        var controller = scenes.flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController
            ?? scenes.flatMap(\.windows).first(where: { !$0.isHidden })?.rootViewController
        while let presented = controller?.presentedViewController, !presented.isBeingDismissed {
            controller = presented
        }
        return controller
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
