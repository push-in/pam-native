package dev.pam.nativeapp

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Host side of PHP's `pam_native_crypto()` (Pam\Native\Crypto): the Android
 * PHP runtime has neither ext-sodium nor ext-openssl.
 *
 * - AES-256-GCM through the platform JCA (Conscrypt), 12-byte nonce, 16-byte
 *   tag appended: the same bytes as OpenSSL and libsodium.
 * - Ed25519 verification in Kotlin with libsodium's exact rules, so every
 *   API level (26+) decides like `sodium_crypto_sign_verify_detached()`:
 *   canonical S (< L), R and A not one of libsodium's small-order encodings,
 *   canonical A (y < p) that decodes, and the cofactorless equation
 *   ([S]B - [h]A must encode to exactly R). The platform's Ed25519 (API 33+)
 *   skips the small-order checks, so it is not used. Verification only
 *   handles public data, so variable-time arithmetic is fine.
 *
 * Pinned by packages/native/tests/Fixtures/crypto-vectors.json (generated
 * from libsodium and OpenSSL) in the JVM and instrumented tests.
 */
object PamCrypto {
    const val ED25519_VERIFY = 1
    const val AES256_GCM_ENCRYPT = 2
    const val AES256_GCM_DECRYPT = 3

    private const val TAG_BYTES = 16
    private val VERIFIED = byteArrayOf(1)

    /**
     * One `pam_native_crypto()` call. Returns the output (ciphertext + tag,
     * plaintext, or a single 1 byte for a valid signature) or null when the
     * signature is invalid, authentication fails or the input is malformed.
     */
    @JvmStatic
    fun perform(operation: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray? =
        when (operation) {
            ED25519_VERIFY -> if (Ed25519.verify(signature = nonce, message = input, publicKey = key)) VERIFIED else null
            AES256_GCM_ENCRYPT -> aesGcm(Cipher.ENCRYPT_MODE, key, nonce, aad, input)
            AES256_GCM_DECRYPT -> if (input.size < TAG_BYTES) null else aesGcm(Cipher.DECRYPT_MODE, key, nonce, aad, input)
            else -> null
        }

    private fun aesGcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray? {
        if (key.size != 32 || nonce.size != 12) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(input)
        } catch (_: GeneralSecurityException) {
            // AEADBadTagException and any provider refusal: authentication failed.
            null
        }
    }

    /** RFC 8032 Ed25519 verification with libsodium's checks (see the class comment). */
    internal object Ed25519 {
        private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        private val L: BigInteger = BigInteger.ONE.shiftLeft(252)
            .add(BigInteger("27742317777372353535851937790883648493"))
        private val D: BigInteger = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
        private val D2: BigInteger = D.shiftLeft(1).mod(P)
        private val SQRT_M1: BigInteger = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
        private val SQRT_EXPONENT: BigInteger = P.add(BigInteger.valueOf(3)).shiftRight(3)
        private val BASE: Point = run {
            val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
            requireNotNull(decode(encodeY(y))) { "Ed25519 base point" }
        }

        /** libsodium's ge25519_has_small_order() list; the sign bit is ignored. */
        private val SMALL_ORDER: List<ByteArray> = listOf(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
            "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        ).map(::hex)

        /** Extended coordinates (X:Y:Z:T), x = X/Z, y = Y/Z, xy = T/Z. */
        private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

        private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

        fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
            if (signature.size != 64 || publicKey.size != 32) return false
            val encodedR = signature.copyOfRange(0, 32)
            val encodedS = signature.copyOfRange(32, 64)
            val s = littleEndian(encodedS)
            if (s >= L || hasSmallOrder(encodedR)) return false
            if (!isCanonical(publicKey) || hasSmallOrder(publicKey)) return false
            val a = decode(publicKey) ?: return false
            val digest = MessageDigest.getInstance("SHA-512").run {
                update(encodedR)
                update(publicKey)
                update(message)
                digest()
            }
            val h = littleEndian(digest).mod(L)
            val negatedA = Point(P.subtract(a.x).mod(P), a.y, a.z, P.subtract(a.t).mod(P))
            val check = add(multiply(s, BASE), multiply(h, negatedA))
            return MessageDigest.isEqual(encode(check), encodedR)
        }

        private fun hasSmallOrder(encoded: ByteArray): Boolean = SMALL_ORDER.any { candidate ->
            (0 until 31).all { encoded[it] == candidate[it] } &&
                (encoded[31].toInt() and 0x7f) == (candidate[31].toInt() and 0xff)
        }

        /** ge25519_is_canonical(): y (sign bit cleared) must be below p. */
        private fun isCanonical(encoded: ByteArray): Boolean {
            if ((encoded[31].toInt() and 0x7f) != 0x7f) return true
            for (index in 30 downTo 1) {
                if ((encoded[index].toInt() and 0xff) != 0xff) return true
            }
            return (encoded[0].toInt() and 0xff) < 0xed
        }

        /** ge25519_frombytes(): y must already be canonical; x from the curve equation. */
        private fun decode(encoded: ByteArray): Point? {
            val sign = (encoded[31].toInt() ushr 7) and 1
            val yBytes = encoded.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() }
            val y = littleEndian(yBytes)
            val y2 = y.multiply(y).mod(P)
            val u = y2.subtract(BigInteger.ONE).mod(P)
            val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
            val x2 = u.multiply(v.modInverse(P)).mod(P)
            var x = x2.modPow(SQRT_EXPONENT, P)
            if (x.multiply(x).subtract(x2).mod(P).signum() != 0) {
                x = x.multiply(SQRT_M1).mod(P)
                if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
            }
            if (x.testBit(0) != (sign == 1)) x = P.subtract(x).mod(P)
            return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
        }

        private fun encode(point: Point): ByteArray {
            val inverse = point.z.modInverse(P)
            val x = point.x.multiply(inverse).mod(P)
            val encoded = encodeY(point.y.multiply(inverse).mod(P))
            if (x.testBit(0)) encoded[31] = (encoded[31].toInt() or 0x80).toByte()
            return encoded
        }

        private fun encodeY(y: BigInteger): ByteArray {
            val bigEndian = y.toByteArray()
            val output = ByteArray(32)
            for (index in 0 until minOf(32, bigEndian.size)) {
                output[index] = bigEndian[bigEndian.size - 1 - index]
            }
            return output
        }

        /** RFC 8032 section 5.1.4 addition (complete for a = -1). */
        private fun add(first: Point, second: Point): Point {
            val a = first.y.subtract(first.x).multiply(second.y.subtract(second.x)).mod(P)
            val b = first.y.add(first.x).multiply(second.y.add(second.x)).mod(P)
            val c = first.t.multiply(D2).multiply(second.t).mod(P)
            val d = first.z.shiftLeft(1).multiply(second.z).mod(P)
            val e = b.subtract(a)
            val f = d.subtract(c)
            val g = d.add(c)
            val h = b.add(a)
            return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
        }

        private fun multiply(scalar: BigInteger, point: Point): Point {
            var result = IDENTITY
            for (bit in scalar.bitLength() - 1 downTo 0) {
                result = add(result, result)
                if (scalar.testBit(bit)) result = add(result, point)
            }
            return result
        }

        private fun littleEndian(bytes: ByteArray): BigInteger {
            val bigEndian = ByteArray(bytes.size + 1)
            for (index in bytes.indices) {
                bigEndian[bytes.size - index] = bytes[index]
            }
            return BigInteger(bigEndian)
        }

        private fun hex(value: String): ByteArray =
            ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
