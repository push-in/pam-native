import Foundation
import Photos
import UniformTypeIdentifiers

enum PamPhotoAssetURI {
    static func make(_ identifier: String) -> String {
        let token = Data(identifier.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "phasset://asset/\(token)"
    }

    static func identifier(_ source: String) -> String? {
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

    static func asset(_ source: String) -> PHAsset? {
        guard let identifier = identifier(source) else { return nil }
        return PHAsset.fetchAssets(withLocalIdentifiers: [identifier], options: nil).firstObject
    }

    static func primaryResource(for asset: PHAsset) -> PHAssetResource? {
        let resources = PHAssetResource.assetResources(for: asset)
        if asset.mediaType == .video {
            return resources.first { $0.type == .video || $0.type == .fullSizeVideo }
                ?? resources.first
        }
        return resources.first { $0.type == .photo || $0.type == .fullSizePhoto }
            ?? resources.first
    }
}

final class MediaLibraryModule: NativeModule {
    private let queue = DispatchQueue(label: "dev.pam.native.media-library", qos: .userInitiated)
    private let maximumPageSize = 200

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        guard method == "assets" || method == "albums" else {
            completion(.failure, Data("Unknown media-library method \(method)".utf8))
            return
        }
        guard [.authorized, .limited].contains(
            PHPhotoLibrary.authorizationStatus(for: .readWrite)
        ) else {
            completion(.failure, Data("Photos permission is not granted".utf8))
            return
        }
        queue.async {
            do {
                let values = try WireMap.decode(payload)
                let type = Int(self.integer(values, "type", fallback: 5))
                let result: [String: WireValue]
                switch method {
                case "assets":
                    result = try self.assets(
                        type: type,
                        albumId: self.text(values, "albumId"),
                        offset: max(0, Int(self.integer(values, "offset", fallback: 0))),
                        limit: min(self.maximumPageSize, max(1, Int(self.integer(values, "limit", fallback: 80))))
                    )
                default:
                    result = try self.albums(type: type)
                }
                completion(.success, try WireMap.encode(result))
            } catch {
                completion(.failure, Data(error.localizedDescription.utf8))
            }
        }
    }

    private func assets(type: Int, albumId: String, offset: Int, limit: Int) throws -> [String: WireValue] {
        let options = fetchOptions(type: type)
        let album: PHAssetCollection?
        if albumId.isEmpty {
            album = nil
        } else {
            guard let found = PHAssetCollection.fetchAssetCollections(
                withLocalIdentifiers: [albumId], options: nil
            ).firstObject else {
                return ["items": .text("[]"), "hasMore": .flag(false)]
            }
            album = found
        }
        let result = album.map { PHAsset.fetchAssets(in: $0, options: options) }
            ?? PHAsset.fetchAssets(with: options)
        let end = min(result.count, offset > Int.max - limit ? Int.max : offset + limit)
        var items: [[String: Any]] = []
        if offset < end {
            for index in offset..<end {
                let asset = result.object(at: index)
                let related = album ?? firstAlbum(containing: asset)
                items.append(assetRow(asset, album: related))
            }
        }
        return [
            "items": .text(try json(items)),
            "hasMore": .flag(end < result.count),
        ]
    }

    private func albums(type: Int) throws -> [String: WireValue] {
        var rows: [[String: Any]] = []
        for collectionType in [PHAssetCollectionType.album, .smartAlbum] {
            let collections = PHAssetCollection.fetchAssetCollections(
                with: collectionType, subtype: .any, options: nil
            )
            collections.enumerateObjects { album, _, _ in
                let assets = PHAsset.fetchAssets(in: album, options: self.fetchOptions(type: type))
                guard assets.count > 0 else { return }
                rows.append([
                    "id": album.localIdentifier,
                    "title": album.localizedTitle ?? "",
                    "count": assets.count,
                    "coverUri": PamPhotoAssetURI.make(assets.object(at: 0).localIdentifier),
                ])
            }
        }
        rows.sort {
            let first = $0["title"] as? String ?? ""
            let second = $1["title"] as? String ?? ""
            return first.localizedStandardCompare(second) == .orderedAscending
        }
        return ["items": .text(try json(rows))]
    }

    private func fetchOptions(type: Int) -> PHFetchOptions {
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        switch type {
        case 1:
            options.predicate = NSPredicate(format: "mediaType == %d", PHAssetMediaType.image.rawValue)
        case 2:
            options.predicate = NSPredicate(format: "mediaType == %d", PHAssetMediaType.video.rawValue)
        default:
            // Match Android's media-library query: other picker types include visual media.
            options.predicate = NSCompoundPredicate(orPredicateWithSubpredicates: [
                NSPredicate(format: "mediaType == %d", PHAssetMediaType.image.rawValue),
                NSPredicate(format: "mediaType == %d", PHAssetMediaType.video.rawValue),
            ])
        }
        return options
    }

    private func firstAlbum(containing asset: PHAsset) -> PHAssetCollection? {
        PHAssetCollection.fetchAssetCollectionsContaining(
            asset, with: .album, options: nil
        ).firstObject
    }

    private func assetRow(_ asset: PHAsset, album: PHAssetCollection?) -> [String: Any] {
        let resource = PamPhotoAssetURI.primaryResource(for: asset)
        let mime = resource.flatMap { UTType($0.uniformTypeIdentifier)?.preferredMIMEType }
            ?? (asset.mediaType == .video ? "video/mp4" : "image/jpeg")
        return [
            "id": asset.localIdentifier,
            "uri": PamPhotoAssetURI.make(asset.localIdentifier),
            "name": resource?.originalFilename ?? "",
            "mimeType": mime,
            "width": asset.pixelWidth,
            "height": asset.pixelHeight,
            "durationMs": max(0, Int64(asset.duration * 1_000)),
            "size": 0,
            "createdAt": milliseconds(asset.creationDate),
            "modifiedAt": milliseconds(asset.modificationDate),
            "albumId": album?.localIdentifier ?? "",
            "albumTitle": album?.localizedTitle ?? "",
            "favorite": asset.isFavorite,
        ]
    }

    private func milliseconds(_ date: Date?) -> Int64 {
        guard let date else { return 0 }
        return max(0, Int64(date.timeIntervalSince1970 * 1_000))
    }

    private func json(_ rows: [[String: Any]]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: rows)
        return String(decoding: data, as: UTF8.self)
    }

    private func integer(_ values: [String: WireValue], _ key: String, fallback: Int64) -> Int64 {
        if case let .integer(value)? = values[key] { return value }
        return fallback
    }

    private func text(_ values: [String: WireValue], _ key: String) -> String {
        if case let .text(value)? = values[key] { return value }
        return ""
    }
}
