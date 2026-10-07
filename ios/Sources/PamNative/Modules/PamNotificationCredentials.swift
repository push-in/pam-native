import CryptoKit
import Foundation
import Security

/// Per-account bearer tokens for notification action endpoints, so an inline
/// reply or mark-as-read is sent as the account that received the push even
/// while PHP is not running (`{credential:user_id}` placeholders).
///
/// Tokens live in the Keychain (`AfterFirstUnlockThisDeviceOnly`, never
/// synced or backed up) under a SHA-256 of the normalized account id; a token
/// only ever leaves this store into the Authorization header of an action.
public enum PamNotificationCredentials {
    /// Backing store; replaced in tests to keep the host Keychain untouched.
    protocol Store {
        func read(_ slot: String) -> String?
        func write(_ slot: String, _ token: String) throws
        func delete(_ slot: String)
        func deleteAll()
    }

    static let maxAccount = 128
    static let maxToken = 8_192
    private static let lock = NSLock()
    static var store: Store = KeychainStore()

    static func set(account: String, token: String) throws {
        let id = normalize(account)
        guard !id.isEmpty, id.utf8.count <= maxAccount else { throw NotificationsError("Invalid credential account") }
        guard !token.isEmpty, token.utf8.count <= maxToken, !token.contains("\r"), !token.contains("\n") else {
            throw NotificationsError("Invalid credential token")
        }
        lock.lock()
        defer { lock.unlock() }
        try store.write(slot(id), token)
    }

    static func remove(account: String) {
        let id = normalize(account)
        guard !id.isEmpty else { return }
        lock.lock()
        defer { lock.unlock() }
        store.delete(slot(id))
    }

    /// Replaces every stored credential with `tokens` (account id to token).
    static func replace(_ tokens: [String: String]) throws {
        lock.lock()
        store.deleteAll()
        lock.unlock()
        for (account, token) in tokens {
            try set(account: account, token: token)
        }
    }

    /// The token of `account`, or nil when that account has none on this device.
    static func token(_ account: String) -> String? {
        let id = normalize(account)
        guard !id.isEmpty else { return nil }
        lock.lock()
        defer { lock.unlock() }
        return store.read(slot(id)).flatMap { $0.isEmpty ? nil : $0 }
    }

    static func normalize(_ account: String) -> String {
        account.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    static func slot(_ normalized: String) -> String {
        SHA256.hash(data: Data(normalized.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    struct KeychainStore: Store {
        let service = "dev.pam.native.notification-credentials"

        private func query(_ slot: String? = nil) -> [String: Any] {
            var query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
            ]
            if let slot { query[kSecAttrAccount as String] = slot }
            return query
        }

        func read(_ slot: String) -> String? {
            var item = query(slot)
            item[kSecReturnData as String] = true
            item[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: CFTypeRef?
            guard SecItemCopyMatching(item as CFDictionary, &result) == errSecSuccess, let data = result as? Data else {
                return nil
            }
            return String(data: data, encoding: .utf8)
        }

        func write(_ slot: String, _ token: String) throws {
            delete(slot)
            var item = query(slot)
            item[kSecValueData as String] = Data(token.utf8)
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let status = SecItemAdd(item as CFDictionary, nil)
            guard status == errSecSuccess else { throw NotificationsError("Keychain write failed (\(status))") }
        }

        func delete(_ slot: String) {
            SecItemDelete(query(slot) as CFDictionary)
        }

        func deleteAll() {
            SecItemDelete(query() as CFDictionary)
        }
    }
}
