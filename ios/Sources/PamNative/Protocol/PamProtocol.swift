import Foundation

public enum PamProtocolError: Error {
    case invalidProtocol(String)
    case invalidPayload(String)
}

public let PAM_PROTOCOL_VERSION = 1
private let MAX_FRAME_BYTES = 16 * 1024 * 1024
private let MAX_MUTATIONS = 800_000
private let MAX_PROPERTIES = 128
private let MAX_VALUE_BYTES = 1_000_000

public enum NodeKind: Int {
    case screen = 1
    case column = 2
    case row = 3
    case text = 4
    case button = 5
    case input = 6
    case image = 7
    case scroll = 8
    case list = 9
    case spacer = 10
    case view = 11
    case pressable = 12
    case activityIndicator = 13
    case toggle = 14
    case modal = 15
    case imageBackground = 16
    case keyboardAvoidingView = 17
    case sectionList = 18
    case refreshControl = 19
    case statusBar = 20
    case safeAreaView = 21
    case drawerLayout = 22
    case inputAccessoryView = 23
    case customView = 24
    case navigationHost = 25
    case virtualList = 26

    public init(_ rawValue: Int) throws {
        guard let kind = NodeKind(rawValue: rawValue) else {
            throw PamProtocolError.invalidProtocol("Unknown node kind: \(rawValue)")
        }
        self = kind
    }
}

public enum EventKind: Int {
    case press = 1
    case change = 2
    case back = 3
    case moduleResult = 4
    case longPress = 5
    case focus = 6
    case blur = 7
    case submit = 8
    case scroll = 9
    case refresh = 10
    case toggle = 11
    case endReached = 12
    case drawerOpen = 13
    case drawerClose = 14
    case native = 15
    case appState = 16
    case dimensions = 17
    case memoryPressure = 18
    case imageLoadStart = 19
    case imageProgress = 20
    case imageLoad = 21
    case imageError = 22
    case imageLoadEnd = 23
    case inputEndEditing = 24
    case inputSelectionChange = 25
    case inputContentSizeChange = 26
    case inputKeyPress = 27
    case pressIn = 28
    case pressOut = 29
    case pressMove = 30
    case modalRequestClose = 31
    case modalShow = 32
    case modalDismiss = 33
    case modalOrientationChange = 34
}

public enum PropValue {
    case text(String)
    case integer(Int64)
    case decimal(Double)
    case flag(Bool)
    case bytes(Data)
    case strings(PackedStringList)
    case sections(PackedSectionList)
    case properties([String: WireValue])

    public func text() -> String {
        if case let .text(value) = self { return value }
        return ""
    }

    public func integer() -> Int64 {
        if case let .integer(value) = self { return value }
        return 0
    }

    public func decimal() -> Double {
        if case let .decimal(value) = self { return value }
        return 0
    }

    public func flag() -> Bool {
        if case let .flag(value) = self { return value }
        return false
    }
}

public struct PackedStringList {
    private let bytes: Data
    private let offsets: [Int]
    private let lengths: [Int]

    public var size: Int { offsets.count }

    public init(_ source: Data, _ offsets: [Int], _ lengths: [Int]) {
        self.bytes = source
        self.offsets = offsets
        self.lengths = lengths
    }

    public subscript(index: Int) -> String {
        guard index >= 0 && index < offsets.count else {
            return ""
        }
        let start = offsets[index]
        let length = lengths[index]
        let value = bytes.subdata(in: start ..< start + length)
        return String(data: value, encoding: .utf8) ?? ""
    }

    public static func decode(_ source: Data) throws -> PackedStringList {
        var reader = BinaryReader(source: source)
        let count = try reader.u32()
        let safeCount = Int(count)
        guard safeCount <= 100_000 else {
            throw PamProtocolError.invalidPayload("List contains too many items")
        }
        var offsets = [Int]()
        offsets.reserveCapacity(safeCount)
        var lengths = [Int]()
        lengths.reserveCapacity(safeCount)

        for _ in 0..<safeCount {
            let length = try reader.u32()
            guard length <= MAX_VALUE_BYTES else {
                throw PamProtocolError.invalidPayload("List item is too large")
            }
            let offset = reader.offset
            try reader.skip(Int(length))
            offsets.append(offset)
            lengths.append(Int(length))
        }
        try reader.finish()

        return PackedStringList(source, offsets, lengths)
    }
}

