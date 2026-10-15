import Contacts
import ContactsUI
import Foundation
import UIKit

final class ContactsModule: NativeModule, ClosableNativeModule, @unchecked Sendable {
    private let store = CNContactStore()
    private let queue = DispatchQueue(label: "pam.native.contacts", qos: .userInitiated)
    private let lock = NSLock()
    private var closed = false
    /// The open "new contact" form (main thread only); failed on close.
    private var newContactForm: PamNewContactForm?

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        if method == "openNewContactForm" {
            DispatchQueue.main.async { self.openNewContactForm(completion) }
            return
        }
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

    /// Presents `CNContactViewController(forNewContact:)` in a navigation
    /// controller from the top presenter; created = the delegate's contact != nil.
    /// Needs no contacts permission.
    private func openNewContactForm(_ completion: @escaping ModuleCompletion) {
        lock.lock()
        let isClosed = closed
        lock.unlock()
        if isClosed {
            completion(.failure, Data("Contacts module is closed".utf8))
            return
        }
        guard newContactForm == nil else {
            completion(.failure, Data("A new contact form is already open".utf8))
            return
        }
        guard let presenter = Self.presenter() else {
            completion(.failure, Data("No view controller is available for the contact form".utf8))
            return
        }
        let form = PamNewContactForm(completion: completion) { [weak self] in
            self?.newContactForm = nil
        }
        newContactForm = form
        presenter.present(form.navigation, animated: true)
    }

    private static func presenter() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        var controller = scenes.flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController
            ?? scenes.flatMap(\.windows).first(where: { !$0.isHidden })?.rootViewController
        while let presented = controller?.presentedViewController, !presented.isBeingDismissed {
            controller = presented
        }
        return controller
    }

    func close() {
        lock.lock()
        closed = true
        lock.unlock()
        DispatchQueue.main.async {
            self.newContactForm?.interrupt()
            self.newContactForm = nil
        }
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

/// Owns one "new contact" form: its delegate answers exactly once.
private final class PamNewContactForm: NSObject, CNContactViewControllerDelegate {
    let navigation: UINavigationController
    private var completion: ModuleCompletion?
    private let finished: () -> Void

    init(completion: @escaping ModuleCompletion, finished: @escaping () -> Void) {
        let editor = CNContactViewController(forNewContact: nil)
        editor.contactStore = CNContactStore()
        navigation = UINavigationController(rootViewController: editor)
        // The editor's own Cancel/Done buttons must answer; a swipe-down would skip the delegate.
        navigation.isModalInPresentation = true
        self.completion = completion
        self.finished = finished
        super.init()
        editor.delegate = self
    }

    func contactViewController(_ viewController: CNContactViewController, didCompleteWith contact: CNContact?) {
        navigation.dismiss(animated: true)
        answer(.success, (try? WireMap.encode(["created": .flag(contact != nil)])) ?? Data())
    }

    func interrupt() {
        navigation.dismiss(animated: false)
        answer(.failure, Data("The contact form was interrupted".utf8))
    }

    private func answer(_ status: ModuleResultStatus, _ payload: Data) {
        guard let completion else { return }
        self.completion = nil
        completion(status, payload)
        finished()
    }
}
