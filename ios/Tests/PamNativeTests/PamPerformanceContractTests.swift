import XCTest
@testable import PamNative

final class PamPerformanceContractTests: XCTestCase {
    func testBinaryReaderScalarDecodeBenchmark() throws {
        // Layout-heavy batches read one node ID and four floats per mutation.
        // Compare against the old Data.subdata path on the same simulator run.
        let count = 20_000
        var payload = Data(capacity: count * 24)
        for index in 0..<count {
            var id = UInt64(index + 1).littleEndian
            withUnsafeBytes(of: &id) { payload.append(contentsOf: $0) }
            for value in [Float(index % 8192), 24, 390, 48] {
                var bits = value.bitPattern.littleEndian
                withUnsafeBytes(of: &bits) { payload.append(contentsOf: $0) }
            }
        }

        func oldPath() -> Double {
            var offset = 0
            var sum = 0.0
            for _ in 0..<count {
                let id = payload.subdata(in: offset..<(offset + 8))
                sum += Double(UInt64(littleEndian: id.withUnsafeBytes { $0.load(as: UInt64.self) }))
                offset += 8
                for _ in 0..<4 {
                    let bytes = payload.subdata(in: offset..<(offset + 4))
                    sum += Double(Float(bitPattern: bytes.withUnsafeBytes { $0.load(as: UInt32.self) }))
                    offset += 4
                }
            }
            return sum
        }

        func newPath() throws -> Double {
            try BinaryReader.withSource(payload) { reader in
                var sum = 0.0
                for _ in 0..<count {
                    sum += Double(try reader.u64())
                    for _ in 0..<4 {
                        sum += Double(try reader.f32())
                    }
                }
                try reader.finish()
                return sum
            }
        }

        let expected = try newPath()
        XCTAssertEqual(expected, oldPath())
        var oldSamples: [Double] = []
        var newSamples: [Double] = []
        for _ in 0..<5 {
            let oldStart = ProcessInfo.processInfo.systemUptime
            let oldChecksum = oldPath()
            oldSamples.append(ProcessInfo.processInfo.systemUptime - oldStart)
            XCTAssertEqual(oldChecksum, expected)
            let newStart = ProcessInfo.processInfo.systemUptime
            let newChecksum = try newPath()
            newSamples.append(ProcessInfo.processInfo.systemUptime - newStart)
            XCTAssertEqual(newChecksum, expected)
        }
        let oldMedian = oldSamples.sorted()[2]
        let newMedian = newSamples.sorted()[2]
        print("PAM_BINARY_READER_LAYOUT_BENCHMARK old=\(oldMedian)s new=\(newMedian)s records=\(count)")
    }

    func testHundredThousandItemVirtualWindowStaysBounded() {
        let frames = (0..<100_000).map { index in
            (Int64(index + 1), CGRect(x: 0, y: index * 48, width: 390, height: 48))
        }
        let viewport = CGRect(x: 0, y: 2_400_000, width: 390, height: 844)
        let started = CFAbsoluteTimeGetCurrent()
        var visible = Set<Int64>()

        for velocity in stride(from: -12_000, through: 12_000, by: 240) {
            visible = PamVirtualWindow.visibleIds(
                frames: frames,
                viewport: viewport,
                horizontal: false,
                overscan: 844 * 2,
                velocity: CGFloat(velocity)
            )
        }

        let elapsed = CFAbsoluteTimeGetCurrent() - started
        XCTAssertLessThan(elapsed, 2)
        XCTAssertLessThanOrEqual(visible.count, 128)
        XCTAssertFalse(visible.isEmpty)
    }
}