public struct PackedSectionList {
    public struct Entry {
        let kind: Int
        let offset: Int
        let length: Int

        fileprivate static func decode(source: inout BinaryReader, kind: Int) throws -> Entry {
            let length = try source.u32()
            guard length <= MAX_VALUE_BYTES else {
                throw PamProtocolError.invalidPayload("Section value too large")
            }
            let value = source.offset
            try source.skip(Int(length))
            return Entry(kind: kind, offset: value, length: Int(length))
        }
    }

    private let bytes: Data
    private let entries: [Entry]

    public static let headerKind = 1
    public static let itemKind = 2
    public var size: Int { entries.count }

    public init(_ source: Data, _ entries: [Entry]) {
        self.bytes = source
        self.entries = entries
    }

    public func isHeader(index: Int) -> Bool {
        guard index >= 0 && index < entries.count else { return false }
        return entries[index].kind == Self.headerKind
    }

    public subscript(index: Int) -> String {
        let entry = entries[index]
        let value = bytes.subdata(in: entry.offset ..< (entry.offset + entry.length))
        return String(data: value, encoding: .utf8) ?? ""
    }

    public static func decode(_ source: Data) throws -> PackedSectionList {
        var reader = BinaryReader(source: source)
        let sections = try reader.u32()
        let safeSections = Int(sections)
        guard safeSections <= 10_000 else {
            throw PamProtocolError.invalidPayload("Section list contains too many sections")
        }

        var entries: [Entry] = []
        for _ in 0..<safeSections {
            entries.append(
                try Entry.decode(source: &reader, kind: Self.headerKind)
            )
            let itemCount = try reader.u32()
            let safeItemCount = Int(itemCount)
            guard safeItemCount <= 100_000 else {
                throw PamProtocolError.invalidPayload("Section list contains too many items")
            }
            for _ in 0..<safeItemCount {
                entries.append(
                    try Entry.decode(source: &reader, kind: Self.itemKind)
                )
            }
        }
        try reader.finish()
        return PackedSectionList(source, entries)
    }

}

public struct NodeSpec {
    public let id: Int64
    public let parent: Int64
    public let index: Int
    public let kind: NodeKind
    public var properties: [Int: PropValue]

    init(id: Int64, parent: Int64, index: Int, kind: NodeKind, properties: [Int: PropValue]) {
        self.id = id
        self.parent = parent
        self.index = index
        self.kind = kind
        self.properties = properties
    }
}

public struct Frame {
    public let x: Float
    public let y: Float
    public let width: Float
    public let height: Float
}

public enum Mutation {
    case create(NodeSpec)
    case remove(Int64)
    case update(id: Int64, key: Int, value: PropValue?)
    case move(id: Int64, parent: Int64, index: Int)
    case layout(id: Int64, frame: Frame)
    case setRoot(Int64)
}

