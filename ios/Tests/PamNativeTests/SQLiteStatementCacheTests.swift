import SQLite3
import XCTest
@testable import PamNative

/// Prepared-statement reuse in the iOS SQLite module (Android
/// SQLiteDatabase statement-cache parity, used by pam-native-nitro).
/// Uncompiled — needs Mac validation.
final class SQLiteStatementCacheTests: XCTestCase {
    private let module = SQLiteModule()
    private let name = "statement-cache-\(UUID().uuidString).db"

    override func tearDown() {
        module.close()
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pam-databases")
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(at: root.appendingPathComponent(name + suffix))
        }
        super.tearDown()
    }

    private func call(_ method: String, _ sql: String, _ arguments: String = "[]") -> (ok: Bool, rows: [[String: Any]], message: String) {
        let done = expectation(description: method)
        var result: (Bool, [[String: Any]], String) = (false, [], "")
        module.invoke(method: method, payload: (try? WireMap.encode([
            "database": .text(name), "sql": .text(sql), "arguments": .text(arguments),
        ])) ?? Data()) { status, payload in
            var rows: [[String: Any]] = []
            if case let .text(json)? = (try? WireMap.decode(payload))?["rows"] {
                rows = (try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [[String: Any]]) ?? []
            }
            result = (status == .success, rows, String(decoding: payload, as: UTF8.self))
            done.fulfill()
        }
        wait(for: [done], timeout: 5)
        return result
    }

    func testReusedStatementsKeepCorrectBindingsAndReleaseReads() {
        XCTAssertTrue(call("execute", "CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)").ok)
        for index in 0..<50 {
            XCTAssertTrue(call("execute", "INSERT INTO t (id, v) VALUES (?, ?)", "[\(index), \"v\(index)\"]").ok)
        }
        for index in [0, 7, 49] {
            let rows = call("query", "SELECT v FROM t WHERE id = ?", "[\(index)]").rows
            XCTAssertEqual(rows.first?["v"] as? String, "v\(index)")
        }
        // A cached SELECT must not keep a read transaction open across calls.
        XCTAssertTrue(call("transaction", "", #"[{"sql":"DELETE FROM t WHERE id >= ?","arguments":[10]},{"sql":"INSERT INTO t (id, v) VALUES (?, ?)","argumentSets":[[100,"a"],[101,"b"]]}]"#).ok)
        XCTAssertEqual(call("query", "SELECT COUNT(*) AS c FROM t").rows.first?["c"] as? Int, 12)
        // Schema changes re-prepare cached statements transparently.
        XCTAssertTrue(call("execute", "ALTER TABLE t ADD COLUMN extra TEXT").ok)
        XCTAssertEqual(call("query", "SELECT v FROM t WHERE id = ?", "[100]").rows.first?["v"] as? String, "a")
        XCTAssertFalse(call("execute", "INSERT INTO missing VALUES (?)", "[1]").ok)
    }

    func testLruEvictsAndSkipsOversizedSql() throws {
        var database: OpaquePointer?
        XCTAssertEqual(sqlite3_open(":memory:", &database), SQLITE_OK)
        defer { sqlite3_close(database) }
        let cache = SQLiteStatementCache()
        for index in 0..<(SQLiteStatementCache.capacity + 5) {
            var statement: OpaquePointer?
            sqlite3_prepare_v2(database, "SELECT \(index)", -1, &statement, nil)
            cache.put("SELECT \(index)", try XCTUnwrap(statement))
        }
        XCTAssertEqual(cache.count, SQLiteStatementCache.capacity)
        XCTAssertNil(cache.take("SELECT 0"))
        XCTAssertNotNil(cache.take("SELECT \(SQLiteStatementCache.capacity + 4)"))
        let large = "SELECT 1 " + String(repeating: " ", count: SQLiteStatementCache.maxSqlBytes)
        var statement: OpaquePointer?
        sqlite3_prepare_v2(database, large, -1, &statement, nil)
        cache.put(large, try XCTUnwrap(statement))
        XCTAssertNil(cache.take(large))
        cache.finalizeAll()
        XCTAssertEqual(cache.count, 0)
    }
}
