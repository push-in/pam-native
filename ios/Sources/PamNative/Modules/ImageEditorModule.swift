import CoreImage
import Foundation
import UIKit

final class ImageEditorModule: NativeModule {
    private let queue = DispatchQueue(label: "dev.pam.native.image-editor", qos: .userInitiated)
    private let root: URL
    private let context = CIContext()

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        root = base.appendingPathComponent("pam-files", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        guard method == "render" else {
            completion(.failure, Data("Unknown image editor method \(method)".utf8))
            return
        }
        queue.async {
            do {
                let output = try self.render(WireMap.decode(payload))
                let size = (try? output.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                completion(.success, try WireMap.encode([
                    "name": .text(output.lastPathComponent),
                    "path": .text(output.path.replacingOccurrences(of: self.root.path + "/", with: "")),
                    "size": .integer(Int64(size)),
                ]))
            } catch {
                completion(.failure, Data(error.localizedDescription.utf8))
            }
        }
    }

    private func render(_ values: [String: WireValue]) throws -> URL {
        let source = try resolve(values["path"]?.imageEditorText ?? "")
        guard var image = UIImage(contentsOfFile: source.path) else {
            throw ImageEditorError("Unable to decode the image.")
        }
        image = orient(
            image,
            turns: Int(values["quarterTurns"]?.imageEditorInteger ?? 0),
            flip: values["flipHorizontal"]?.imageEditorInteger == 1
        )
        image = crop(image, ratio: Int(values["cropRatio"]?.imageEditorInteger ?? 1))
        image = try color(
            image,
            filter: Int(values["filter"]?.imageEditorInteger ?? 1),
            brightness: Int(values["brightness"]?.imageEditorInteger ?? 0),
            contrast: Int(values["contrast"]?.imageEditorInteger ?? 0),
            saturation: Int(values["saturation"]?.imageEditorInteger ?? 0)
        )
        image = compose(image, text: String((values["overlayText"]?.imageEditorText ?? "").prefix(120)), sticker: false)
        image = compose(image, text: String((values["sticker"]?.imageEditorText ?? "").prefix(8)), sticker: true)

        let directory = root.appendingPathComponent("editor", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let output = directory.appendingPathComponent("image-edit-\(UUID().uuidString).jpg")
        guard let data = image.jpegData(compressionQuality: 0.94) else {
            throw ImageEditorError("Unable to encode the edited image.")
        }
        try data.write(to: output, options: Data.WritingOptions.atomic)
        return output
    }

    private func orient(_ image: UIImage, turns: Int, flip: Bool) -> UIImage {
        guard turns != 0 || flip else { return image }
        let angle = CGFloat(turns) * .pi / 2
        let rotated = turns % 2 == 0
            ? image.size
            : CGSize(width: image.size.height, height: image.size.width)
        return UIGraphicsImageRenderer(size: rotated).image { renderer in
            let context = renderer.cgContext
            context.translateBy(x: rotated.width / 2, y: rotated.height / 2)
            context.rotate(by: angle)
            context.scaleBy(x: flip ? -1 : 1, y: 1)
            image.draw(in: CGRect(
                x: -image.size.width / 2,
                y: -image.size.height / 2,
                width: image.size.width,
                height: image.size.height
            ))
        }
    }

    private func crop(_ image: UIImage, ratio: Int) -> UIImage {
        let target: CGFloat
        switch ratio {
        case 2: target = 1
        case 3: target = 4 / 5
        case 4: target = 9 / 16
        case 5: target = 16 / 9
        default: return image
        }
        let current = image.size.width / image.size.height
        let size = current > target
            ? CGSize(width: image.size.height * target, height: image.size.height)
            : CGSize(width: image.size.width, height: image.size.width / target)
        let origin = CGPoint(
            x: (image.size.width - size.width) / 2,
            y: (image.size.height - size.height) / 2
        )
        return UIGraphicsImageRenderer(size: size).image { _ in
            image.draw(at: CGPoint(x: -origin.x, y: -origin.y))
        }
    }

    private func color(
        _ image: UIImage,
        filter: Int,
        brightness: Int,
        contrast: Int,
        saturation: Int
    ) throws -> UIImage {
        guard let input = CIImage(image: image) else { return image }
        let controls = CIFilter(name: "CIColorControls")!
        controls.setValue(input, forKey: kCIInputImageKey)
        let filterSaturation: CGFloat = filter == 2 ? 0 : (filter == 3 ? 1.35 : 1)
        controls.setValue(filterSaturation * max(0, 1 + CGFloat(saturation) / 100), forKey: kCIInputSaturationKey)
        controls.setValue(CGFloat(brightness) / 100, forKey: kCIInputBrightnessKey)
        controls.setValue(max(0, 1 + CGFloat(contrast) / 100), forKey: kCIInputContrastKey)
        guard var output = controls.outputImage else { return image }
        if filter == 4 || filter == 5 {
            let temperature = CIFilter(name: "CITemperatureAndTint")!
            temperature.setValue(output, forKey: kCIInputImageKey)
            temperature.setValue(CIVector(x: filter == 4 ? 5400 : 7200, y: 0), forKey: "inputNeutral")
            temperature.setValue(CIVector(x: 6500, y: 0), forKey: "inputTargetNeutral")
            output = temperature.outputImage ?? output
        }
        guard let cg = context.createCGImage(output, from: output.extent) else {
            throw ImageEditorError("Unable to render image adjustments.")
        }
        return UIImage(cgImage: cg, scale: image.scale, orientation: .up)
    }

    private func compose(_ image: UIImage, text: String, sticker: Bool) -> UIImage {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return image }
        return UIGraphicsImageRenderer(size: image.size).image { _ in
            image.draw(at: .zero)
            let size = max(sticker ? 72 : 34, min(sticker ? 320 : 110, image.size.width * (sticker ? 0.20 : 0.072)))
            let style = NSMutableParagraphStyle()
            style.alignment = .center
            let shadow = NSShadow()
            shadow.shadowColor = UIColor.black.withAlphaComponent(0.75)
            shadow.shadowOffset = CGSize(width: 0, height: size * 0.07)
            shadow.shadowBlurRadius = size * 0.1
            let attributes: [NSAttributedString.Key: Any] = [
                .font: sticker ? UIFont.systemFont(ofSize: size) : UIFont.boldSystemFont(ofSize: size),
                .foregroundColor: UIColor.white,
                .paragraphStyle: style,
                .shadow: shadow,
            ]
            let y = image.size.height * (sticker ? 0.48 : 0.72) - size / 2
            value.draw(in: CGRect(x: image.size.width * 0.07, y: y, width: image.size.width * 0.86, height: size * 2), withAttributes: attributes)
        }
    }

    private func resolve(_ relative: String) throws -> URL {
        let file = root.appendingPathComponent(relative).standardizedFileURL
        guard file.path.hasPrefix(root.path + "/"), FileManager.default.fileExists(atPath: file.path) else {
            throw ImageEditorError("Invalid editor source")
        }
        return file
    }
}

private struct ImageEditorError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

private extension WireValue {
    var imageEditorText: String? {
        if case let .text(value) = self { return value }
        return nil
    }

    var imageEditorInteger: Int64? {
        if case let .integer(value) = self { return value }
        return nil
    }
}
