import UIKit
import XCTest
@testable import PamNative

/// A photo whose view is created in the same batch as its `source` decodes at
/// the view's laid-out size, not at its full resolution.
@MainActor
final class PamSizelessImageDecodeTests: XCTestCase {
    private var fileURL: URL!

    override func setUpWithError() throws {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pam-files", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("sizeless-photo-test.png")
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let photo = UIGraphicsImageRenderer(size: CGSize(width: 1_600, height: 1_600), format: format).image { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 1_600, height: 1_600))
        }
        try XCTUnwrap(photo.pngData()).write(to: fileURL)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: fileURL)
    }

    func testPhotoCreatedWithItsLayoutDecodesAtTheViewSize() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.source: .text("pam-file:///sizeless-photo-test.png"),
        ], kind: .image, frame: Frame(x: 0, y: 0, width: 100, height: 100))
        defer { renderer.close() }
        let image = try XCTUnwrap((view as? PamImageView)?.image)
        let covering = 100 * UIScreen.main.scale
        XCTAssertLessThanOrEqual(image.size.width * image.scale, ceil(covering) + 1)
        XCTAssertGreaterThanOrEqual(image.size.width * image.scale, floor(covering) - 1)
    }
}

@MainActor
final class PamImageCoverClipTests: XCTestCase {
    /// resizeMode cover never paints over the neighbouring cells of a grid.
    func testCoverImageClipsToItsBounds() {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.imageFit: .integer(1),
        ], kind: .image, frame: Frame(x: 0, y: 0, width: 120, height: 120))
        defer { renderer.close() }
        XCTAssertEqual(view.contentMode, .scaleAspectFill)
        XCTAssertTrue(view.clipsToBounds)
    }
}

@MainActor
final class PamInputAccessibilityTests: XCTestCase {
    /// A multiline input stays an accessibility element with UIKit's text
    /// entry traits, so VoiceOver and UI tests find the focused editor.
    func testMultilineInputKeepsTextEntryAccessibility() {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.multiline: .flag(true),
            PamConstants.accessibilityLabel: .text("Mensagem"),
        ], kind: .input, frame: Frame(x: 0, y: 0, width: 300, height: 44))
        defer { renderer.close() }
        XCTAssertTrue(view is UITextView)
        XCTAssertTrue(view.isAccessibilityElement)
        XCTAssertTrue(view.accessibilityTraits.isSuperset(of: PamRenderer.inputBaseTraits(view)))
    }
}

/// A recycled thumbnail whose source turns into a local file keeps that file:
/// the cached remote picture its previous source was still loading never
/// paints over it afterwards.
@MainActor
final class PamImageSourceSwitchTests: XCTestCase {
    private var fileURL: URL!
    private let remote = "https://example.invalid/pam-source-switch-\(UUID().uuidString).png"

    private static func png(_ color: UIColor) throws -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 40), format: format).image { context in
            color.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 40, height: 40))
        }
        return try XCTUnwrap(image.pngData())
    }

    override func setUpWithError() throws {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pam-files", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("source-switch-local.png")
        try Self.png(.blue).write(to: fileURL)
        let stored = expectation(description: "remote picture cached")
        PamMediaDiskCache.shared.store(
            try Self.png(.red),
            source: remote,
            stableKey: nil,
            checksum: nil,
            limit: PamMediaDiskCache.defaultDiskBytes,
            pinned: false
        ) { _ in stored.fulfill() }
        wait(for: [stored], timeout: 5)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: fileURL)
    }

    func testLocalSourceIsNotOverwrittenByThePreviousSourcesPendingLoad() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.source: .text(remote),
        ], kind: .image, frame: Frame(x: 0, y: 0, width: 40, height: 40))
        defer { renderer.close() }
        // The cached remote picture is decoded and painted asynchronously;
        // the source changes before that happens.
        renderer.commit([[.update(id: 2, key: PamConstants.source, value: .text("pam-file:///source-switch-local.png"))]])
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        let pixel = PamRenderTestSupport.pixel(PamRenderTestSupport.snapshot(view), 20, 20)
        PamRenderTestSupport.assertColor(pixel, 0xFF0000FF, tolerance: 40)
    }
}
