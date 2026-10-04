import Foundation
import QuickLook
import Photos
import UniformTypeIdentifiers
import UIKit

final class FilesModule: NSObject, NativeModule, ClosableNativeModule,
    UIDocumentPickerDelegate, UINavigationControllerDelegate, UIImagePickerControllerDelegate,
    QLPreviewControllerDataSource, QLPreviewControllerDelegate {
    private let queue = DispatchQueue(label: "dev.pam.native.files")
    private let root: URL
    private var pending: ModuleCompletion?
    private var pendingMultiple = false
    private var pendingLimit = 10
    private var captureType = 1
    private var previewURL: URL?
    private weak var previewController: QLPreviewController?
    private var nextDownloadId: Int64 = 1
    private var downloads: [Int64: (RemoteFileDownload, FileDownloadChannel)] = [:]

    override init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        root = base.appendingPathComponent("pam-files", isDirectory: true)
        super.init()
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        do {
            switch method {
            case "read":
                queue.async { self.read(payload, completion) }
            case "sha256":
                queue.async { self.sha256(payload, completion) }
            case "write":
                queue.async { self.write(payload, completion) }
            case "copyAsset":
                queue.async { self.copyAsset(payload, completion) }
            case "download":
                queue.async { self.download(payload, completion) }
            case "downloadStart":
                queue.async { self.startObservedDownload(payload, completion) }
            case "downloadNext":
                queue.async { self.nextObservedDownload(payload, completion) }
            case "downloadCancel":
                queue.async { self.cancelObservedDownload(payload, completion) }
            case "open":
                openFile(payload, completion: completion)
            case "stat":
                queue.async { self.stat(payload, completion) }
            case "list":
                queue.async { self.list(payload, completion) }
            case "delete":
                queue.async { self.delete(payload, completion) }
            case "pick":
                let values = try WireMap.decode(payload)
                let type = values["type"]?.integerValue ?? 4
                presentPicker(type: Int(type), multiple: false, limit: 1, completion: completion)
            case "pickMany":
                let values = try WireMap.decode(payload)
                let type = values["type"]?.integerValue ?? 4
                let limit = min(50, max(1, Int(values["limit"]?.integerValue ?? 10)))
                presentPicker(type: Int(type), multiple: true, limit: limit, completion: completion)
            case "importUri":
                importPhotoAsset(payload, completion: completion)
            case "capture":
                let values = try WireMap.decode(payload)
                presentCapture(type: Int(values["type"]?.integerValue ?? 1), completion: completion)
            default:
                throw FileModuleError("Unknown files method \(method)")
            }
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    private func sha256(_ payload: Data, _ completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            guard case let .text(path)? = values["path"] else { throw FileModuleError("Hash source path is required") }
            let digest = try PrivateFileSha256.digest(root: root, path: path)
            completion(.success, try WireMap.encode(["sha256": .text(digest)]))
        } catch { completion(.failure, Data(error.localizedDescription.utf8)) }
    }

    private func stat(_ payload: Data, _ completion: @escaping ModuleCompletion) {
        do {
            let file = try requiredPath(payload)
            var isDirectory: ObjCBool = false
            guard FileManager.default.fileExists(atPath: file.path, isDirectory: &isDirectory),
                  !isDirectory.boolValue else {
                throw FileModuleError("File does not exist")
            }
            completion(.success, try WireMap.encode(reference(file)))
        } catch { completion(.failure, Data(error.localizedDescription.utf8)) }
    }

    private func list(_ payload: Data, _ completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            let relative = values["path"]?.textValue ?? ""
            let directory = relative.isEmpty ? root : try resolve(relative)
            let urls = try FileManager.default.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
                options: [.skipsHiddenFiles]
            )
            let items = try urls
                .filter { try $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true }
                .sorted { $0.lastPathComponent.localizedCaseInsensitiveCompare($1.lastPathComponent) == .orderedAscending }
                .map { url -> [String: Any] in
                    let values = try url.resourceValues(forKeys: [.fileSizeKey])
                    return [
                        "path": relativePath(url),
                        "name": url.lastPathComponent,
                        "mimeType": mimeType(url),
                        "size": values.fileSize ?? 0,
                    ]
                }
            let data = try JSONSerialization.data(withJSONObject: items)
            completion(.success, try WireMap.encode([
                "items": .text(String(decoding: data, as: UTF8.self)),
            ]))
        } catch { completion(.failure, Data(error.localizedDescription.utf8)) }
    }

    private func delete(_ payload: Data, _ completion: @escaping ModuleCompletion) {
        do {
            let file = try requiredPath(payload)
            var isDirectory: ObjCBool = false
            guard FileManager.default.fileExists(atPath: file.path, isDirectory: &isDirectory),
                  !isDirectory.boolValue else {
                throw FileModuleError("File does not exist")
            }
            try FileManager.default.removeItem(at: file)
            completion(.success, Data())
        } catch { completion(.failure, Data(error.localizedDescription.utf8)) }
    }

    private func read(_ payload: Data, _ completion: @escaping ModuleCompletion) {
        do {
            let path = try requiredPath(payload)
            let data = try Data(contentsOf: path)
            guard data.count <= 1_048_576 else { throw FileModuleError("File exceeds bridge limit") }
            completion(.success, try WireMap.encode(["data": .text(data.base64EncodedString())]))
        } catch { completion(.failure, Data(error.localizedDescription.utf8)) }
    }

    private func write(_ payload: Data, _ completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            guard case let .text(path)? = values["path"],
                  case let .text(encoded)? = values["data"],
                  let data = Data(base64Encoded: encoded),
                  data.count <= 1_048_576 else {
                throw FileModuleError("Invalid file payload")
            }
            let destination = try resolve(path)
            try FileManager.default.createDirectory(
                at: destination.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try data.write(to: destination, options: .atomic)
            completion(.success, Data())
        } catch { completion(.failure, Data(error.localizedDescription.utf8)) }
    }

    private func copyAsset(_ payload: Data, _ completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            guard case let .text(assetPath)? = values["assetPath"],
                  case let .text(path)? = values["path"],
                  let bundledPath = try normalizedPamAssetPath("asset://\(assetPath)"),
                  let resourceRoot = Bundle.main.resourceURL else {
                throw FileModuleError("Invalid bundled asset payload")
            }
            let source = resourceRoot.appendingPathComponent(bundledPath, isDirectory: false)
            var isDirectory: ObjCBool = false
            guard FileManager.default.fileExists(atPath: source.path, isDirectory: &isDirectory),
                  !isDirectory.boolValue else {
                throw FileModuleError("Bundled asset does not exist")
            }
            let destination = try resolve(path)
            try FileManager.default.createDirectory(
                at: destination.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            let temporary = destination.deletingLastPathComponent()
                .appendingPathComponent("\(destination.lastPathComponent).tmp-\(UUID().uuidString)")
            defer { try? FileManager.default.removeItem(at: temporary) }
            try FileManager.default.copyItem(at: source, to: temporary)
            if FileManager.default.fileExists(atPath: destination.path) {
                _ = try FileManager.default.replaceItemAt(destination, withItemAt: temporary)
            } else {
                try FileManager.default.moveItem(at: temporary, to: destination)
            }
            completion(.success, try WireMap.encode(reference(destination)))
        } catch { completion(.failure, Data(error.localizedDescription.utf8)) }
    }

    private func download(_ payload: Data, _ completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            guard case let .text(rawURL)? = values["url"],
                  let url = URL(string: rawURL),
                  url.scheme?.lowercased() == "https",
                  url.host?.isEmpty == false,
                  url.user == nil,
                  url.password == nil,
                  case let .text(path)? = values["path"] else {
                throw FileModuleError("Download URL must be an absolute HTTPS URL without credentials")
            }
            let maximumBytes = values["maximumBytes"]?.integerValue ?? 64 * 1_024 * 1_024
            guard maximumBytes > 0, maximumBytes <= 256 * 1_024 * 1_024 else {
                throw FileModuleError("Invalid download size limit")
            }
            let headers = try downloadHeaders(values["headers"]?.textValue ?? "")
            let destination = try resolve(path)
            try FileManager.default.createDirectory(
                at: destination.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            RemoteFileDownload.start(
                url: url,
                destination: destination,
                root: root,
                maximumBytes: maximumBytes,
                headers: headers,
                completion: completion
            )
        } catch { completion(.failure, Data(error.localizedDescription.utf8)) }
    }

    private func startObservedDownload(_ payload: Data, _ completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            guard case let .text(rawURL)? = values["url"],
                  let url = URL(string: rawURL),
                  url.scheme?.lowercased() == "https",
                  url.host?.isEmpty == false,
                  url.user == nil, url.password == nil,
                  case let .text(path)? = values["path"] else {
                throw FileModuleError("Download URL must be an absolute HTTPS URL without credentials")
            }
            let maximumBytes = values["maximumBytes"]?.integerValue ?? 64 * 1_024 * 1_024
            guard maximumBytes > 0, maximumBytes <= 256 * 1_024 * 1_024 else {
                throw FileModuleError("Invalid download size limit")
            }
            let destination = try resolve(path)
            let headers = try downloadHeaders(values["headers"]?.textValue ?? "")
            try FileManager.default.createDirectory(
                at: destination.deletingLastPathComponent(), withIntermediateDirectories: true
            )
            let id = nextDownloadId
            nextDownloadId += 1
            let channel = FileDownloadChannel()
            let task = RemoteFileDownload.start(
                url: url,
                destination: destination,
                root: root,
                maximumBytes: maximumBytes,
                headers: headers,
                progress: { bytes, total in
                    channel.offer((try? WireMap.encode([
                        "state": .integer(1),
                        "bytesWritten": .integer(bytes),
                        "totalBytes": .integer(total),
                    ])) ?? Data())
                },
                completion: { status, payload in
                    let result: Data
                    if status == .success,
                       let reference = try? WireMap.decode(payload) {
                        var values = reference
                        values["state"] = .integer(2)
                        result = (try? WireMap.encode(values)) ?? Data()
                    } else {
                        result = (try? WireMap.encode([
                            "state": .integer(3),
                            "message": .text(String(decoding: payload, as: UTF8.self)),
                        ])) ?? Data()
                    }
                    channel.offer(result)
                }
            )
            downloads[id] = (task, channel)
            completion(.success, try WireMap.encode(["subscription": .integer(id)]))
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    private func nextObservedDownload(_ payload: Data, _ completion: @escaping ModuleCompletion) {
        do {
            let id = try downloadSubscription(payload)
            guard let (_, channel) = downloads[id] else {
                throw FileModuleError("File download not found")
            }
            channel.next(completion)
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    private func cancelObservedDownload(_ payload: Data, _ completion: @escaping ModuleCompletion) {
        do {
            let id = try downloadSubscription(payload)
            if let active = downloads.removeValue(forKey: id) {
                active.1.close()
                active.0.cancel()
            }
            completion(.success, Data())
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    private func downloadSubscription(_ payload: Data) throws -> Int64 {
        let values = try WireMap.decode(payload)
        guard case let .integer(id)? = values["subscription"], id > 0 else {
            throw FileModuleError("File download subscription is required")
        }
        return id
    }

    private func downloadHeaders(_ raw: String) throws -> [String: String] {
        if raw.isEmpty { return [:] }
        guard let data = raw.data(using: .utf8),
              let values = try JSONSerialization.jsonObject(with: data) as? [String: String],
              values.count <= 32 else {
            throw FileModuleError("Download request headers are invalid or unsafe")
        }
        let blocked: Set<String> = ["connection", "content-length", "host", "transfer-encoding"]
        for (name, value) in values {
            guard name.count <= 64,
                  name.range(of: "^[!#$%&'*+.^_`|~0-9A-Za-z-]+$", options: .regularExpression) != nil,
                  value.utf8.count <= 8_192,
                  !value.contains("\r"), !value.contains("\n"),
                  !blocked.contains(name.lowercased()) else {
                throw FileModuleError("Download request headers are invalid or unsafe")
            }
        }
        return values
    }

    private func openFile(_ payload: Data, completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            guard case let .text(path)? = values["path"] else {
                throw FileModuleError("File path is required")
            }
            let file = try resolve(path)
            var directory: ObjCBool = false
            guard FileManager.default.fileExists(atPath: file.path, isDirectory: &directory),
                  !directory.boolValue else {
                throw FileModuleError("File does not exist")
            }
            DispatchQueue.main.async {
                guard QLPreviewController.canPreview(file as NSURL) else {
                    completion(.failure, Data("No application can open this file type".utf8))
                    return
                }
                guard self.previewController == nil,
                      let presenter = Self.presenter() else {
                    completion(.failure, Data("Another file preview is active".utf8))
                    return
                }
                let controller = QLPreviewController()
                self.previewURL = file
                self.previewController = controller
                controller.dataSource = self
                controller.delegate = self
                presenter.present(controller, animated: true) {
                    completion(.success, Data())
                }
            }
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    func numberOfPreviewItems(in controller: QLPreviewController) -> Int {
        previewURL == nil ? 0 : 1
    }

    func previewController(
        _ controller: QLPreviewController, previewItemAt index: Int
    ) -> QLPreviewItem {
        (previewURL ?? URL(fileURLWithPath: "/dev/null")) as NSURL
    }

    func previewControllerDidDismiss(_ controller: QLPreviewController) {
        previewURL = nil
        previewController = nil
    }

    private func presentPicker(
        type: Int,
        multiple: Bool,
        limit: Int,
        completion: @escaping ModuleCompletion
    ) {
        DispatchQueue.main.async {
            guard self.pending == nil, let presenter = Self.presenter() else {
                completion(.failure, Data("Another picker is active".utf8))
                return
            }
            let types: [UTType] = switch type {
            case 1: [.image]
            case 2: [.movie]
            case 3: [.audio]
            case 5: [.image, .movie]
            default: [.item]
            }
            self.pending = completion
            self.pendingMultiple = multiple
            self.pendingLimit = limit
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: types)
            picker.delegate = self
            picker.allowsMultipleSelection = multiple
            presenter.present(picker, animated: true)
        }
    }

    private func presentCapture(type: Int, completion: @escaping ModuleCompletion) {
        DispatchQueue.main.async {
            guard self.pending == nil,
                  UIImagePickerController.isSourceTypeAvailable(.camera),
                  let presenter = Self.presenter() else {
                completion(.failure, Data("Camera is unavailable".utf8))
                return
            }
            self.pending = completion
            self.captureType = type
            let picker = UIImagePickerController()
            picker.delegate = self
            picker.sourceType = .camera
            picker.mediaTypes = [type == 2 ? UTType.movie.identifier : UTType.image.identifier]
            presenter.present(picker, animated: true)
        }
    }

    func documentPicker(
        _ controller: UIDocumentPickerViewController,
        didPickDocumentsAt urls: [URL]
    ) {
        guard !urls.isEmpty else { return finishFailure("No document was selected") }
        let multiple = pendingMultiple
        let selected = Array(urls.prefix(pendingLimit))
        queue.async {
            if multiple {
                self.importFiles(selected, completion: self.takePending())
            } else if let source = selected.first {
                self.importFile(source, completion: self.takePending())
            }
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        let multiple = pendingMultiple
        let completion = takePending()
        if multiple {
            let emptyItems = try? WireMap.encode(["items": .text("[]")])
            completion?(.success, emptyItems ?? Data())
        } else {
            completion?(.success, Data())
        }
    }

    func imagePickerController(
        _ picker: UIImagePickerController,
        didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
    ) {
        picker.dismiss(animated: true)
        let completion = takePending()
        queue.async {
            if self.captureType == 2, let url = info[.mediaURL] as? URL {
                self.importFile(url, completion: completion)
            } else if let image = info[.originalImage] as? UIImage,
                      let data = image.jpegData(compressionQuality: 0.92) {
                self.store(data, name: "capture.jpg", mime: "image/jpeg", completion: completion)
            } else {
                completion?(.failure, Data("Camera returned no media".utf8))
            }
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
        takePending()?(.success, Data())
    }

    private func importFile(_ source: URL, completion: ModuleCompletion?) {
        guard let completion else { return }
        let accessing = source.startAccessingSecurityScopedResource()
        defer { if accessing { source.stopAccessingSecurityScopedResource() } }
        do {
            let values = try source.resourceValues(forKeys: [.fileSizeKey])
            if let size = values.fileSize, size > 64 * 1_024 * 1_024 {
                throw FileModuleError("Selected file exceeds 64 MiB")
            }
            let data = try Data(contentsOf: source)
            guard data.count <= 64 * 1_024 * 1_024 else {
                throw FileModuleError("Selected file exceeds 64 MiB")
            }
            store(
                data,
                name: source.lastPathComponent,
                mime: UTType(filenameExtension: source.pathExtension)?.preferredMIMEType
                    ?? "application/octet-stream",
                completion: completion
            )
        } catch { completion(.failure, Data(error.localizedDescription.utf8)) }
    }

    private func importPhotoAsset(_ payload: Data, completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            guard [.authorized, .limited].contains(
                PHPhotoLibrary.authorizationStatus(for: .readWrite)
            ) else {
                throw FileModuleError("Photos permission is not granted")
            }
            guard case let .text(source)? = values["uri"],
                  let asset = PamPhotoAssetURI.asset(source),
                  let resource = PamPhotoAssetURI.primaryResource(for: asset) else {
                throw FileModuleError("Photo asset URI is invalid or unavailable")
            }
            let safeName = resource.originalFilename.replacingOccurrences(
                of: "[^A-Za-z0-9_.-]", with: "_", options: .regularExpression
            ).prefix(120)
            let name = safeName.isEmpty ? "photo-asset" : String(safeName)
            let relative = "imports/\(UUID().uuidString)-\(name)"
            let destination = try resolve(relative)
            try FileManager.default.createDirectory(
                at: destination.deletingLastPathComponent(), withIntermediateDirectories: true
            )
            let options = PHAssetResourceRequestOptions()
            options.isNetworkAccessAllowed = true
            PHAssetResourceManager.default().writeData(
                for: resource, toFile: destination, options: options
            ) { error in
                self.queue.async {
                    do {
                        if let error { throw error }
                        let metadata = try destination.resourceValues(
                            forKeys: [.isRegularFileKey, .fileSizeKey]
                        )
                        guard metadata.isRegularFile == true,
                              let size = metadata.fileSize,
                              size >= 0, size <= 64 * 1_024 * 1_024 else {
                            throw FileModuleError("Selected file exceeds 64 MiB")
                        }
                        let mime = UTType(resource.uniformTypeIdentifier)?.preferredMIMEType
                            ?? "application/octet-stream"
                        completion(.success, try WireMap.encode([
                            "path": .text(relative),
                            "name": .text(resource.originalFilename),
                            "mimeType": .text(mime),
                            "size": .integer(Int64(size)),
                        ]))
                    } catch {
                        try? FileManager.default.removeItem(at: destination)
                        completion(.failure, Data(error.localizedDescription.utf8))
                    }
                }
            }
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    private func importFiles(_ sources: [URL], completion: ModuleCompletion?) {
        guard let completion else { return }
        var imported: [URL] = []
        var totalBytes = 0
        do {
            let items = try sources.map { source -> [String: Any] in
                let remaining = 256 * 1_024 * 1_024 - totalBytes
                guard remaining > 0 else {
                    throw FileModuleError("Selected files exceed 256 MiB")
                }
                let item = try importFileReference(
                    source,
                    maximumBytes: min(64 * 1_024 * 1_024, remaining)
                )
                imported.append(item.url)
                totalBytes += item.size
                return item.reference
            }
            let data = try JSONSerialization.data(withJSONObject: items)
            completion(.success, try WireMap.encode([
                "items": .text(String(decoding: data, as: UTF8.self)),
            ]))
        } catch {
            imported.forEach { try? FileManager.default.removeItem(at: $0) }
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    private func importFileReference(
        _ source: URL,
        maximumBytes: Int
    ) throws -> (url: URL, reference: [String: Any], size: Int) {
        let accessing = source.startAccessingSecurityScopedResource()
        defer { if accessing { source.stopAccessingSecurityScopedResource() } }
        let values = try source.resourceValues(forKeys: [.fileSizeKey])
        if let size = values.fileSize, size > maximumBytes {
            throw FileModuleError(
                maximumBytes < 64 * 1_024 * 1_024
                    ? "Selected files exceed 256 MiB"
                    : "Selected file exceeds 64 MiB"
            )
        }
        let safe = source.lastPathComponent.replacingOccurrences(
            of: "[^A-Za-z0-9_.-]",
            with: "_",
            options: .regularExpression
        )
        let relative = "imports/\(UUID().uuidString)-\(safe)"
        let destination = try resolve(relative)
        try FileManager.default.createDirectory(
            at: destination.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        do {
            try FileManager.default.copyItem(at: source, to: destination)
        } catch {
            try? FileManager.default.removeItem(at: destination)
            throw error
        }
        let importedValues = try destination.resourceValues(forKeys: [.fileSizeKey])
        let importedSize = importedValues.fileSize ?? 0
        guard importedSize <= maximumBytes else {
            try? FileManager.default.removeItem(at: destination)
            throw FileModuleError(
                maximumBytes < 64 * 1_024 * 1_024
                    ? "Selected files exceed 256 MiB"
                    : "Selected file exceeds 64 MiB"
            )
        }
        let mime = UTType(filenameExtension: source.pathExtension)?.preferredMIMEType
            ?? "application/octet-stream"

        return (
            destination,
            [
                "path": relative,
                "name": source.lastPathComponent,
                "mimeType": mime,
                "size": importedSize,
            ],
            importedSize
        )
    }

    private func store(
        _ data: Data,
        name: String,
        mime: String,
        completion: ModuleCompletion?
    ) {
        guard let completion else { return }
        do {
            guard data.count <= 64 * 1_024 * 1_024 else {
                throw FileModuleError("Selected file exceeds 64 MiB")
            }
            let safe = name.replacingOccurrences(
                of: "[^A-Za-z0-9_.-]",
                with: "_",
                options: .regularExpression
            )
            let relative = "imports/\(UUID().uuidString)-\(safe)"
            let destination = try resolve(relative)
            try FileManager.default.createDirectory(
                at: destination.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try data.write(to: destination, options: .atomic)
            completion(.success, try WireMap.encode([
                "path": .text(relative),
                "name": .text(name),
                "mimeType": .text(mime),
                "size": .integer(Int64(data.count)),
            ]))
        } catch { completion(.failure, Data(error.localizedDescription.utf8)) }
    }

    private func requiredPath(_ payload: Data) throws -> URL {
        let values = try WireMap.decode(payload)
        guard case let .text(path)? = values["path"] else {
            throw FileModuleError("Missing file path")
        }
        return try resolve(path)
    }

    private func reference(_ url: URL) throws -> [String: WireValue] {
        let values = try url.resourceValues(forKeys: [.fileSizeKey])
        return [
            "path": .text(relativePath(url)),
            "name": .text(url.lastPathComponent),
            "mimeType": .text(mimeType(url)),
            "size": .integer(Int64(values.fileSize ?? 0)),
        ]
    }

    private func relativePath(_ url: URL) -> String {
        String(url.standardizedFileURL.path.dropFirst(root.standardizedFileURL.path.count + 1))
    }

    private func mimeType(_ url: URL) -> String {
        UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    }

    private func resolve(_ path: String) throws -> URL {
        guard !path.isEmpty, !path.hasPrefix("/") else { throw FileModuleError("Invalid file path") }
        let value = root.appendingPathComponent(path).standardizedFileURL
        guard value.path.hasPrefix(root.standardizedFileURL.path + "/") else {
            throw FileModuleError("File path escapes sandbox")
        }
        return value
    }

    private func takePending() -> ModuleCompletion? {
        defer {
            pending = nil
            pendingMultiple = false
            pendingLimit = 10
        }
        return pending
    }

    private func finishFailure(_ message: String) {
        takePending()?(.failure, Data(message.utf8))
    }

    private static func presenter() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        var controller = scenes.flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController
        while let presented = controller?.presentedViewController { controller = presented }
        return controller
    }

    func close() {
        queue.async {
            let active = Array(self.downloads.values)
            self.downloads.removeAll()
            for (task, channel) in active {
                channel.close()
                task.cancel()
            }
        }
        DispatchQueue.main.async { self.finishFailure("Files module closed") }
        DispatchQueue.main.async {
            self.previewController?.dismiss(animated: false)
            self.previewURL = nil
            self.previewController = nil
        }
    }
}

private struct FileModuleError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

final class FileDownloadChannel {
    private let lock = NSLock()
    private var events: [Data] = []
    private var waiter: ModuleCompletion?
    private var closed = false

    func next(_ completion: @escaping ModuleCompletion) {
        lock.lock()
        if closed {
            lock.unlock()
            completion(.failure, Data("Observation is closed".utf8))
        } else if !events.isEmpty {
            let event = events.removeFirst()
            lock.unlock()
            completion(.success, event)
        } else if waiter != nil {
            lock.unlock()
            completion(.failure, Data("Observation already has a pending read".utf8))
        } else {
            waiter = completion
            lock.unlock()
        }
    }

    func offer(_ payload: Data) {
        lock.lock()
        if closed {
            lock.unlock()
            return
        }
        let pending = waiter
        waiter = nil
        if pending == nil {
            if events.count >= 4 { events.removeFirst() }
            events.append(payload)
        }
        lock.unlock()
        pending?(.success, payload)
    }

    func close() {
        lock.lock()
        if closed {
            lock.unlock()
            return
        }
        closed = true
        events.removeAll()
        let pending = waiter
        waiter = nil
        lock.unlock()
        pending?(.failure, Data("Observation stopped".utf8))
    }
}

private final class RemoteFileDownload: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let destination: URL
    private let root: URL
    private let maximumBytes: Int64
    private let completion: ModuleCompletion
    private let progress: ((Int64, Int64) -> Void)?
    private var session: URLSession?
    private var finished = false
    private let finishLock = NSLock()
    private var lastProgressPercent: Int64 = -1

    private init(
        destination: URL,
        root: URL,
        maximumBytes: Int64,
        progress: ((Int64, Int64) -> Void)?,
        completion: @escaping ModuleCompletion
    ) {
        self.destination = destination
        self.root = root
        self.maximumBytes = maximumBytes
        self.progress = progress
        self.completion = completion
    }

    @discardableResult static func start(
        url: URL,
        destination: URL,
        root: URL,
        maximumBytes: Int64,
        headers: [String: String] = [:],
        progress: ((Int64, Int64) -> Void)? = nil,
        completion: @escaping ModuleCompletion
    ) -> RemoteFileDownload {
        let delegate = RemoteFileDownload(
            destination: destination,
            root: root,
            maximumBytes: maximumBytes,
            progress: progress,
            completion: completion
        )
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.httpShouldUsePipelining = true
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 120
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: queue)
        delegate.session = session
        var request = URLRequest(url: url)
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        session.downloadTask(with: request).resume()
        return delegate
    }

    func cancel() {
        finish(.failure, Data("Download cancelled".utf8))
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        finishLock.lock()
        let alreadyFinished = finished
        finishLock.unlock()
        if alreadyFinished { return }
        if totalBytesWritten > maximumBytes
            || (totalBytesExpectedToWrite > maximumBytes && totalBytesExpectedToWrite > 0) {
            downloadTask.cancel()
            finish(.failure, Data("Download exceeds configured size limit".utf8))
            return
        }
        let total = max(0, totalBytesExpectedToWrite)
        let percent = total > 0 ? (totalBytesWritten * 100) / total : 0
        if percent != lastProgressPercent {
            lastProgressPercent = percent
            progress?(totalBytesWritten, total)
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard let url = request.url,
              url.scheme?.lowercased() == "https",
              url.host?.isEmpty == false,
              url.user == nil,
              url.password == nil else {
            completionHandler(nil)
            finish(.failure, Data("Download redirect must use HTTPS without credentials".utf8))
            return
        }
        completionHandler(nil)
        finish(.failure, Data("Download redirects are not allowed".utf8))
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        do {
            guard let response = downloadTask.response as? HTTPURLResponse,
                  (200...299).contains(response.statusCode),
                  response.url?.scheme?.lowercased() == "https" else {
                throw FileModuleError(
                    "Download failed with HTTP \((downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0)"
                )
            }
            let size = try location.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size <= maximumBytes else {
                throw FileModuleError("Download exceeds configured size limit")
            }
            if FileManager.default.fileExists(atPath: destination.path) {
                _ = try FileManager.default.replaceItemAt(destination, withItemAt: location)
            } else {
                try FileManager.default.moveItem(at: location, to: destination)
            }
            let relative = String(destination.standardizedFileURL.path.dropFirst(root.standardizedFileURL.path.count + 1))
            finish(.success, try WireMap.encode([
                "path": .text(relative),
                "name": .text(destination.lastPathComponent),
                "mimeType": .text(response.mimeType
                    ?? UTType(filenameExtension: destination.pathExtension)?.preferredMIMEType
                    ?? "application/octet-stream"),
                "size": .integer(Int64(size)),
            ]))
        } catch { finish(.failure, Data(error.localizedDescription.utf8)) }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        if let error { finish(.failure, Data(error.localizedDescription.utf8)) }
    }

    private func finish(_ status: ModuleResultStatus, _ data: Data) {
        finishLock.lock()
        guard !finished else {
            finishLock.unlock()
            return
        }
        finished = true
        let active = session
        session = nil
        finishLock.unlock()
        completion(status, data)
        if status == .success {
            active?.finishTasksAndInvalidate()
        } else {
            active?.invalidateAndCancel()
        }
    }
}

private extension WireValue {
    var integerValue: Int64? {
        if case let .integer(value) = self { return value }
        return nil
    }

    var textValue: String? {
        if case let .text(value) = self { return value }
        return nil
    }
}
