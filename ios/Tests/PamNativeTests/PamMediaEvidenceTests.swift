import AVFoundation
import UIKit
import XCTest
@testable import PamNative

/// Visual evidence scene for the Simulator (screenshots taken from outside
/// with `simctl io booted screenshot`). Runs only with
/// `TEST_RUNNER_PAM_MEDIA_EVIDENCE=1`; otherwise skipped.
@MainActor
final class PamMediaEvidenceTests: XCTestCase {
    func testEvidenceScene() throws {
        let env = ProcessInfo.processInfo.environment
        guard env["PAM_MEDIA_EVIDENCE"] == "1" else {
            throw XCTSkip("evidence scene only on request")
        }
        let dark = env["PAM_MEDIA_EVIDENCE_DARK"] == "1"
        let screen = UIScreen.main.bounds
        let window = pamTestWindow(frame: screen)
        window.overrideUserInterfaceStyle = dark ? .dark : .light
        window.backgroundColor = .systemBackground
        window.windowLevel = .alert + 1
        window.isHidden = false
        defer { window.isHidden = true }
        let top = max(window.safeAreaInsets.top, 62) + 12
        var y = top

        func label(_ text: String) {
            let view = UILabel(frame: CGRect(x: 16, y: y, width: screen.width - 32, height: 20))
            view.text = text
            view.font = .systemFont(ofSize: 14, weight: .semibold)
            view.textColor = .label
            window.addSubview(view)
            y += 26
        }

        label("GIF animado (4 quadros, defasados)")
        let gif = PamAnimatedImageTests.gif(
            colors: [.systemRed, .systemGreen, .systemBlue, .systemOrange],
            delays: [0.3, 0.3, 0.3, 0.3],
            size: CGSize(width: 360, height: 360)
        )
        for index in 0..<4 {
            let view = PamImageView(frame: CGRect(x: 16 + CGFloat(index) * 88, y: y, width: 72, height: 72))
            view.layer.cornerRadius = 12
            view.clipsToBounds = true
            view.image = PamImageDownsampling.image(data: gif, targetSize: view.bounds.size)
            window.addSubview(view)
            // Offset each one so a single screenshot shows different frames.
            view.advanceAnimation(by: 0.3 * Double(index) + 0.01)
        }
        y += 88

        label("WebP animado (figurinha) em 96 pt")
        let webp = PamImageView(frame: CGRect(x: 16, y: y, width: 96, height: 96))
        webp.layer.magnificationFilter = .nearest
        webp.image = PamInlineImages.image("data:image/webp;base64," + PamAnimatedImageTests.webp)
        window.addSubview(webp)
        y += 112

        let poster = UIGraphicsImageRenderer(size: CGSize(width: 320, height: 180)).image { context in
            let colors = [UIColor.systemPurple.cgColor, UIColor.systemTeal.cgColor] as CFArray
            let gradient = CGGradient(colorsSpace: nil, colors: colors, locations: nil)!
            context.cgContext.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 320, y: 180), options: [])
            let text = "POSTER" as NSString
            text.draw(at: CGPoint(x: 112, y: 76), withAttributes: [
                .font: UIFont.systemFont(ofSize: 24, weight: .bold), .foregroundColor: UIColor.white,
            ])
        }
        let posterURI = "data:image/png;base64," + poster.pngData()!.base64EncodedString()
        let width = (screen.width - 48) / 2

        label("Vídeo carregando: capa (sem quadro preto)")
        let loading = PamMediaView(frame: CGRect(x: 16, y: y, width: width, height: width * 9 / 16))
        loading.localImageResolver = { source, _ in PamInlineImages.image(source) }
        loading.setControls(false)
        loading.setResizeMode(1)
        window.addSubview(loading)
        loading.setThumbnail(posterURI, cacheKey: nil, policy: 1, maxAgeMs: 0)
        loading.setSource(FileManager.default.temporaryDirectory.appendingPathComponent("missing.mp4").absoluteString)

        let webm = PamMediaView(frame: CGRect(x: 32 + width, y: y, width: width, height: width * 9 / 16))
        webm.localImageResolver = { source, _ in PamInlineImages.image(source) }
        webm.setControls(false)
        webm.setResizeMode(1)
        window.addSubview(webm)
        webm.setThumbnail(posterURI, cacheKey: nil, policy: 1, maxAgeMs: 0)
        webm.setSource("https://cdn.example.test/sticker.webm")
        y += width * 9 / 16 + 8
        label("Esq.: carregando · Dir.: WebM (fallback)")
        y += 8

        label("Vídeo pausado sem capa: 1º quadro")
        let paused = PamMediaView(frame: CGRect(x: 16, y: y, width: width, height: width * 9 / 16))
        paused.setControls(false)
        paused.setResizeMode(1)
        window.addSubview(paused)
        paused.setSource(try PamMediaParityTests.makeVideo().absoluteString)

        let controls = PamMediaView(frame: CGRect(x: 32 + width, y: y, width: width, height: width * 9 / 16))
        controls.setControls(true)
        controls.setResizeMode(1)
        window.addSubview(controls)
        controls.setSource(try PamMediaParityTests.makeVideo().absoluteString)
        y += width * 9 / 16 + 8
        label("Esq.: AVPlayerLayer · Dir.: controls=true")

        // Package tests run in the xctest tool (nothing on the Simulator
        // display), so the scene is rendered to PNGs at two moments.
        let output = URL(fileURLWithPath: env["PAM_MEDIA_EVIDENCE_DIR"] ?? NSTemporaryDirectory())
        func settle(_ seconds: TimeInterval) {
            let deadline = Date().addingTimeInterval(seconds)
            while Date() < deadline { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        }
        func shot(_ name: String) throws {
            let format = UIGraphicsImageRendererFormat()
            format.scale = 3
            let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { context in
                window.layer.render(in: context.cgContext)
                // AVPlayerLayer content is not captured by layer rendering:
                // draw the frame the player shows (paused at currentTime).
                for media in [paused, controls] where media.videoReadyForDisplay {
                    let generator = AVAssetImageGenerator(asset: AVURLAsset(url: try! PamMediaParityTests.makeVideo()))
                    generator.requestedTimeToleranceBefore = .zero
                    generator.requestedTimeToleranceAfter = .zero
                    if let frame = try? generator.copyCGImage(at: .zero, actualTime: nil) {
                        UIImage(cgImage: frame).draw(in: media.convert(media.bounds, to: window))
                    }
                }
            }
            try image.pngData()!.write(to: output.appendingPathComponent("media-evidence-\(dark ? "dark" : "light")-\(name).png"))
        }
        settle(4)
        try shot("a")
        settle(0.45)
        try shot("b")
        XCTAssertTrue(paused.videoReadyForDisplay)
        XCTAssertTrue(loading.isPosterVisible)
        XCTAssertTrue(webm.isPosterVisible)
    }
}
