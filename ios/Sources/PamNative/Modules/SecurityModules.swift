import CryptoKit
import Foundation
import LocalAuthentication
import Security
import UIKit

/// Application text scale (`Accessibility::setTextScale()`), persisted in
/// UserDefaults. The values a process starts with stay in force until the
/// next launch, like Android `PamTextScale`.
enum PamTextScale {
    static let minMultiplier: Float = 0.5
    static let maxMultiplier: Float = 3
    private static let multiplierKey = "dev.pam.native.text-scale.multiplier"
    private static let capKey = "dev.pam.native.text-scale.maxSystemScale"
    private static let lock = NSLock()
    private static var appliedValues: (Float, Float)?

    static func isValidMultiplier(_ value: Float) -> Bool {
        value.isFinite && value >= minMultiplier && value <= maxMultiplier
    }

    static func isValidCap(_ value: Float) -> Bool { value.isFinite && (value == 0 || value >= 1) }

    static func stored() -> (Float, Float) {
        let defaults = UserDefaults.standard
        let multiplier = defaults.object(forKey: multiplierKey) == nil ? 1 : defaults.float(forKey: multiplierKey)
        let cap = defaults.float(forKey: capKey)
        return (isValidMultiplier(multiplier) ? multiplier : 1, isValidCap(cap) ? cap : 0)
    }

    static func applied() -> (Float, Float) {
        lock.lock()
        defer { lock.unlock() }
        if let appliedValues { return appliedValues }
        let values = stored()
        appliedValues = values
        return values
    }

    static func persist(multiplier: Float, maxSystemScale: Float) -> Bool {
        guard isValidMultiplier(multiplier), isValidCap(maxSystemScale) else { return false }
        UserDefaults.standard.set(multiplier, forKey: multiplierKey)
        UserDefaults.standard.set(maxSystemScale, forKey: capKey)
        return true
    }

    static func combine(multiplier: Float, cap: Float, system: Float) -> Float {
        let base = system.isFinite && system > 0 ? system : 1
        return multiplier * (cap > 0 ? min(base, cap) : base)
    }

    /// The scale text renders at for the Dynamic Type `system` multiplier.
    static func effective(system: Float) -> Float {
        let (multiplier, cap) = applied()
        return combine(multiplier: multiplier, cap: cap, system: system)
    }

    static func snapshot(system: Float) -> Data {
        let (multiplier, cap) = stored()
        let (appliedMultiplier, appliedCap) = applied()
        return (try? WireMap.encode([
            "multiplier": .decimal(Double(multiplier)),
            "maxSystemScale": .decimal(Double(cap)),
            "appliedMultiplier": .decimal(Double(appliedMultiplier)),
            "appliedMaxSystemScale": .decimal(Double(appliedCap)),
            "systemScale": .decimal(Double(system)),
            "effectiveScale": .decimal(Double(effective(system: system))),
        ])) ?? Data()
    }
}

/// Face ID / Touch ID through LocalAuthentication (`System\Biometrics`).
/// BiometricKind: 1 none, 2 fingerprint, 3 face, 4 iris, 5 other.
/// BiometricError: 1 none, 2 cancelled, 3 unavailable, 4 lockout.
final class BiometricsModule: NativeModule {
    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        switch method {
        case "status":
            DispatchQueue.main.async {
                completion(.success, (try? WireMap.encode(["available": .flag(Self.kind() != 1), "kind": .integer(Self.kind())])) ?? Data())
            }
        case "authenticate":
            guard let values = try? WireMap.decode(payload),
                  case let .text(title)? = values["title"], !title.isEmpty,
                  case let .text(cancel)? = values["cancelLabel"], !cancel.isEmpty else {
                completion(.failure, Data("Biometric prompt title and cancel label are required".utf8))
                return
            }
            let context = LAContext()
            context.localizedCancelTitle = cancel
            context.localizedFallbackTitle = ""
            var error: NSError?
            guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
                completion(.success, Self.result(false, error: Self.code(error)))
                return
            }
            context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: title) { passed, failure in
                completion(.success, Self.result(passed, error: passed ? 1 : Self.code(failure as NSError?)))
            }
        default:
            completion(.failure, Data("Unknown biometrics method \(method)".utf8))
        }
    }

    private static func kind() -> Int64 {
        let context = LAContext()
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else { return 1 }
        switch context.biometryType {
        case .touchID: return 2
        case .faceID: return 3
        case .none: return 1
        default: return 5
        }
    }

    private static func code(_ error: NSError?) -> Int64 {
        guard let error, error.domain == LAError.errorDomain else { return 3 }
        switch LAError.Code(rawValue: error.code) {
        case .userCancel?, .appCancel?, .systemCancel?, .userFallback?: return 2
        case .biometryLockout?: return 4
        default: return 3
        }
    }

    private static func result(_ passed: Bool, error: Int64) -> Data {
        (try? WireMap.encode(["authenticated": .flag(passed), "error": .integer(error)])) ?? Data()
    }
}

/// Keychain-backed small secrets (`Storage\SecureStorage`): this device only,
/// readable while unlocked; keys are stored as their SHA-256.
final class SecureStorageModule: NativeModule {
    private let service = "dev.pam.native.secure-storage"
    private static let maxKey = 256
    private static let maxValue = 65_536

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        guard let values = try? WireMap.decode(payload),
              case let .text(key)? = values["key"], !key.isEmpty, key.count <= Self.maxKey else {
            completion(.failure, Data("Secure storage key is required".utf8))
            return
        }
        let slot = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        switch method {
        case "get":
            let value = read(slot)
            completion(.success, (try? WireMap.encode(["found": .flag(value != nil), "value": .text(value ?? "")])) ?? Data())
        case "set":
            guard case let .text(value)? = values["value"], value.count <= Self.maxValue else {
                completion(.failure, Data("Secure storage value is invalid".utf8))
                return
            }
            delete(slot)
            var item = query(slot)
            item[kSecValueData as String] = Data(value.utf8)
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            let status = SecItemAdd(item as CFDictionary, nil)
            if status == errSecSuccess {
                completion(.success, Data())
            } else {
                completion(.failure, Data("Keychain write failed (\(status))".utf8))
            }
        case "delete":
            delete(slot)
            completion(.success, Data())
        default:
            completion(.failure, Data("Unknown secure storage method \(method)".utf8))
        }
    }

    private func query(_ slot: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: slot,
        ]
    }

    private func read(_ slot: String) -> String? {
        var item = query(slot)
        item[kSecReturnData as String] = true
        item[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(item as CFDictionary, &result) == errSecSuccess, let data = result as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    private func delete(_ slot: String) {
        SecItemDelete(query(slot) as CFDictionary)
    }
}
