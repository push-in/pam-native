import Foundation
import SQLite3

final class SQLiteModule: NativeModule, ClosableNativeModule {
    private let queue = DispatchQueue(label: "dev.pam.native.sqlite")
    private var databases: [String: OpaquePointer] = [:]

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        queue.async {
            do {
                let values = try WireMap.decode(payload)
                guard case let .text(name)? = values["database"],
                      case let .text(sql)? = values["sql"],
                      case let .text(argumentsJSON)? = values["arguments"] else {
                    throw SQLiteError("Invalid SQLite payload")
                }
                let database = try self.open(name)
                switch method {
                case "execute":
                    let arguments = try self.decodeArguments(argumentsJSON)
                    let statement = try self.prepare(database, sql)
                    defer { sqlite3_finalize(statement) }
                    try self.bind(arguments, to: statement)
                    guard sqlite3_step(statement) == SQLITE_DONE else {
                        throw SQLiteError(String(cString: sqlite3_errmsg(database)))
                    }
                    completion(.success, Data())
                case "query":
                    let arguments = try self.decodeArguments(argumentsJSON)
                    let rows = try self.query(database, sql, arguments)
                    let json = try JSONSerialization.data(withJSONObject: rows)
                    let text = String(data: json, encoding: .utf8) ?? "[]"
                    completion(.success, try WireMap.encode(["rows": .text(text)]))
                case "executeMany":
                    let argumentSets = try self.decodeArgumentSets(argumentsJSON)
                    try self.executeMany(database, sql, argumentSets)
                    completion(.success, Data())
                default:
                    throw SQLiteError("Unknown SQLite method \(method)")
                }
            } catch {
                completion(.failure, Data(error.localizedDescription.utf8))
            }
        }
    }

    private func open(_ name: String) throws -> OpaquePointer {
        if let database = databases[name] { return database }
        let root = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        )[0].appendingPathComponent("pam-databases", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var database: OpaquePointer?
        guard sqlite3_open(root.appendingPathComponent(name).path, &database) == SQLITE_OK,
              let database else {
            throw SQLiteError("Unable to open SQLite database")
        }
        try execute(database, "PRAGMA journal_mode=WAL")
        try execute(database, "PRAGMA synchronous=NORMAL")
        try execute(database, "PRAGMA foreign_keys=ON")
        try execute(database, "PRAGMA busy_timeout=5000")
        try execute(database, "PRAGMA temp_store=MEMORY")
        databases[name] = database
        return database
    }

    private func execute(_ database: OpaquePointer, _ sql: String) throws {
        guard sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else {
            throw SQLiteError(String(cString: sqlite3_errmsg(database)))
        }
    }

    private func prepare(_ database: OpaquePointer, _ sql: String) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK,
              let statement else {
            throw SQLiteError(String(cString: sqlite3_errmsg(database)))
        }
        return statement
    }

    private func decodeArguments(_ json: String) throws -> [Any] {
        let value = try JSONSerialization.jsonObject(with: Data(json.utf8))
        guard let arguments = value as? [Any] else {
            throw SQLiteError("SQLite arguments must be an array")
        }
        return arguments
    }

    private func decodeArgumentSets(_ json: String) throws -> [[Any]] {
        let value = try JSONSerialization.jsonObject(with: Data(json.utf8))
        guard let argumentSets = value as? [[Any]],
              (1...10_000).contains(argumentSets.count) else {
            throw SQLiteError("SQLite executeMany requires between 1 and 10000 argument sets")
        }
        return argumentSets
    }

    private func executeMany(
        _ database: OpaquePointer,
        _ sql: String,
        _ argumentSets: [[Any]]
    ) throws {
        let statement = try prepare(database, sql)
        defer { sqlite3_finalize(statement) }
        try execute(database, "BEGIN IMMEDIATE")
        do {
            for arguments in argumentSets {
                sqlite3_reset(statement)
                sqlite3_clear_bindings(statement)
                try bind(arguments, to: statement)
                guard sqlite3_step(statement) == SQLITE_DONE else {
                    throw SQLiteError(String(cString: sqlite3_errmsg(database)))
                }
            }
            try execute(database, "COMMIT")
        } catch {
            try? execute(database, "ROLLBACK")
            throw error
        }
    }

    private func bind(_ arguments: [Any], to statement: OpaquePointer) throws {
        for (offset, value) in arguments.enumerated() {
            let index = Int32(offset + 1)
            let result: Int32
            switch value {
            case is NSNull:
                result = sqlite3_bind_null(statement, index)
            case let value as Bool:
                result = sqlite3_bind_int64(statement, index, value ? 1 : 0)
            case let value as NSNumber:
                result = sqlite3_bind_double(statement, index, value.doubleValue)
            case let value as String:
                result = sqlite3_bind_text(statement, index, value, -1, SQLITE_TRANSIENT)
            default:
                throw SQLiteError("SQLite arguments must be scalar")
            }
            guard result == SQLITE_OK else { throw SQLiteError("Unable to bind SQLite argument") }
        }
    }

    private func query(
        _ database: OpaquePointer,
        _ sql: String,
        _ arguments: [Any]
    ) throws -> [[String: Any]] {
        let statement = try prepare(database, sql)
        defer { sqlite3_finalize(statement) }
        try bind(arguments, to: statement)
        var rows: [[String: Any]] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            guard rows.count < 1_000 else {
                throw SQLiteError("SQLite query exceeded the 1000-row bridge limit; paginate the query")
            }
            guard sqlite3_column_count(statement) <= 256 else {
                throw SQLiteError("SQLite query exceeded the 256-column bridge limit")
            }
            var row: [String: Any] = [:]
            for index in 0..<sqlite3_column_count(statement) {
                let name = String(cString: sqlite3_column_name(statement, index))
                switch sqlite3_column_type(statement, index) {
                case SQLITE_INTEGER:
                    row[name] = sqlite3_column_int64(statement, index)
                case SQLITE_FLOAT:
                    row[name] = sqlite3_column_double(statement, index)
                case SQLITE_TEXT:
                    row[name] = String(cString: sqlite3_column_text(statement, index))
                case SQLITE_BLOB:
                    let count = Int(sqlite3_column_bytes(statement, index))
                    let bytes = sqlite3_column_blob(statement, index)
                    row[name] = bytes.map { Data(bytes: $0, count: count).base64EncodedString() } ?? ""
                default:
                    row[name] = NSNull()
                }
            }
            rows.append(row)
        }
        return rows
    }

    func close() {
        queue.sync {
            databases.values.forEach { database in
                _ = sqlite3_close(database)
            }
            databases.removeAll()
        }
    }
}

private struct SQLiteError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
