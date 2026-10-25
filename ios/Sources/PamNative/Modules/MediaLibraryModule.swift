import Foundation
import Photos
import UIKit
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
        if method == "save" {
            save(payload, completion)
            return
        }
        if method == "refreshLimitedSelection" {
            DispatchQueue.main.async { self.refreshLimitedSelection(completion) }
            return
        }
        guard method == "assets" || method == "albums" || method == "thumbnail" else {
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
                if method == "thumbnail" {
                    try self.thumbnail(
                        id: self.text(values, "id"),
                        maxPx: PamMediaThumbnailCache.clampEdge(self.integer(values, "maxPx", fallback: 256)),
                        completion: completion
                    )
                    return
                }
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

    /// A JPEG of at most `maxPx` on its longer edge (videos: the poster
    /// frame), decoded by Photos at that size into a bounded disk cache.
    /// Runs on the module queue; completes with a `file://` URI.
    private func thumbnail(id: String, maxPx: Int, completion: @escaping ModuleCompletion) throws {
        guard !id.isEmpty,
              let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject,
              asset.mediaType == .image || asset.mediaType == .video else {
            completion(.failure, Data("Media asset not found".utf8))
            return
        }
        let cache = PamMediaThumbnailCache.shared
        let file = cache.file(for: "\(id):\(milliseconds(asset.modificationDate)):\(maxPx)")
        if cache.hit(file) {
            completion(.success, try WireMap.encode(["uri": .text(file.absoluteString)]))
            return
        }
        let options = PHImageRequestOptions()
        options.isNetworkAccessAllowed = true
        options.deliveryMode = .highQualityFormat
        options.resizeMode = .fast
        options.isSynchronous = true
        var produced: UIImage?
        var failure: String?
        PHImageManager.default().requestImage(
            for: asset,
            targetSize: CGSize(width: maxPx, height: maxPx),
            contentMode: .aspectFit,
            options: options
        ) { image, info in
            if info?[PHImageResultIsDegradedKey] as? Bool == true { return }
            produced = image
            failure = (info?[PHImageErrorKey] as? Error)?.localizedDescription
        }
        guard let image = produced,
              let data = PamMediaThumbnailCache.fitted(image, maxPx: maxPx).jpegData(compressionQuality: 0.82) else {
            completion(.failure, Data((failure ?? "Cannot create a thumbnail for this asset").utf8))
            return
        }
        try cache.store(data, at: file)
        completion(.success, try WireMap.encode(["uri": .text(file.absoluteString)]))
    }

    /// iOS 14+ limited Photos access: presents the limited-library picker so
    /// the user can change the selection; completes with whether access is
    /// still limited. Other states complete at once.
    private func refreshLimitedSelection(_ completion: @escaping ModuleCompletion) {
        func finish() {
            let limited = PHPhotoLibrary.authorizationStatus(for: .readWrite) == .limited
            completion(.success, (try? WireMap.encode(["limited": .flag(limited)])) ?? Data())
        }
        guard PHPhotoLibrary.authorizationStatus(for: .readWrite) == .limited,
              let presenter = Self.presenter() else {
            finish()
            return
        }
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: presenter) { _ in
            DispatchQueue.main.async { finish() }
        }
    }

    private static func presenter() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        var controller = scenes.flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController
        while let presented = controller?.presentedViewController { controller = presented }
        return controller
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
            // The renderer draws a Photos asset at the view size (videos: poster frame).
            "thumbnailUri": PamPhotoAssetURI.make(asset.localIdentifier),
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

    /// Adds a private image or video to Photos with add-only access, optionally into an album.
    private func save(_ payload: Data, _ completion: @escaping ModuleCompletion) {
        let values: [String: WireValue]
        do { values = try WireMap.decode(payload) } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
            return
        }
        let path = text(values, "path")
        let album = text(values, "album").trimmingCharacters(in: .whitespaces)
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pam-files", isDirectory: true).standardizedFileURL
        let file = base.appendingPathComponent(path).standardizedFileURL
        guard !path.isEmpty, !path.hasPrefix("/"), file.path.hasPrefix(base.path + "/"),
              FileManager.default.fileExists(atPath: file.path) else {
            completion(.failure, Data("File does not exist".utf8))
            return
        }
        var mime = text(values, "mimeType")
        if mime.isEmpty { mime = UTType(filenameExtension: file.pathExtension)?.preferredMIMEType ?? "" }
        let video = mime.hasPrefix("video/")
        guard video || mime.hasPrefix("image/") else {
            completion(.failure, Data("Only images and videos can be saved to the media library".utf8))
            return
        }
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                completion(.failure, Data("Photos add permission was denied".utf8))
                return
            }
            var identifier: String?
            PHPhotoLibrary.shared().performChanges({
                let request = video
                    ? PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: file)
                    : PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: file)
                identifier = request?.placeholderForCreatedAsset?.localIdentifier
                guard !album.isEmpty, let placeholder = request?.placeholderForCreatedAsset else { return }
                let options = PHFetchOptions()
                options.predicate = NSPredicate(format: "title = %@", album)
                let existing = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: options).firstObject
                let collection = existing.flatMap { PHAssetCollectionChangeRequest(for: $0) }
                    ?? PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: album)
                collection.addAssets([placeholder] as NSArray)
            }) { success, error in
                guard success, let identifier else {
                    completion(.failure, Data((error?.localizedDescription ?? "Cannot save to Photos").utf8))
                    return
                }
                let now = Int64(Date().timeIntervalSince1970 * 1_000)
                let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                let item: [String: Any] = [
                    "id": identifier,
                    "uri": PamPhotoAssetURI.make(identifier),
                    "name": file.lastPathComponent,
                    "mimeType": mime,
                    "size": size,
                    "createdAt": now,
                    "modifiedAt": now,
                    "albumTitle": album,
                ]
                do {
                    let data = try JSONSerialization.data(withJSONObject: item)
                    completion(.success, try WireMap.encode(["item": .text(String(decoding: data, as: UTF8.self))]))
                } catch {
                    completion(.failure, Data(error.localizedDescription.utf8))
                }
            }
        }
    }
}
