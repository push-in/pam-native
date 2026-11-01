import CryptoKit
import Foundation
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

    /// React Native `RCTFontSizeMultiplier`: the Dynamic Type multiplier of
    /// each content size category. `UIFontMetrics.scaledValue(for: 1)` rounds
    /// to whole points, so every category below the accessibility sizes read
    /// 1 and Larger Text (XL, XXL, XXXL) never reached the app.
    static func systemMultiplier(_ category: UIContentSizeCategory) -> Float {
        switch category {
        case .extraSmall: return 0.823
        case .small: return 0.882
        case .medium: return 0.941
        case .large: return 1.0
        case .extraLarge: return 1.118
        case .extraExtraLarge: return 1.235
        case .extraExtraExtraLarge: return 1.353
        case .accessibilityMedium: return 1.786
        case .accessibilityLarge: return 2.143
        case .accessibilityExtraLarge: return 2.643
        case .accessibilityExtraExtraLarge: return 3.143
        case .accessibilityExtraExtraExtraLarge: return 3.571
        default: return 1.0
        }
    }

    /// The device multiplier for `traits` (the screen's when nil).
    static func systemMultiplier(traits: UITraitCollection? = nil) -> Float {
        systemMultiplier((traits ?? UIScreen.main.traitCollection).preferredContentSizeCategory)
    }
}

public extension PamRuntime {
    /// Text scale a host passes to `start`/`updateViewport`: the Dynamic Type
    /// multiplier of `traits` (React Native's table) with the application's
    /// stored multiplier and cap applied.
    static func deviceTextScale(traits: UITraitCollection? = nil) -> Float {
        PamTextScale.effective(system: PamTextScale.systemMultiplier(traits: traits))
    }
}

extension PamTextScale {

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
