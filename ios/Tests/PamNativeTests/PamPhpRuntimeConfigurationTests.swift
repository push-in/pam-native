import XCTest
@testable import PamNative

final class PamPhpRuntimeConfigurationTests: XCTestCase {
    private let gib: UInt64 = 1_073_741_824

    func testMemoryLimitFollowsTheAndroidRamTiers() {
        typealias Config = PamPhpRuntimeConfiguration
        XCTAssertEqual(Config.memoryLimitMegabytes(physicalMemoryBytes: 2 * gib, availableMemoryBytes: 0), 128)
        XCTAssertEqual(Config.memoryLimitMegabytes(physicalMemoryBytes: 3 * gib, availableMemoryBytes: 0), 128)
        XCTAssertEqual(Config.memoryLimitMegabytes(physicalMemoryBytes: 4 * gib, availableMemoryBytes: 0), 192)
        XCTAssertEqual(Config.memoryLimitMegabytes(physicalMemoryBytes: 6 * gib, availableMemoryBytes: 0), 256)
        XCTAssertEqual(Config.memoryLimitMegabytes(physicalMemoryBytes: 8 * gib, availableMemoryBytes: 0), 256)
    }

    func testMemoryLimitNeverTakesMoreThanAThirdOfTheJetsamBudget() {
        typealias Config = PamPhpRuntimeConfiguration
        let mib: UInt64 = 1_048_576
        XCTAssertEqual(Config.memoryLimitMegabytes(physicalMemoryBytes: 8 * gib, availableMemoryBytes: 3 * gib), 256)
        XCTAssertEqual(Config.memoryLimitMegabytes(physicalMemoryBytes: 8 * gib, availableMemoryBytes: 600 * mib), 200)
        XCTAssertEqual(Config.memoryLimitMegabytes(physicalMemoryBytes: 8 * gib, availableMemoryBytes: 200 * mib), 128)
    }

    func testOpcacheReservationShrinksOnSmallDevices() {
        XCTAssertEqual(PamPhpRuntimeConfiguration.opcacheMemoryMegabytes(physicalMemoryBytes: 2 * gib), 64)
        XCTAssertEqual(PamPhpRuntimeConfiguration.opcacheMemoryMegabytes(physicalMemoryBytes: 6 * gib), 128)
    }

    func testExportNeverOverridesAnExplicitEnvironment() {
        let names = [
            PamPhpRuntimeConfiguration.memoryLimitVariable,
            PamPhpRuntimeConfiguration.opcacheMemoryVariable,
            PamPhpRuntimeConfiguration.immutableSourcesVariable,
            PamPhpRuntimeConfiguration.opcacheDirectoryVariable,
        ]
        defer { names.forEach { unsetenv($0) } }
        names.forEach { unsetenv($0) }
        let configuration = PamPhpRuntimeConfiguration(
            memoryLimitMegabytes: 192,
            opcacheMemoryMegabytes: 128,
            opcacheDirectory: "/tmp/pam-opcache",
            immutableSources: true
        )
        configuration.export { name in
            name == PamPhpRuntimeConfiguration.memoryLimitVariable ? "512M" : nil
        }
        XCTAssertNil(getenv(PamPhpRuntimeConfiguration.memoryLimitVariable))
        XCTAssertEqual(String(cString: getenv(PamPhpRuntimeConfiguration.opcacheMemoryVariable)), "128")
        XCTAssertEqual(String(cString: getenv(PamPhpRuntimeConfiguration.immutableSourcesVariable)), "1")
        XCTAssertEqual(String(cString: getenv(PamPhpRuntimeConfiguration.opcacheDirectoryVariable)), "/tmp/pam-opcache")
    }

