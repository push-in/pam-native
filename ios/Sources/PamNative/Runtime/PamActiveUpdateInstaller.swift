import CryptoKit
import Foundation

/// Selects an app-private OTA slot previously authenticated by the PHP update API.
public enum PamActiveUpdateInstaller {
    private static let maximumBundleBytes = 256 * 1024 * 1024

    public static func resolve(embeddedEntry: URL) -> URL {
        let manager = FileManager.default
        guard let documents = manager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return embeddedEntry
        }
        let updates = documents.appendingPathComponent("pam/updates", isDirectory: true)
        let bundle = updates.appendingPathComponent("active.bundle", isDirectory: false)
        let metadata = updates.appendingPathComponent("active.json", isDirectory: false)
        guard manager.fileExists(atPath: bundle.path), manager.fileExists(atPath: metadata.path) else {
            return embeddedEntry
        }
        // An OTA slot patches the app bundle it was activated over. Once an
        // app update ships a different embedded bundle, that bundle wins: the
        // slot is discarded instead of masking the update.
        let embeddedRelease = embeddedReleaseIdentity(embeddedEntry: embeddedEntry)
        let base = updates.appendingPathComponent(baseStamp, isDirectory: false)
        let recordedBase = (try? String(contentsOf: base, encoding: .utf8))?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if recordedBase.isEmpty {
            try? embeddedRelease.write(to: base, atomically: true, encoding: .utf8)
        } else if recordedBase != embeddedRelease {
            discardSupersededUpdates(updates: updates, documents: documents, manager: manager)
            return embeddedEntry
        }

        do {
            let manifestData = try Data(contentsOf: metadata, options: [.mappedIfSafe])
            guard manifestData.count <= 64 * 1024,
                  let manifest = try JSONSerialization.jsonObject(with: manifestData) as? [String: Any],
                  manifest["version"] as? Int == 1,
                  let expected = manifest["bundleSha256"] as? String,
                  expected.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
                throw UpdateError.invalidManifest
            }
            let values = try bundle.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            guard values.isRegularFile == true, let size = values.fileSize,
                  (1...maximumBundleBytes).contains(size) else {
                throw UpdateError.invalidBundle
            }
            let data = try Data(contentsOf: bundle, options: [.mappedIfSafe])
            let actual = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            guard actual == expected else { throw UpdateError.invalidBundle }
            let destination = documents.appendingPathComponent("pam/ota-releases/\(expected)", isDirectory: true)
            let entry = destination.appendingPathComponent("index.php", isDirectory: false)
            if manager.fileExists(atPath: entry.path) { return entry }
            return try PamDevBundle.extract(data, to: destination, maximumBytes: maximumBundleBytes)
        } catch {
            quarantine(updates: updates, manager: manager)
            return embeddedEntry
        }
    }

    private static let baseStamp = "active.base"

    /// Content digest the CLI writes beside the embedded entry (manifest.sha256).
    private static func embeddedReleaseIdentity(embeddedEntry: URL) -> String {
        let manifest = embeddedEntry.deletingLastPathComponent()
            .appendingPathComponent("manifest.sha256", isDirectory: false)
        let digest = (try? String(contentsOf: manifest, encoding: .utf8))?
            .split(whereSeparator: \.isNewline).first.map(String.init)?
            .trimmingCharacters(in: .whitespaces) ?? ""
        if !digest.isEmpty { return digest }
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? ""
        let build = info?["CFBundleVersion"] as? String ?? ""
        return "\(version)+\(build)"
    }

    private static func discardSupersededUpdates(updates: URL, documents: URL, manager: FileManager) {
        for item in (try? manager.contentsOfDirectory(at: updates, includingPropertiesForKeys: nil)) ?? [] {
            try? manager.removeItem(at: item)
        }
        try? manager.removeItem(at: documents.appendingPathComponent("pam/ota-releases", isDirectory: true))
    }

    private static func quarantine(updates: URL, manager: FileManager) {
        for name in ["bundle", "json"] {
            let active = updates.appendingPathComponent("active.\(name)")
            let failed = updates.appendingPathComponent("failed.\(name)")
            try? manager.removeItem(at: failed)
            try? manager.moveItem(at: active, to: failed)
        }
    }

    private enum UpdateError: Error {
        case invalidManifest
        case invalidBundle
    }
}
