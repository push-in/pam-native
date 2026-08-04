import Foundation
import MessageUI
import UIKit

final class SmsModule: NSObject, NativeModule, MFMessageComposeViewControllerDelegate {
    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        switch method {
        case "isAvailable":
            do {
                completion(.success, try WireMap.encode([
                    "available": .flag(MFMessageComposeViewController.canSendText()),
                ]))
            } catch {
                completion(.failure, Data(error.localizedDescription.utf8))
            }
        case "compose":
            compose(payload: payload, completion: completion)
        default:
            completion(.failure, Data("Unknown SMS method \(method)".utf8))
        }
    }

    private func compose(payload: Data, completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            guard case let .text(rawRecipients)? = values["recipients"] else {
                throw SmsError.invalidRecipients
            }
            let recipients = rawRecipients
                .split(separator: "\n", omittingEmptySubsequences: true)
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
            guard (1...50).contains(recipients.count),
                  recipients.allSatisfy({ $0.utf8.count <= 128 }) else {
                throw SmsError.invalidRecipients
            }
            let body: String
            if case let .text(value)? = values["body"] { body = value } else { body = "" }
            guard body.utf8.count <= 10_000 else { throw SmsError.bodyTooLong }
            guard MFMessageComposeViewController.canSendText() else {
                throw SmsError.unavailable
            }
            DispatchQueue.main.async { [weak self] in
                guard let self, let presenter = Self.presenter() else {
                    completion(.failure, Data(SmsError.noPresenter.localizedDescription.utf8))
                    return
                }
                let controller = MFMessageComposeViewController()
                controller.messageComposeDelegate = self
                controller.recipients = recipients
                controller.body = body
                presenter.present(controller, animated: true) {
                    completion(.success, Data())
                }
            }
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    func messageComposeViewController(
        _ controller: MFMessageComposeViewController,
        didFinishWith result: MessageComposeResult
    ) {
        controller.dismiss(animated: true)
    }

    private static func presenter() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        var controller = scenes.flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController
            ?? scenes.flatMap(\.windows).first(where: { !$0.isHidden })?.rootViewController
        while let presented = controller?.presentedViewController { controller = presented }
        return controller
    }
}

private enum SmsError: LocalizedError {
    case invalidRecipients
    case bodyTooLong
    case unavailable
    case noPresenter

    var errorDescription: String? {
        switch self {
        case .invalidRecipients: return "SMS requires between 1 and 50 valid recipients"
        case .bodyTooLong: return "SMS body exceeds 10000 bytes"
        case .unavailable: return "SMS is unavailable"
        case .noPresenter: return "No view controller is available for SMS"
        }
    }
}
