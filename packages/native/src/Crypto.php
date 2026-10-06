<?php

declare(strict_types=1);

namespace Pam\Native;

/**
 * Ed25519 signature verification and AES-256-GCM on every PAM runtime.
 *
 * The Android and iOS PHP runtimes have neither ext-sodium nor ext-openssl.
 * Where the extensions are loaded (desktop `pam dev`, tests, servers) they do
 * the work; on a device the host does it through `pam_native_crypto()`:
 * Android uses the platform JCA (AES-GCM) and a Kotlin Ed25519 verifier,
 * iOS uses CryptoKit. Every backend gives the same bytes and the same
 * decisions as libsodium and OpenSSL, including libsodium's Ed25519 rules
 * (canonical S, no small-order R or public key, canonical public key,
 * cofactorless equation); tests/Fixtures/crypto-vectors.json pins them on
 * all three.
 *
 * AES-256-GCM output is `ciphertext . tag` (16-byte tag), like
 * sodium_crypto_aead_aes256gcm_encrypt() and Java's AES/GCM/NoPadding.
 * HMAC and HKDF need nothing here: ext-hash (hash_hmac(), hash_hkdf()) and
 * random_bytes() are always compiled.
 */
final class Crypto
{
    public const int ED25519_PUBLIC_KEY_BYTES = 32;
    public const int ED25519_SIGNATURE_BYTES = 64;
    public const int AES256_GCM_KEY_BYTES = 32;
    public const int AES256_GCM_NONCE_BYTES = 12;
    public const int AES256_GCM_TAG_BYTES = 16;

    /** Operation codes of the host's pam_native_crypto() (append-only). */
    public const int NATIVE_ED25519_VERIFY = 1;
    public const int NATIVE_AES256_GCM_ENCRYPT = 2;
    public const int NATIVE_AES256_GCM_DECRYPT = 3;

    private static ?CryptoBackend $forcedBackend = null;

    private function __construct()
    {
    }

    /**
     * True when $signature is a valid Ed25519 signature of $message under
     * $publicKey (raw 64 and 32 bytes), with libsodium's verification rules.
     * Malformed sizes return false.
     *
     * @throws CryptoUnavailableException when no backend exists
     */
    public static function ed25519Verify(string $signature, string $message, string $publicKey): bool
    {
        $backend = self::ed25519Backend();
        if (strlen($signature) !== self::ED25519_SIGNATURE_BYTES || strlen($publicKey) !== self::ED25519_PUBLIC_KEY_BYTES) {
            return false;
        }

        return match ($backend) {
            CryptoBackend::Extension => sodium_crypto_sign_verify_detached($signature, $message, $publicKey),
            CryptoBackend::Native => self::native(self::NATIVE_ED25519_VERIFY, $publicKey, $signature, '', $message) === true,
            CryptoBackend::Unavailable => throw self::unavailable('Ed25519 verification', 'ext-sodium'),
        };
    }

    /**
     * AES-256-GCM encryption: returns the ciphertext followed by the 16-byte tag.
     *
     * @throws \InvalidArgumentException for a key that is not 32 bytes or a nonce that is not 12 bytes
     * @throws CryptoUnavailableException when no backend exists
     * @throws \RuntimeException when the backend fails
     */
    public static function aes256GcmEncrypt(string $plaintext, string $key, string $nonce, string $additionalData = ''): string
    {
        self::assertAesParameters($key, $nonce);
        $sealed = match (self::aes256GcmBackend()) {
            CryptoBackend::Extension => self::extensionEncrypt($plaintext, $key, $nonce, $additionalData),
            CryptoBackend::Native => self::native(self::NATIVE_AES256_GCM_ENCRYPT, $key, $nonce, $additionalData, $plaintext),
            CryptoBackend::Unavailable => throw self::unavailable('AES-256-GCM', 'ext-openssl'),
        };
        if (!is_string($sealed) || strlen($sealed) !== strlen($plaintext) + self::AES256_GCM_TAG_BYTES) {
            throw new \RuntimeException('AES-256-GCM encryption failed.');
        }

        return $sealed;
    }

