import XCTest
@testable import PamNative

/// PHP's pam_native_crypto() on iOS (PamCrypto.swift, CryptoKit) must give
/// libsodium's and OpenSSL's exact bytes and decisions: replays
/// packages/native/tests/Fixtures/crypto-vectors.json
/// (scripts/generate-crypto-vectors.php), shared with the PHP and Android tests.
final class PamCryptoTests: XCTestCase {
    private struct Vectors: Decodable {
        struct Signature: Decodable {
            let name: String
            let publicKey: String
            let signature: String
            let message: String
            let valid: Bool
        }

        struct Sealed: Decodable {
            let name: String
            let key: String
            let nonce: String
            let aad: String
            let plaintext: String
            let ciphertext: String
        }

        struct Failure: Decodable {
            let name: String
            let key: String
            let nonce: String
            let aad: String
            let ciphertext: String
        }

        let version: Int
        let ed25519: [Signature]
        let aes256gcm: [Sealed]
        let aes256gcmOpenFailures: [Failure]
    }

    private func vectors() throws -> Vectors {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent("../../../packages/native/tests/Fixtures/crypto-vectors.json")
            .standardizedFileURL
        let vectors = try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: url))
        XCTAssertEqual(vectors.version, 1)
        return vectors
    }

    private func data(_ hex: String) -> Data {
        Data(PamCrypto.hexBytes(hex))
    }

    func testEd25519MatchesLibsodium() throws {
        let vectors = try vectors()
        XCTAssertGreaterThanOrEqual(vectors.ed25519.count, 70)
        for vector in vectors.ed25519 {
            let verified = PamCrypto.perform(
                operation: PamCrypto.ed25519Verify,
                key: data(vector.publicKey),
                nonce: data(vector.signature),
                aad: Data(),
                input: data(vector.message)
            ) != nil
            XCTAssertEqual(verified, vector.valid, vector.name)
        }
    }

    func testAes256GcmMatchesOpenSsl() throws {
        let vectors = try vectors()
        for vector in vectors.aes256gcm {
            let key = data(vector.key)
            let nonce = data(vector.nonce)
            let aad = data(vector.aad)
            XCTAssertEqual(
                PamCrypto.perform(operation: PamCrypto.aes256GcmEncrypt, key: key, nonce: nonce, aad: aad, input: data(vector.plaintext)),
                data(vector.ciphertext),
                vector.name
            )
            XCTAssertEqual(
                PamCrypto.perform(operation: PamCrypto.aes256GcmDecrypt, key: key, nonce: nonce, aad: aad, input: data(vector.ciphertext)),
                data(vector.plaintext),
                vector.name
            )
        }
        for vector in vectors.aes256gcmOpenFailures {
            XCTAssertNil(
                PamCrypto.perform(
                    operation: PamCrypto.aes256GcmDecrypt,
                    key: data(vector.key),
                    nonce: data(vector.nonce),
                    aad: data(vector.aad),
                    input: data(vector.ciphertext)
                ),
                vector.name
            )
        }
    }

    func testMalformedInputIsRejected() {
        let key = Data(repeating: 1, count: 32)
        let nonce = Data(repeating: 2, count: 12)
        XCTAssertNil(PamCrypto.perform(operation: PamCrypto.aes256GcmEncrypt, key: key.prefix(31), nonce: nonce, aad: Data(), input: Data()))
        XCTAssertNil(PamCrypto.perform(operation: PamCrypto.aes256GcmEncrypt, key: key, nonce: nonce.prefix(11), aad: Data(), input: Data()))
        XCTAssertNil(PamCrypto.perform(operation: PamCrypto.ed25519Verify, key: key, nonce: Data(count: 63), aad: Data(), input: Data()))
        XCTAssertNil(PamCrypto.perform(operation: 99, key: key, nonce: nonce, aad: Data(), input: Data()))
    }

    func testLargeJournalRoundTrip() throws {
        let key = Data((0..<32).map { UInt8($0) })
        let nonce = Data((0..<12).map { UInt8(200 + $0) })
        let plaintext = Data((0..<(4 * 1024 * 1024)).map { UInt8(truncatingIfNeeded: $0 &* 31) })
        let sealed = try XCTUnwrap(PamCrypto.perform(operation: PamCrypto.aes256GcmEncrypt, key: key, nonce: nonce, aad: Data("PAM-NATIVE-LF1".utf8), input: plaintext))
        XCTAssertEqual(sealed.count, plaintext.count + 16)
        XCTAssertEqual(PamCrypto.perform(operation: PamCrypto.aes256GcmDecrypt, key: key, nonce: nonce, aad: Data("PAM-NATIVE-LF1".utf8), input: sealed), plaintext)
    }
}
