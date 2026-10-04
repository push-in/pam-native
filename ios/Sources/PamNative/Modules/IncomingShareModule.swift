import Foundation
import UIKit
import UniformTypeIdentifiers

private enum SharedInboxItemKind: Int {
    case text = 1
    case url = 2
    case file = 3
}

final class IncomingShareModule: NativeModule, ClosableNativeModule {
    private let queue = DispatchQueue(label: "dev.pam.native.incoming-share")
    private let defaults: UserDefaults?
    private let groupRoot: URL?
    private let privateRoot: URL
    private var waiter: ModuleCompletion? = nil
    private var activationObserver: NSObjectProtocol? = nil
    private var closed = false

    init(
        groupName: String? = nil,
        groupRoot: URL? = nil,
        privateRoot: URL? = nil
    ) {
        let identifier = Bundle.main.bundleIdentifier ?? ""
        let name = groupName ?? "group.\(identifier).pam-native"
        defaults = UserDefaults(suiteName: name)
        self.groupRoot = groupRoot
            ?? FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: name)
        self.privateRoot = privateRoot
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("pam-files", isDirectory: true)
        activationObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: nil
        ) { [weak self] _ in
            self?.queue.async { self?.deliverPending() }
        }
    }

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        guard method == "initial" || method == "next" else {
            completion(.failure, Data("Unknown incoming-share method \(method)".utf8))
            return
        }
        queue.async {
            guard !self.closed else {
                completion(.failure, Data("Incoming-share module is closed".utf8))
                return
            }
            if method == "next" && self.waiter != nil {
                completion(.failure, Data("Only one incoming-share listener can wait at a time".utf8))
                return
            }
            do {
                if let share = try self.nextShare() {
                    completion(.success, share)
                } else if method == "initial" {
                    completion(.success, try self.emptyPayload())
                } else {
                    self.waiter = completion
                }
            } catch {
                completion(.failure, Data(error.localizedDescription.utf8))
            }
        }
    }

    func close() {
        queue.async {
            guard !self.closed else { return }
            self.closed = true
            if let observer = self.activationObserver {
                NotificationCenter.default.removeObserver(observer)
                self.activationObserver = nil
            }
            let pending = self.waiter
            self.waiter = nil
            pending?(.failure, Data("Incoming-share module closed".utf8))
        }
    }

    private func deliverPending() {
        guard let pending = waiter, !closed else { return }
        do {
            guard let share = try nextShare() else { return }
            waiter = nil
            pending(.success, share)
        } catch {
            waiter = nil
            pending(.failure, Data(error.localizedDescription.utf8))
        }
    }

    private func nextShare() throws -> Data? {
        guard let defaults,
              let rows = defaults.array(forKey: "pam.share.items") as? [[String: Any]],
              let first = rows.first else { return nil }
        let shareID = first["shareId"] as? String
        let time = (first["createdAtMillis"] as? NSNumber)?.int64Value ?? 0
        let group = Array(rows.prefix {
            if let shareID { return ($0["shareId"] as? String) == shareID }
            return (($0["createdAtMillis"] as? NSNumber)?.int64Value ?? 0) == time
        })
        var text = ""
        var mimeType = ""
        var files: [[String: Any]] = []
        var copied: [URL] = []
        var sources: [URL] = []
        do {
            for row in group {
                let kind = SharedInboxItemKind(
                    rawValue: (row["kind"] as? NSNumber)?.intValue ?? 0
                )
                let value = (row["value"] as? String) ?? ""
                let rawType = (row["mimeType"] as? String) ?? ""
                let normalizedType = UTType(rawType)?.preferredMIMEType ?? rawType
                switch kind {
                case .some(.text), .some(.url):
                    if text.utf8.count < 65_536 {
                        let separator = text.isEmpty ? "" : "\n"
                        text += boundedText(
                            separator + value,
                            maximumBytes: 65_536 - text.utf8.count
                        )
                    }
                    if mimeType.isEmpty { mimeType = normalizedType }
                case .some(.file):
                    guard files.count < 10 else { continue }
                    let item = try copyFile(
                        named: value,
                        displayName: (row["name"] as? String) ?? value,
                        mimeType: normalizedType
                    )
                    files.append(item.reference)
                    copied.append(item.destination)
                    sources.append(item.source)
                    if mimeType.isEmpty { mimeType = normalizedType }
                default:
                    continue
                }
            }
            let payload = try WireMap.encode([
                "available": .flag(!text.isEmpty || !files.isEmpty),
                "text": .text(text),
                "subject": .text(boundedText((first["subject"] as? String) ?? "", maximumBytes: 4_096)),
                "mimeType": .text(mimeType),
                "files": .text(try json(files)),
            ])
            let remaining = Array(rows.dropFirst(group.count))
            if remaining.isEmpty {
                defaults.removeObject(forKey: "pam.share.items")
            } else {
                defaults.set(remaining, forKey: "pam.share.items")
            }
            sources.forEach { try? FileManager.default.removeItem(at: $0) }
            return payload
        } catch {
            copied.forEach { try? FileManager.default.removeItem(at: $0) }
            throw error
        }
    }

    private func copyFile(
        named name: String, displayName: String, mimeType: String
    ) throws -> (source: URL, destination: URL, reference: [String: Any]) {
        guard let groupRoot,
              !name.isEmpty,
              name.range(of: "^[A-Za-z0-9._-]{1,120}$", options: .regularExpression) != nil,
              name != ".", name != ".." else {
            throw IncomingShareError("Shared file is unavailable")
        }
        let source = groupRoot.appendingPathComponent("pam-share-inbox", isDirectory: true)
            .appendingPathComponent(name, isDirectory: false)
        let values = try source.resourceValues(
            forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey]
        )
        guard values.isRegularFile == true,
              values.isSymbolicLink != true,
              let size = values.fileSize,
              size >= 0, size <= 64 * 1_024 * 1_024 else {
            throw IncomingShareError("Shared file exceeds 64 MiB or is not regular")
        }
        let suffix = UTType(mimeType: mimeType)?.preferredFilenameExtension ?? "bin"
        let relative = "imports/incoming-\(UUID().uuidString).\(suffix)"
        let destination = privateRoot.appendingPathComponent(relative)
        try FileManager.default.createDirectory(
            at: destination.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        try FileManager.default.copyItem(at: source, to: destination)
        let copiedSize = try destination.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? -1
        guard copiedSize == size else {
            try? FileManager.default.removeItem(at: destination)
            throw IncomingShareError("Shared file changed during import")
        }
        return (source, destination, [
            "path": relative,
            "name": boundedText(displayName, maximumBytes: 255),
            "mimeType": mimeType.isEmpty ? "application/octet-stream" : mimeType,
            "size": size,
        ])
    }

    private func emptyPayload() throws -> Data {
        try WireMap.encode([
            "available": .flag(false),
            "text": .text(""),
            "subject": .text(""),
            "mimeType": .text(""),
            "files": .text("[]"),
        ])
    }

    private func json(_ rows: [[String: Any]]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: rows)
        return String(decoding: data, as: UTF8.self)
    }

    private func boundedText(_ value: String, maximumBytes: Int) -> String {
        var result = ""
        var used = 0
        for character in value {
            let bytes = String(character).utf8.count
            if used + bytes > maximumBytes { break }
            result.append(character)
            used += bytes
        }
        return result
    }
}

private struct IncomingShareError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