    /**
     * Opens `ciphertext . tag` from aes256GcmEncrypt(). Returns null when the
     * tag does not authenticate the ciphertext, nonce, key and additional data.
     *
     * @throws \InvalidArgumentException for a key that is not 32 bytes or a nonce that is not 12 bytes
     * @throws CryptoUnavailableException when no backend exists
     */
    public static function aes256GcmDecrypt(string $sealed, string $key, string $nonce, string $additionalData = ''): ?string
    {
        self::assertAesParameters($key, $nonce);
        $backend = self::aes256GcmBackend();
        if (strlen($sealed) < self::AES256_GCM_TAG_BYTES) {
            return null;
        }
        $plaintext = match ($backend) {
            CryptoBackend::Extension => self::extensionDecrypt($sealed, $key, $nonce, $additionalData),
            CryptoBackend::Native => self::native(self::NATIVE_AES256_GCM_DECRYPT, $key, $nonce, $additionalData, $sealed),
            CryptoBackend::Unavailable => throw self::unavailable('AES-256-GCM', 'ext-openssl'),
        };

        return is_string($plaintext) && strlen($plaintext) === strlen($sealed) - self::AES256_GCM_TAG_BYTES ? $plaintext : null;
    }

    public static function ed25519Backend(): CryptoBackend
    {
        return self::$forcedBackend ?? match (true) {
            function_exists('sodium_crypto_sign_verify_detached') => CryptoBackend::Extension,
            function_exists('pam_native_crypto') => CryptoBackend::Native,
            default => CryptoBackend::Unavailable,
        };
    }

    public static function aes256GcmBackend(): CryptoBackend
    {
        return self::$forcedBackend ?? match (true) {
            self::opensslGcm() || self::sodiumGcm() => CryptoBackend::Extension,
            function_exists('pam_native_crypto') => CryptoBackend::Native,
            default => CryptoBackend::Unavailable,
        };
    }

    /**
     * Test seam: forces one backend for every primitive (null restores
     * detection), e.g. the native path on a host that also has ext-sodium.
     *
     * @internal
     */
    public static function useBackend(?CryptoBackend $backend): void
    {
        self::$forcedBackend = $backend;
    }

    private static function native(int $operation, string $key, string $nonce, string $additionalData, string $input): string|bool
    {
        if (!function_exists('pam_native_crypto')) {
            throw new CryptoUnavailableException('This PAM Native host does not provide pam_native_crypto(); update the Android/iOS host to PAM Native 1.19.0 or later.');
        }
        $result = pam_native_crypto($operation, $key, $nonce, $additionalData, $input);
        if ($result === null) {
            throw new CryptoUnavailableException('The PAM Native host has no crypto provider installed.');
        }

        return $result;
    }

    private static function extensionEncrypt(string $plaintext, string $key, string $nonce, string $additionalData): string|false
    {
        if (self::opensslGcm()) {
            $tag = '';
            $ciphertext = openssl_encrypt($plaintext, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $nonce, $tag, $additionalData, self::AES256_GCM_TAG_BYTES);

            return is_string($ciphertext) && strlen($tag) === self::AES256_GCM_TAG_BYTES ? $ciphertext.$tag : false;
        }

        return sodium_crypto_aead_aes256gcm_encrypt($plaintext, $additionalData, $nonce, $key);
    }

    private static function extensionDecrypt(string $sealed, string $key, string $nonce, string $additionalData): string|false
    {
        if (self::opensslGcm()) {
            return openssl_decrypt(
                substr($sealed, 0, -self::AES256_GCM_TAG_BYTES),
                'aes-256-gcm',
                $key,
                OPENSSL_RAW_DATA,
                $nonce,
                substr($sealed, -self::AES256_GCM_TAG_BYTES),
                $additionalData,
            );
        }

        return sodium_crypto_aead_aes256gcm_decrypt($sealed, $additionalData, $nonce, $key);
    }

    private static function opensslGcm(): bool
    {
        static $available = null;

        return $available ??= function_exists('openssl_encrypt')
            && in_array('aes-256-gcm', openssl_get_cipher_methods(), true);
    }

    private static function sodiumGcm(): bool
    {
        return function_exists('sodium_crypto_aead_aes256gcm_is_available') && sodium_crypto_aead_aes256gcm_is_available();
    }

    private static function assertAesParameters(string $key, string $nonce): void
    {
        if (strlen($key) !== self::AES256_GCM_KEY_BYTES) {
            throw new \InvalidArgumentException('AES-256-GCM requires a 32-byte key.');
        }
        if (strlen($nonce) !== self::AES256_GCM_NONCE_BYTES) {
            throw new \InvalidArgumentException('AES-256-GCM requires a 12-byte nonce.');
        }
    }

    private static function unavailable(string $primitive, string $extension): CryptoUnavailableException
    {
        return new CryptoUnavailableException(sprintf(
            '%s needs %s or a PAM Native host that provides pam_native_crypto() (Android/iOS 1.19.0+); neither is available.',
            $primitive,
            $extension,
        ));
    }
}