public enum PamConstants {
    // Common property keys used by the runtime/renderer.
    public static let text = 1
    public static let value = 2
    public static let placeholder = 3
    public static let source = 4
    public static let width = 5
    public static let height = 6
    public static let items = 16
    public static let onPress = 14
    public static let onChange = 15
    public static let onLongPress = 19
    public static let onFocus = 20
    public static let onBlur = 21
    public static let onSubmit = 22
    public static let onScroll = 23
    public static let onRefresh = 24
    public static let onToggle = 25
    public static let onEndReached = 83
    public static let endReachedThreshold = 84
    public static let onDrawerOpen = 87
    public static let onDrawerClose = 88
    public static let visible = 57
    public static let refreshing = 63
    public static let drawerOpen = 85
    public static let modalPresentation = 58
    public static let refreshColors = 157
    public static let refreshProgressBackgroundColor = 158
    public static let refreshProgressViewOffset = 159
    public static let refreshIndicatorSize = 160
    public static let scrollHorizontal = 177
    public static let sectionItems = 80
    public static let hostProperties = 100
    public static let opacity = 38
    public static let backgroundColor = 10
    public static let textColor = 11
    public static let fontSize = 12
    public static let enabled = 13
    public static let borderRadius = 35
    public static let borderWidth = 36
    public static let borderColor = 37
    public static let margin = 26
    public static let testId = 18
    public static let padding = 8
    public static let flexGrow = 7
    public static let accessibilityLabel = 17
    public static let navigationOperation = 281
    public static let navigationTransition = 282
    public static let navigationDurationMs = 283
    public static let navigationRevision = 284
    public static let hostName = 99
    public static let onNativeEvent = 101
    public static let onImageLoadStart = 205
    public static let onImageProgress = 206
    public static let onImageLoad = 207
    public static let onImageError = 208
    public static let onImageLoadEnd = 209
    public static let onInputEndEditing = 229
    public static let onInputSelectionChange = 230
    public static let onInputContentSizeChange = 231
    public static let onInputKeyPress = 232
    public static let onPressIn = 249
    public static let onPressOut = 250
    public static let onPressMove = 251
    public static let onModalRequestClose = 259
    public static let onModalShow = 260
    public static let onModalDismiss = 261
    public static let onModalOrientationChange = 262
}

public enum BatchDecoder {
    public static func decode(_ input: Data) throws -> [Mutation] {
        guard input.count <= MAX_FRAME_BYTES else {
            throw PamProtocolError.invalidProtocol("Batch exceeds 16 MiB")
        }
        var reader = BinaryReader(source: input)
        guard try reader.ascii(4) == "PNB1" else {
            throw PamProtocolError.invalidProtocol("Invalid batch magic")
        }
        let version = try reader.u16()
        guard version == PAM_PROTOCOL_VERSION else {
            throw PamProtocolError.invalidProtocol("Unsupported protocol version")
        }
        let count = try reader.u32()
        guard count <= MAX_MUTATIONS else {
            throw PamProtocolError.invalidPayload("Batch has too many mutations")
        }

        var mutations: [Mutation] = []
        mutations.reserveCapacity(count)

        for _ in 0..<count {
            let kind = try reader.u8()
            switch kind {
            case 1:
                let spec = try reader.node()
                mutations.append(.create(spec))
            case 2:
                let id = try reader.positiveId()
                mutations.append(.remove(id))
            case 3:
                let id = try reader.positiveId()
                let key = try reader.u16()
                let presence = try reader.u8()
                let value: PropValue?
                if presence == 1 {
                    value = try reader.value(for: key)
                } else if presence == 2 {
                    value = nil
                } else {
                    throw PamProtocolError.invalidPayload("Unknown update marker")
                }
                mutations.append(.update(id: id, key: key, value: value))
            case 4:
                mutations.append(
                    .move(
                        id: try reader.positiveId(),
                        parent: try reader.u64(),
                        index: try reader.u32(),
                    )
                )
            case 5:
                let id = try reader.positiveId()
                let frame = Frame(
                    x: try reader.f32(),
                    y: try reader.f32(),
                    width: try reader.f32(),
                    height: try reader.f32(),
                )
                mutations.append(.layout(id: id, frame: frame))
            case 6:
                mutations.append(.setRoot(try reader.positiveId()))
            default:
                throw PamProtocolError.invalidProtocol("Unknown mutation type")
            }
        }

        try reader.finish()
        return mutations
    }
}

struct BinaryReader {
    private let source: Data
    private var cursor: Data.Index

    var offset: Int { cursor }

    init(source: Data) {
        self.source = source
        self.cursor = source.startIndex
    }

    mutating func ascii(_ length: Int) throws -> String {
        let bytes = try bytes(length)
        guard let value = String(data: bytes, encoding: .ascii) else {
            throw PamProtocolError.invalidPayload("Invalid ascii payload")
        }
        return value
    }

