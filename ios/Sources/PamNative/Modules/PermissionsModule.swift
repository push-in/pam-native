import AVFoundation
import CoreLocation
import Contacts
import Foundation
import Photos
import UIKit
import UserNotifications

final class PermissionsModule: NSObject, NativeModule, ClosableNativeModule, CLLocationManagerDelegate {
    private let location = CLLocationManager()
    private var locationCompletion: ModuleCompletion?
    private var locationRequestGeneration = 0

    override init() {
        super.init()
        location.delegate = self
    }

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        do {
            switch method {
            case "status":
                status(try kind(payload), completion)
            case "request":
                request(try kind(payload), completion)
            case "openSettings":
                DispatchQueue.main.async {
                    guard let url = URL(string: UIApplication.openSettingsURLString) else {
                        completion(.failure, Data("Application settings are unavailable".utf8))
                        return
                    }
                    UIApplication.shared.open(url) { opened in
                        completion(opened ? .success : .failure, Data())
                    }
                }
            default:
                throw PermissionModuleError("Unknown permissions method \(method)")
            }
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    private func status(_ kind: Int, _ completion: @escaping ModuleCompletion) {
        switch kind {
        case 1:
            finish(AVCaptureDevice.authorizationStatus(for: .video), completion)
        case 2:
            finish(AVCaptureDevice.authorizationStatus(for: .audio), completion)
        case 3:
            let value = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            if value == .limited {
                finish(status: 4, canAskAgain: false, completion)
            } else {
                finish(value, completion)
            }
        case 4:
            UNUserNotificationCenter.current().getNotificationSettings { settings in
                let status: Int
                switch settings.authorizationStatus {
                case .authorized, .provisional, .ephemeral: status = 1
                case .denied: status = 3
                case .notDetermined: status = 2
                @unknown default: status = 2
                }
                self.finish(status: status, canAskAgain: status == 2, completion)
            }
        case 5:
            finish(location.authorizationStatus, completion)
        case 6:
            finishContacts(CNContactStore.authorizationStatus(for: .contacts), completion)
        default:
            completion(.failure, Data("Unknown permission kind \(kind)".utf8))
        }
    }

    private func request(_ kind: Int, _ completion: @escaping ModuleCompletion) {
        switch kind {
        case 1:
            AVCaptureDevice.requestAccess(for: .video) { _ in self.status(kind, completion) }
        case 2:
            AVCaptureDevice.requestAccess(for: .audio) { _ in self.status(kind, completion) }
        case 3:
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { _ in self.status(kind, completion) }
        case 4:
            UNUserNotificationCenter.current().requestAuthorization(
                options: [.alert, .badge, .sound]
            ) { _, _ in self.status(kind, completion) }
        case 5:
            DispatchQueue.main.async {
                guard self.locationCompletion == nil else {
                    completion(.failure, Data("A location permission request is already active".utf8))
                    return
                }
                if self.location.authorizationStatus == .notDetermined {
                    self.locationCompletion = completion
                    self.locationRequestGeneration += 1
                    let generation = self.locationRequestGeneration
                    self.location.requestWhenInUseAuthorization()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 15) {
                        guard self.locationRequestGeneration == generation,
                              let pending = self.locationCompletion else { return }
                        self.locationCompletion = nil
                        pending(.failure, Data("Location permission request timed out".utf8))
                    }
                } else {
                    self.status(kind, completion)
                }
            }
        case 6:
            CNContactStore().requestAccess(for: .contacts) { _, _ in
                self.status(kind, completion)
            }
        default:
            completion(.failure, Data("Unknown permission kind \(kind)".utf8))
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard manager.authorizationStatus != .notDetermined,
              let completion = locationCompletion else { return }
        locationCompletion = nil
        locationRequestGeneration += 1
        finish(manager.authorizationStatus, completion)
    }

    private func finish(
        _ status: AVAuthorizationStatus,
        _ completion: @escaping ModuleCompletion
    ) {
        switch status {
        case .authorized: finish(status: 1, canAskAgain: false, completion)
        case .notDetermined: finish(status: 2, canAskAgain: true, completion)
        case .denied, .restricted: finish(status: 3, canAskAgain: false, completion)
        @unknown default: finish(status: 2, canAskAgain: false, completion)
        }
    }

    private func finish(
        _ status: PHAuthorizationStatus,
        _ completion: @escaping ModuleCompletion
    ) {
        switch status {
        case .authorized: finish(status: 1, canAskAgain: false, completion)
        case .limited: finish(status: 4, canAskAgain: false, completion)
        case .notDetermined: finish(status: 2, canAskAgain: true, completion)
        case .denied, .restricted: finish(status: 3, canAskAgain: false, completion)
        @unknown default: finish(status: 2, canAskAgain: false, completion)
        }
    }

    private func finish(
        _ status: CLAuthorizationStatus,
        _ completion: @escaping ModuleCompletion
    ) {
        switch status {
        case .authorizedAlways, .authorizedWhenInUse:
            finish(status: 1, canAskAgain: false, completion)
        case .notDetermined:
            finish(status: 2, canAskAgain: true, completion)
        case .denied, .restricted:
            finish(status: 3, canAskAgain: false, completion)
        @unknown default:
            finish(status: 2, canAskAgain: false, completion)
        }
    }

    private func finish(
        status: Int,
        canAskAgain: Bool,
        _ completion: @escaping ModuleCompletion
    ) {
        completion(.success, (try? WireMap.encode([
            "status": .integer(Int64(status)),
            "canAskAgain": .flag(canAskAgain),
        ])) ?? Data())
    }

    private func finishContacts(
        _ status: CNAuthorizationStatus,
        _ completion: @escaping ModuleCompletion
    ) {
        if #available(iOS 18.0, *), status == .limited {
            finish(status: 4, canAskAgain: false, completion)
            return
        }
        switch status {
        case .authorized:
            finish(status: 1, canAskAgain: false, completion)
        case .notDetermined:
            finish(status: 2, canAskAgain: true, completion)
        case .denied, .restricted:
            finish(status: 3, canAskAgain: false, completion)
        @unknown default:
            finish(status: 2, canAskAgain: false, completion)
        }
    }

    private func kind(_ payload: Data) throws -> Int {
        let values = try WireMap.decode(payload)
        guard case let .integer(kind)? = values["kind"] else {
            throw PermissionModuleError("Missing permission kind")
        }
        return Int(kind)
    }

    func close() {
        DispatchQueue.main.async {
            let pending = self.locationCompletion
            self.locationCompletion = nil
            self.locationRequestGeneration += 1
            pending?(.failure, Data("Permissions module closed".utf8))
        }
    }
}

private struct PermissionModuleError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
