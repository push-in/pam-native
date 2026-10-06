package dev.pam.nativeapp

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PamCrypto (PHP's pam_native_crypto() host) on a real device: the platform
 * JCA/Conscrypt AES-GCM and the Kotlin Ed25519 verifier against the
 * libsodium/OpenSSL vectors (assets/crypto-vectors.json, a copy of
 * packages/native/tests/Fixtures/crypto-vectors.json).
 */
@RunWith(AndroidJUnit4::class)
class NativeCryptoInstrumentedTest {
    private fun vectors(): JSONObject {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return JSONObject(assets.open("crypto-vectors.json").bufferedReader().use { it.readText() })
    }

    private fun JSONObject.bytes(key: String): ByteArray {
        val hex = getString(key)
        return ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    @Test
    fun ed25519MatchesLibsodium() {
        val signatures = vectors().getJSONArray("ed25519")
        assertTrue(signatures.length() >= 70)
        val started = System.nanoTime()
        for (index in 0 until signatures.length()) {
            val vector = signatures.getJSONObject(index)
            val verified = PamCrypto.perform(
                PamCrypto.ED25519_VERIFY,
                vector.bytes("publicKey"),
                vector.bytes("signature"),
                ByteArray(0),
                vector.bytes("message"),
            ) != null
            assertEquals(vector.getString("name"), vector.getBoolean("valid"), verified)
        }
        Log.i(
            "PamNativeCrypto",
            "Ed25519: ${signatures.length()} vectors in ${(System.nanoTime() - started) / 1_000_000} ms " +
                "on ${Build.MODEL} (API ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.first()})",
        )
    }

    @Test
    fun aes256GcmMatchesOpenSsl() {
        val document = vectors()
        val sealedVectors = document.getJSONArray("aes256gcm")
        for (index in 0 until sealedVectors.length()) {
            val vector = sealedVectors.getJSONObject(index)
            val name = vector.getString("name")
            assertArrayEquals(
                name,
                vector.bytes("ciphertext"),
                PamCrypto.perform(PamCrypto.AES256_GCM_ENCRYPT, vector.bytes("key"), vector.bytes("nonce"), vector.bytes("aad"), vector.bytes("plaintext")),
            )
            assertArrayEquals(
                name,
                vector.bytes("plaintext"),
                PamCrypto.perform(PamCrypto.AES256_GCM_DECRYPT, vector.bytes("key"), vector.bytes("nonce"), vector.bytes("aad"), vector.bytes("ciphertext")),
            )
        }
        val failures = document.getJSONArray("aes256gcmOpenFailures")
        for (index in 0 until failures.length()) {
            val vector = failures.getJSONObject(index)
            assertNull(
                vector.getString("name"),
                PamCrypto.perform(PamCrypto.AES256_GCM_DECRYPT, vector.bytes("key"), vector.bytes("nonce"), vector.bytes("aad"), vector.bytes("ciphertext")),
            )
        }
    }

    @Test
    fun largeJournalRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (200 + it).toByte() }
        val aad = "PAM-NATIVE-LF1".toByteArray()
        val plaintext = ByteArray(16 * 1024 * 1024) { (it * 31).toByte() }
        val sealed = requireNotNull(PamCrypto.perform(PamCrypto.AES256_GCM_ENCRYPT, key, nonce, aad, plaintext))
        assertEquals(plaintext.size + 16, sealed.size)
        assertArrayEquals(plaintext, PamCrypto.perform(PamCrypto.AES256_GCM_DECRYPT, key, nonce, aad, sealed))
        sealed[sealed.size / 2] = (sealed[sealed.size / 2].toInt() xor 1).toByte()
        assertNull(PamCrypto.perform(PamCrypto.AES256_GCM_DECRYPT, key, nonce, aad, sealed))
    }
}
