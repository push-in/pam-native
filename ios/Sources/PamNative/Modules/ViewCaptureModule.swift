import Foundation
import UIKit

/// `ViewCapture::capture()`: renders a mounted view (found by its `nativeRef`)
/// and its subtree into a private PNG/JPEG under `pam-files`. The layer tree is
/// rendered with the view's own content only, so a view kept invisible by an
/// ancestor (opacity 0, off screen, under other content) captures as it would
/// look on screen. Rendering happens on the main thread, encoding on a queue;
/// each side is capped at 4096 pixels.
final class ViewCaptureModule: NativeModule {
    static let formatJpeg: Int64 = 2
    static let maxSide: CGFloat = 4096
    private let queue = DispatchQueue(label: "dev.pam.native.view-capture", qos: .userInitiated)
    private let root: URL
    private let findView: (String) -> UIView?

    init(findView: @escaping (String) -> UIView? = ViewCaptureModule.mountedView(_:)) {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        root = base.appendingPathComponent("pam-files", isDirectory: true)
        self.findView = findView
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        guard method == "capture" else {
            completion(.failure, Data("Unknown view capture method \(method)".utf8))
            return
        }
        guard let values = try? WireMap.decode(payload) else {
            completion(.failure, Data("Invalid view capture request".utf8))
            return
        }
        let ref = values["ref"]?.captureText ?? ""
        let pixelRatio = values["pixelRatio"]?.captureNumber ?? 0
        let jpeg = values["format"]?.captureInteger == Self.formatJpeg
        let quality = max(1, min(100, values["quality"]?.captureInteger ?? 92))
        let directory = values["directory"]?.captureText ?? ""
        DispatchQueue.main.async {
            let image: UIImage
            do {
                image = try self.render(ref: ref, pixelRatio: pixelRatio)
            } catch {
                completion(.failure, Data(error.localizedDescription.utf8))
                return
            }
            self.queue.async {
                do {
                    let output = try self.write(image, directory: directory, jpeg: jpeg, quality: Int(quality))
                    let size = (try? output.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                    completion(.success, try WireMap.encode([
                        "path": .text(output.path.replacingOccurrences(of: self.root.path + "/", with: "")),
                        "name": .text(output.lastPathComponent),
                        "size": .integer(Int64(size)),
                        "width": .integer(Int64((image.size.width * image.scale).rounded())),
                        "height": .integer(Int64((image.size.height * image.scale).rounded())),
                        "mimeType": .text(jpeg ? "image/jpeg" : "image/png"),
                    ]))
                } catch {
                    completion(.failure, Data(error.localizedDescription.utf8))
                }
            }
        }
    }

    private func render(ref: String, pixelRatio: Double) throws -> UIImage {
        guard !ref.isEmpty else { throw ViewCaptureError("A nativeRef is required.") }
        guard let view = findView(ref) else { throw ViewCaptureError("No mounted view has nativeRef \(ref).") }
        let bounds = view.bounds
        guard bounds.width > 0, bounds.height > 0 else { throw ViewCaptureError("The view \(ref) has not been laid out.") }
        let format = UIGraphicsImageRendererFormat()
        format.scale = Self.captureScale(size: bounds.size, pixelRatio: pixelRatio, screenScale: view.window?.screen.scale ?? UIScreen.main.scale)
        format.opaque = false
        return UIGraphicsImageRenderer(bounds: bounds, format: format).image { context in
            // The view's own content: its transform and opacity belong to its parent.
            view.layer.render(in: context.cgContext)
        }
    }

    private func write(_ image: UIImage, directory: String, jpeg: Bool, quality: Int) throws -> URL {
        let folder = root.appendingPathComponent(try Self.safeDirectory(directory), isDirectory: true).standardizedFileURL
        guard folder.path.hasPrefix(root.standardizedFileURL.path) else { throw ViewCaptureError("Invalid capture directory") }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let output = folder.appendingPathComponent("capture-\(DispatchTime.now().uptimeNanoseconds).\(jpeg ? "jpg" : "png")")
        guard let data = jpeg ? image.jpegData(compressionQuality: CGFloat(quality) / 100) : image.pngData() else {
            throw ViewCaptureError("Unable to encode the capture.")
        }
        try data.write(to: output, options: .atomic)
        return output
    }

    /// Image scale (pixels per point): `pixelRatio`, or the screen scale when not
    /// positive, capped so neither side exceeds `maxSide` pixels.
    static func captureScale(size: CGSize, pixelRatio: Double, screenScale: CGFloat) -> CGFloat {
        let requested = pixelRatio > 0 && pixelRatio.isFinite ? CGFloat(pixelRatio) : screenScale
        let largest = max(size.width, size.height, 1)
        return max(0.01, min(requested, maxSide / largest))
    }

    /// "a/b" folders of letters, digits, "_", "-" and "."; never "..".
    static func safeDirectory(_ directory: String) throws -> String {
        let parts = directory.split(separator: "/").map(String.init)
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-")
        for part in parts where part == "." || part == ".." || part.count > 64
            || part.unicodeScalars.contains(where: { !allowed.contains($0) }) {
            throw ViewCaptureError("Invalid capture directory")
        }
        return parts.isEmpty ? "view-captures" : parts.joined(separator: "/")
    }

    /// The view tagged with `nativeRef` in any window of the app.
    static func mountedView(_ ref: String) -> UIView? {
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for window in scene.windows {
                if let match = window.pamFindNativeRef(ref) { return match }
            }
        }
        return nil
    }
}

private struct ViewCaptureError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

private extension WireValue {
    var captureText: String? {
        if case let .text(value) = self { return value }
        return nil
    }

    var captureInteger: Int64? {
        if case let .integer(value) = self { return value }
        return nil
    }

    var captureNumber: Double? {
        switch self {
        case let .decimal(value): return value
        case let .integer(value): return Double(value)
        default: return nil
        }
    }
}