    mutating func u8() throws -> Int {
        let value = try bytes(1).first ?? 0
        return Int(value)
    }

    mutating func u16() throws -> Int {
        let raw = try bytes(2)
        return Int(UInt16(littleEndian: raw.withUnsafeBytes { $0.load(as: UInt16.self) }))
    }

    mutating func u32() throws -> Int {
        let raw = try bytes(4)
        let value = Int(UInt32(littleEndian: raw.withUnsafeBytes { $0.load(as: UInt32.self) }))
        guard value >= 0 else {
            throw PamProtocolError.invalidPayload("Cannot decode u32")
        }
        return value
    }

    mutating func u64() throws -> Int64 {
        let raw = try bytes(8)
        return Int64(littleEndian: Int64(bitPattern: raw.withUnsafeBytes { $0.load(as: UInt64.self) }))
    }

    mutating func f32() throws -> Float {
        let raw = try bytes(4)
        let value = Float(bitPattern: raw.withUnsafeBytes { $0.load(as: UInt32.self) })
        guard value.isFinite && value >= 0 else {
            throw PamProtocolError.invalidPayload("Invalid layout value")
        }
        return value
    }

    mutating func node() throws -> NodeSpec {
        let id = try positiveId()
        let parent = try u64()
        let index = try u32()
        let kindRaw = try u8()
        let kind = try NodeKind(kindRaw)
        let propertyCount = try u16()
        guard propertyCount <= MAX_PROPERTIES else {
            throw PamProtocolError.invalidPayload("Node has too many properties")
        }

        var properties: [Int: PropValue] = [:]
        properties.reserveCapacity(propertyCount)
        for _ in 0..<propertyCount {
            let key = try u16()
            if properties[key] != nil {
                throw PamProtocolError.invalidPayload("Duplicate property id")
            }
            properties[key] = try value(for: key)
        }

        return NodeSpec(
            id: id,
            parent: parent,
            index: index,
            kind: kind,
            properties: properties,
        )
    }

    mutating func value(for key: Int) throws -> PropValue {
        let tag = try u8()
        switch tag {
        case 1:
            return .text(String(data: try bytes(try u32()), encoding: .utf8) ?? "")
        case 2:
            return .integer(try i64())
        case 3:
            return .decimal(try d64())
        case 4:
            switch try u8() {
            case 0: return .flag(false)
            case 1: return .flag(true)
            default: throw PamProtocolError.invalidPayload("Invalid boolean value")
            }
        case 5:
            let value = try bytes(try u32())
            switch key {
            case PamConstants.items:
                return .strings(try PackedStringList.decode(value))
            case PamConstants.sectionItems:
                return .sections(try PackedSectionList.decode(value))
            case PamConstants.hostProperties:
                return .properties(try WireMap.decode(value))
            default:
                return .bytes(value)
            }
        default:
            throw PamProtocolError.invalidPayload("Unknown property tag")
        }
    }

    mutating func positiveId() throws -> Int64 {
        let value = try u64()
        if value <= 0 {
            throw PamProtocolError.invalidPayload("Node ids must be positive")
        }
        return value
    }

    mutating func bytes(_ count: Int) throws -> Data {
        guard count >= 0 else {
            throw PamProtocolError.invalidPayload("Invalid length")
        }
        let end = cursor + count
        guard end <= source.endIndex else {
            throw PamProtocolError.invalidPayload("Payload truncated")
        }
        defer { cursor = end }
        return source.subdata(in: cursor..<end)
    }

    mutating func skip(_ count: Int) throws {
        _ = try bytes(count)
    }

    mutating func i64() throws -> Int64 {
        let raw = try bytes(8)
        return Int64(bitPattern: raw.withUnsafeBytes { $0.load(as: UInt64.self) })
    }

    mutating func d64() throws -> Double {
        let raw = try bytes(8)
        return Double(bitPattern: raw.withUnsafeBytes { $0.load(as: UInt64.self) })
    }

    func finish() throws {
        if offset != source.endIndex {
            throw PamProtocolError.invalidPayload("Batch has trailing bytes")
        }
    }
}
