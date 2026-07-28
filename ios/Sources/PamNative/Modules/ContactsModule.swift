import Contacts
import Foundation

final class ContactsModule: NativeModule, ClosableNativeModule, @unchecked Sendable {
    private let store = CNContactStore()
    private let queue = DispatchQueue(label: "pam.native.contacts", qos: .userInitiated)
    private let lock = NSLock()
    private var closed = false

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        guard method == "list" else {
            completion(.failure, Data("Unknown contacts method \(method)".utf8))
            return
        }
        guard Self.canReadContacts else {
            completion(.failure, Data("Contacts permission is not granted".utf8))
            return
        }
        queue.async {
            do {
                self.lock.lock()
                let isClosed = self.closed
                self.lock.unlock()
                guard !isClosed else { throw ContactsModuleError("Contacts module is closed") }
                completion(.success, try self.list(payload))
            } catch {
                completion(.failure, Data(error.localizedDescription.utf8))
            }
        }
    }

    private func list(_ payload: Data) throws -> Data {
        let values = try WireMap.decode(payload)
        let offset: Int
        let limit: Int
        if case let .integer(value)? = values["offset"] {
            offset = max(0, Int(value))
        } else {
            offset = 0
        }
        if case let .integer(value)? = values["limit"] {
            limit = min(250, max(1, Int(value)))
        } else {
            limit = 250
        }

        let keys: [CNKeyDescriptor] = [
            CNContactIdentifierKey as CNKeyDescriptor,
            CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
            CNContactPhoneNumbersKey as CNKeyDescriptor,
            CNContactEmailAddressesKey as CNKeyDescriptor,
        ]
        let request = CNContactFetchRequest(keysToFetch: keys)
        request.sortOrder = .userDefault
        var index = 0
        var contacts: [[String: Any]] = []
        var hasMore = false
        try store.enumerateContacts(with: request) { contact, stop in
            defer { index += 1 }
            guard index >= offset else { return }
            if contacts.count == limit {
                hasMore = true
                stop.pointee = true
                return
            }
            let displayName = CNContactFormatter.string(from: contact, style: .fullName)
                ?? [contact.givenName, contact.familyName]
                    .filter { !$0.isEmpty }
                    .joined(separator: " ")
            contacts.append([
                "id": contact.identifier,
                "displayName": displayName,
                "givenName": contact.givenName,
                "familyName": contact.familyName,
                "phoneNumbers": contact.phoneNumbers.map(\.value.stringValue),
                "emailAddresses": contact.emailAddresses.map { String($0.value) },
            ])
        }
        let json = try JSONSerialization.data(withJSONObject: contacts)
        guard let items = String(data: json, encoding: .utf8) else {
            throw ContactsModuleError("Cannot encode contacts")
        }
        return try WireMap.encode([
            "items": .text(items),
            "hasMore": .flag(hasMore),
        ])
    }

    func close() {
        lock.lock()
        closed = true
        lock.unlock()
    }

    private static var canReadContacts: Bool {
        let status = CNContactStore.authorizationStatus(for: .contacts)
        if status == .authorized {
            return true
        }
        if #available(iOS 18.0, *), status == .limited {
            return true
        }
        return false
    }
}

private struct ContactsModuleError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
