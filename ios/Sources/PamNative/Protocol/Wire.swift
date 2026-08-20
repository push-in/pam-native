import Foundation

private func strictWireUTF8(_ value: Data, label: String) throws -> String {
    guard let decoded = String(data: value, encoding: .utf8) else {
        throw PamProtocolError.invalidPayload("\(label) is not valid UTF-8")
    }
    return decoded
}

private func validWireKey(_ key: String) -> Bool {
    guard let first = key.utf8.first, key.utf8.allSatisfy({
        $0 == 95 || ($0 >= 48 && $0 <= 57) || ($0 >= 65 && $0 <= 90) || ($0 >= 97 && $0 <= 122)
    }) else {
        return false
    }
    return (first >= 65 && first <= 90) || (first >= 97 && first <= 122)
}

public enum WireValue: Equatable {
    case text(String)
    case integer(Int64)
    case decimal(Double)
    case flag(Bool)
}

public enum WireMap {
    public static let maxBytes = 1024 * 1024

    public static func decode(_ bytes: Data) throws -> [String: WireValue] {
        guard bytes.count <= maxBytes else {
            throw PamProtocolError.invalidPayload("Native module payload exceeds one MiB")
        }
        var cursor = 0

        func read(_ count: Int) throws -> Data {
            guard count >= 0 else { throw PamProtocolError.invalidPayload("Invalid value length") }
            let end = cursor + count
            guard end <= bytes.count else { throw PamProtocolError.invalidPayload("Payload truncated") }
            defer { cursor = end }
            return bytes.subdata(in: cursor..<end)
        }

        func readU8() throws -> Int {
            Int(try read(1)[0])
        }

        func readU16() throws -> Int {
            let raw = try read(2)
            return Int(UInt16(littleEndian: raw.withUnsafeBytes { $0.load(as: UInt16.self) }))
        }

        func readU32() throws -> Int {
            let raw = try read(4)
            return Int(UInt32(littleEndian: raw.withUnsafeBytes { $0.load(as: UInt32.self) }))
        }

        func readInt64() throws -> Int64 {
            let raw = try read(8)
            return Int64(bitPattern: raw.withUnsafeBytes { $0.load(as: UInt64.self) })
        }

        func readDouble() throws -> Double {
            let raw = try read(8)
            let value = Double(bitPattern: raw.withUnsafeBytes { $0.load(as: UInt64.self) })
            guard value.isFinite else {
                throw PamProtocolError.invalidPayload("Native decimal must be finite")
            }
            return value
        }

        let count = try readU16()
        var result: [String: WireValue] = [:]
        result.reserveCapacity(count)

        for _ in 0..<count {
            let keyLength = try readU16()
            guard (1...255).contains(keyLength) else {
                throw PamProtocolError.invalidPayload("Invalid native map key length")
            }
            let keyData = try read(keyLength)
            let key = try strictWireUTF8(keyData, label: "Native module key")
            guard validWireKey(key) else {
                throw PamProtocolError.invalidPayload("Invalid native module key")
            }
            guard result[key] == nil else {
                throw PamProtocolError.invalidPayload("Duplicate native module key")
            }

            let tag = try readU8()
            let value: WireValue = try {
                switch tag {
                case 1:
                    let size = try readU32()
                    guard size <= maxBytes else { throw PamProtocolError.invalidPayload("Native value too large") }
                    let payload = try read(size)
                    return .text(try strictWireUTF8(payload, label: "Native module value"))
                case 2:
                    return .integer(try readInt64())
                case 3:
                    return .decimal(try readDouble())
                case 4:
                    let flag = try readU8()
                    switch flag {
                    case 0: return .flag(false)
                    case 1: return .flag(true)
                    default: throw PamProtocolError.invalidPayload("Invalid native boolean")
                    }
                default:
                    throw PamProtocolError.invalidPayload("Unknown native value type")
                }
            }()

            result[key] = value
        }

        guard cursor == bytes.count else {
            throw PamProtocolError.invalidPayload("Native payload has trailing bytes")
        }
        return result
    }

    public static func decode(_ payload: [UInt8]) throws -> [String: WireValue] {
        try decode(Data(payload))
    }

    public static func encode(_ values: [String: WireValue]) throws -> Data {
        guard values.count <= 65_535 else {
            throw PamProtocolError.invalidPayload("Too many native values")
        }
        var output = Data()
        output.append(contentsOf: withUnsafeBytes(of: UInt16(values.count).littleEndian, Array.init))

        for (key, value) in values.sorted(by: { $0.key < $1.key }) {
            let keyBytes = key.data(using: .utf8) ?? Data()
            guard (1...255).contains(keyBytes.count), validWireKey(key) else {
                throw PamProtocolError.invalidPayload("Invalid module key")
            }
            output.append(contentsOf: withUnsafeBytes(of: UInt16(keyBytes.count).littleEndian, Array.init))
            output.append(contentsOf: keyBytes)

            switch value {
            case let .text(text):
                output.append(1)
                let raw = text.data(using: .utf8) ?? Data()
                guard raw.count <= maxBytes else {
                    throw PamProtocolError.invalidPayload("Value too large")
                }
                output.append(contentsOf: withUnsafeBytes(of: UInt32(raw.count).littleEndian, Array.init))
                output.append(contentsOf: raw)
            case let .integer(int):
                output.append(2)
                output.append(contentsOf: withUnsafeBytes(of: Int64(int).littleEndian, Array.init))
            case let .decimal(double):
                guard double.isFinite else {
                    throw PamProtocolError.invalidPayload("Native decimal must be finite")
                }
                output.append(3)
                output.append(contentsOf: withUnsafeBytes(of: double.bitPattern.littleEndian, Array.init))
            case let .flag(flag):
                output.append(4)
                output.append(flag ? 1 : 0)
            }
        }

        guard output.count <= maxBytes else {
            throw PamProtocolError.invalidPayload("Native payload exceeds one MiB")
        }
        return output
    }
}
