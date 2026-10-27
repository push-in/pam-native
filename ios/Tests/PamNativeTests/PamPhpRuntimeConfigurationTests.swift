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

    func testProcessClockIsPositive() throws {
        let elapsed = try XCTUnwrap(PamProcessClock.millisecondsSinceProcessStart())
        XCTAssertGreaterThan(elapsed, 0)
    }
}
