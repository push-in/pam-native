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
        image = composeDrawing(
            image,
            drawing: values["drawing"]?.imageEditorText ?? ""
        )
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
        image = composeTextLayers(image, encoded: values["textLayers"]?.imageEditorText ?? "")
        image = compose(image, text: String((values["overlayText"]?.imageEditorText ?? "").prefix(120)), sticker: false)
        image = compose(image, text: String((values["sticker"]?.imageEditorText ?? "").prefix(8)), sticker: true)
        image = resize(
            image,
            maxWidth: Int(values["maxWidth"]?.imageEditorInteger ?? 0),
            maxHeight: Int(values["maxHeight"]?.imageEditorInteger ?? 0)
        )

        let directory = root.appendingPathComponent("editor", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let output = directory.appendingPathComponent("image-edit-\(UUID().uuidString).jpg")
        let quality = max(
            1,
            min(100, Int(values["outputQuality"]?.imageEditorInteger ?? 94))
        )
        guard let data = image.jpegData(compressionQuality: CGFloat(quality) / 100) else {
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

    private func composeTextLayers(_ image: UIImage, encoded: String) -> UIImage {
        guard
            let data = encoded.data(using: .utf8),
            let raw = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]],
            !raw.isEmpty
        else { return image }
        return UIGraphicsImageRenderer(size: image.size).image { renderer in
            image.draw(at: .zero)
            let context = renderer.cgContext
            raw.prefix(80).forEach { layer in
                let text = String((layer["text"] as? String ?? "")
                    .trimmingCharacters(in: .whitespacesAndNewlines).prefix(500))
                guard !text.isEmpty else { return }
                let scale = CGFloat(max(0.25, min(4, layer["scale"] as? Double ?? 1)))
                let fontSize = max(18, min(image.size.width * 0.22, image.size.width * 0.055 * scale))
                let styleType = max(1, min(3, layer["styleType"] as? Int ?? 1))
                let paragraph = NSMutableParagraphStyle()
                paragraph.alignment = .center
                let shadow = NSShadow()
                shadow.shadowColor = UIColor.black.withAlphaComponent(styleType == 1 ? 0.78 : 0)
                shadow.shadowOffset = CGSize(width: 0, height: fontSize * 0.06)
                shadow.shadowBlurRadius = fontSize * 0.09
                let attributes: [NSAttributedString.Key: Any] = [
                    .font: UIFont.boldSystemFont(ofSize: fontSize),
                    .foregroundColor: textLayerColor(layer["color"] as? String),
                    .paragraphStyle: paragraph,
                    .shadow: shadow,
                ]
                let maximum = CGSize(width: image.size.width * 0.78, height: image.size.height * 0.5)
                let measured = (text as NSString).boundingRect(
                    with: maximum,
                    options: [.usesLineFragmentOrigin, .usesFontLeading],
                    attributes: attributes,
                    context: nil
                ).integral.size
                let padding = fontSize * 0.36
                let center = CGPoint(
                    x: CGFloat(max(0, min(1, layer["x"] as? Double ?? 0.5))) * image.size.width,
                    y: CGFloat(max(0, min(1, layer["y"] as? Double ?? 0.5))) * image.size.height
                )
                context.saveGState()
                context.translateBy(x: center.x, y: center.y)
                context.rotate(by: CGFloat(layer["rotation"] as? Double ?? 0))
                let box = CGRect(
                    x: -(measured.width + padding * 2) / 2,
                    y: -(measured.height + padding * 2) / 2,
                    width: measured.width + padding * 2,
                    height: measured.height + padding * 2
                )
                if styleType != 1 {
                    let color = styleType == 2
                        ? UIColor.white.withAlphaComponent(0.94)
                        : UIColor(red: CGFloat(16) / 255, green: CGFloat(19) / 255, blue: CGFloat(24) / 255, alpha: 0.70)
                    color.setFill()
                    UIBezierPath(roundedRect: box, cornerRadius: fontSize * 0.28).fill()
                }
                (text as NSString).draw(
                    in: CGRect(x: -measured.width / 2, y: -measured.height / 2, width: measured.width, height: measured.height),
                    withAttributes: attributes
                )
                context.restoreGState()
            }
        }
    }

    private func textLayerColor(_ value: String?) -> UIColor {
        let text = (value ?? "#FFFFFF").trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.hasPrefix("#") else { return .white }
        let hex = String(text.dropFirst())
        guard (hex.count == 6 || hex.count == 8), let raw = UInt64(hex, radix: 16) else { return .white }
        if hex.count == 8 {
            return UIColor(
                red: CGFloat((raw >> 24) & 0xFF) / 255,
                green: CGFloat((raw >> 16) & 0xFF) / 255,
                blue: CGFloat((raw >> 8) & 0xFF) / 255,
                alpha: CGFloat(raw & 0xFF) / 255
            )
        }
        return UIColor(
            red: CGFloat((raw >> 16) & 0xFF) / 255,
            green: CGFloat((raw >> 8) & 0xFF) / 255,
            blue: CGFloat(raw & 0xFF) / 255,
            alpha: 1
        )
    }

    private func composeDrawing(_ image: UIImage, drawing: String) -> UIImage {
        let strokes = PamDrawingCodec.decode(drawing)
        guard !strokes.isEmpty else { return image }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let overlay = UIGraphicsImageRenderer(size: image.size, format: format).image {
            renderer in
            let context = renderer.cgContext
            strokes.forEach { stroke in
                drawStroke(
                    stroke,
                    in: context,
                    width: image.size.width,
                    height: image.size.height
                )
            }
        }
        return UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
            overlay.draw(in: CGRect(origin: .zero, size: image.size))
        }
    }

    private func drawStroke(
        _ stroke: PamDrawingStroke,
        in context: CGContext,
        width: CGFloat,
        height: CGFloat
    ) {
        guard let first = stroke.points.first else { return }
        context.saveGState()
        context.setBlendMode(stroke.mode == 2 ? .clear : .normal)
        context.setStrokeColor(UIColor(argb: stroke.color).cgColor)
        context.setLineWidth(max(1, stroke.width * width))
        context.setLineCap(.round)
        context.setLineJoin(.round)
        context.beginPath()
        context.move(to: CGPoint(x: first.x * width, y: first.y * height))
        stroke.points.dropFirst().forEach { point in
            context.addLine(to: CGPoint(x: point.x * width, y: point.y * height))
        }
        if stroke.points.count == 1 {
            context.addLine(to: CGPoint(
                x: first.x * width + 0.01,
                y: first.y * height + 0.01
            ))
        }
        context.strokePath()
        context.restoreGState()
    }

    private func resize(_ image: UIImage, maxWidth: Int, maxHeight: Int) -> UIImage {
        guard maxWidth > 0 || maxHeight > 0 else { return image }
        let widthScale = maxWidth > 0
            ? CGFloat(maxWidth) / max(image.size.width, 1)
            : 1
        let heightScale = maxHeight > 0
            ? CGFloat(maxHeight) / max(image.size.height, 1)
            : 1
        let scale = min(1, min(widthScale, heightScale))
        guard scale < 1 else { return image }
        let target = CGSize(
            width: max(1, floor(image.size.width * scale)),
            height: max(1, floor(image.size.height * scale))
        )
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
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