    func testOpcacheDirectoryIsDroppedWhenTheBuildStampChanges() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("pam-opcache-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let first = try XCTUnwrap(PamPhpRuntimeConfiguration.prepareOpcacheDirectory(stamp: "build-1", root: root))
        let cached = URL(fileURLWithPath: first).appendingPathComponent("entry.bin")
        try Data([1]).write(to: cached)

        XCTAssertEqual(PamPhpRuntimeConfiguration.prepareOpcacheDirectory(stamp: "build-1", root: root), first)
        XCTAssertTrue(FileManager.default.fileExists(atPath: cached.path), "Same build keeps the warm cache")

        XCTAssertEqual(PamPhpRuntimeConfiguration.prepareOpcacheDirectory(stamp: "build-2", root: root), first)
        XCTAssertFalse(FileManager.default.fileExists(atPath: cached.path), "A new bundle drops stale opcodes")
        XCTAssertTrue(FileManager.default.fileExists(atPath: first))
    }

    func testBuildStampTracksTheBundlePath() {
        XCTAssertTrue(PamPhpRuntimeConfiguration.buildStamp(bundle: .main).hasPrefix(Bundle.main.bundlePath))
    }

    func testBuildStampChangesWithThePhpBundleContentUnderTheSamePath() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("pam-stamp-\(UUID().uuidString).app", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let pam = root.appendingPathComponent("PamBundle", isDirectory: true)
        try FileManager.default.createDirectory(at: pam, withIntermediateDirectories: true)
        let info: [String: Any] = [
            "CFBundleIdentifier": "dev.pam.stamp-test",
            "CFBundleShortVersionString": "1.0",
            "CFBundleVersion": "7",
        ]
        try PropertyListSerialization.data(fromPropertyList: info, format: .xml, options: 0)
            .write(to: root.appendingPathComponent("Info.plist"))
        let manifest = pam.appendingPathComponent("manifest.sha256")
        try (String(repeating: "a", count: 64) + "\n").write(to: manifest, atomically: true, encoding: .utf8)
        let bundle = try XCTUnwrap(Bundle(url: root))

        let first = PamPhpRuntimeConfiguration.buildStamp(bundle: bundle)
        XCTAssertTrue(first.hasSuffix("|1.0|7|" + String(repeating: "a", count: 64)))
        XCTAssertEqual(PamPhpRuntimeConfiguration.buildStamp(bundle: bundle), first, "Same bundle keeps the cache")

        // Same path, same version and build, new PHP: an in-place install.
        try (String(repeating: "b", count: 64) + "\n").write(to: manifest, atomically: true, encoding: .utf8)
        let second = PamPhpRuntimeConfiguration.buildStamp(bundle: bundle)
        XCTAssertNotEqual(second, first, "New PHP content must drop the opcodes compiled from the old bundle")

        let cache = root.appendingPathComponent("caches", isDirectory: true)
        let directory = try XCTUnwrap(PamPhpRuntimeConfiguration.prepareOpcacheDirectory(stamp: first, root: cache))
        let stale = URL(fileURLWithPath: directory).appendingPathComponent("index.php.bin")
        try Data([1]).write(to: stale)
        _ = PamPhpRuntimeConfiguration.prepareOpcacheDirectory(stamp: second, root: cache)
        XCTAssertFalse(FileManager.default.fileExists(atPath: stale.path))
    }

    func testManifestDigestAcceptsOnlyASha256Line() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("pam-manifest-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: file) }
        let digest = String(repeating: "0f", count: 32)
        try "\(digest)\nextra\n".write(to: file, atomically: true, encoding: .utf8)
        XCTAssertEqual(PamPhpRuntimeConfiguration.manifestDigest(at: file), digest)
        try "not-a-digest\n".write(to: file, atomically: true, encoding: .utf8)
        XCTAssertNil(PamPhpRuntimeConfiguration.manifestDigest(at: file))
        XCTAssertNil(PamPhpRuntimeConfiguration.manifestDigest(at: file.appendingPathExtension("missing")))
    }

    func testBundleWithoutManifestFallsBackToTheExecutableIdentity() {
        XCTAssertFalse(PamPhpRuntimeConfiguration.contentIdentity(bundle: .main).isEmpty)
    }

    func testProcessClockIsPositive() throws {
        let elapsed = try XCTUnwrap(PamProcessClock.millisecondsSinceProcessStart())
        XCTAssertGreaterThan(elapsed, 0)
    }
}
