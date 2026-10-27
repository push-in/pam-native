import XCTest
@testable import PamNative

/// Android parity (`guardProperty` / `onPropertyError`): one invalid prop is
/// skipped and reported, the rest of the tree still renders.
final class PamTolerantDecodeTests: XCTestCase {
    func testInvalidTextIsSkippedButTheNodeAndOtherPropsSurvive() throws {
        var properties = Data()
        properties.appendLE(UInt16(PamConstants.text))
        properties.append(1)
        properties.appendLE(UInt32(2))
        properties.append(contentsOf: [0xc3, 0x28])
        properties.appendLE(UInt16(PamConstants.width))
        properties.append(3)
        properties.appendLE(Double(42).bitPattern)
        let bytes = createBatch(propertyCount: 2, properties: properties)

        XCTAssertThrowsError(try BatchDecoder.decode(bytes))
        let batch = try BatchDecoder.decodeTolerant(bytes)
        guard case let .create(node)? = batch.mutations.first else {
            return XCTFail("Expected the node to be created")
        }
        XCTAssertNil(node.properties[PamConstants.text])
        guard case .decimal(42)? = node.properties[PamConstants.width] else {
            return XCTFail("Expected the valid width to survive")
        }
        XCTAssertEqual(batch.skippedProperties.count, 1)
        XCTAssertTrue(batch.skippedProperties[0].contains("property \(PamConstants.text) on node 1"))
    }

    func testNonFiniteDecimalAndInvalidBooleanAreSkipped() throws {
        var properties = Data()
        properties.appendLE(UInt16(PamConstants.width))
        properties.append(3)
        properties.appendLE(Double.nan.bitPattern)
        properties.appendLE(UInt16(7))
        properties.append(4)
        properties.append(9)
        let bytes = createBatch(propertyCount: 2, properties: properties)

        XCTAssertThrowsError(try BatchDecoder.decode(bytes))
        let batch = try BatchDecoder.decodeTolerant(bytes)
        guard case let .create(node)? = batch.mutations.first else {
            return XCTFail("Expected the node to be created")
        }
        XCTAssertTrue(node.properties.isEmpty)
        XCTAssertEqual(batch.skippedProperties.count, 2)
    }

    func testInvalidUpdateIsDroppedInsteadOfClearingTheProp() throws {
        var data = Data("PNB1".utf8)
        data.appendLE(UInt16(1))
        data.appendLE(UInt32(2))
        data.append(3)
        data.appendLE(UInt64(1))
        data.appendLE(UInt16(PamConstants.text))
        data.append(1)
        data.append(1)
        data.appendLE(UInt32(1))
        data.append(0xff)
        data.append(6)
        data.appendLE(UInt64(1))

        XCTAssertThrowsError(try BatchDecoder.decode(data))
        let batch = try BatchDecoder.decodeTolerant(data)
        XCTAssertEqual(batch.mutations.count, 1)
        guard case .setRoot(1)? = batch.mutations.first else {
            return XCTFail("Expected only the SetRoot mutation")
        }
        XCTAssertEqual(batch.skippedProperties.count, 1)
    }

    func testDuplicatePropertyKeepsTheLastValue() throws {
        var properties = Data()
        for flag in [UInt8(0), UInt8(1)] {
            properties.appendLE(UInt16(7))
            properties.append(4)
            properties.append(flag)
        }
        let bytes = createBatch(propertyCount: 2, properties: properties)
        XCTAssertThrowsError(try BatchDecoder.decode(bytes))
        let batch = try BatchDecoder.decodeTolerant(bytes)
        guard case let .create(node)? = batch.mutations.first else {
            return XCTFail("Expected the node to be created")
        }
        guard case .flag(true)? = node.properties[7] else {
            return XCTFail("Expected the last duplicate value")
        }
        XCTAssertEqual(batch.skippedProperties.count, 1)
    }

    func testFramingErrorsStillRejectTheBatch() {
        var properties = Data()
        properties.appendLE(UInt16(7))
        properties.append(99)
        XCTAssertThrowsError(try BatchDecoder.decodeTolerant(createBatch(propertyCount: 1, properties: properties)))
    }

    func testValidBatchReportsNothing() throws {
        var properties = Data()
        properties.appendLE(UInt16(7))
        properties.append(4)
        properties.append(1)
        let batch = try BatchDecoder.decodeTolerant(createBatch(propertyCount: 1, properties: properties))
        XCTAssertEqual(batch.mutations.count, 1)
        XCTAssertTrue(batch.skippedProperties.isEmpty)
    }
}

private func createBatch(propertyCount: Int, properties: Data) -> Data {
    var data = Data("PNB1".utf8)
    data.appendLE(UInt16(1))
    data.appendLE(UInt32(1))
    data.append(1)
    data.appendLE(UInt64(1))
    data.appendLE(UInt64(0))
    data.appendLE(UInt32(0))
    data.append(UInt8(NodeKind.screen.rawValue))
    data.appendLE(UInt16(propertyCount))
    data.append(properties)
    return data
}

private extension Data {
    mutating func appendLE<T: FixedWidthInteger>(_ value: T) {
        var littleEndian = value.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { append(contentsOf: $0) }
    }
}
