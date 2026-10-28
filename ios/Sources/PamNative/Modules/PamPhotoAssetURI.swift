import Foundation
import Photos
import UIKit
import UniformTypeIdentifiers

public enum PamPhotoAssetURI {
    public static func make(_ identifier: String) -> String {
        let token = Data(identifier.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "phasset://asset/\(token)"
    }

    public static func identifier(_ source: String) -> String? {
        guard let url = URL(string: source), url.scheme == "phasset",
              url.host == "asset", url.pathComponents.count == 2 else { return nil }
        let token = url.lastPathComponent
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let padded = token + String(repeating: "=", count: (4 - token.count % 4) % 4)
        guard let data = Data(base64Encoded: padded),
              let identifier = String(data: data, encoding: .utf8),
              !identifier.isEmpty else { return nil }
        return identifier
    }

    public static func asset(_ source: String) -> PHAsset? {
        guard let identifier = identifier(source) else { return nil }
        return PHAsset.fetchAssets(withLocalIdentifiers: [identifier], options: nil).firstObject
    }

    public static func primaryResource(for asset: PHAsset) -> PHAssetResource? {
        let resources = PHAssetResource.assetResources(for: asset)
        if asset.mediaType == .video {
            return resources.first { $0.type == .video || $0.type == .fullSizeVideo }
                ?? resources.first
        }
        return resources.first { $0.type == .photo || $0.type == .fullSizePhoto }
            ?? resources.first
    }
}

