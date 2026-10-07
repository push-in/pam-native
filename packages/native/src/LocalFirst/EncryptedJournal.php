<?php

declare(strict_types=1);

namespace Pam\Native\LocalFirst;

use Pam\Native\Crypto;

use function is_array;
use function is_string;
use function strlen;

/**
 * AES-256-GCM sealed local-first journal: 'PNL1' . nonce(12) . tag(16) .
 * ciphertext, additional data 'PAM-NATIVE-LF1'. Uses ext-openssl when loaded
 * and the Android/iOS host otherwise (Pam\Native\Crypto), so an envelope
 * sealed on one opens on the other.
 */
final class EncryptedJournal
{
    private const int MAX_BYTES = 16_777_216;
    private const string ADDITIONAL_DATA = 'PAM-NATIVE-LF1';

    public function __construct(private readonly string $key)
    {
        if (strlen($key) !== 32) {
            throw new \InvalidArgumentException('Local-first encryption requires a 256-bit key.');
        }
    }

    /** @param list<array<string, mixed>> $entries */
    public function seal(array $entries): string
    {
        $plaintext = json_encode($entries, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);
        if (strlen($plaintext) > self::MAX_BYTES) {
            throw new \OverflowException('Local-first journal exceeds 16 MiB.');
        }
        $nonce = random_bytes(Crypto::AES256_GCM_NONCE_BYTES);
        $sealed = Crypto::aes256GcmEncrypt($plaintext, $this->key, $nonce, self::ADDITIONAL_DATA);
        return 'PNL1'.$nonce.substr($sealed, -Crypto::AES256_GCM_TAG_BYTES).substr($sealed, 0, -Crypto::AES256_GCM_TAG_BYTES);
    }

    /** @return list<array<string, mixed>> */
    public function open(string $payload): array
    {
        if (!str_starts_with($payload, 'PNL1') || strlen($payload) < 32 || strlen($payload) > self::MAX_BYTES + 32) {
            throw new \InvalidArgumentException('Local-first journal envelope is invalid.');
        }
        $plaintext = Crypto::aes256GcmDecrypt(
            substr($payload, 32).substr($payload, 16, 16),
            $this->key,
            substr($payload, 4, 12),
            self::ADDITIONAL_DATA,
        );
        if (!is_string($plaintext)) {
            throw new \RuntimeException('Local-first journal authentication failed.');
        }
        $decoded = json_decode($plaintext, true, 64, JSON_THROW_ON_ERROR);
        if (!is_array($decoded) || !array_is_list($decoded)) {
            throw new \RuntimeException('Local-first journal content is invalid.');
        }
        $entries = [];
        foreach ($decoded as $entry) {
            if (!is_array($entry)) {
                throw new \RuntimeException('Local-first journal entries must be objects.');
            }
            $normalized = [];
            foreach ($entry as $key => $value) {
                if (!is_string($key)) {
                    throw new \RuntimeException('Local-first journal entry keys must be strings.');
                }
                $normalized[$key] = $value;
            }
            $entries[] = $normalized;
        }
        return $entries;
    }
}
