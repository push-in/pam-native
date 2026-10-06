import CryptoKit
import Foundation

typealias PamNativeCryptoCallback = @convention(c) (
    Int32,
    UnsafePointer<UInt8>?,
    Int,
    UnsafePointer<UInt8>?,
    Int,
    UnsafePointer<UInt8>?,
    Int,
    UnsafePointer<UInt8>?,
    Int,
    UnsafeMutablePointer<UInt8>?,
    Int,
    UnsafeMutablePointer<Int>?
) -> Int32

@_silgen_name("pam_native_ios_set_crypto_provider")
func pam_native_ios_set_crypto_provider(_ callback: PamNativeCryptoCallback?)

/// Host side of PHP's `pam_native_crypto()` (Pam\Native\Crypto): the iOS PHP
/// runtime has neither ext-sodium nor ext-openssl.
///
/// - AES-256-GCM through CryptoKit (12-byte nonce, 16-byte tag appended): the
///   same bytes as OpenSSL and libsodium.
/// - Ed25519 through `Curve25519.Signing`, after libsodium's own encoding
///   checks (canonical S < L, R and the public key not one of libsodium's
///   small-order encodings, canonical public key y < p), so decisions match
///   `sodium_crypto_sign_verify_detached()`. CryptoKit's verification is the
///   cofactorless RFC 8032 equation; `PamCryptoTests` replays
///   packages/native/tests/Fixtures/crypto-vectors.json (libsodium/OpenSSL),
///   including the mixed-order vectors that would expose a cofactored check.
enum PamCrypto {
    static let ed25519Verify: Int32 = 1
    static let aes256GcmEncrypt: Int32 = 2
    static let aes256GcmDecrypt: Int32 = 3

    static func install() {
        pam_native_ios_set_crypto_provider(pamNativeIosCrypto)
    }

    /// One `pam_native_crypto()` call; nil rejects (invalid signature, failed
    /// authentication, malformed input). A valid signature returns empty data.
    static func perform(operation: Int32, key: Data, nonce: Data, aad: Data, input: Data) -> Data? {
        switch operation {
        case ed25519Verify:
            return verifyEd25519(signature: nonce, message: input, publicKey: key) ? Data() : nil
        case aes256GcmEncrypt:
            guard key.count == 32, nonce.count == 12, let gcmNonce = try? AES.GCM.Nonce(data: nonce) else { return nil }
            guard let box = try? AES.GCM.seal(input, using: SymmetricKey(data: key), nonce: gcmNonce, authenticating: aad) else {
                return nil
            }
            return box.ciphertext + box.tag
        case aes256GcmDecrypt:
            guard key.count == 32, nonce.count == 12, input.count >= 16,
                  let gcmNonce = try? AES.GCM.Nonce(data: nonce),
                  let box = try? AES.GCM.SealedBox(nonce: gcmNonce, ciphertext: input.prefix(input.count - 16), tag: input.suffix(16)) else {
                return nil
            }
            return try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
        default:
            return nil
        }
    }

    static func verifyEd25519(signature: Data, message: Data, publicKey: Data) -> Bool {
        let signature = [UInt8](signature)
        let publicKey = [UInt8](publicKey)
        guard signature.count == 64, publicKey.count == 32 else { return false }
        let encodedR = Array(signature[0..<32])
        let encodedS = Array(signature[32..<64])
        guard isCanonicalScalar(encodedS), !hasSmallOrder(encodedR),
              isCanonicalPoint(publicKey), !hasSmallOrder(publicKey),
              let key = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKey) else {
            return false
        }
        return key.isValidSignature(Data(signature), for: message)
    }

    /// libsodium's ge25519_has_small_order() list; the sign bit is ignored.
    private static let smallOrder: [[UInt8]] = [
        "0000000000000000000000000000000000000000000000000000000000000000",
        "0100000000000000000000000000000000000000000000000000000000000000",
        "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
        "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    ].map(hexBytes)

    /// L = 2^252 + 27742317777372353535851937790883648493, little-endian.
    private static let groupOrder: [UInt8] = hexBytes("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010")

    static func hasSmallOrder(_ encoded: [UInt8]) -> Bool {
        smallOrder.contains { candidate in
            encoded[0..<31].elementsEqual(candidate[0..<31]) && (encoded[31] & 0x7f) == candidate[31]
        }
    }

    /// ge25519_is_canonical(): y (sign bit cleared) must be below p = 2^255 - 19.
    static func isCanonicalPoint(_ encoded: [UInt8]) -> Bool {
        guard encoded[31] & 0x7f == 0x7f, encoded[1..<31].allSatisfy({ $0 == 0xff }) else { return true }
        return encoded[0] < 0xed
    }

    /// sc25519_is_canonical(): S < L.
    static func isCanonicalScalar(_ scalar: [UInt8]) -> Bool {
        for index in stride(from: 31, through: 0, by: -1) where scalar[index] != groupOrder[index] {
            return scalar[index] < groupOrder[index]
        }
        return false
    }

    static func hexBytes(_ hex: String) -> [UInt8] {
        var bytes: [UInt8] = []
        bytes.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            bytes.append(UInt8(hex[index..<next], radix: 16) ?? 0)
            index = next
        }
        return bytes
    }
}

private func bytes(_ pointer: UnsafePointer<UInt8>?, _ length: Int) -> Data {
    guard let pointer, length > 0 else { return Data() }
    return Data(bytes: pointer, count: length)
}

/// C entry point registered with pam_native_ios_set_crypto_provider (PHP worker thread).
private func pamNativeIosCrypto(
    _ operation: Int32,
    _ key: UnsafePointer<UInt8>?,
    _ keyLength: Int,
    _ nonce: UnsafePointer<UInt8>?,
    _ nonceLength: Int,
    _ aad: UnsafePointer<UInt8>?,
    _ aadLength: Int,
    _ input: UnsafePointer<UInt8>?,
    _ inputLength: Int,
    _ output: UnsafeMutablePointer<UInt8>?,
    _ outputCapacity: Int,
    _ outputLength: UnsafeMutablePointer<Int>?
) -> Int32 {
    guard let result = PamCrypto.perform(
        operation: operation,
        key: bytes(key, keyLength),
        nonce: bytes(nonce, nonceLength),
        aad: bytes(aad, aadLength),
        input: bytes(input, inputLength)
    ) else {
        return 0
    }
    if operation == PamCrypto.ed25519Verify {
        return 1
    }
    guard result.count == outputCapacity, let outputLength else { return 0 }
    if !result.isEmpty {
        guard let output else { return 0 }
        result.copyBytes(to: output, count: result.count)
    }
    outputLength.pointee = result.count
    return 1
}
