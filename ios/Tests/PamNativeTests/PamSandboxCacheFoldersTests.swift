import XCTest
@testable import PamNative

final class PamSandboxCacheFoldersTests: XCTestCase {
    func testCacheFolderIsClearedAndImportsOnlyOnRequest() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let folders = PamSandboxCacheFolders(
            downloads: root.appendingPathComponent("cache", isDirectory: true),
            imports: root.appendingPathComponent("imports", isDirectory: true)
        )
        let pdf = folders.downloads.appendingPathComponent("pdf", isDirectory: true)
        try FileManager.default.createDirectory(at: pdf, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: folders.imports, withIntermediateDirectories: true)
        try Data(count: 23).write(to: pdf.appendingPathComponent("terms.pdf"))
        try Data(count: 29).write(to: folders.imports.appendingPathComponent("picked.jpg"))

        var usage = folders.usage()
        XCTAssertEqual(usage.downloads.bytes, 23)
        XCTAssertEqual(usage.imports.bytes, 29)
        XCTAssertEqual(usage.downloads.files + usage.imports.files, 2)

        folders.clear(imports: false)
        usage = folders.usage()
        XCTAssertEqual(usage.downloads.bytes, 0)
        XCTAssertEqual(usage.imports.bytes, 29)

        folders.clear(imports: true)
        XCTAssertEqual(folders.usage().imports.bytes, 0)
    }
}
