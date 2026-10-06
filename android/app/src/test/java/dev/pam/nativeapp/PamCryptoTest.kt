package dev.pam.nativeapp

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHP's pam_native_crypto() on Android (PamCrypto.kt) must give libsodium's
 * and OpenSSL's exact bytes and decisions. Replays
 * packages/native/tests/Fixtures/crypto-vectors.json (the androidTest asset is
 * a byte-identical copy, checked by the PHP tests) on the JVM;
 * NativeCryptoInstrumentedTest replays it on a device with the platform JCA.
 */
class PamCryptoTest {
    private val vectors = CryptoVectorFile.parse(File("src/androidTest/assets/crypto-vectors.json").readText())

    @Test
    fun ed25519MatchesLibsodium() {
        val signatures = vectors.getValue("ed25519")
        assertTrue(signatures.size >= 70)
        for (vector in signatures) {
            val verified = PamCrypto.perform(
                PamCrypto.ED25519_VERIFY,
                vector.bytes("publicKey"),
                vector.bytes("signature"),
                ByteArray(0),
                vector.bytes("message"),
            ) != null
            assertEquals(vector.getValue("name"), vector.getValue("valid") == "true", verified)
        }
    }

    @Test
    fun aes256GcmMatchesOpenSsl() {
        for (vector in vectors.getValue("aes256gcm")) {
            val name = vector.getValue("name")
            val sealed = PamCrypto.perform(
                PamCrypto.AES256_GCM_ENCRYPT,
                vector.bytes("key"),
                vector.bytes("nonce"),
                vector.bytes("aad"),
                vector.bytes("plaintext"),
            )
            assertArrayEquals(name, vector.bytes("ciphertext"), sealed)
            val opened = PamCrypto.perform(
                PamCrypto.AES256_GCM_DECRYPT,
                vector.bytes("key"),
                vector.bytes("nonce"),
                vector.bytes("aad"),
                vector.bytes("ciphertext"),
            )
            assertArrayEquals(name, vector.bytes("plaintext"), opened)
        }
        for (vector in vectors.getValue("aes256gcmOpenFailures")) {
            assertNull(
                vector.getValue("name"),
                PamCrypto.perform(
                    PamCrypto.AES256_GCM_DECRYPT,
                    vector.bytes("key"),
                    vector.bytes("nonce"),
                    vector.bytes("aad"),
                    vector.bytes("ciphertext"),
                ),
            )
        }
    }

    @Test
    fun malformedInputIsRejected() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(12) { 2 }
        assertNull(PamCrypto.perform(PamCrypto.AES256_GCM_ENCRYPT, key.copyOf(31), nonce, ByteArray(0), ByteArray(0)))
        assertNull(PamCrypto.perform(PamCrypto.AES256_GCM_ENCRYPT, key, nonce.copyOf(16), ByteArray(0), ByteArray(0)))
        assertNull(PamCrypto.perform(PamCrypto.AES256_GCM_DECRYPT, key, nonce, ByteArray(0), ByteArray(15)))
        assertNull(PamCrypto.perform(PamCrypto.ED25519_VERIFY, key, ByteArray(63), ByteArray(0), ByteArray(0)))
        assertNull(PamCrypto.perform(PamCrypto.ED25519_VERIFY, key.copyOf(33), ByteArray(64), ByteArray(0), ByteArray(0)))
        assertNull(PamCrypto.perform(99, key, nonce, ByteArray(0), ByteArray(0)))
    }
}

/** The vector file is flat JSON (arrays of string/bool objects); no JSON library on the JVM test classpath. */
internal object CryptoVectorFile {
    fun parse(json: String): Map<String, List<Map<String, String>>> {
        val sections = mutableMapOf<String, List<Map<String, String>>>()
        val section = Regex("\"(\\w+)\": \\[(.*?)\\n    ]", RegexOption.DOT_MATCHES_ALL)
        val entry = Regex("\\{(.*?)}", RegexOption.DOT_MATCHES_ALL)
        val field = Regex("\"(\\w+)\": (\"([^\"]*)\"|true|false)")
        for (match in section.findAll(json)) {
            sections[match.groupValues[1]] = entry.findAll(match.groupValues[2]).map { item ->
                field.findAll(item.groupValues[1]).associate { pair ->
                    pair.groupValues[1] to (if (pair.groupValues[2].startsWith("\"")) pair.groupValues[3] else pair.groupValues[2])
                }
            }.toList()
        }
        return sections
    }
}

internal fun Map<String, String>.bytes(key: String): ByteArray {
    val hex = getValue(key)
    return ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
